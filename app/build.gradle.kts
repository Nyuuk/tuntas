import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseSigningNames = listOf(
    "TUNTAS_KEYSTORE_FILE", "TUNTAS_KEYSTORE_PASSWORD", "TUNTAS_KEY_ALIAS", "TUNTAS_KEY_PASSWORD"
)
val releaseSigning = releaseSigningNames.associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigning.values.all { !it.isNullOrBlank() }
val validateReleaseSigningInputs = tasks.register("validateReleaseSigningInputs") {
    doLast {
        val missing = releaseSigningNames.filter { releaseSigning[it].isNullOrBlank() }
        check(missing.isEmpty()) {
            "Release signing requires environment variables: ${missing.joinToString()}. No unsigned release is allowed."
        }
        check(file(releaseSigning.getValue("TUNTAS_KEYSTORE_FILE")!!).isFile) {
            "TUNTAS_KEYSTORE_FILE must point to a private keystore file outside source control."
        }
    }
}
// Guard every release task, including aggregate invocations; debug-only builds need no secrets.
tasks.configureEach {
    if (name.contains("release", ignoreCase = true) && name != "validateReleaseSigningInputs") {
        dependsOn(validateReleaseSigningInputs)
    }
}

android {
    namespace = "com.tuntas.app"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.tuntas.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigning.getValue("TUNTAS_KEYSTORE_FILE")!!)
                storePassword = releaseSigning.getValue("TUNTAS_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("TUNTAS_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("TUNTAS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
