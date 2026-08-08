@file:Suppress("UnstableApiUsage")

import java.util.Base64

val pkg: String = providers.gradleProperty("wireguardPackageName").get()
val appId: String = providers.gradleProperty("wireguardApplicationId").get()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
}

android {
    compileSdk = 36
    buildFeatures {
        buildConfig = true
        dataBinding = true
        viewBinding = true
    }
    namespace = pkg
    defaultConfig {
        applicationId = appId
        minSdk = 24
        versionCode = providers.gradleProperty("wireguardVersionCode").get().toInt()
        versionName = providers.gradleProperty("wireguardVersionName").get()
        buildConfigField("int", "MIN_SDK_VERSION", minSdk.toString())
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    // Release signing material is supplied only via the environment / CI secrets (never committed).
    // When KEYSTORE_BASE64 is absent (local dev) the release build stays unsigned instead of failing.
    val releaseSigningConfig = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }?.let { keystoreBase64 ->
        signingConfigs.create("release") {
            val keystoreFile = layout.buildDirectory.file("release-signing/release.jks").get().asFile
            keystoreFile.parentFile.mkdirs()
            keystoreFile.writeBytes(Base64.getMimeDecoder().decode(keystoreBase64))
            storeFile = keystoreFile
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-android-optimize.txt")
            signingConfig = releaseSigningConfig
            packaging {
                resources {
                    excludes += "DebugProbesKt.bin"
                    excludes += "kotlin-tooling-metadata.json"
                    excludes += "META-INF/*.version"
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("googleplay") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "LongLogTag"
        warning += "MissingTranslation"
        warning += "ImpliedQuantity"
    }
}

dependencies {
    implementation(project(":tunnel"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.material)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugarJdkLibs)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:unchecked")
    options.isDeprecation = true
}
