plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yg.xbudport"
    // 36：liquidglass 模块的 RuntimeColorFilter/RuntimeXfermode（AGSL 2.0 特性）要求
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yg.xbudport"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-port"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "META-INF/INDEX.LIST"
    }
}

dependencies {
    // 纯 Kotlin 协议层（proto/ 目录）——已去 gomobile AAR。
    // BOM 2025.10.01 锁定 Compose 1.9.4：backdrop 1.0.0 的运行时下限。
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material3 1.4 起不再传递带入 icons-core（该构件冻结在 1.7.8）；
    // 现有页面的 Icons.Default.* 依赖它，按 1.7 运行时编译、跑在 1.9.4 上属受支持方向。
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // 液态玻璃：LiquidGlass for Android v2.0.11（QWEA0/Liquid-Glass-Android，MIT），
    // 源码 vendor 成 :liquidglass 模块。Android View 体系的液态玻璃：
    // API 33+ AGSL 透镜管线（SDF 折射/色散/高光），31+ 硬件模糊，24+ C++/Kotlin 管线，
    // 低版本零崩溃降级。替代原 io.github.kyant0:backdrop:1.0.0（Compose 版）。
    implementation(project(":liquidglass"))

    // 传输层：原 App 同款网络栈（xutils 底层即 okhttp，TLS 指纹天然真机形状）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // 高德：原 App 同款 SDK。9.8.2 是最后一个带 x86_64 native 库的 3D 地图版本，
    // 可在 MuMu 等 x86_64 模拟器上跑原生底图；10.0.600 只有 arm 库。
    // 3dmap 自带 com.amap.api.location 全套，不能再引 location（duplicate class）。
    implementation("com.amap.api:3dmap:9.8.2")
}
