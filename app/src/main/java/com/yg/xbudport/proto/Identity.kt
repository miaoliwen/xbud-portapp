package com.yg.xbudport.proto

import org.json.JSONObject
import java.io.File
import java.util.Random

// Identity — 协议头的设备面 + 画像库（与 PC 工具链 device_profiles.json 同 schema）。
//
// 业务基线 DefaultIdentity 与登录面形状不同（实测 11307 对拍）：
//   登录面要求 isRoot="0"、DeviceId 64 hex（loginDeviceID）；具名画像自带这些。

data class DeviceIdentity(
    val uid: Long,
    val deviceID: String,
    val customID: String,
    val deviceName: String,
    val cpuModel: String,
    val pixel: String,
    val logicPixel: String,
    val osVersion: String,
    val platform: String,   // ""=android / "ios"
    val isRoot: String,
    val androidID: String,
    val imei: String,
    val blMac: String,
    val wifiMac: String,
    val appInstall: String,
    val appUpdate: String,
) {
    val osType: String get() = if (platform == "ios") "1" else "0"
}

/** 业务/登录默认基线（Samsung SM-A5260 实测对照）。 */
fun defaultIdentity(uid: Long): DeviceIdentity = DeviceIdentity(
    uid = uid,
    deviceID = "431d3582fa89c99a039816e9c73da6ba",
    customID = "AA40160A047430DB5C9A43970749034B",
    deviceName = "Samsung SM-A5260",
    cpuModel = "x86_64",
    pixel = "1080x1920",
    logicPixel = "617x1097",
    osVersion = "15",
    platform = "",
    isRoot = "1",
    androidID = "",
    imei = "",
    blMac = "02:00:00:00:00:00",
    wifiMac = "02:00:00:00:00:00",
    appInstall = "0",
    appUpdate = "0",
)

/** 登录面实测通过的 DeviceId（64 hex，loginauth Device 默认值）。 */
const val LOGIN_DEVICE_ID =
    "460d04cfdef0a217972232ae3765a166977c91addf66c2ac51510505008d018b"

/** 登录态设备面：uid 恒 -1、isRoot=0、DeviceId 64 hex；画像按名字取（不写 bind）。 */
fun loginIdentity(dir: String, profileName: String): DeviceIdentity {
    var id = defaultIdentity(-1).copy(isRoot = "0", deviceID = LOGIN_DEVICE_ID)
    val prof = profileForLogin(dir, profileName) ?: return id
    id = id.copy(
        deviceName = prof.model,
        cpuModel = prof.cpu,
        pixel = prof.pixel,
        logicPixel = prof.logicPixel,
        osVersion = prof.osVersion,
        isRoot = prof.isRoot,
        androidID = prof.androidId,
        deviceID = if (prof.deviceId.length == 64) prof.deviceId else LOGIN_DEVICE_ID,
        customID = prof.customId,
        imei = prof.imei,
        blMac = prof.blMac,
        wifiMac = prof.wifiMac,
        appInstall = prof.appInstall,
        appUpdate = prof.appUpdate,
        platform = prof.platform,
    )
    return id
}

private fun profileForLogin(dir: String, profileName: String): Profile? {
    val name = profileName.trim()
    if (name.isEmpty()) return null
    return ProfileStore.load(dir).ensure(name)
}

// —— 画像与画像库 ——

data class Profile(
    val name: String,
    val model: String,
    val cpu: String,
    val pixel: String,
    val logicPixel: String,
    val osVersion: String,
    val platform: String,
    val isRoot: String,
    val androidId: String,
    val deviceId: String,
    val customId: String,
    val imei: String,
    val blMac: String,
    val wifiMac: String,
    val appInstall: String,
    val appUpdate: String,
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("name", name)
        put("model", model)
        put("cpu", cpu)
        put("pixel", pixel)
        put("logicPixel", logicPixel)
        put("osVersion", osVersion)
        if (platform.isNotEmpty()) put("platform", platform)
        put("isRoot", isRoot)
        put("androidId", androidId)
        put("deviceId", deviceId)
        put("customDeviceId", customId)
        put("imei", imei)
        put("blMac", blMac)
        put("wifiMac", wifiMac)
        put("appInstallTime", appInstall)
        put("appUpdateTime", appUpdate)
    }

    companion object {
        fun fromJSON(o: JSONObject): Profile = Profile(
            name = o.optString("name"),
            model = o.optString("model"),
            cpu = o.optString("cpu"),
            pixel = o.optString("pixel"),
            logicPixel = o.optString("logicPixel"),
            osVersion = o.optString("osVersion"),
            platform = o.optString("platform"),
            isRoot = o.optString("isRoot", "0"),
            androidId = o.optString("androidId"),
            deviceId = o.optString("deviceId"),
            customId = o.optString("customDeviceId"),
            imei = o.optString("imei"),
            blMac = o.optString("blMac"),
            wifiMac = o.optString("wifiMac"),
            appInstall = o.optString("appInstallTime", "0"),
            appUpdate = o.optString("appUpdateTime", "0"),
        )
    }
}

