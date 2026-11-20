# 液态玻璃 UI 重建 · 技术方案（2026-11-19）

> 范围：`portapp/`（Kotlin + Compose 单 Activity App）UI 层工程化重建。
> 底座：Kyant0/AndroidLiquidGlass（Backdrop）。业务层（data/ proto/ run/）零改动，
> 界面重建全部落在 `ui/`（主题、designsystem、页面视图）。

---

## 一、现状审计

### 1.1 工程配置

| 项 | 现值 | 方案 | 理由 |
|---|---|---|---|
| minSdk / compileSdk / targetSdk | 26 / 35 / 35 | 不变 | 玻璃效果全部靠运行时降级兜底，不逼用户升系统 |
| Kotlin / Compose 编译器 | 2.0.21 | **2.2.21** | backdrop 1.0.0 以 Kotlin 2.2.21 元数据发布，2.0 编译器读不了（跳两个版本的元数据） |
| Compose 运行时 | BOM 2024.10.01（1.7.5） | **BOM 2025.10.01（ui 1.9.4 + material3 1.4.0）** | backdrop 1.0.0 POM 依赖 compose 1.9.4；运行时向后兼容，现有页面无感 |
| AGP / Gradle / JDK | 8.7.3 / 8.9 / 21 | 不变 | compose 1.9.4 的 minCompileSdk=35、minAGP=8.6，现配置即满足 |
| Backdrop | 无 | **io.github.kyant0:backdrop:1.0.0** | 见 §2 版本论证 |
| activity/navigation/lifecycle | 1.9.3 / 2.8.4 / 2.8.7 | 不变 | 均按更旧运行时编译，跑在 1.9.4 上是受支持方向 |

验证：Kotlin 2.2.21 KGP 支持 Gradle 7.6–8.10（本机 8.9 在内）；AGP 8.7.3 在其支持面内。

### 1.2 页面与组件现状

| 层 | 现状 | 处置 |
|---|---|---|
| 导航 | 单 Activity + NavHost，login/register/forgot/home/history/profile/run 七个路由 | 结构不动，只换 UI 实现 |
| 页面骨架 | `Scaffold` + 自绘 `XbudBottomBar`（QMUI 规范白底 56dp） | 换 `LiquidScaffold` + `LiquidBottomBar` |
| 组件桥 | `ui/components/Qmui.kt`（QMUI View 桥：分组列表/圆钮/加载/对话框/底栏弹层）+ `Common.kt`（QMUI 风格 Compose 组件） | 由 designsystem 的纯 Compose 组件对等替换；QMUI 桥在迁移完成后删除 |
| 主题 | `ui/theme/Theme.kt` 仅浅色（forceDarkAllowed=false） | designsystem 自带深浅双 Palette，逐页包裹，迁移收尾后再把 App 默认切到 `isSystemInDarkTheme()` |
| 动效 | 无（点击/切页/弹层均为瞬时） | designsystem 统一 spring 物理曲线 |
| 屏幕方向 | `screenOrientation="portrait"` 锁定 | 不处理旋转；WindowInsets / fontScale 1.3 / 窄屏仍需全覆盖 |
| 状态层 | `RunEngine` 为进程级单例（`RunSession.engine`），`mutableStateOf` 直读 | 不动 run/ 包；UI 侧用 lambda modifier / graphicsLayer 读，避免每帧重组 |

### 1.3 文案存量（迁移时按 §六 精简）

登录/注册/改密 3 页 + 主页/我的/历史/学分跑 4 页 + 验证码弹窗 + 设置弹层，共约 120 处中文字面量。
问题集中在这三类：加载态三种写法（"正在读取跑步进度…/正在读取个人信息…/正在读取历史记录…"）、
操作结果带过程叙述（"退出后需要重新完成易盾验证并登录。"）、空态双段式（"还没有跑步记录" + 一句说明）。

---

## 二、Backdrop 版本论证

