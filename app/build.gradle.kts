import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The version people see is "<base>.<build>". The base is chosen by a human. The build number is
// the number of commits, so a build from a newer commit always installs over every older one and
// nobody has to remember to bump a number before publishing. Outside a git checkout (a source
// archive) the build number is unknown and the version falls back to the base alone.
val baseVersion = "1.1"
val buildNumber: Int = try {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull() ?: 0
} catch (_: Exception) {
    0
}

android {
    namespace = "com.focus.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.focus.launcher"
        minSdk = 26
        targetSdk = 36
        // 1.1 was published with versionCode 2; commit counts passed that long ago.
        versionCode = maxOf(buildNumber, 2)
        versionName = if (buildNumber > 0) "$baseVersion.$buildNumber" else baseVersion
    }

    // The key for anything that leaves this machine. keystore.properties (git-ignored) says where
    // it lives; without that file the "dist" build simply cannot be signed, which is the point.
    val keystoreProperties = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        create("dist") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Optimized build for the developer's own phone. Signed with the debug key, so it installs
        // over the debug build without losing data. Never hand this one to anybody else.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // -Pfocus.unsigned leaves the APK unsigned. That is how F-Droid builds it: it then checks
            // that its build is identical to the published APK and ships that one, signature and all.
            signingConfig = if (project.hasProperty("focus.unsigned")) null else signingConfigs.getByName("debug")
            // The build plugin would stamp the git state into the APK. The version already says which
            // commit it is, and the stamp differs between checkouts of the same commit, which is
            // exactly what a reproducible build cannot have.
            vcsInfo { include = false }
        }
        // The same optimized build, signed with the real key: this is the APK on the website.
        // Android refuses to install it over a debug-signed build (different signature), and
        // every future public version must be signed with this same key.
        create("dist") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("dist")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Lists every values-xx folder as a supported language (see res/resources.properties), so
    // Android 13+ offers each translation in the system's per-app language setting.
    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Google's build plugin can add an encrypted list of dependencies to the APK that only Google
    // can read. It serves Play; elsewhere it is an opaque blob in a free-software app (F-Droid
    // asks for it to be left out), and it keeps two builds of the same source from being identical.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Installs the baseline profiles shipped inside the Compose libraries, so a sideloaded build
    // is precompiled instead of interpreted. Without it the first swipes and scrolls stutter.
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)   // the JVM has no org.json of its own; Android does
}
