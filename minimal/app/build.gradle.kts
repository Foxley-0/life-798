import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 敏感配置从 minimal/secrets.properties 读取，该文件不进版本控制。
// CI 由 workflow 从 GitHub Secrets 生成，本地开发自行创建。
val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}

fun secret(key: String): String = secrets.getProperty(key, "")

android {
    namespace = "com.life798.mini"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.life798.mini"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        buildConfigField("String", "API_GATEWAY", "\"${secret("API_GATEWAY")}\"")
        buildConfigField("String", "APP_SIGN_SALT", "\"${secret("APP_SIGN_SALT")}\"")

        // ZXing 为纯 Java，无原生库；CameraX 自带 so，保留两个主流 ABI。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 签名：CI 通过环境变量注入固定 keystore；本地缺省时回落到系统 debug 签名。
    // debug 与 release 共用同一签名，保证两个构建产物可以互相覆盖安装。
    val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
    val ksPass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val ksAlias = System.getenv("ANDROID_KEY_ALIAS")
    val ksKeyPass = System.getenv("ANDROID_KEY_PASSWORD")
    val ksFile = ksPath?.takeIf { it.isNotBlank() }?.let { file(it) }
    val hasCustomSigning = ksFile != null && ksFile.exists() &&
        !ksPass.isNullOrBlank() && !ksAlias.isNullOrBlank() && !ksKeyPass.isNullOrBlank()

    signingConfigs {
        if (hasCustomSigning) {
            create("custom") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasCustomSigning) signingConfig = signingConfigs.getByName("custom")
        }

        getByName("release") {
            if (hasCustomSigning) signingConfig = signingConfigs.getByName("custom")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.17.0")
    // MIUI / HyperOS 风格组件库（取代 Material3）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4-rc01")
    // 设置项组件（ArrowPreference / OverlayDropdownPreference / SwitchPreference）
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4-rc01")
    // 背景流动效果依赖 RuntimeShader
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // 扫码：用成熟封装库，避免自己处理帧旋转/裁剪/二值化
    // 纯 Java 实现，不依赖 Google Play 服务
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