| 版本 | 运行时 | compileSdk | 结论 |
|---|---|---|---|
| **1.0.0** | compose 1.9.4 | minCompileSdk=1（无约束） | **选用**。纯 Android 线，无 shapes 附加依赖 |
| 1.0.6 | compose 1.10.3 | minCompileSdk=36 | 需 AGP≥8.9 + Gradle≥8.13，本机只有 Gradle 8.9，否 |
| 2.0.1 | compose 1.12.0 | — | Compose Multiplatform 线，运行时 1.12 与全工程不兼容，否 |

API 面（1.0.0，均已从源码核对）：`rememberLayerBackdrop()` / `Modifier.layerBackdrop()` /
`Modifier.drawBackdrop(backdrop, shape, effects, highlight, shadow, onDrawSurface, onDrawFront…)` /
`drawPlainBackdrop()`；效果 `blur(radius)`（API 31+）、`lens(h, amount, depthEffect, chromaticAberration)`
（API 33+，限 CornerBasedShape）、`colorFilter/opacity/vibrancy/exposureAdjustment/gammaAdjustment`；
`Highlight(width, blurRadius, alpha, style)`、`Shadow(radius, offset, color, alpha)`、`InnerShadow`。
`effects` 经 `observeReads` 观测——状态读在 draw 阶段，不触发重组。

---

## 三、架构：单采样源 + 同窗口浮层

```
LiquidScaffold
├─ Box(background = 页面渐变).layerBackdrop(backdrop)   ← 唯一采样源：只画背景，无子节点
├─ Column: TopBar / content(padding) / BottomBar        ← 消费方是采样源的兄弟，不是后代
└─ overlay: BottomSheet / Dialog（AnimatedVisibility）   ← 同组合树内浮层，采样同一背景
```

三条硬规则（对应验收项）：

1. **严禁循环采样**：每屏 exactly 一处 `layerBackdrop`，挂在背景层；所有 `drawBackdrop` 消费方
   必须是它的兄弟。组件库不提供任何"导出 backdrop"的用法（`exportedBackdrop` 不用）。
2. **浮层必须同窗口**：Compose `Dialog`/QMUI 对话框都是独立窗口，采样不到 Activity 的画面。
   `LiquidBottomSheet`/`LiquidDialog` 一律做成组合树内的 overlay（scrim + 内容）。
3. **地图 HUD 不采样**：AMap `TextureMapView` 的内容不进 Compose GraphicsLayer，采不到。
   跑动态 HUD（打卡条/指标卡）用 `sampled=false` 的材质：透明遮罩直接叠在地图上，
   文字对比度由遮罩 alpha 保证（§4）。

### 降级矩阵

| 环境 | 表现 |
|---|---|
| API ≥ 33 | 全效果：blur + lens 折射 + 高光 + 投影 + tint |
| API 31–32 | blur + 高光 + 投影 + tint（lens/gamma 库内自动跳过） |
| API 26–30 | 不采样不挂 RenderEffect：高质量半透明磨砂面（tint + 高光边 + 投影），观感一致、零崩溃 |
| 减少动态效果（动画时长缩放=0） | spring 全部退化为瞬时切换；AnimatedVisibility 退化为极简淡入 |
| Preview/layoutlib | effects 走 API 门禁，渲染退化为 tint 面，不阻塞预览 |

---

## 四、对比度兜底（WCAG AA ≥ 4.5:1）

玻璃是半透明材质，可读性不能靠"背景刚好是白的"。做法：**每个材质自带 tint + contentColor 配对**，
配对按最坏背景亮度演算（页面渐变最亮/最暗端、地图最亮/最暗区域），并由单测锁死
（`LiquidTokensTest` 对全部配对算 WCAG 比值，< 4.5 即失败）。

| 材质 | 场景 | tint | 前景 | 最坏背景 | 比值 |
|---|---|---|---|---|---|
| Chrome / Sheet / Card | 页面浮层 | 白 0.62 / 黑 0.42 | #1F2430 / #E8EBF0 | 页面渐变端 | ≥ 13:1 |
| Hud | 地图浮层 | 白 0.80（sampled=false） | #1F2430 | 深色地图区（lum≈0.05） | ≥ 12:1 |
| Brand 按钮 | 主 CTA | 实底 #1573D8 | 白 16sp SemiBold | — | 4.7:1 |
| Tinted 按钮/标签 | 次操作 | 主色 10% 底 | #1573D8 / #6FB6FF | 玻璃面 | ≥ 4.5:1 |

