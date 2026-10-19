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
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        // 签名信息从 keystore.properties（已 gitignore，仅本机）读取，
        // 未提供时回退环境变量；两者皆缺则跳过 release 签名（仅构建未签名产物）。
        val keystoreProps = rootProject.file("keystore.properties")
        val props = Properties()
        if (keystoreProps.exists()) {
            keystoreProps.inputStream().use { props.load(it) }
        }
        fun signingKey(name: String): String? =
            props.getProperty(name) ?: System.getenv("XMS_${name.uppercase()}")
        create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile") ?: "keystore/xmediasaver.jks")
            storePassword = signingKey("storePassword")
            keyAlias = signingKey("keyAlias")
            keyPassword = signingKey("keyPassword")
            // 全部齐备才启用签名；否则构建未签名 release（由 CI/本机另行签名）
            if (storePassword == null || keyAlias == null || keyPassword == null) {
                logger.warn("XMediaSaver: 缺少签名配置（keystore.properties 或 XMS_* 环境变量），release 将不签名")
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
            signingConfig = signingConfigs.getByName("release")
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