/** 内置机型预设（与 PC 工具链同名单的 Android 子集）。 */
data class Preset(val name: String, val model: String, val cpu: String, val pixel: String, val logicPixel: String, val osVersion: String)

val PRESETS = listOf(
    Preset("小米14", "Xiaomi 23127PN0CC", "arm64-v8a", "1080x2400", "360x800", "14"),
    Preset("Redmi K70", "Redmi 23113RKC6C", "arm64-v8a", "1200x2670", "393x875", "14"),
    Preset("iQOO Neo9", "vivo V2338A", "arm64-v8a", "1080x2400", "360x800", "14"),
    Preset("OPPO Reno11", "OPPO PJH110", "arm64-v8a", "1080x2412", "360x804", "14"),
    Preset("荣耀90", "HONOR REA-AN00", "arm64-v8a", "1080x2388", "360x796", "13"),
    Preset("华为Mate60", "HUAWEI ALN-AL00", "arm64-v8a", "1080x2400", "360x800", "12"),
    Preset("realme GT Neo5", "realme RMX3706", "arm64-v8a", "1080x2400", "360x800", "14"),
    Preset("一加 Ace2", "OnePlus PHK110", "arm64-v8a", "1080x2400", "360x800", "13"),
    Preset("三星 A52", "Samsung SM-A5260", "x86_64", "1080x1920", "617x1097", "15"),
)

fun presetNames(): List<String> = PRESETS.map { it.name }

/** 画像库：device_profiles.json 读写 + 随机 ID 实例化 + 导入。 */
class ProfileStore private constructor(private val file: File) {
    val profiles = LinkedHashMap<String, Profile>()
    val bind = LinkedHashMap<String, String>()

    companion object {
        fun load(dir: String): ProfileStore {
            val store = ProfileStore(File(dir, "device_profiles.json"))
            runCatching {
                if (store.file.exists()) {
                    val o = JSONObject(store.file.readText(Charsets.UTF_8))
                    val ps = o.optJSONObject("profiles")
                    if (ps != null) {
                        for (key in ps.keys()) {
                            runCatching { store.profiles[key] = Profile.fromJSON(ps.getJSONObject(key)) }
                        }
                    }
                    val bs = o.optJSONObject("bind")
                    if (bs != null) {
                        for (key in bs.keys()) store.bind[key] = bs.optString(key)
                    }
                }
            }
            return store
        }
    }

    fun save() {
        val o = JSONObject()
        val ps = JSONObject()
        for ((k, v) in profiles) ps.put(k, v.toJSON())
        val bs = JSONObject()
        for ((k, v) in bind) bs.put(k, v)
        o.put("profiles", ps)
        o.put("bind", bs)
        file.parentFile?.mkdirs()
        file.writeText(o.toString(), Charsets.UTF_8)
    }

    /** 取/建具名画像：库里没有则按预设模板 + 随机 ID 实例化。 */
    fun ensure(name: String): Profile? {
        profiles[name]?.let { return it }
        val preset = PRESETS.firstOrNull { it.name == name } ?: return null
        val p = fromPreset(preset)
        profiles[name] = p
        save()
        return p
    }

    fun import(p: Profile) {
        profiles[p.name] = p
        save()
    }

    fun names(): List<String> = profiles.keys.toList()

    private fun fromPreset(p: Preset): Profile {
        val rnd = Random()
        fun hexStr(n: Int) = buildString { repeat(n) { append("0123456789abcdef"[rnd.nextInt(16)]) } }
        fun digits(n: Int) = buildString { repeat(n) { append('1' + rnd.nextInt(9)) } }
        fun mac() = "02:00:00:%02x:%02x:%02x".format(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        return Profile(
            name = p.name, model = p.model, cpu = p.cpu, pixel = p.pixel,
            logicPixel = p.logicPixel, osVersion = p.osVersion, platform = "",
            isRoot = "0", androidId = hexStr(16), deviceId = hexStr(64),
            customId = hexStr(32).uppercase(), imei = digits(15),
            blMac = mac(), wifiMac = mac(), appInstall = "0", appUpdate = "0",
        )
    }
}

/** 业务请求 UA：与画像/uid 同源派生（flow.go UserAgentFor 同款）。 */
fun userAgentFor(id: DeviceIdentity): String {
    if (id.platform == "ios") {
        val os = id.osVersion.replace(".", "_")
        return "xiaobudian/${ProtoClient.APP_VERSION} (iPhone; CPU iPhone OS $os like Mac OS X) CFNetwork/1410.0.3 Darwin/23.0.0"
    }
    val sum = Crypto.md5Hex("${id.uid}|${id.deviceName}".toByteArray()).substring(0, 8)
    return "Dalvik/2.1.0 (Linux; U; Android ${id.osVersion}; ${id.deviceName} Build/$sum.0)"
}
