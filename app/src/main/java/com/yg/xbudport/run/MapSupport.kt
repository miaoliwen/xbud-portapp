package com.yg.xbudport.run

import android.content.Context
import android.os.Build
import com.yg.xbudport.data.SecretStore

// 高德地图可用性。
//
// 3dmap 9.8.2 带 arm64-v8a/armeabi-v7a/armeabi/x86_64 native 库；x86（32 位）没有。
// 地图一律用 TextureMapView：天玑/联发科机型的 Mali GPU 上，GL 引擎对 SurfaceView
// 的 surface 生命周期更敏感，纹理版掉 surface 只黑一帧，不会把 native 层带崩。
object MapSupport {
    fun amapUsable(ctx: Context): Boolean {
        // MuMu 等 x86 模拟器会通过 native bridge 把 arm64 列进 SUPPORTED_ABIS，
        // 但主 ABI 仍是 x86_64，AMap 的 arm so 实际加载失败；按主 ABI 判断。
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val supportedAbi = primaryAbi == "arm64-v8a" ||
            primaryAbi == "armeabi-v7a" ||
            primaryAbi == "armeabi" ||
            primaryAbi == "x86_64"
        return supportedAbi &&
            SecretStore.amapPlatform(ctx) == "android" &&
            !SecretStore.amapKey(ctx).isNullOrBlank()
    }
}
