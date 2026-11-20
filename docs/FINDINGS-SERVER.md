# 服务端行为与技术结论（实测汇总）

> 抓取时间：2026-11-10 · 目标：`run.xbud.android` + `campusapi.xbud.run`
> 样本账号：1051628 / 1051928 / 1052364 / 1186620 / 1191253 / 894824
> 所有结论均带可复现命令或原始回执，未验证的推断单独标注。

---

## 1. 接口全表

从 `cacert/xbud/xbud.apk` 的 `classes*.dex` 静态提取（`/v[0-9]/sport/run/*` + 关联接口）：

| 路径 | 方法 | 用途 | 实测 |
|---|---|---|---|
| `/v1/sport/run/get_fixed_point/{1}/{distance}` | POST | 分配本次跑步点位 | ✅ 需 `sign`，可离线复算（见 §3） |
| `/v1/university/get_campus_point_list` | POST | 校园点位表（12 个） | ✅ body `{"unid":...,"createdTime":""}` |
| `/v1/sport/run/get_record_list` | POST | 记录列表 | ✅ 模块 `4K.LIST` 走它 |
| `/v4/sport/run/get_record_detail` | POST | 记录详情 | ✅ **无归属校验**（见 §6） |
| `/v4/sport/run/save_record` | POST | 提交成绩 | ✅ 模块注入 bean 直发 |
| `/v1/sport/run/get_appeal_info` | POST | 申诉信息 | ✅ 只读 |
| `/v1/sport/run/submit_appeal` | POST | 提交申诉 | ✅ 只改状态，不删记录 |
| `/v1/sport/run/get_config` | POST | 跑步政策配置 | ⚠️ body 需 `RumModePolicyReqModel`，字段未知（`{}` 返回 `-10000`） |
| `/v1/sport/run/refresh_record` | POST | 统计刷新 | 未测 |
| `/v1/sport/run/completed_info` | POST | 完成度统计 | 未测 |
| `/v1/sport/run/get_rank_history_list` / `get_speed_diagram` / `get_step_diagram` / `get_weekly_sport_data` | POST | 榜单/图表 | 未测 |
| `/v2/sport/run/get_rank_list` / `get_record_list_single_header` | GET/POST | 榜单 / 单 header 列表 | ⚠️ 后者直接 GET 返回 200 但 0 字节 |
| **删除类** | — | **不存在** | 全量扫描无 `del*` / `delete*` |

> 复现：读取 apk 内 `classes*.dex` 的 ASCII 串，正则 `/v[0-9]/sport/run/[a-zA-Z0-9_]+`。

---

## 2. 打卡点（fivePointJson）——三层结论

> 代码级全链路（下发 → 选点 → 判定 → 上报 → 伪造实现映射）见 [TECH-POINTS-CHAIN.md](TECH-POINTS-CHAIN.md)。
> 本节只保留**服务端侧**实测结论。

### 2.1 实测矩阵

| rrid | 来源 | policy | selDist | 请求里带的点数 | 服务端 `fivePointJson` | `reasonList[0]` |
|---|---|---|---|---|---|---|
| 71945101 | **真人跑** 4037 m | 3 | 1.0 | 3（App 自采） | `""` | complete=true |
| 71937362 | **真人跑** 1 km | 3 | 1.0 | — | `""` | complete=true |
| 71967049 | **真人跑** 4137 m | 3 | 1.0 | — | `""` | complete=true |
| 72036382 | 注入 | 3 | 1.0 | 1（模块默认 `xibu`） | `""` | complete=true |
| 72036659 | 注入 | 3 | 1.0 | 4（校园点） | `""` | complete=true |
| 72039923 | 注入 | **1** | 1.0 | 4（校园点） | `""` | complete=true |
| 72040731 | 注入 | 3 | 1.0 | **5（服务端分配点）** | `""` | complete=true |
| 72040928 | 注入 | **1** | **4.0** | **5（服务端分配点）** | `""` | complete=true |

