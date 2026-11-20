# 移植版研发日志 · 2026-11-19（M0/M1 第一日）

> 计划见 `docs/PORT-APP-PLAN-20261119.md`。本文只记移植版（portapp + goxbud/mobile）线，
> 与 `DEVLOG-20261119.md`（服务端 v0.2.33 线）互不覆盖。

## 一、认证三件套协议实现（pkg/proto/flow_auth.go，零设备）

- 登录/注册/忘记密码/logout/get_info + `CallDirect` 通用信封 POST，全部纯协议。
- 未登录态 header：`uid="-1"`、`token=""`、`tokenSign=""`（LOGIN-AUTH §2 实测形态）；
  登录响应按 header keyData 解（`decryptAuth` header 优先、body 兜底）。
- 注册/忘记密码请求形态取自反编译源（此前计划里的 M0 端点还原，已提前完成）：
  - `register_verify_code {phone, code:<易盾validate>, type:0|1}` → `verifyToken`
    （wt0.java:127 / vy.java:129；`type` 字段名 = `AgooConstants.MESSAGE_TYPE`，已核实字面值）
  - `register / reset_password {phone, verifyToken, code:<短信码>, password}`
- `findToken/findVerifyToken` 为 loginauth `_find_token` 的 Go 同源；`AuthResult` 统一
  error/message/token/uid/verifyToken/raw。
- `client.go` 新增 `Get`（无 body 端点的 GET 通道，get_info 用）。
- `go test ./pkg/proto/ -count=1` 绿。

## 二、gomobile 导出层（goxbud/mobile → xbudmobile）

- 包 `xbudmobile`：`Login/SendRegisterCode/Register/ResetPassword`（未登录态）+
  `Session`（newSession：uid/token/画像/代理 → DirectClient）+
  `Health/GetInfo/GetConfig/CampusPoints/FetchFixedPoints/Records/RecordDetail/Call/Logout` +
  画像管理 `PresetList/ProfileEnsure/ProfileSummary/ProfileBind`。
- 结构化数据一律 JSON 字符串进出（gomobile 边界约定）。
- `LoginIdentity`（proto）取具名画像但不写 Bind——登录发生在知道 uid 之前，不能绑。
- bind 产物：`portapp/libs/xbud.aar`（27.8MB）+ sources.jar。绑定面：
  `xbudmobile.Xbudmobile.login/newSession/...`（静态）+ `xbudmobile.Session`。
- go.mod 记录 `tool golang.org/x/mobile/cmd/gomobile`；复跑：
  `cd goxbud && go tool gomobile bind -target=android -androidapi 26 -o ../portapp/libs/xbud.aar github.com/local/xbudtool/mobile`

## 三、M0 探针 CLI（cmd/xbudprobe）

- `login / regcode / register / reset / call / getinfo / logout` 七个子命令，与 mobile 层同源。
- 实测入口：
  `build/xbudprobe.exe login --dir tools --user <手机号> --password '***' --validate <CN31_...>`

## 四、App 骨架（portapp/）

- Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.10.01；minSdk 26 / target 35；
  包名 `com.yg.xbudport`（占位，定死后申请高德 Android key 时填这个 + SHA1）。
- 依赖：AAR + compose-bom + navigation-compose + lifecycle + `com.amap.api:3dmap/location`（mavenCentral，latest.release）。
- 页面：登录 / 注册（两步）/ 忘记密码（两步）/ 主页进度骨架 / 我的骨架 / 跑步（MapView 已挂）。
- 易盾验证码 WebView（`ui/captcha/YidunCaptcha.kt`）：官方 H5 mobile.v2.13.5 + JSInterface
  （onReady → captchaVerify()，onValidate → validate），与 LOGIN-AUTH §4 同契约。
- AMap 隐私合规四连调（MapsInitializer + AMapLocationClient 的 Show/Agree）在 `XbudApp.onCreate`。
- 高德 key 不写 Manifest：`assets/amap_secret.bin` 为 AES-GCM 密文，`XbudApp` 运行时解密后
  用 `MapsInitializer.setApiKey` 注入；生成脚本 `portapp/tools/gen_amap_secret.py`。
  `amap_key.json` 的 `key` 是 Web(JS API) 平台 key，`androidKey` 是控制台新建的 Android 平台
  key（包名 `com.yg.xbudport` + debug SHA1）；生成时用 `--platform android`。MuMu 等 x86
  模拟器仍走本地轨迹画布，arm64 真机启用原生高德地图。

