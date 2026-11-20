# 小步点第三方移植版 · 开发计划（2026-11-19）

> 目标：Kotlin 原生 App，复刻「登录 / 注册 / 忘记密码 / 主页跑步进度 / 个人主页 / 学分跑」，
> 移除社区、星球、签到、比赛、广告等一切多余面。协议层零逆向阻塞（登录/信封/写/轨迹/COS 全闭合），
> 本计划的 M0 之外全部是工程活。

---

## 0. 功能范围定案

| # | 功能 | 状态 | 依据 |
|---|---|---|---|
| 1 | 登录 | 协议已闭合（LOGIN-AUTH） | 双信封 + Basic auth + 易盾 validate → token |
| 2 | 注册 | 端点已知，未实测 | `POST /v1/user/login/register_verify_code`（type=0）→ `verifyToken` → `POST /v1/user/login/register`（wt0.java:127-151） |
| 3 | 忘记密码 | 端点已知，未实测 | 同上 type=1 → `POST /v1/user/login/reset_password`（vy.java:107-112） |
| 4 | 主页跑步进度 | 数据源已知，未实测 | `get_config.runRuleModel`（dayGoal/weekCountGoal/minDistance/validTime…）+ `get_record_list` + `completed_info` |
| 5 | 个人主页 | 端点已知，未实测 | `/v4/user/account/get_info`、`/v1/user/home_page_info`、`/v5/user/personal_page_header`、`/v1/user/sport_info`、`/v2/user/account/update_info`、`/v1/user/upload_icon/`、头像框 `get_head_frame_list`/`save_head_frame` |
| 6 | 学分跑（完整复刻 + 高德 SDK） | 链路全闭合 | TECH-POINTS-CHAIN 全链 + `SavePlanDirect` 同款写路径，改真实采集 |