主色 #1B88EE 上白字只有 3.6:1（不达 AA 常规字号）——按钮实底改用加深一档的 `PrimaryDeep #1573D8`；
原主色保留给大号数字/图标（≥24sp 粗体属 AA large，3:1 即过，3.6 达标）。

---

## 五、Design System 组件清单

`ui/designsystem/`（全部新增文件，不动现有包）：

| 文件 | 内容 |
|---|---|
| `LiquidTokens.kt` | 间距/圆角/模糊/不透明度令牌；`LiquidMaterial` 材质数据类与预设；深浅 Palette；等宽数字字阶（tnum） |
| `LiquidMotion.kt` | spring 规格（按压/指示器/面板）；`rememberReduceMotion()`（ContentObserver 监听动画时长缩放）；触觉封装 |
| `LiquidBackdrop.kt` | `GlassLevel` 能力判定；`LiquidBackdropHost`/`LocalLiquidBackdrop`；`LiquidScaffold`（背景层 + 采样 + Insets）；`Modifier.liquidSurface()`（含降级分支） |
| `LiquidSurface.kt` | `LiquidSurface` 基元 + 按压微缩放/高光交互 |
| `LiquidButtons.kt` | `LiquidButton`(filled/tonal/ghost/text) / `LiquidIconButton` / `LiquidChip` |
| `LiquidBars.kt` | `LiquidTopBar` / `LiquidBottomBar`（液态滑块 spring 过渡） |
| `LiquidSheet.kt` | `LiquidBottomSheet`（同窗口 overlay）/ `LiquidDialog` |
| `LiquidInputs.kt` | `LiquidInput` / `LiquidToggle` / `LiquidSlider` / `LiquidSection`（分组列表，替 QMUI GroupList）/ `LiquidTag` / `LiquidAvatar` |
| `LiquidFeedback.kt` | `LiquidLoading` / `LiquidStateView`（空态/错误态） |
| `LiquidPreviews.kt` | @Preview：浅色/深色/超长文案/fontScale=1.3/320dp 窄屏 |

渲染稳定性约定：动效值只进 `graphicsLayer{}` lambda 与 lambda 型 modifier（translationX/scale/offset），
不进组合；`effects`/`shape` lambda 用 `remember` 固定引用，避免每次重组重建 RenderEffect 链。

---

## 六、文案精简对照表

| 现文案 | 精简后 | 依据 |
|---|---|---|
| 正在读取跑步进度… / 个人信息… / 历史记录… | 加载中 | 统一加载态，去掉宾语 |
| 登录已过期，请重新登录 | 登录已过期（按钮：重新登录） | 动作归按钮 |
| 退出后需要重新完成易盾验证并登录。 | 删除 | 按钮"退出登录"已表意 |
| 选择跑距，领取点位后开始 | 选择跑距 | 次句是流程复述 |
| 当前不在有效时段，暂不能开跑 | 未在有效时段 | 状态直陈 |
| 当前不在有效时段，$it 开放 | 有效时段外 · ${window} | 压缩为一行 |
| 结束后停止采样并进入成绩判定。 | 删除（确认框仅留 结束/取消） | 标题已表意 |
| 第 1 步 / 共 2 步 | 第 1 步 | 步进指示器替代 |
| 输入手机号获取短信验证码 | 获取短信验证码 | 输入框自带标签 |
| 完成验证后会自动继续登录 | 删除 | 自动行为无需说明 |
| 获取验证码 / 重新获取验证码 | 发验证码 / 重新获取 | 按钮 ≤4 字 |
| 需要定位权限才能开跑 | 定位权限（按钮 去设置） | 标题即状态 |
| 系统定位未开启（请打开 GPS） | 定位未开启（按钮 去开启） | 同上 |
| 分配点位为空（服务端返回 0 个点） | 未获取到点位 | 去过程细节 |
| 提交完成 · rrid=… · 轨迹已上传并校验 | 提交成功 + rrid 行 | 删装饰性结果描述 |
| 短信已发送至 153****4023 | 验证码已发送 | 号码在输入框里 |
| 还没有跑步记录 + 完成一次学分跑后，记录会出现在这里 | 暂无记录（按钮 去跑步） | 空态单行 + 动作 |
| 资料已完善 / 资料待完善 | 已完善 / 待完善 | 标签 ≤4 字 |
| 实名${label} | ${label}（已实名/未实名） | 前缀冗余 |
| XbudPort 0.1.0 · 协议直连 | v0.1.0 | 版本号留，slogan 删 |
| 当前位置精度持续偏低，请移动到开阔处，或检查定位权限。 | GPS 信号弱，请到开阔处 | 与 HUD 提示合并为一句 |
| 底栏 主页/跑步/我的 | 不变 | 已合规（2 字） |