## 五、网络环境记录（M0 冒烟挂起）

- 本机 Clash TUN fake-ip 活跃（campusapi.xbud.run → 198.18.0.10）；代理节点当前对该域名
  TLS 掐断（Go/curl/Python 三栈同症状：EOF / handshake failed）。
- DoH（223.5.5.5）拿到真 IP 直拨超时——当前出口对 EdgeOne 边缘不可达。
- netdial 自愈拨号按设计工作（真 IP + utls okhttp 指纹），瓶颈在链路本身。
- **登录冒烟（10116 判据）等链路恢复即可跑**，命令见 §三。Azure 侧生产链路不受影响。

## 六、续（04:00-04:15）：M0 登录闭合 + M5 全链实现

### 登录链闭合判定（全部实测）

| 实验 | 结果 | 结论 |
|---|---|---|
| Go 登录（错误密码） | 响应解密成功，`error=11307` | 信封/解密链全通，卡参数面 |
| Python 对照（同账号） | `10116` | 服务端行为未变 |
| Go header 明文 vs Python Device.header | 键序/值逐字节同构 | 排除 payload |
| 同一 validate 两键序（字母序/插入序） | 双 `10116` | 排除 JSON 键序 |
| 同一 Python 栈换解析路径（TUN/真 IP） | 双 `10116` | 排除边缘版本分叉 |
| **Go 信封原样经 Python 传输重放** | **`10116`** | **信封字节无错，差异在 Go 传输层** |
| TLS 指纹矩阵（chrome/okhttp/firefox/ios/golang） | chrome→11307；其余→10104 | chrome 指纹在登录端点必拦；其余可达业务层 |
| 同 validate 连打 3 次 | 11307→10104→10104 | **WAF 对新源首连拦截一次，第二次起放行** |

处置：
- `flow_auth.sendAuth` 加 11307 自动重发（2s 间隔 ×2，`isWafIntercept` 解 error 码判定）。
- `netdial.HelloOverride` + `XBUD_TLS_HELLO` 诊断开关保留（chrome 在登录端点必拦，勿用）。
- 之后实测撞上 `10007`（message 全问号脱敏）= 登录失败次数过多的账号限流——测试号 19551338692
  当日吃下 20+ 次错误密码探针触发；**登录链判定为闭合**（10116/10104/11307 三态语义干净，
  真实密码登录不进失败计数器），等限流窗口过后 App 内真实登录即验终态。

### M5 学分跑 Go 侧（新增两文件）

- `pkg/track/realrun.go`：`RunSample` + `AssembleReal`——真实定位/计步样本 → COS 轨迹对象。
  占位点坐标沿用上一有效点不推进距离（Generate 同语义）；totalDis = 逐点几何累加（几何比天然≈1）；
  Type 5/6/-1/0；10s 窗 SpeedWin/StepWin 从样本插值；ID 三段独立基址。
- `pkg/proto/flow_realrun.go`：`RealRunInput/RealRunResult` + `SaveRealRunDirect`——
  真实值 save_record（21 键重签，距离以轨迹几何为准回填）→ `track.Encode` → COS PUT/回读 →
  real-profile Verify 随结果返回。
- `mobile.SubmitRun` 导出；AAR 已重绑（28.5MB）。

### M5 学分跑 App 侧（新增 RunEngine.kt + RunScreen.kt 重写）

- `RunEngine`：系统 `LocationManager`（GPS/网络，2s）采样 + WGS84→GCJ02 + TYPE_STEP_COUNTER
  计步 + 进圈 50m 打卡判定（普通点≥2 + 必经点）+ 四项判定（距离≥1km、配速 3.5–10、
  步频 50–300、打卡）；AMap 3D 地图在 arm64/x86_64 + Android 平台 key 时启用，
  key 平台不匹配或 x86(32 位) 时用本地轨迹画布兜底。
- `RunScreen` 完整状态机：选距离(1-4km) → 领取点位（fetchFixedPoints + campusPoints pid 归并）
  → 实时轨迹 Polyline + 打卡圈标注 + 相机跟随 → 暂停/继续/结束 → 结果卡（判定结论）→
  SubmitRun（rrid 回显）。提交时显示「组装轨迹 / 提交记录 / 上传轨迹 / 校验轨迹」进度，
  COS PUT 按字节回调。
