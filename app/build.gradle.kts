import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Telegram API credentials are resolved at build time from, in order:
 *   1. Gradle properties (-PTELEGRAM_API_ID=... or a local ~/.gradle/gradle.properties),
 *   2. environment variables (TELEGRAM_API_ID / TELEGRAM_API_HASH),
 *   3. local.properties (not tracked by Git).
 *
 * Credentials are never committed to the repository and never logged at runtime.
 */
fun readLocalProperties(): Properties {
    val properties = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { properties.load(it) }
    }
    return properties
}

fun credential(name: String): String? {
    val local = readLocalProperties()
    return providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: local.getProperty(name)?.takeIf { it.isNotBlank() }
}

val telegramApiId: String = credential("TELEGRAM_API_ID")?.trim().orEmpty()
val telegramApiHash: String = credential("TELEGRAM_API_HASH")?.trim().orEmpty()
val hasTelegramCredentials = telegramApiId.isNotEmpty() &&
    telegramApiId != "0" &&
    telegramApiHash.isNotEmpty()

if (!hasTelegramCredentials) {
    logger.warn(
        "Telegram API credentials are not configured. The APK will build, but authentication " +
            "requires TELEGRAM_API_ID and TELEGRAM_API_HASH (see README)."
    )
}

android {
    namespace = "com.telefarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.telefarm"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native TDLib is shipped for all four ABIs by the TDLib dependency.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.ifEmpty { "0" })
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        buildConfigField("boolean", "TELEGRAM_CREDENTIALS_CONFIGURED", "$hasTelegramCredentials")
    }

    signingConfigs {
        create("releaseLocal") {
            // Only used when the local keystore properties are provided.
            val properties = readLocalProperties()
            val storePath = properties.getProperty("TELEFARM_KEYSTORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = properties.getProperty("TELEFARM_KEYSTORE_PASSWORD")
                keyAlias = properties.getProperty("TELEFARM_KEY_ALIAS")
                keyPassword = properties.getProperty("TELEFARM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Fall back to the debug signing configuration so that personal release
            // builds are installable without publishing a keystore.
            signingConfig = if (readLocalProperties().getProperty("TELEFARM_KEYSTORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("releaseLocal")
            }
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
        // Native libraries are already stripped and 16 KB page aligned by the TDLib artifact.
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Android platform / UI
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Lifecycle / coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // TDLib: prebuilt native library (all ABIs) + generated Java API.
    implementation("io.github.tdlib-android:core:0.1.1")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