错误诊断类文案（协议错误码、加载失败原因）原样保留——那是排障信息，不是装饰。

---

## 七、动效规范

| 节点 | 曲线 | 实现要点 |
|---|---|---|
| 按压微缩放 | spring(dampingRatio 0.75, stiffness 700) | scale 进 graphicsLayer；高光 alpha 同步抬升 |
| 底栏液态滑块 | spring(0.6 / 300) | translationX 进 graphicsLayer；选中色过渡仅限图标/文字叶子节点 |
| BottomSheet / Dialog 进出 | spring(0.85 / 380) + scrim 淡入 | AnimatedVisibility + animateContentSize |
| 卡片展开 | spring(0.9 / 500) | animateContentSize |
| 触觉 | 仅三处：结束跑步确认、退出登录确认、Toggle 切换 | `LocalHapticFeedback`；切页不加（免疲劳） |

---

## 八、内存与稳定性红线

1. 单例/ViewModel 不持有 Activity/Context/View/Composable 状态（现有代码已守，迁移不破）。
2. Flow 订阅一律 `collectAsStateWithLifecycle()`（现有页面用 `LaunchedEffect` 直采，迁移时对齐）。
3. `GraphicsLayer` 全部经 `rememberGraphicsLayer()/rememberLayerBackdrop()` 创建，随组合释放；不手写缓存。
4. `ContentObserver`（减少动效监听）包 `DisposableEffect`，`onDispose` 反注册。
5. 传给 UI 的数据模型 `@Immutable`/`data class` + 不可变集合（现有 HomeUi/ProfileUi/PageResult 已是）。
6. 同屏实时玻璃面 ≤ 3（TopBar/BottomBar/Sheet），滚动列表项用静态材质。
7. 无新线程/无新单例；协议调用维持 `Dispatchers.IO` + `rememberCoroutineScope`。

---

## 九、迁移顺序（Phase 3，每步独立可编译）

1. 底座：`LiquidScaffold` + `LiquidBottomBar` 接入 `MainActivity`（QMUI 底栏退场）
2. 登录 → 注册 → 忘记密码（认证流独立，风险最低）
3. 主页（进度卡 + 规则分组 + 去跑步按钮）
4. 我的（资料卡 + 分组 + 退出确认）
5. 历史（列表 + 详情 overlay + 轨迹回放 canvas）
6. 学分跑（地图 HUD 材质 + 控制卡 + 打卡条，最后动，改动最大）
7. 收尾：删 `ui/components/Qmui.kt` 桥、App 默认深浅色、全量构建 + DoD

---

## 十、Phase 2 交付状态（2026-11-19）
依赖底座与 `ui/designsystem/` 已落地并通过验证：`assembleDebug` 成功，单测 32 项全绿
（新增 `LiquidTokensTest` 4 项：浅/深玻璃、地图 HUD 的对比度闸门 + 材质不变量），
零新增编译警告。业务层（data/ proto/ run/）与既有页面零改动。

新增文件：