- 主页新增「历史」入口：`get_record_list` 按 `pageNum` 分页（原 App 参数名），
  列表展示日期/距离/时长/配速/状态，点击走 `get_record_detail` 展示判定项。
- 跑步页读取 `get_config` 做时段闸门与四项判定阈值；`RunService` 前台保活，切 tab/锁屏
  继续采集；实时显示下一点位/距离、GPS 弱提示、暂停上限；历史详情支持 COS 轨迹回放。
- 选点顺序按原版 L2：首点就近（last known），后续优先 `relatedPids` 随机并避开上一点，
  候选耗尽回退就近。
- assembleDebug 通过：`app-debug.apk` 111MB（04:14）。

## 七、构建结果与待办

1. **assembleDebug 首次构建通过**：`portapp/app/build/outputs/apk/debug/app-debug.apk`
   （83MB，含 4 ABI 的 libgojni + 高德 SDK；包名 com.yg.xbudport，versionName 0.1.0-port）。
   两个坑已修：3dmap 9.8.2 内置 location 全套类（再引 location 是 duplicate class，
   只留 3dmap）；AMap 版本必须钉死（latest.release 的元数据查询在慢网下挂死构建）。
   9.8.2 带 x86_64 native 库，MuMu 也能跑原生底图。
   复跑：`portapp/build_app.ps1`（bind + assembleDebug，-Install 直装）。
2. 网络恢复 → `xbudprobe login` 冒烟（10116）→ `regcode type=0/1` 实测（需测试手机号收短信）。
3. 高德 Android key 已创建（控制台 `xbudport-debug`，包名 `com.yg.xbudport` + debug SHA1），
   已加密进 `assets/amap_secret.bin`；Manifest 不再写 key。release 签名时需在控制台补发布版 SHA1。
4. M2：登录/注册/忘记密码三屏真机走通；M3 主页进度环形 UI（runRuleModel 已能拉到）。

## 九、纯 Kotlin 原生版（05:00-05:20）：去 Go 去 AAR

- 目标（用户指令）：协议层整体移植进 App（Kotlin），不依赖 gomobile AAR；模拟器实测登录闭环。
- 新增 `com.yg.xbudport.proto` 五件：
  - `Crypto.kt`：bionic TYPE_3 BRand / K0FromSeed / AES-128-CBC(IV0,PKCS7) / md5 / RSA(N_REAL)/PKCS1 / session16 / randomKeyData / uuid4
  - `Envelope.kt`：wrapP 手工拼串（字段序字节级对齐 crypto.py）、offlineEnvelope、decryptOffline/Any（session16）
  - `Sign.kt`：xutils 形态 md5 签名（cmpIgnoreCase 排序 + char 低 8 位截断）、tokenSign、runSignature(21 键)
  - `Identity.kt`：DeviceIdentity / loginIdentity（isRoot=0 + 64hex DeviceId）/ Profile + ProfileStore
    （device_profiles.json 同 schema 读写、随机 ID 实例化、导入）/ userAgentFor
  - `ProtoClient.kt`：OkHttp 传输（原 App 同款网络栈）、认证三件套、Session（call/getInfo/
    getConfig/campusPoints/fetchFixedPoints/records/detail/logout）、11307 首连自动重发
  - `RealRun.kt` + `Cos.kt`：真实样本装配 → save_record（21 键重签）→ STS + q-sign → COS PUT/回读
- `app/build.gradle.kts`：去 AAR，加 okhttp 4.12.0 + buildConfig；APK 83MB（轻 28MB，无 libgojni）。
- 登录页加 `validate（调试直填）`（BuildConfig.DEBUG 才显示）——模拟器自动化与诊断用，
  填了就跳过 WebView。
- 工程坑：`do` 是 Kotlin 硬关键字（Cos.kt 的 doCall 改名）；gradle.properties 加
  `systemProp.java.net.preferIPv4Stack=true`（daemon loopback ::1/127.0.0.1 错配根治）。
- 传输层简化：端上无需 utls 指纹/DoH 自愈（无 Clash TUN fake-ip 问题，okhttp+Conscrypt
  就是真机形状）；WAF 首连重发保留。

## 十、模拟器实测（05:40-06:40）：纯 Kotlin 登录链全通

- 单测 6/6 绿：`CryptoTest` 用 crypto.py 自测向量（K0 篇种子/session16 四串）+ 真机登录信封
  （`_devdata/login_env.json` 解出 body 明文、h=md5(P) 校验）双验证 Kotlin 密码层逐字节正确。