### 2.2 结论

1. **`fivePointJson` 是请求侧 DTO，服务端从不回填**。真人记录也是空串，所以与本工具无关。
2. `policy` 与 `selDistance` **被服务端尊重**（改成 1 / 4.0 后，reason 文案从「最少要跑1.00公里」变成「最少要跑4.00公里」），说明 bean 字段有效，只有点位字段无效。
3. 打卡校验挂在**开跑会话**上：App 开跑时调 `get_fixed_point` 拿点位，服务端按该会话校验轨迹。注入 bean 没有会话 → 无可校验对象 → 该项判 `complete=true`（等价"本次免打卡"）。
4. 因此"让服务端记 3~5 个点"在现有接口面**不可实现**；打卡数据只存在于 COS 轨迹对象的 `fixedPointJson`（地图回放用）。
5. 代码侧与实测对得上：`complete` 与 `isPass` 都在客户端算完再上报（`ex0.java:483` / `fw0.java:162`），服务端拿到的是**结论**而不是过程；签名也不覆盖 `fivePointJson` / `allLocJson`。详见 [TECH-POINTS-CHAIN.md](TECH-POINTS-CHAIN.md) §4。

---

## 3. `get_fixed_point` 的 sign 算法（可离线复算）

```python
path = "/v1/sport/run/get_fixed_point/1/4000"
base = ("https://campusapi.xbud.run" + path).replace("https", "http")
sign = md5(base + "2slhe02lsfiwowlcixisla_sls-_slaor")
# = 97699ee0881129ea0daecf1c6840550e   ← 实测 HTTP 200
```

- salt4 = `SportJniUtils.getSecretKey(4)`
- `uuid` 不参与校验（塞假 UUID 也 200）；`sign` 错则 `{"error":10137,"message":"请求不合法"}`
- body 必填：`longitude` / `latitude` / `selectedUnid` / `uuid` / `runec` / `sign`

### 返回样例（`/1/4000`）

```json
{"error":10000,"message":"成功","data":{
  "pointsResModels":[
    {"lat":31.919796,"lon":120.977409,"pointName":"学校西门","isFixed":1},
    {"lat":31.92006, "lon":120.979086,"pointName":"图文信息楼北门","isFixed":0},
    {"lat":31.917266,"lon":120.98076, "pointName":"田径场3号点","isFixed":0},
    {"lat":31.917614,"lon":120.981419,"pointName":"田径场4号点","isFixed":0},
    {"lat":31.920625,"lon":120.977968,"pointName":"食堂门口","isFixed":0}],
  "sportStartTime":1794308674084}}
```

- `distance` 参数生效（0/500/1000/4000 返回不同集合）
- `isFixed=1` 的点每次随机
- `sportStartTime` 是服务端"现在"

---

## 4. `get_record_detail` 字段全表

| 字段 | 类型 | 说明 | 实测值 |
|---|---|---|---|
| `rrid` / `sportType` | int | 记录 id / 运动类型 | 72040928 / 1 |
| `totalDis` `validDis` | double | 总/有效距离 | 4000.0 |
| `totalTime` `validTime` | double/int | 秒 | 1303.0 / 1303 |
| `startTime` `stopTime` | ms | 起止 | 1794308640000 |
| `avgStepFreq` | int | 步频 | 129 |
| `speed` | double | 配速 min/km | 5.44 |
| `complete` | bool | 是否合格 | true |
| `unCompleteReason` | int | 1=全满足 | 1 |
| `canAppeal` | int | 可否申诉 | 0 |
| `isCheat` | int | 作弊标记 | 0 |
| `appealStatus` / `appealStatusInfo` | int/str | 申诉状态 | 1 / "" |
| `policy` | int | 3=自由跑，1/2=学分/定点 | 3 或 1 |
| `selDistance` | double | 领取点位的跑距（km） | 1.0 / 4.0 |
| `reasonList` | array | 四项判定 | 见 §2 |
| `fivePointJson` | str | 打卡点 | **恒 `""`** |
| `allLocJson` | null | 轨迹不走这里 | null |
| `statusInfo` `segmentJson` `recordUrl` `prizeName` | null | 未使用 | null |
| `speedTop/Bottom` `stepTop/Bottom` | num | 阈值 | 0 |