| 文件 | 内容 |
|---|---|
| `ui/designsystem/LiquidTokens.kt` | 间距/圆角/字阶（tnum）、`LiquidPalette` 双主题、`LiquidChrome`（含 GlassLevel/reduceMotion）、`LiquidMaterial` 与 chrome/sheet/card/field/hud/tonal/ghost 七种材质 |
| `ui/designsystem/LiquidMotion.kt` | spring 规格、`rememberReduceMotion()`（ContentObserver 监听动画时长缩放，DisposableEffect 反注册）、确认类触觉 |
| `ui/designsystem/LiquidBackdrop.kt` | `LiquidTheme`、`LiquidScaffold`（背景层单采样源 + 固定高度 chrome + overlay 槽）、`Modifier.liquidSurface()`（drawBackdrop 与降级分支）、`rememberLiquidEffects`（固定 lambda，防每帧重建 RenderEffect 链）、`Modifier.liquidTint`（HUD 用不采样遮罩） |
| `ui/designsystem/LiquidSurface.kt` | 玻璃面基元（缩放走 graphicsLayer lambda） |
| `ui/designsystem/LiquidButtons.kt` | LiquidButton（Filled/Tonal/Ghost/Text/DangerText，按压微缩放 + 加载圈）、LiquidIconButton、LiquidChip、LiquidSpinner |
| `ui/designsystem/LiquidBars.kt` | LiquidTopBar、LiquidBottomBar（液态滑块 translationX 走 Draw 阶段，配色过渡只限图标/文字叶子） |
| `ui/designsystem/LiquidSheet.kt` | LiquidBottomSheet、LiquidDialog（同组合树 overlay + scrim，reduceMotion 时滑窗退化为淡入） |
| `ui/designsystem/LiquidInputs.kt` | LiquidInput（聚焦环 + 数字 tnum）、LiquidToggle、LiquidSlider、LiquidSection/LiquidRow、LiquidTag、LiquidAvatar |
| `ui/designsystem/LiquidFeedback.kt` | LiquidLoading、LiquidStateView |
| `ui/designsystem/LiquidPreviews.kt` | @Preview：浅色 / 深色 / fontScale=1.3 + 320dp 窄屏 + 弹层 |
| `app/src/test/…/LiquidTokensTest.kt` | WCAG AA 对比度闸门（浅/深玻璃、HUD）+ 材质不变量 |

依赖变更：Kotlin 2.0.21 → 2.2.21（backdrop 1.0.0 按 2.2.21 元数据发布）、
Compose BOM 2024.10.01 → 2025.10.01（运行时 1.9.4，backdrop 的依赖下限）、
新增 `io.github.kyant0:backdrop:1.0.0`、`material-icons-core:1.7.8`
（material3 1.4 起不再传递带入，既有页面 Icons 依赖它）、`ui-tooling-preview`。
AGP 8.7.3 / Gradle 8.9 / compileSdk 35 / minSdk 26 不变——compose 1.9.4 的
minCompileSdk=35、minAGP=8.6，现配置即满足。

下一步：Phase 3 逐页迁移（顺序见 §九），每页独立可编译；迁移完成后删除
`ui/components/Qmui.kt` 桥、App 默认切换 `isSystemInDarkTheme()`。

---

## 十一、Phase 3 交付状态（2026-11-19）

七个页面全部迁移到 designsystem，QMUI 桥（`ui/components/Qmui.kt`）与旧 QMUI 风格
组件（`Common.kt`）已删除，`com.qmuiteam:qmui` 依赖移除，XML 主题换成框架
`Theme.Material(.Light).NoActionBar`（values-night 一份，深色冷启动不白闪）。
`data/ proto/ run/` 三个业务包与既有单测零改动。

| 页面 | 迁移内容 |
|---|---|
| MainActivity | `LiquidScaffold`（背景渐变单采样源）+ `LiquidBottomBar`；主题跟随系统深浅色，状态栏图标随主题反转 |
| 登录 / 注册 / 忘记密码 | 玻璃输入框与按钮；设置弹层、画像选择、导入 JSON、调试 validate 全部换成 `LiquidBottomSheet` / `LiquidPromptDialog`（新增）；文案按 §六 精简 |
| 主页 | 学期环 + 三统计格玻璃卡；规则分组与历史入口走 `LiquidSection`；「更多」分组标题删除 |
| 我的 | 资料卡 + 三组 `LiquidSection`；头像换 `LiquidAvatar`；退出确认换 `LiquidDialog`（danger，无赘述文案） |
| 历史 | 列表玻璃卡；详情与轨迹回放从平台 Dialog 改为同组合树 `LiquidBottomSheet`（带 `BackHandler`，独立窗口采不到本页背景）；空态单行「暂无记录」 |
| 学分跑 | 地图 HUD 全部改用不采样遮罩材质（`liquidTint` + `hud`）；选距 chip、计时卡、成绩卡、打卡条重绘；结束确认与 GPS 提示换 `LiquidDialog`；提交结果拆「提交成功」+ rrid 行 |