- 模拟器（test35 / API 35 / WHPX）：安装 → UI 注入账号/密码 → validate 文件注入
  （`run-as` 推 `files/debug_validate.txt`）→ 登录 → **服务端真实响应 10116**（解密 + 中文呈现）。
- 10116 = Azure env 里 13205559828 的密码过期（env 是 11-16 写的）——链路每一环
  （验证码接受 / 信封受理 / 响应解密 / 错误码解析）都在模拟器上实证工作；
  真实密码是用户侧 secret，填入即达 10000/token。
- 环境坑全记：双 adb 版本互杀 server（统一到 SDK 37.0）；模拟器 auto-register 不稳
  （显式 `adb connect 127.0.0.1:5555` 走 TCP serial）；大传输断流（APK 按 5MB 分块推送
  设备端拼接，MD5 逐位核对）；gradle daemon ::1 错配（systemProp IPv4 固化）；
  同 AVD 双实例 FATAL（插件 30s 超时假象 + 手动启动撞锁）。

## 八、真机首轮实测（04:22-04:26）与两处 UI 修复

- 真机装 APK 实测：WebView 验证码 → 信封登录 → 解密 → 错误码呈现 **全链走通**。
- `[10007]`（message 服务端脱敏为问号）= 测试号 19551338692 被当日的 20+ 次错误密码探针
  触发账号级限流，等冷却；真实密码登录不进失败计数器。
- 下一坎：894824 绑定画像 `V2417A~894824`（device_profiles.json），App 无此画像会 10121 →
  `ProfilesList/ProfileImport` 导出 + 登录页「＋导入画像（粘贴 JSON）」，单个 profile 或整个
  device_profiles.json 均可（profiles 数组自动展开）。`build/profile-894824.json` 已导出备用。
- `缺少易盾 validate` 一例：官方 H5 在智能无感失败时也回调 onValidate（result=false、
  validate 空、随后页面自己出滑块兜底），桥接未判 result 把空串放进了登录 →
  修为 result=="true" 且 validate 非空才放行，失败态弹窗保持打开。
- 10007 复现 + 修复批次：APK 04:46（含验证码桥接修复 + 画像导入）。

## 十一、闪退收敛（15:00-15:20）：四处进程级异常

对 portapp 全量过一遍「未捕获即杀进程」的路径，改掉 4 处；每条都在 MuMu（API 35，
x86_64）上复跑验证，crash buffer 干净。

| # | 位置 | 触发路径 | 修法 |
|---|---|---|---|
| 1 | `ui/main/HistoryScreen.kt` openDetail | `withContext` 里的 try/catch 只做 rethrow，异常逃出 `scope.launch` → 无 handler → 杀进程。点任意一条历史记录，弱网/token 失效/详情结构异常都崩 | 整段 `runCatching { withContext(IO){…} }`，ErrTokenExpired 单独映射「登录已过期」文案 |
| 2 | `ui/captcha/YidunCaptcha.kt` | 两个：`destroy()` 时 WebView 还挂在弹窗视图树上；H5 在弹窗关闭后仍异步回 `onReady`/`onValidate`，对已销毁 WebView 调 `evaluateJavascript` 抛 RuntimeException | 桥接加 `alive` 闸门；销毁改为「摘父容器 → removeJavascriptInterface → stopLoading → loadUrl("about:blank") → destroy」 |
| 3 | `ui/main/RunScreen.kt` MapView | 三个：`onCreate` 只在 AndroidView 工厂里，进场时 Activity 已 RESUMED 就再也收不到 ON_RESUME，GL 面不 resume；`onDestroy` 在 onDispose 里调而视图还挂在树上；`MapView(ctx)` 构造失败（ABI 指纹骗过判断 / SDK 在 API 34+ 注册广播被拒）直接掀掉整个跑步页 | `teardownMap()` 统一收口（先摘父容器再 onPause/onDestroy，幂等），工厂里补一次 resumed 分支的 onResume，构造包 `runCatching` 失败退本地画布 |
| 4 | `run/RunService.kt` | `foregroundServiceType="location"` 下，Android 14+ 启动瞬间缺定位权限抛 `SecurityException`、Android 12+ 后台启动抛 `ForegroundServiceStartNotAllowedException`——两者都在 service 进程里未捕获 | 前置权限检查 + `ServiceCompat.startForeground` 带 type，外层 try/catch 兜住后 `stopSelf()` |

