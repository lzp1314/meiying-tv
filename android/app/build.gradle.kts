plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lik.iptvplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lik.iptvplayer"
        // 21 = Android 5.0。Compose 与 Media3 的下限就是这里，再往下（4.x）这套技术栈装不上。
        minSdk = 21
        targetSdk = 36
        // 覆盖安装（升级）必须递增 versionCode；包名与签名不变时
        // SharedPreferences（源列表、锁定、上次频道）会自动保留
        versionCode = 22
        versionName = "0.4.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // 输出文件名带版本号：app-debug.apk 这种名字拷到 U 盘后根本分不清新旧，
    // 覆盖安装时也不知道装的是哪一版
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "iptv-player-${versionName}.apk"
        }
    }

    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // 播放内核：Media3 1.8.1 是最后一个支持 Android 5.0 的版本；
    // 1.9 起要求 API 23，配合 minSdk 21 会直接编译失败。
    val media3 = "1.8.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // 手机与 TV 共用一套 Compose，TV 侧自行管理 D-pad 焦点，不依赖不稳定版 tv-material
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    // 全屏：WindowCompat / WindowInsetsControllerCompat
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 二维码生成（设备端显示，手机扫码推送）；不做本机扫码，因此不含 CameraX / ML Kit
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
