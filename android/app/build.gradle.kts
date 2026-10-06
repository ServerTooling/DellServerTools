plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lilayam.dellservertools"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lilayam.dellservertools"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // CI release signing; see .github/workflows/release.yml. Never commit the keystore.
        val storeFilePath = System.getenv("RELEASE_STORE_FILE")
        if (!storeFilePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // JSch is a multi-release jar; Android only uses the base classes.
            excludes += "META-INF/versions/**"
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // SSH client for the iDRAC6 command line (racadm + serial console) and the Proxmox host shell.
    implementation("com.github.mwiede:jsch:2.28.7")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20250107")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
