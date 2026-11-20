package com.yg.xbudport.data

import android.content.Context
import android.content.SharedPreferences

// AccountStore — 当前账号（uid/token/绑定画像）的本地持久化。
// 换账号 = clear 后重登；多账号后续在此扩展成列表。
class AccountStore(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("xbud_port", Context.MODE_PRIVATE)

    // gomobile 画像库与 PC 工具同款：device_profiles.json 放 filesDir
    val dir: String = context.filesDir.absolutePath

    var uid: String?
        get() = sp.getString("uid", null)
        set(v) = sp.edit().putString("uid", v).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var username: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit().putString("username", v).apply()

    // 账号绑定画像名（登录/会话必须同画像，10121 防线）
    var profileName: String?
        get() = sp.getString("profileName", null)
        set(v) = sp.edit().putString("profileName", v).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrBlank() && !uid.isNullOrBlank()

    fun clear() {
        sp.edit().clear().apply()
    }
}