顺带两处非崩溃项：登录页 `debug_validate.txt` 的读取移出组合帧（慢盘上首帧 ANR）；
`Sign.javaValue` 的双精度 `String.format` 钉 `Locale.US`（签名是线格式，随 Locale 漂会
导致服务端拒签）。

复跑：`portapp/build_app.ps1`（gomobile bind 已不再需要，纯 Kotlin 后可直接
`gradle assembleDebug`）。模拟器实跑：登录（易盾 H5 无感通过 → 10116 密码错，链路通）、
主页/跑步/我的切 tab 多轮、前后台切换，`logcat -b crash` 空。

## 十二、跑步板块对抗式审查 + 计时/暂停/选点复刻（16:00-16:20）

### 12.1 崩溃面：把「静默失败」变成「可见失败」

对抗式过一遍跑步链路，真正的进程级崩溃点上一轮已收口（§十一）。这一轮查出的是
**更致命的一类：页面看似正常、实际什么都没画**。

| 现象 | 根因 | 修法 |
|---|---|---|
| 跑步页打开是空白/黑屏，点位一个都看不到 | `MapView`（SurfaceView 版）在 MuMu 与天玑/Mali 上 `nativeCreateAMapEngineWithFrame` 失败，map 对象存在但 GL 面起不来；失败只在 `System.err` 里，App 不知道 | 换 `TextureMapView`（纹理版走 View 合成，掉 surface 只黑一帧）；加 `onMapLoaded` 10s 看门狗，没等到就 `teardownMap()` 退本地轨迹画布，点位照常标 |
| 点位在图上不出现（即使地图正常） | 标注 effect 只以 `engine.checkpoints`/`passes.size` 为 key；`aMap` 在工厂里赋值的时刻晚于首次组合，effect 早退后**永远不会再跑** | `LaunchedEffect(aMap, engine.checkpoints, engine.passes.size)`，`aMap` 就绪即补画 |
| 两个 pid=0 的点位，打卡一个算通过两个 | 判重用 `pid == pid || name == name`，归并失败的 pid 全是 0 | `Checkpoint.key` 做本次跑内稳定槽位，全链路改按 key 判重 |
| 长距离跑久了地图卡顿、点位闪烁 | 每 2s `remove()+addPolyline()`，GL 对象持续抖动 | 单条 Polyline 原地 `points = pts`；相机跟随加开关与节流 |

### 12.2 计时 / 暂停：按原版语义重写

- **秒表 1Hz 独立驱动**：原来 UI 直接读 `currentDurationSec()`，只在 `meters` 变化（有
  GPS 采样）时才重算——弱信号下秒表直接冻住。改为 ticker 每秒写 `elapsedSec` 状态，
  UI 读它，与采样解耦。
- **暂停停采**：暂停时同时摘定位 listener 与计步 listener，恢复时重新起算基线
  （原版暂停期间不计步、轨迹留空档）。原实现只停定位，暂停期间走的步数会累计进步频。
- **计步改增量累加**：`stepTotal += (raw − baseline)`，传感器原始值回退/清零时只重置
  基线、不产生跳变（MTK 机型 reboot 后 STEP_COUNTER 归零，原实现会把累计值打成负数）。
- **五项判定**：补上原版 `selMinRunTime`（`ex0.java:603`）。`unCompleteReason` 按原版码值
  输出：1 全通过 / 2 距离 / 3 点位 / 9 配速或步频 / 10 时长。
- **fivePointJson 改为全量上报**：原版 `ex0.java:542-545` 提交本次分配的**所有**点位并逐个
  带 `isPass/position`；原实现只报已通过的点。

### 12.3 点位分配：`dn0.s2` 逐行复刻（`run/PointSelector.kt`）

原版在 `get_fixed_point` 成功回调里做本地排序：

```
pointSize = (Random.nextInt(4) % 2) + 3      → 3 或 4
fPosition = Random.nextInt(pointSize)        → 必经点落位
取出第一个 isFixed==1 的点；没有则不排序（position 保持 999）
其余点枚举「长度 pointSize-1 的有序排列」
  折线长度（当前定位 → 序列）落在 [0.3×distance, 0.8×distance] → 候选
  候选非空随机取一组，否则取最接近 0.8×distance 的一组
必经点插入 fPosition，setPosition(0..n-1)
```

