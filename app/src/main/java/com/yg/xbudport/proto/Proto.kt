package com.yg.xbudport.proto

// Proto — App 各屏的门面（纯 Kotlin 协议层，原 xbudmobile 绑定层的同名替身）。
// 结构化数据一律 JSON 字符串进出；全部实现在 ProtoClient.kt（AuthOut 也定义在那）。

object Proto {
    val version: String get() = ProtoClient.version

    fun login(dir: String, username: String, password: String, validate: String, profile: String): AuthOut =
        ProtoClient.login(dir, username, password, validate, profile)

    fun sendRegisterCode(dir: String, phone: String, validate: String, type: Long, profile: String): AuthOut =
        ProtoClient.sendRegisterCode(dir, phone, validate, type, profile)

    fun register(dir: String, phone: String, verifyToken: String, code: String, password: String, profile: String): AuthOut =
        ProtoClient.register(dir, phone, verifyToken, code, password, profile)

    fun resetPassword(dir: String, phone: String, verifyToken: String, code: String, password: String, profile: String): AuthOut =
        ProtoClient.resetPassword(dir, phone, verifyToken, code, password, profile)

    fun newSession(dir: String, uid: String, token: String, profile: String): Session =
        ProtoClient.newSession(dir, uid, token, profile)

    fun presetList(): List<String> = ProtoClient.presetList()

    fun profilesList(dir: String): List<String> = ProtoClient.profilesList(dir)

    /** 导入单个 profile 或整个 device_profiles.json（profiles 数组展开），返回导入数量。 */
    fun profileImport(dir: String, text: String): Int = ProtoClient.profileImport(dir, text)
}