> 轨迹只走 COS：`stable/<rrid//1e6>/<rrid>.json`（gzip，见 `GEN-REAL-PROFILE.md`）。

---

## 5. 时间窗硬约束（回归验证）

| 约束 | 实测边界 | 触发码 |
|---|---|---|
| 记录 start 必须落在 **06:30–21:00** | 20:50 可过、窗口外拒 | — |
| `stop` 距提交 ≥ **30 分钟** | 2.5 min 拒、31 min 可过 | `11519` |
| `start` 距提交 ≤ **约 20 小时** | 20.4 h 可过、24.5 h 拒 | `11518` |
| 记录过老（3 天前） | 拒 | `11519` |
| 配速 3.5–10.0 min/km、步频 50–300 | 越界记不合格 | `reasonList` |

---

## 6. 越权：`get_record_detail` 无归属校验（IDOR）

| 当前登录 uid | 查询 rrid | 该记录归属 | 结果 |
|---|---|---|---|
| 894824 | 72036659 | 894824 | 正常返回 |
| 894824 | 72031197 | 1191253 | **正常返回完整详情** |
| 894824 | 72000001 | 他人 | **正常返回完整详情** |
| 894824 | 72999999 | 不存在 | `{"error":10303,"message":"跑步记录不存在"}` |

rrid 自增（71.9M → 72.0M），登录任意账号即可按 id 遍历全站记录（距离/时长/步频/完整度/作弊标记/申诉状态）。

> 复现：`XbudToolGo-v0.1.0-cli.exe detail --rrid 72031197`

---

## 7. 记录不可删除

- Apk 内 `sport/run` 全部 16 个端点无删除类
- `submit_appeal` 只写申诉状态，不减损记录
- 结论：**学生端无法清记录**；只能后台删、或弃号

---

## 8. 管理员能看到什么（结论 + 边界）

**记录层**：全部合格 —— `complete=true`、`unCompleteReason=1`、`isCheat=0`、`reasonList` 四项全 true、`fivePointJson` 空。也就是说"未经过点位"这件事**在数据里不存在**。

**行为层**（真正的暴露面，按优先级）：

1. 同一账号一天多条（实测某号一天 5 条 4 km）
2. 距离恒为整千米（4000.0 米连发）
3. 配速/步频分布过窄
4. 轨迹是同一组点的固定闭环（起终点重合）
5. 无对应开跑会话（只有结果、没有过程）
6. 多账号同出口 IP、同设备环境

> 边界：本仓库只掌握学生端接口，**超管后台页面形态是推断**，未取得后台访问权限。

---

## 9. 已落地的风控参数（Go 版）

| 参数 | 默认 | 作用 |
|---|---|---|
| `--max-per-day 1` | 1 | 同账号同一天最多几条，`--force` 可绕过 |
| `--campus ntsf` | 关 | 12 点位随机环线（每次 6~12 个点、随机顺序） |
| `--dist 4.0 --jitter 0.3` | jitter 0.25 | 距离 4.0~4.3，不取整千米 |
| `--pace-min/--pace-max` | 5.0/8.0 | 控制配速分布 |
| `--freq-min/--freq-max` | 110/135 | 控制步频分布 |

---

## 10. 待办（未完成）

1. **开跑会话复现**：需在 App 内手动点一次「开始跑步」，抓 `get_fixed_point` 与会话建立请求，才能让服务端真正校验打卡。
2. **起跑时间随机化**：当前 `PlanDay` 只随机到分钟，仍偏整点；可加 ±20 分钟散列。
3. `get_config` 的 `RumModePolicyReqModel` 字段未还原，政策配置读不到。