`PointSelectorTest` 6 条用例覆盖：3/4 点、必经点唯一、position 连续、无必经点不排序、
单点、点数不足。

### 12.4 天玑（MediaTek）兼容

- **纹理版地图**：MTK/Mali 上 SurfaceView 的 surface 生命周期更脆，纹理版交给 View 体系
  合成（本机 MuMu 从「黑图 + System.err 刷屏」变成正常出图，见截图验证）。
- **卫星数走 `GnssStatus.Callback`**：MTK 的 `Location.extras` 常不带 `satellites`，
  extras 只作兜底，键名兼容 4 种写法。
- **accuracy=0 判无效**：MTK 网络定位会回 `accuracy=0`，原逻辑会当成「精度 0 米」直接
  入库并判定打卡；现在 0/缺省一律视为无效定位。
- **ACTIVITY_RECOGNITION**：Android 10+ 读 `TYPE_STEP_COUNTER` 必需，补进 Manifest 与
  运行时权限列表；传感器缺失/恒 0 时退化为里程/步幅估算。

### 12.5 渲染与高刷

- Polyline `setUseTexture(true)` + 原地更新；Marker 用 `position` 属性移动而非重建。
- 本地画布用 `drawWithCache`，网格 Path 只在尺寸变化时重建。
- `MainActivity.applyMaxRefreshRate()`：把窗口 `preferredRefreshRate` 钉到屏幕支持的
  最高档（120/144Hz），计时与地图平移不再被系统压到 60Hz。

### 12.6 验证

- `assembleDebug` + `testDebugUnitTest` 绿（Crypto 6 + PointSelector 6）。
- MuMu（API 35 / x86_64）实跑：跑步页地图正常出图（此前 SurfaceView 版黑图）、切 tab
  三轮无崩、`logcat -b crash` 中 `com.yg.xbudport` 计数 0。
- 跑步真实链路（领点位 → 采样 → 打卡 → 提交）需有效 token，未在模拟器上跑通；本机
  账户 token 已过期，未动线上批次。

### 12.7 图标

从原版 APK（`_apk/base.apk`，`run.xbud.android` 2.6.6）取出 launcher 图标的全部密度档，
按原尺寸落进 `res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`（48/72/96/144/192 px，
即原版 `bg.png`/`y2.png`/`nY.png`/`8-.png`/`dK.png`），Manifest 的 `android:icon` 从
`@android:drawable/sym_def_app_icon` 换成 `@mipmap/ic_launcher`。原版自身也是位图图标
（无 adaptive icon），照搬同款形态，桌面/最近任务里与原版一致。

## 十三、页面重设计：只留必需元素

认证页、主页、我的三屏按「一屏一件事」重排，砍掉解释性文案与重复信息。

| 页 | 去掉 | 留下 |
|---|---|---|
| 登录 | 版本号副标题、常驻的设备画像选择器、常驻 validate 输入框 | 图标 + 字标、手机号、密码、登录、注册/忘记密码；画像与 validate 收进右上角齿轮弹窗 |
| 注册 / 忘记密码 | 底部「返回登录」文字链、页面内的画像选择器（沿用登录设置里选的那份） | 左上返回箭头、标题、字段、主按钮 |
| 主页 | 「跑步规则」整卡（8 行阈值）、服务端提示行、周/日两条进度条及其说明、重复的百分比文案 | 标题 + 历史/刷新、学期环形、本周(含目标)/跑量/今日三项、去跑步 |
| 我的 | 学籍信息 7 行、账号状态 3 行、昵称行、uid 行 | 头像 + 姓名 + 学号·院系、一行状态（实名·资料·校区）、退出登录 |
| 跑步 | 标题里的「· 领取点位跑距」 | 标题、跑距、规则异常提示、主按钮 |
| 历史 | 标题从「历史跑步记录」缩成「历史记录」 | 不变 |

交互上的统一：主按钮统一 52dp 高；表单列宽收窄到 32dp 边距并整体居中；刷新从文字按钮改成图标，
标题层级从 headlineMedium 降到 headlineSmall（原尺寸在大屏上压过内容）。

验证：`assembleDebug` 通过；模拟器逐屏截图核对（登录 / 设置弹窗 / 主页 / 我的 / 跑步），
`logcat -b crash` 中 `com.yg.xbudport` 计数 0。

