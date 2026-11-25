"""Build and verify a LibreTV APK, or prove a restored signing key is usable."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def fingerprint(value):
    digest = value.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise argparse.ArgumentTypeError("Provide the expected certificate SHA-256 (64 hex digits).")
    return digest


def verify_certificate(tools, apk, expected):
    result = subprocess.check_output(
        [tools / "apksigner", "verify", "--verbose", "--print-certs", apk], text=True
    )
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", result, re.M)
    if signers != [expected]:
        raise ValueError("APK signing certificate does not match the expected SHA-256.")


def build(args, tools):
    destination = args.output.absolute()
    if os.path.lexists(destination):
        raise ValueError("Output already exists. Choose a new delivery filename.")
    if (ROOT / "app/build") in destination.resolve().parents or (ROOT / "build") in destination.resolve().parents:
        raise ValueError("Choose a delivery path outside Gradle build outputs.")
    params = ["--offline", "--no-configuration-cache", "-PlibretvSignRelease=true",
              f"-PlibretvRehearsal={str(args.mode == 'rehearsal').lower()}"]
    subprocess.run(
        [ROOT / "gradlew", *params, "clean", ":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease"],
        cwd=ROOT, check=True,
    )
    package = "dev.libretv.android" + (".rehearsal" if args.mode == "rehearsal" else "")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Validate the private copy, then publish this exact inode. An existing output is never replaced.
    with tempfile.TemporaryDirectory(dir=destination.parent) as scratch:
        staged = Path(scratch) / "verified.apk"
        shutil.copyfile(ROOT / "app/build/outputs/apk/release/app-release.apk", staged)
        verify_certificate(tools, staged, args.cert_sha256)
        badging = subprocess.check_output([tools / "aapt", "dump", "badging", staged], text=True)
        header = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
        fields = dict(re.findall(r"(\w+)='([^']*)'", header))
        expected = {"name": package, "versionCode": str(args.version_code), "versionName": args.version_name}
        if any(fields.get(k) != v for k, v in expected.items()):
            raise ValueError(f"Package or version mismatch. Expected {expected}, observed {header}.")
        digest = hashlib.sha256(staged.read_bytes()).hexdigest()
        os.link(staged, destination)
    print(f"{package} {args.version_name} ({args.version_code})\ncertificate SHA-256 {args.cert_sha256}\n{digest}  {destination}")


def verify_backup(args, tools):
    # apksigner prompts on the user's terminal. The keystore and input APK are opened read-only.
    with tempfile.TemporaryDirectory() as scratch:
        proof = Path(scratch) / "backup-proof.apk"
        subprocess.run(
            [tools / "apksigner", "sign", "--ks", args.keystore, "--ks-key-alias", args.alias,
             "--v4-signing-enabled", "false", "--out", proof, args.sample_apk],
            check=True,
        )
        verify_certificate(tools, proof, args.cert_sha256)
    print(f"Backup signed and verified a temporary APK. Certificate SHA-256 {args.cert_sha256}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    release = actions.add_parser("build", help="Build and publish one verified, new APK")
    release.add_argument("mode", choices=("release", "rehearsal"))
    release.add_argument("--version-name", required=True)
    release.add_argument("--version-code", type=int, required=True)
    release.add_argument("--output", type=Path, required=True)
    backup = actions.add_parser("verify-backup", help="Use a restored key to sign a disposable test APK")
    backup.add_argument("--keystore", type=Path, required=True)
    backup.add_argument("--alias", required=True)
    backup.add_argument("--sample-apk", type=Path, required=True)
    for action in (release, backup):
        action.add_argument("--cert-sha256", type=fingerprint, required=True)
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME")
    if not sdk:
        parser.error("Set ANDROID_HOME to the installed Android SDK.")
    tools = Path(sdk) / "build-tools/36.0.0"
    try:
        (build if args.action == "build" else verify_backup)(args, tools)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"Release check failed: {error}")


if __name__ == "__main__":
    main()
