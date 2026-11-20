# MANIFEST · 原生安卓版工程包

打包时间：2026-11-20。来源：仓库 `portapp/`（纯 Kotlin 线，`PORT-APP-DEVLOG-20261119.md` §九起）。

## 纳入内容

| 路径 | 内容 | 来源 |
|---|---|---|
| `app/src/main/java/` | 协议层 `proto/`、采集 `run/`、界面 `ui/`、账号态 `data/`，42 个 Kotlin 文件 | `portapp/app/src/main/java/` |
| `app/src/main/res/`、`AndroidManifest.xml`、`assets/amap_secret.bin` | 资源、权限、启动图标、高德 key 密文 | `portapp/app/src/main/` |
| `app/src/test/` | Crypto / PointSelector / HomeData / HistoryData / SessionLease / ErrNorm / LiquidTokens 单测 | `portapp/app/src/test/` |
| `app/build.gradle.kts` | Compose BOM 2025.10.01、OkHttp 4.12.0、AMap 3dmap 9.8.2、`:liquidglass` | `portapp/app/` |
| `liquidglass/` | LiquidGlass for Android v2.0.11 vendored 模块（MIT），含 `src/main/cpp` | `portapp/liquidglass/` |
| `gradle/wrapper/`、`gradlew*`、`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties` | Gradle 8.9 / AGP 8.7.3 / Kotlin 2.2.21 构建入口 | `portapp/` |
| `tools/gen_amap_secret.py` | 由 `amap_key.json` 生成 `amap_secret.bin` | `portapp/tools/` |
| `config/amap_key.json` | 高德 Web key + Android key | 仓库根 |
| `config/device_profiles.json` | 设备画像库（含 `V2417A~894824` 等绑定画像） | 仓库根 |
| `dist/app-debug.apk` | 本包源码重新构建的 debug APK（65.3 MB） | 见下方构建记录 |
| `docs/` | 移植计划/日志、K-TRUTH、LOGIN-AUTH、TECH-POINTS-CHAIN、FINDINGS-SERVER、NATIVE-FINGERPRINT、PROTOCOL-ONLY、ENVELOPE-NEXT、DIRECT-PULL、UI-REBUILD | `docs/` |

## 构建验证

- 命令：`.\gradlew.bat --no-daemon assembleDebug`（本目录，JDK 21.0.10，Android SDK 36 / NDK 27.0.12077973 / CMake 3.22.1）
- 结果：`BUILD SUCCESSFUL in 1m 7s`，74 tasks
- 产物：`dist/app-debug.apk`，65,306,116 bytes，SHA-256 `314be95dd6f2fbfa8543c6442d5ecf1dcad62d08f8114ed968560945f7461730`
- 单测：`testDebugUnitTest` 35 项全绿（failures=0, errors=0）。

## 未纳入（原仓库路径）及原因

| 路径 | 原因 |
|---|---|
| `portapp/app/build/`、`portapp/liquidglass/build/`、`portapp/.gradle/`、`.kotlin/` | 构建缓存；本包已实测可重新生成 |
| `portapp/libs/xbud.aar` | 旧 gomobile AAR 路线（28.5 MB），纯 Kotlin 版不再引用 |
| `portapp/build_app.ps1` | 旧脚本仍会先 `gomobile bind`，与当前纯 Kotlin 构建不符；已用本包 `build.ps1` 替代 |
| `goxbud/` | PC/服务端 Go 工具链，App 不依赖；协议参考实现见逆向包 `02-protocol/reference-go/` |
| `_apk/`、`analysis/`、`cacert/` | 原版逆向产物，单独放 `BUNDLE-REVERSE-20261120` |

## 敏感项

- `config/device_profiles.json` 含设备/安装 ID，不含 token 或密码。
- 单测里出现的手机号（`19551338692`、`17946354580` 等）是固定测试夹具，不是可用账号。
- `local.properties` 是当前机器路径（`D:\Android\Sdk`）；换机器改这一行或删掉让 Android Studio 重建。

## 公开仓库未纳入

| 路径 | 原因 |
|---|---|
| `config/amap_key.json` | 高德 key 明文凭据；仓库只带 `config/amap_key.json.example` |
| `app/src/main/assets/amap_secret.bin` | 密文密钥由包名 + 源码内 pepper 派生，等同明文；按 README「高德 key」本地生成 |
| `dist/app-debug.apk` | 构建产物，且内嵌上述密文；源码可重新构建 |
| `local.properties` | 本机 SDK 路径；模板见 `local.properties.example` |