## 十四、QMUI_Android 接入（UI 组件层）

- 依赖 `com.qmuiteam:qmui:2.1.0`（Maven Central）；`Theme.Xbud` 改挂 `QMUI.Compat.NoActionBar`，
  页面底 `#F4F5F7`、主色 `#1B88EE`、分隔线 `#DEE0E2`，状态栏/导航栏保持浅色图标。
- Compose 主题 `ui/theme/Theme.kt` 与 XML 主题同一套令牌；页面骨架与地图浮层仍由 Compose 承担，
  QMUI 组件经 `ui/components/Qmui.kt` 接入：`QMUIGroupListView`（我的资料 / 主页规则）、
  `QMUIRoundButton`（全站主按钮）、`QMUIRadiusImageView`（圆形头像）、`QMUILoadingView`（加载态）、
  `QMUIDialog`/`QMUIDialogAction`（确认、编辑、导入）、`QMUIBottomSheet`（画像与登录设置）。
- 逐屏：登录 / 注册 / 忘记密码改为 QMUI 表单视觉 + 胶囊主按钮；主页改为白色大卡环形进度
  + 三列统计 + 规则分组列表；我的改为头像卡 + 资料/账号/更多三组 QMUI 列表，退出登录走
  QMUI 确认框；历史列表改卡片式，详情与轨迹回放用圆角对话框；跑步页浮层统一白卡、胶囊
  按钮，结束跑步与 GPS 弱提示走 QMUI 对话框。
- 验证：`assembleDebug`、`testDebugUnitTest` 通过；MuMu（SDY_AN00）逐屏截图核对——登录、
  设置底部弹层、画像选择、调试 validate 对话框、主页/我的会话失效态、跑步选距卡渲染正常；
  `QMUIGroupListView` 在 Compose 滚动容器内 wrap-content 测量正常。

## 十五、get_config 拉不到跑步规则（11-20 凌晨修复）

**现象**：主页「跑步规则」分组消失，跑步页提示「跑步规则加载失败，将使用默认阈值」。

**定位**：用 sidecar tokens.json 里的活 token（uid 1198982）在 JVM 上直调协议复现。
对照组 `get_record_list` 正常（token 有效），`get_config` 回
`{"error":-10000,"message":"ERROR","data":null}`——网关兜底异常。参数矩阵扫出来：

| runMode | 结果 |
|---|---|
| 0（原 body） | -10000/ERROR，服务端兜底异常 |
| **1** | **正常受理**；时段外回 `11016`「当前不在有效时间内……跑步有效时间为06:30-21:00」+ `data:null` |
| 2/3/4/5 | -10000/ERROR |

即服务端只认 `runMode=1`（学分跑）。11-14 K-TRUTH §13.4 用 runMode=0 还能拿到 989B
完整规则，11-15 起 Go 侧 health 探针就报错误信封——服务端在此期间收紧了校验，
K-TRUTH §13.4 的 runMode=0 结论作废。另外确认：**时段闸门做在规则下发上**，
时段外该端点不回 runRuleModel，只回 11016 + message 里的时段。

**修复**：
- `ProtoClient.getConfig()` body 改 `runMode:1`。
- `HomeData.parseRunRule` 处理 11016：从 message 正则解出时段
  （`(\d{1,2}:\d{2})[-–~至](\d{1,2}:\d{2})`），返回仅含 validWindows 的窗口规则——
  闸门（isOpen/nextOpenHint）与「有效时段外 · 今日 06:30」提示照常工作；
  解不出时段或其余错误码仍回 null（走加载失败/默认阈值，不虚构规则）。
- 顺带修掉迁移遗留的层级问题：主页/我的滚动容器没吃 scaffold 让出的底栏
  padding，「跑步历史」被底栏压住。scaffold 经 `LocalLiquidContentPadding`
  下发 padding，页面滚动容器、跑步页三个贴底浮层统一改读它。

**验证**：单测 35 项全绿（新增 HomeDataTest 3 项锁死三种响应形态）；真机装包
推活会话实测——主页「有效时段 06:30–21:00」正常显示，去跑步禁用 + 提示正确，
我的页资料卡/分组/标签正常。时段内（06:30 后）应拿到含 runRuleModel 的完整
规则，此形态待开窗后实测一次确认。

**探针过程产物**：临时 ConfigProbeTest 跑完即删；SSH 隧道用完即撤。
