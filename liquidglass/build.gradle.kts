import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// LiquidGlass for Android（QWEA0/Liquid-Glass-Android v2.0.11）源码 vendor 模块。
// MIT，https://github.com/QWEA0/Liquid-Glass-Android —— Android View 体系的液态玻璃：
// 真实 SDF 折射 / 物理色散 / 背景模糊，API 24+，AGSL 快路径 33+，<33 走 C++/Kotlin 管线。
// 构建配置按本仓库工具链改写（原模块用 AGP 8.10-alpha01 + Kotlin 2.0.21）。

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.liquidglass"
    // API 36：RuntimeColorFilter / RuntimeXfermode（AGSL 颜色滤镜与混合模式）
    compileSdk = 36
    // 与本地 NDK 一致（原模块锁定的版本）
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24

        // compileOnly 依赖（appcompat / material / viewpager2）的 -dontwarn：
        // R8 下没引这些库也不会因 DialogBuilder / TabLayoutMediator 报缺类
        consumerProguardFiles("consumer-rules.pro")

        // NDK 原生模糊/色差加速（CPU 管线）；x86_64 = 云手机 / MuMu 等模拟器主
        // ABI，缺了会在 CPU 模糊首次调用时 dlopen 失败
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // 16KB 页对齐（Android 16 设备的 ELF 对齐要求；NDK r28 起默认，r27 需显式开启）
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // 只有 LiquidGlassDialogBuilder 用到 AlertDialog / MaterialAlertDialogBuilder，
    // 只有 LiquidGlassTabLayoutMediator 用到 ViewPager2——本仓库都不用，
    // 保持 compileOnly，不把 appcompat / material 拖进 app。
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("com.google.android.material:material:1.12.0")
    compileOnly("androidx.viewpager2:viewpager2:1.1.0")
}