架构修正（迁移中发现）：页面级弹层最初被内容区裁着、够不到叠在上层的底栏。
`LiquidScaffold` 改为「内容层吃整屏 + 底栏叠在其上」，页面把自己的滚动容器
pad 进去，弹层因此能盖住底栏；NavHost 不再整体 padding。

验证：
- `assembleDebug` 成功，零新增警告；单测 32 项全绿（含对比度闸门 4 项）。
- 真机（MuMu 1080×1920）安装运行：登录页与注册页截图确认玻璃面、渐变背景、
  可读性正常，无崩溃/黑屏；导航跳转正常；测试后设备动画缩放已复原。
- 采样隔离核验：全工程 `layerBackdrop` 仅 LiquidScaffold 一处，`drawBackdrop`
  仅在 `Modifier.liquidSurface` 内，无 `exportedBackdrop` 用法——结构上不存在嵌套自采样。

## 十二、Definition of Done 核对（Phase 4）

- [x] 无新增内存泄漏隐患：无新增单例/全局 Context 持有；`rememberReduceMotion` 的
      ContentObserver 包 DisposableEffect 反注册；GraphicsLayer 全部经
      `rememberLayerBackdrop`/`rememberGraphicsLayer` 随组合释放；协程仍绑定
      `rememberCoroutineScope`/`LaunchedEffect`。
- [x] 无 Backdrop 嵌套循环采样（grep 核验见 §十一）；动效值只进 graphicsLayer
      lambda 与 lambda modifier，`effects` lambda 固定引用，按压不重建 RenderEffect 链。
- [x] API 26–30 降级为半透明磨砂面（tint + 高光边 + 投影）；深浅色双 Palette +
      values-night；地图 HUD 遮罩按最坏背景演算，对比度单测锁死。
- [x] 文案精简至最小必要集（§六 对照表 22 条全部落地）；`fontScale=1.3` 与
      320dp 窄屏由 @Preview 覆盖。
- [x] 现有单测 32 项 100% 通过，`assembleDebug` 零新增警告，真机运行验证通过。

---

## 十三、玻璃引擎替换：Liquid-Glass-Android 2.0.11（2026-11-20）

应用方指定改用 QWEA0/Liquid-Glass-Android v2.0.11（MIT，Android View 体系），
替换 Kyant0/Backdrop 1.0.0。**设计标准不变**：令牌、材质、对比度配对、
动效规格、降级矩阵全部维持，只有底层引擎与采样机制换了。

| 维度 | Kyant Backdrop 1.0.0（旧） | LiquidGlass 2.0.11（新） |
|---|---|---|
| 体系 | Compose Modifier | View（`LiquidGlassView`，FrameLayout）+ Compose 互操作 |
| 采样 | `layerBackdrop` 显式采样源 + GraphicsLayer 重放 | 捕获宿主（`View.draw` 重放），玻璃分支置 INVISIBLE 排除自身，内置「嵌套采样只允许一层」的多玻璃互采防护 |
| 模糊 | API 31+（低版本退化平 tint） | API 33+ AGSL 透镜 / 31+ 硬件模糊 / 24+ C++·Kotlin 管线——**26-30 也能真模糊** |
| 折射 | lens() | SDF 透镜 + 物理色散（refractionHeight/bevelWidth/dispersion） |
| 无障碍 | 无 | Reduce Transparency → 不透明回退（内置） |

### 接入方式

