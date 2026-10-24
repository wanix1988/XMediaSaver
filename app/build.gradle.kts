import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wenix.xmediasaver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wenix.xmediasaver"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    // 签名信息从 keystore.properties（已 gitignore，仅本机）读取，
    // 未提供时回退环境变量；两者皆缺则跳过 release 签名（仅构建未签名产物）。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }
    fun signingValue(property: String, vararg envNames: String): String? {
        val fromFile = keystoreProps.getProperty(property)?.takeIf { it.isNotBlank() }
        if (fromFile != null) return fromFile
        return envNames.firstNotNullOfOrNull { name ->
            System.getenv(name)?.takeIf { it.isNotBlank() }
        }
    }
    val releaseStorePath = signingValue("storeFile", "XMS_STORE_FILE") ?: "keystore/xmediasaver.jks"
    val releaseStorePassword = signingValue("storePassword", "XMS_STORE_PASSWORD", "XMS_STOREPASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "XMS_KEY_ALIAS", "XMS_KEYALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "XMS_KEY_PASSWORD", "XMS_KEYPASSWORD")
    val releaseStoreFile = rootProject.file(releaseStorePath)
    val releaseSigningReady = releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null &&
        releaseStoreFile.isFile
    if (!releaseSigningReady) {
        logger.warn("XMediaSaver: 缺少签名配置（keystore.properties 或 XMS_* 环境变量），release 将不签名")
    }
    if (System.getenv("XMS_REQUIRE_SIGNING") == "true" && !releaseSigningReady) {
        error("Release 签名未配置。请提供 keystore，并设置 XMS_STORE_PASSWORD、XMS_KEY_ALIAS、XMS_KEY_PASSWORD。")
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.ui:ui:1.9.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.2")
    implementation("androidx.compose.foundation:foundation:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
