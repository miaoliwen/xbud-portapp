# 小步点原生安卓版（纯 Kotlin）· 完整工程包

包名 `com.yg.xbudport`，versionName `0.1.0-port`。协议层全部在 `app/src/main/java/com/yg/xbudport/proto/`，
不依赖 gomobile AAR，也不依赖 PC 侧 Go 工具链。

## 快速构建

前置：

- JDK 17 或更高（本包用 JDK 21 验证）
- Android SDK：compileSdk 36、build-tools 36、NDK 27.0.12077973、CMake 3.22.1
- 首次构建需联网下载 Gradle 8.9 与 Maven 依赖

```powershell
# 1. SDK 路径：默认已指向 D:\Android\Sdk，换机器改 local.properties
# 2. 构建
.\build.ps1
# 或
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。直装：

```powershell
.\build.ps1 -Install
# 多设备：.\build.ps1 -Install -Device 127.0.0.1:5555
```

单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

## 目录

```
app/                    Android 应用模块
  src/main/java/com/yg/xbudport/
    proto/              双信封、签名、登录/注册、记录、轨迹、COS（纯 Kotlin）
    run/                定位/计步采集、点位选择、打卡与判定、前台服务
    ui/                 登录/主页/跑步/历史/我的，液态玻璃设计系统
    data/               账号态、加密存储、会话租约、诊断导出
  src/test/             Crypto / PointSelector / HomeData / SessionLease 等单测
  src/main/assets/      amap_secret.bin（高德 Android key 密文，本地生成，不入库）
liquidglass/            vendored LiquidGlass for Android v2.0.11（MIT），含 C++ 管线
gradle/, gradlew*       Gradle 8.9 wrapper
tools/gen_amap_secret.py  重新生成 assets/amap_secret.bin
config/amap_key.json.example  高德 key 模板（复制为 amap_key.json 后填自己的 key）
config/device_profiles.json  设备画像库（登录/提交同画像，防 10121）
docs/                  移植计划/开发日志 + 协议与认证依据
```

## 高德 key

仓库不含 key 明文，也不含 `assets/amap_secret.bin`（密文密钥由包名 + 源码内固定 pepper 派生，
等同明文，只抬高静态提取成本）。克隆后填入自己的 key 再生成：

```powershell
copy config\amap_key.json.example config\amap_key.json
# 编辑 config\amap_key.json，填入高德控制台的 key
python tools/gen_amap_secret.py config/amap_key.json app/src/main/assets/amap_secret.bin --platform android
```

`amap_key.json` 的 `key`/`securityJsCode` 是 Web(JS API) 平台凭据，Android 原生 SDK 用
`androidKey`；`--platform android` 会只取后者。Android key 需绑定 `com.yg.xbudport` +
所用 debug keystore 的 SHA1，换签名（release / 换机器）后在高德控制台补新 SHA1 再重新生成。
缺 `amap_secret.bin` 不影响构建与运行，仅地图底图不可用。

## 账号与画像

- 新账号：App 内登录会自动建默认画像。
- 老账号：必须用它在 PC 侧绑定的画像登录，否则服务端回 `10121`。在 App 设置里粘贴
  `config/device_profiles.json` 中对应的单个 profile 对象（或整个文件，会自动展开 `profiles` 数组）。
- 登录态只落 `uid/token/username/profileName`；历史记录每次从服务端拉，不做本地库。

## 数据来源

- 业务数据：`campusapi.xbud.run` 的双信封接口（登录、`get_config`、`home_page_info`、
  `get_record_list`、`get_campus_point_list`、`get_fixed_point`、`save_record`、COS STS）。
- 跑步数据：系统 `LocationManager`（GPS/网络，2s）+ `TYPE_STEP_COUNTER` + `GnssStatus`，
  WGS84→GCJ-02 后本地组装轨迹，`save_record` 重签后上传 COS 并回读校验。
- 协议细节见 `docs/K-TRUTH-20261114.md`、`docs/LOGIN-AUTH-20261116.md`、
  `docs/TECH-POINTS-CHAIN.md`、`docs/FINDINGS-SERVER.md`。

## 构建验证

本工程于 2026-11-20 用 `.\gradlew.bat --no-daemon assembleDebug` 完整构建通过（JDK 21 /
AGP 8.7.3 / Gradle 8.9，74 tasks），`testDebugUnitTest` 35 项全绿（failures=0, errors=0）。