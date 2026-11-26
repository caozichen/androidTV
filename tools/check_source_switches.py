"""Verify the expected source switches in an Android UI Automator dump."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def read_switches(xml: str, names) -> dict[str, bool]:
    tree = ET.fromstring(xml)
    checkable_nodes = [node for node in tree.iter("node") if node.get("checkable") == "true"]
    observed = {}
    for name in names:
        labels = [node for node in tree.iter("node") if node.get("content-desc") == f"启用{name}"]
        if len(labels) != 1:
            raise AssertionError(f"无法唯一读取来源名称：{name}")
        matches = [node for node in checkable_nodes if labels[0] in node.iter("node")]
        if len(matches) != 1 or matches[0].get("checked") not in ("true", "false"):
            raise AssertionError(f"无法唯一读取来源开关：{name}")
        switch = matches[0]
        if sum(node.get("content-desc", "").startswith("启用") for node in switch.iter("node")) != 1:
            raise AssertionError(f"同一开关关联多个来源名称：{name}")
        observed[name] = switch.get("checked") == "true"
    return observed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xml", type=Path, help="UI Automator XML file")
    parser.add_argument("expected", nargs="+", metavar="NAME=on|off")
    args = parser.parse_args()
    expected = {}
    for entry in args.expected:
        name, _, state = entry.rpartition("=")
        if not name or name in expected or state not in ("on", "off"):
            parser.error(f"需要互不重复的 NAME=on 或 NAME=off：{entry}")
        expected[name] = state == "on"
    try:
        observed = read_switches(args.xml.read_text(encoding="utf-8"), expected)
    except (OSError, ET.ParseError, AssertionError) as exc:
        parser.exit(1, f"{exc}\n")
    if observed != expected:
        parser.exit(1, f"开关状态不符，预期 {expected}，实际 {observed}\n")
    print(observed)


if __name__ == "__main__":
    main()