- 源码 vendor：`portapp/liquidglass` 模块（构建配置按本仓库工具链改写，
  AGP 8.7.3 / Kotlin 2.2.21 / jvmTarget 17），`settings.gradle.kts` include，
  app 依赖 `implementation(project(":liquidglass"))`，Kyant 依赖移除。
  app compileSdk 35 → 36（模块的 RuntimeColorFilter/RuntimeXfermode 要求）。
- `LiquidSurface` 基元改为 `AndroidView(LiquidGlassView)`：内容子层手动挂
  `ComposeView`（WRAP_CONTENT——MATCH_PARENT 会把 wrap 玻璃撑满可用空间），
  ui 1.9.4 无 AndroidView content 重载。**子组合不继承外层 CompositionLocal**，
  chrome / contentPadding 经 `rememberUpdatedState` 桥接。
- 材质映射：blurAmount = (px-4)/32（引擎上限 36px）、glassTint（alpha 即强度）、
  edgeHighlightOpacity、逐角圆角（CornerSize 按 1000px 参考尺寸求值，引擎钳到
  短边一半，胶囊自然成立）、refractionHeight 仅 Full 级下发；
  引擎自适应行为（adaptive tint / sensor highlight / 色散 / 阴影 / 按压）全部关闭，
  由设计系统令牌驱动。HUD（sampled=false）材质仍走纯 Compose 遮罩（`liquidTint`），
  不接引擎——TextureMapView 的 GL 内容不进画布重放。
- 按钮/芯片的尺寸语义：玻璃吃外层 sizing，内容盒按玻璃确定维度填充
  （fill=true 双向、fill=false 只撑高度），避免 wrap 上下文撑爆。

### 显示安全区（WindowsInsets 全量接入）

targetSdk 35 在 Android 15+ 强制 edge-to-edge（老设备经 `enableEdgeToEdge()` 统一），
玻璃 chrome 延伸到系统栏底下、内容避开安全区：

- `LiquidTopBar`：内容行 `statusBarsPadding` + 56dp，玻璃 wrap = 状态栏 + 56dp；
- `LiquidBottomBar`：内容行 `navigationBarsPadding` + 64dp；
- scaffold `contentPadding.bottom` = 底栏高度 + 导航栏 inset（经 `WindowInsets.navigationBars`）；
- `LiquidBottomSheet` 内容 `navigationBarsPadding`；
- 跑步页顶部悬浮计时卡 `statusBarsPadding`；登录页根节点 `statusBarsPadding`（无顶栏页）；
- 页面滚动容器补 `clipToBounds`（verticalScroll 不自带裁剪，长内容尾部分画进了底栏底下）。

### 验证

单测 35 项全绿；真机（MuMu API 35）截图核验：主页/我的/历史列表/详情弹层全部
正常渲染，标题避开状态栏时钟，滚动裁剪正确。引擎分层降级与contrast闸门
（LiquidTokensTest）不变。

### 补：输入框显示修复（同日）

引擎替换后 `LiquidInput` 的玻璃是 wrap 高度（heightIn(min=50dp)），内容层没有
填满玻璃：文本缩成一行顶在上方、聚焦环也只包住文字那条。修复：

- 单行：玻璃固定 50dp 高，内容 `fillMaxSize` + 垂直居中；
- 多行（JSON 导入）：玻璃按内容生长（下限 50dp），内容顶部对齐；
- 聚焦环从文字层移到玻璃边缘层（`matchParentSize` + 同圆角），不再出现双框。

真机核验：聚焦态文字居中、光标/聚焦环贴玻璃边，双字段等高。

### 补：跑步页浓缩入口（同日）

ready 状态的大面板浓缩为圆形纯色播放按钮（64dp、主色实底、白色 Play 图标、
投影 + 按压微缩放）浮在地图上；点击展开完整面板（距离芯片/规则/领取点位并开始，
slideIn spring + 减少动效退化淡入），按钮图标切换为关闭。深浅色全部走 palette
令牌（主色实底按钮 + HUD 遮罩面板自适应）；时段外窗口规则的空值行在跑步页
同步隐藏。真机核验深浅两种模式下的收起/展开两态。
