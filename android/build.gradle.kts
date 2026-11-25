plugins {
    // compileSdk / targetSdk = 36 (Android 16) 要求 AGP >= 8.9.1
    id("com.android.application") version "8.9.1" apply false
    // Media3 1.11.1 依赖 kotlin-stdlib 2.2.10（metadata 2.2.0），
    // 编译器必须 >= 2.1 才能读取，2.0.21 会报 incompatible metadata 并内部崩溃。
    // Compose 编译插件版本必须与 Kotlin 版本一致。
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