**移除**：social/*（社区）、ai_sport/*（星球/AI 运动）、match/*（比赛）、rollcall/*、signin/*、
`/v1/util/banner`、`/v1/task/watch_video`、`/v1/ad/data_collect`、榜单/申诉（可后补）。

## 0.1 认证三件套的请求形态（已从反编译源挖出，M0 只需上线实测）

```
① 易盾验证码（同登录，neteaseValidate）
② POST /v1/user/login/register_verify_code  {phone, code:<validate>, type:0|1}   → 响应 verifyToken，服务器发短信
   type=0 注册 / type=1 忘记密码
③ POST /v1/user/login/register        {phone, verifyToken, code:<短信码>, password}
   POST /v1/user/login/reset_password {phone, verifyToken, code:<短信码>, password}
备用：POST /v1/util/send_verify_code/{phone}/{type}（hg1.java:60，body 由 eg1.m760do 签名计算，M0 还原）
```

注意：②③ 都是信封请求（同一套 AES-CBC/md5/RSA）；未登录态 header 信封 `uid="-1"`，与登录同构。

---

## 1. P0 前置（不做则 M5 无法开工）

| 项 | 动作 | 说明 |
|---|---|---|
| **高德 Android key** | 高德开放平台 → 应用管理 → 新建 Key，平台选 **Android**，填移植版包名 + 签名 SHA1（先填 debug.keystore 的） | **现有 `amap_key.json` 那把是 Web 端(JS API) 平台 key**（DEVLOG-20261119 §八 实测 `USERKEY_PLAT_NOMATCH` 确认），Android 原生 SDK 用它会报 key 错误。该 key 继续留给 xbudweb 路线编辑器；securityJsCode 同理仅 JS API 有效 |
| 包名 + keystore | 定包名（建议 `com.yg.xbudport`，占位，定死不改）+ 自签 keystore 长期保管 | 服务端不校验包名/签名，只校验信封与设备画像；包名只影响高德 key 绑定 |
| 高德合规 API | App 首启必须调 `MapsInitializer.updatePrivacyShow/updatePrivacyAgree` + `AMapLocationClient.updatePrivacyShow/updatePrivacyAgree` | 2021 后版本 SDK 不调合规接口直接不工作 |
| 账号分流 | 明确哪些账号给 App、哪些留 xbudsrv 调度 | token 服务端单会话：App 登录即顶掉 sidecar token（10121/401）。同一账号"App 在线"与"服务器定时"互斥 |

## 2. 技术栈定案

| 层 | 选型 | 理由 |
|---|---|---|
| UI | Kotlin + QMUI_Android 2.1.0 + Compose，单 Activity + Navigation | QMUI 负责主题与列表/按钮/弹层等 View 组件，Compose 负责页面骨架与地图浮层 |
| 协议 | **gomobile 把 `goxbud/pkg/proto` + `pkg/track` + `pkg/xbud`(Profile) 打 AAR** | 算法已在线上验证一个多月（OfflineEnvelope/SavePlanDirect/track 生成编码/COS），不重写不出新错 |
| 定位 | `AMapLocationClient`（高精度单次+连续） | GCJ-02 直出，与轨迹 `Point.coorType/gLat/gLng` 天然对齐；原 App 同款 |
| 地图 | AMap 3D SDK `TextureMapView`（Polyline/Marker/Circle/Polygon） | 原 App 同款 API 面（jadx 确认） |
| 步频 | `SensorManager.TYPE_STEP_COUNTER`（无传感器机型退化为距离/步幅估算） | 轨迹 `stepFreqJson` 按 10 s 窗聚合，与 SpeedWin/StepWin 结构一致 |
| 验证码 | WebView 加载易盾官方 H5（cstaticdun.126.net/mobile.v2.13.5.html）+ 注入同款 JSInterface | 原 App 3.4.3 同机制；比服务器无头 Chrome 更稳 |
| 存储 | EncryptedSharedPreferences/MMKV 存 token+画像；Room 存本地记录缓存 | MMKV 原 App 同款可选 |

新增 `goxbud/mobile`（gomobile 入口，薄壳，全部逻辑仍在现有 pkg）：

```
Login(user, password, validate, profileJSON) → token/uid
Logout(token) → err
GetConfig(token) → policy/runRuleModel/geoFence
CampusPoints(token) → 12 点位
FetchFixedPoints(token, distanceM) → pointsResModels   // 开跑会话
SaveRun(token, profile, RunResult) → rrid              // save_record + 真实轨迹 COS 上传
Records(token, page, size) / RecordDetail(rrid) / TrackOf(rrid)  // 列表/详情/轨迹回读
Register / SendRegisterCode / ResetPassword            // M0 实测后导出
```

## 3. 里程碑

| 里程碑 | 内容 | 验收 | 估量 |
|---|---|---|---|
| **M0** | 协议补课 + P0 前置 | ① 注册/忘记密码三步真机实测走通（测试手机号）② `send_verify_code` 的 eg1 签名还原 ③ `home_page_info`/`sport_info`/`personal_page_header`/`get_info`/`completed_info`/`refresh_record`/`get_weekly_sport_data`/速度步频图 9 个端点用 `xbud_proto` 直调拿到业务 JSON ④ 高德 Android key 就位 ⑤ 包名/keystore 定案 | 2-3 天 |
| **M1** | App 骨架 + 协议 AAR | `goxbud/mobile` 打出 AAR；空 App 里调 `GetConfig` 返回 10000；画像管理（复用 device_profiles.json，uid→画像绑定）落地 | 2-3 天 |
| **M2** | 登录 / 注册 / 忘记密码 | WebView 易盾 → 短信码 → token；三页全流程真机通过；错误码面（10104/10116/10121）呈现；401 自动重登提示 | 3-4 天 |
| **M3** | 主页跑步进度 | runRuleModel（dayGoal/weekCountGoal/validTime/minDistance）+ 本周/本学期完成次数与里程环形进度；时段外置灰 | 2 天 |
| **M4** | 个人主页 | get_info 全字段展示、改资料（update_info）、改头像（upload_icon multipart）、密码修改（confirm_password）；轨迹回放页（COS 回读 + AMap Polyline 回放） | 2-3 天 |
| **M5** | 学分跑完整复刻 | 见 §4 规格，逐条验收 | 5-7 天 |
| **M6** | 打磨回归发布 | 全流程回归表跑完、release 签名包、`ARTIFACTS.md`/RELEASE 成文 | 2 天 |

单人总量 **3-4 周**。M5 是最大块，但协议侧全部现成——工作量在定位策略、UI 状态机与传感聚合。

## 4. 学分跑复刻规格（M5 验收清单）

**开跑前**
- 距离选择：1/2/3/4 km（selDistance，服务端 `get_fixed_point/1/{distance}` 按 this 分配点位）
- 时段闸门：`runRuleModel.validTime`（06:30–21:00）外禁开跑；`maxRunTime/pauseTime` 读取并在 UI 提示
- `get_fixed_point` 成功后本地选点（原算法已逆向：首点就近，后续按 `relatedPids` 随机避 lastPid，TECH-POINTS-CHAIN §2）；必经点 `isFixed=1` 高亮

**途中**
- AMap 连续定位（2 s 间隔/连续高精度），实时 Polyline + 打卡点 Marker/Circle
- 点位判定（原 `isValidPoint` 在 libsport.so，App 用同义简化规则）：定位半径进入点位圈 → `isPass=true`；普通点 ≥2 + 必经点通过 → 打卡达标
- 距离/配速/步频/时长四项实时算（原 App 也是客户端算完再上报，服务端四规则复算）
- 暂停/恢复（pauseTime 上限）、GPS 弱提示（连续 invalid ≥4 次弹窗，原交互）

**结束**
- `save_record` 25 字段 + 21 键重签（模板与重签在 proto 已实现，改为真实值注入：complete/unCompleteReason/reasonList 四项按本地判定填）
- 轨迹上传 COS：真实定位点组装 `Object/Point/SpeedWin/StepWin/FivePoint`（10 s 窗聚合；编码/结构/自检在 pkg/track 现成，删掉 humanize/钳制、接真实点序列）
- 结果页：距离/时长/配速/步频/打卡点通过情况；失败态（四项任一不达标）对应 unCompleteReason 文案
- 记录出现在 `get_record_list`，详情/轨迹可回放（M4 的回放页复用）

## 5. 风险与既知边界

| 风险 | 处置 |
|---|---|
| token 单会话：App 登录顶掉 xbudsrv | §1 账号分流；App 内不做与调度并行的双活 |
| 10121 画像绑定 | 登录必须带该账号绑定画像（IdentityFor 复用 device_profiles.json）；换设备画像先在服务端/logout 流程处理 |
| 注册接口可能有号段/校区限制 | M0 实测直接回答；不行就明确"移植版仅存量账号" |
| 易盾 H5 版本/captchaId 漂移 | WebView 走官方 H5，captchaId 2451e9031eb340078cdb51c0e3691570 写配置可换 |
| appVersion 白名单 | body 恒填 `2.6.6`（现行可用）；服务端收紧再跟 |
| 高德配额 | 个人 key 日配额对单人量级足够；轨迹点本地缓存、弱网补传 |
| 敏感字段 | token/密码入加密存储；keystore 不入库 |

## 6. 里程碑依赖

```
M0（协议补课+key） ──► M1（骨架+AAR） ──► M2（认证三件套） ──► M3（主页） ──► M6
                                    └────► M4（个人主页） ────┘
M5（学分跑）依赖 M1 + M0 的 ④；可与 M3/M4 并行，最后合入 M6
```
