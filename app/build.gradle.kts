import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val rehearsal = providers.gradleProperty("libretvRehearsal").orNull?.toBooleanStrict() ?: false
val signingFile = providers.environmentVariable("LIBRETV_SIGNING_FILE").orNull?.let { path ->
    val file = File(path).canonicalFile
    require(File(path).isAbsolute && file.isFile && !file.toPath().startsWith(rootDir.canonicalFile.toPath())) {
        "LIBRETV_SIGNING_FILE must point to a readable properties file outside the project."
    }
    file
}
val credentials = Properties()
if (signingFile != null) {
    signingFile.reader(Charsets.UTF_8).use(credentials::load)
    require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !credentials.getProperty(it).isNullOrBlank()
    }) { "The external signing file is missing required fields." }
    require(credentials.getProperty("purpose") == (if (rehearsal) "rehearsal" else "release")) {
        "The signing file purpose does not match the requested build."
    }
}

android {
    namespace = "dev.libretv.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.libretv.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.6.0"
    }

    signingConfigs {
        if (signingFile != null) create("externalRelease") {
            val path = File(credentials.getProperty("storeFile"))
            require(path.isAbsolute && path.isFile && !path.canonicalFile.toPath().startsWith(rootDir.canonicalFile.toPath())) {
                "The keystore must exist outside the project."
            }
            storeFile = path
            storePassword = credentials.getProperty("storePassword")
            keyAlias = credentials.getProperty("keyAlias")
            keyPassword = credentials.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (rehearsal) applicationIdSuffix = ".rehearsal"
            signingConfig = signingConfigs.findByName("externalRelease")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// This dependency runs even when packaging outputs from a previous build are up to date.
if (signingFile == null) {
    val signingRequired = tasks.register("requireReleaseSigning") {
        doLast { throw GradleException("Set LIBRETV_SIGNING_FILE before building a release APK or bundle.") }
    }
    tasks.matching { it.name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle") }
        .configureEach { dependsOn(signingRequired) }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.gson)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.ui)
}
