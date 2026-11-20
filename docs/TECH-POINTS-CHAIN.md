# 打卡点全链路（技术文档）

> 目标：`run.xbud.android`（小步点）· 接口域 `campusapi.xbud.run`
> 反编译源：`cacert/xbud/jadx_app/sources/`（2.6.6/2.6.8 同构，行号以该目录为准）
> 抓包：`cap/ProxyPin11-11_15_27_59.har`、`cap/ProxyPin11-11_15_46_44.har`
> 结论标注：`Observed` = 有抓包/日志/回执 · `Inferred` = 代码静态推得 · `Assumed` = 待验

---

## 0. 一页结论

| 层 | 谁说了算 | 是否可伪造 | 服务端是否复核 |
|---|---|---|---|
| L1 点位库下发 | 服务端（整批快照） | 只读，可本地改库 | — |
| L2 本次选点/顺序 | **客户端本地算法** | 可 | 不参与 |
| L3 是否经过点位 `isPass` | **客户端**（native 闸门 + Java 置位） | 可 | **仅在有开跑会话时** |
| L4 上报字段 `complete/距离/配速` | **客户端算完再上报** | 可（改字段需重签） | 照抄 + 四规则复算 |
| L4' 签名 `signature` | native `getSecretKey(4)` + MD5 | 可离线复算（key 已提取） | 唯一硬校验 |

一句话：**打卡这件事在数据里只有“结论”，没有“过程证据”；服务端能校验的只有距离 / 时长 / 步频 / 配速四项。**

---

## 1. L1 下发：`/v1/university/get_campus_point_list`（批量快照，不是逐点）

请求只带两个字段（[yy0.java:94](cacert/xbud/jadx_app/sources/defpackage/yy0.java#L94)）：

```json
{ "unid": 1001104, "createdTime": "" }
```

响应处理（`ze0.java` 侧 `onSuccess`，摘录见 `TASK_REPORT_PROTOCOL_FINAL.md` §4.4）：

```java
List<SchoolPointTable> list = gson.fromJson(str, List.class);
if (!list.isEmpty()) { delete(SchoolPointTable.class); saveOrUpdate(list); sp_key_last_time = list[0].createdTime; }
// 空列表 → 保留缓存，不动游标
```

| 事实 | 证据 |
|---|---|
| 一次拿回**全校所有点**（实测 12 个） | `campus_points.json` |
| `createdTime` 是增量游标，空串 = 全量 | 抓包 15:46:14 那条响应 389 B（无更新） |
| 触发时机是**进主页**，不是跑步途中 | `cap/har.md` §1 时序表 |
| 字段集：`pid/unid/lon/lat/isFixed/serialNum/pointName/createdTime/relatedPids` | 同上 |

`serialNum` 在服务端是**字符串** `"1".."12"`，`SchoolPointTable` 声明 `int`，Gson 自动强转（`Observed`，`TASK_REPORT_PROTOCOL_FINAL.md` §4.4）。

---

## 2. L2 选点：`yy0.mo6922for()` = `getNextPoint`（纯本地，零网络）

[yy0.java:112-165](cacert/xbud/jadx_app/sources/defpackage/yy0.java#L112-L165)

```java
// 1) 从本地库按 unid 取全部点（内存缓存 f7736do）
selector(SchoolPointTable.class).where("unid","=",unid).orderBy("pid",true).findAll()
// 2) 空 → return null，日志“本地没有学校点位数据”
// 3) 首点（curPid==null && lastPid==null）→ 距离当前位置最近
m7063new(lon, lat, null, list)
// 4) 后续点：读 curPid 的 relatedPids → 去掉本次已用 → 随机挑（避开 lastPid）
m7064try(lastPid, arrayList)
// 5) relatedPids 空 / 候选耗尽 → 回退就近
m7063new(lon, lat, curPid, list)
```

要点：

- `relatedPids` 是**逗号串**，服务端下发的，比如 `"54122,54125,54121"`；
- 随机源是 `new Random()`，无种子、无服务端参与；
- 距离用 GCJ-02 转换后的坐标算（`k00.m1506for`），不是原始 WGS。

**结论**：目标点集合与顺序完全由客户端决定，服务端在这一层没有任何输入。

---

## 3. L3 判定：三道本地闸门

### 3.1 起点与点位一起交给跑步页

[yx0.java:197-216](cacert/xbud/jadx_app/sources/defpackage/yx0.java#L197-L216)：`startPointJson`（LocPointTable）+ `fivePointsJson`（List<FixedPointTable>）序列化后 `F0(i, ...)`。
`sportType == 4` 时 `i = -1`，且上报阶段清空 `fivePointJson`（[ex0.java:539-540](cacert/xbud/jadx_app/sources/defpackage/ex0.java#L539-L540)）→ **sportType=4 是“无点位跑”，整条打卡链路旁路**。

### 3.2 轨迹点闸门在 native

[yx0.java:223](cacert/xbud/jadx_app/sources/defpackage/yx0.java#L223)

```java
if (SportJniUtils.isValidPoint(bdGpsStatus, bdSpeed, count, radius, isGPSLocation, inFence)) {
    LocPointTable p = LocPointTable.formatByLocationBean(locationBean, 1); p.setType(5);
} else { this.invalidCount++; }
if (this.invalidCount == 4) { /* GPS 信号弱弹窗 */ }
```

`isValidPoint(int,float,int,float,boolean,boolean) -> boolean` 实现在 `libsport.so`（`SportJniUtils.java:33`），Java 侧只消费布尔值。同一实现另有一处调用：[ad0.java:38](cacert/xbud/jadx_app/sources/defpackage/ad0.java#L38)。

### 3.3 `isPass` 由 Java 置位

[fw0.java:162-220](cacert/xbud/jadx_app/sources/defpackage/fw0.java#L162-L220)

```java
public void s(int id) {                  // id = FixedPointTable 的本地 id（不是 pid）
    for (FixedPointTable p : I2()) if (id == p.getId()) { p.setIsPass(true); ... }
    // 普通点计数 ≥ 2 → t3(true)「至少通过2个普通点位」
    // 必经点(isFixed==1) 通过 → j3(true)「经过必经点位」
}
```

同构判定器另有四份：`mc0:32` / `qc0:35` / `tc0:35` / `wv0:210`（不同 sportType 分支，逻辑一致）。

### 3.4 点位顺序由本地排列

[dn0.java:173-231](cacert/xbud/jadx_app/sources/defpackage/dn0.java#L173-L231)：拎出必经点 → 枚举其余点全排列 → 用**当前定位点到各点的折线距离**筛 `[0.3×i, 0.8×i]` → 随机抽一组 → `setPosition(0..n)`。
`position == 999` 是 `FixedPointTable` 默认值（[FixedPointTable.java:40-44](cacert/xbud/jadx_app/sources/run/xbud/android/bean/database/FixedPointTable.java#L40-L44)），表示“未参与排序”，L4 判达标时会跳过。

### 3.5 兜底取点接口

[dn0.java:253-264](cacert/xbud/jadx_app/sources/defpackage/dn0.java#L253-L264) → `POST /v1/sport/run/get_fixed_point/1/{distance}`，`{1}` 是硬编码段，`{distance}` 是本次跑动距离（米）。请求体见 `FINDINGS-SERVER.md` §3，`sign` 可离线复算。
**只有本地点位库为空时才走它**（`policy=3` 时由 `r1.java` 直接读本地库，忽略 distance）。

---

## 4. L4 上报：结论 + 一个 MD5

### 4.1 `complete` 怎么算

[ex0.java:483-600](cacert/xbud/jadx_app/sources/defpackage/ex0.java#L483-L600)

```java
if (policy == 1 || policy == 3) {        // 顺序跑：最后一个有序(position!=999)且已通过的点
    for (int i = fivePoint.size()-1; i >= 0; i--) { if (p.position==999) continue; if (p.isPass) { z = true; break; } }
} else {                                  // 自由跑：必经点通过 + 普通点 ≥ 2
    for (FixedPointTable p : fivePoint) { if (p.isPass) { if (p.isFixed==1) z3 = true; else i5++; } }
    z = i5 >= 2;
}
```

`totalDis / totalTime / speed / validDis / validTime / unCompleteReason` 同样在此函数本地算出。

### 4.2 签名覆盖面

[HttpUtil.java:280-290](cacert/xbud/jadx_app/sources/run/xbud/android/utils/HttpUtil.java#L280-L290) → [r11.java:47-48](cacert/xbud/jadx_app/sources/defpackage/r11.java#L47-L48)

```
signature = md5( "k1=v1&k2=v2&…（字典序，忽略大小写）" + SportJniUtils.getSecretKey(4) )
```
参与签名的 20 个字段（`SignRunUploadBean`）：`sportType, totalTime, totalDis, speed, startTime, stopTime, complete, selDistance, unCompleteReason, getPrize, status, uuid, uid, avgStepFreq, totalSteps, selectedUnid, calorie, policy, selRunTime, validDis, validTime`。

**`fivePointJson` / `allLocJson` / `segmentJson` 不在签名内**（`Observed`，`xbud-analysis/S13_save_record_datacollection_static.md` §1.3 反证 + 离线签名自检 9/9 PASS）。

key 明文（提取自 `libsport.so`，实测可用）：
```
salt4 = 2slhe02lsfiwowlcixisla_sls-_slaor
```
接口签名同源（[eg1.java:112](cacert/xbud/jadx_app/sources/defpackage/eg1.java#L112)）：
```
sign = md5( ("https://campusapi.xbud.run" + path).replace("https","http") + salt4 )
```

### 4.3 三段式

```
POST /v4/sport/run/save_record            → 校验签名，返回 rrid
GET  /v2/util/sts                         → COS 临时凭证
PUT  <bucket>/stable/<rrid//1e6>/<rrid>.json   ← gzip(RunDataBean)
POST /v4/sport/run/get_record_detail      → 回读 signature / isCheat / stepFreqStatus / reasonList
```

`RunDataBean` 结构（`xbud-analysis/S13_save_record_datacollection_static.md` §1.3）：

```
{ uid, uuid, rrid, runData(轨迹), speedJson, stepFreqJson, fixedPointJson }
fixedPointJson = {"useZip":false,"fivePointJson":"[{...}]"}   ← 双层转义
```

---

## 5. 伪造实现（本仓库已落地）

### 5.1 A 层：声明式（决定服务端判不判过）

| 位置 | 动作 |
|---|---|
| [HookMain.java:1445-1476](cacert/xbud/lsposed/stubs/com/harness/nsk/HookMain.java#L1445-L1476) | `buildFivePoint(cfg, st)` 由 `shot.cfg` 的 `points=` 生成 N 个点，每点 `"isPass":true`、`position=i`、`flag=start` |
| [HookMain.java:1342-1346](cacert/xbud/lsposed/stubs/com/harness/nsk/HookMain.java#L1342-L1346) | 包成 `{"fivePointJson":"…","useZip":false}` 再 `setFivePointJson` |
| [HookMain.java:1336](cacert/xbud/lsposed/stubs/com/harness/nsk/HookMain.java#L1336) | `setComplete(true)`、`setUnCompleteReason(1)` |
| [HookMain.java:1352-1354](cacert/xbud/lsposed/stubs/com/harness/nsk/HookMain.java#L1352-L1354) | 调 `HttpUtil.signRunRecord(bean)` 由**客户端自己**签名，避免离线实现与版本漂移 |

无 `points=` 时退回单点（默认 `图文信息楼北门` / pid 54130，旧版为 `学校西门` 54129）。

### 5.2 B 层：几何对齐（让点位“有据可依”，供 COS 回放与人工复核）

| 文件 | 作用 |
|---|---|
| `_route_ntsf.py:87/114-171` | 12 点位全校区环线，`build_waypoints()` 按 `get_fixed_point` 顺序串点，`check_waypoints(max_dist=30)` 断言经过 |
| `gen_track2.py:353-356` | 打卡点取自校园点位表并贴到轨迹最近处；`position` 递增断言在 `:451` |
| `finish_track.py:73-82` | `fixed_pts + all_points`：路线走 12 点全环线，打卡点仍只写分配的那几个 |

### 5.3 C 层：开跑会话（未实现）

服务端真正按点位校验轨迹的前提是“App 内手动开跑 → 建立会话 → 会话携带点位”。注入 bean 没有会话，`reasonList[0]` 直接 `complete=true`（见 §6）。这是唯一残留缺口，也不影响现状。

---

## 6. 权威边界与实测矩阵

| rrid | 来源 | policy | selDist | 请求点数 | 服务端 `fivePointJson` | `reasonList[0]` |
|---|---|---|---|---|---|---|
| 71945101 | 真人 4037 m | 3 | 1.0 | 3 | `""` | true |
| 72036382 | 注入 | 3 | 1.0 | 1 | `""` | true |
| 72036659 | 注入 | 3 | 1.0 | 4 | `""` | true |
| 72039923 | 注入 | 1 | 1.0 | 4 | `""` | true |
| 72040731 | 注入 | 3 | 1.0 | 5 | `""` | true |
| 72040928 | 注入 | 1 | 4.0 | 5 | `""` | true |

（完整 8 条见 [FINDINGS-SERVER.md](FINDINGS-SERVER.md) §2）

三条硬结论：

1. `fivePointJson` 是**请求侧 DTO**，服务端从不回填 —— 真人也回空串，所以 detail 里看不到点 ≠ 没写进去。
2. `policy` / `selDistance` 服务端**尊重**（4.0 时文案变「最少要跑4.00公里」）→ bean 字段有效，唯独点位字段不参与校验。
3. 打卡校验挂在**开跑会话**；无会话即无校验对象，该项直接判过。

---

## 7. 未验证 / 已知边界

| 项 | 状态 |
|---|---|
| `isValidPoint` 六参阈值模型 | `Assumed`：未做入参采样，只在真机见过 4 次无效 → 弹窗 |
| `fw0.s(int)` 上游调用点（谁把定位点匹配到点位 id、判定半径取值） | `Inferred`：`mc0/qc0/tc0/wv0` 各有同构实现，未逐一绑定 sportType |
| 服务端是否抽样复算轨迹 | `Assumed`：`isCheat` 恒 0，但无受控对照（改字段重签再回读） |
| 点位可否“存进”记录 | **不可**（Observation，不是权限问题，是服务端不落库） |
| 删除记录 | 不存在删除端点（dex 全量扫描） |

---

## 8. 复现路径

```powershell
# 1) 离线签名自检（不碰设备）
python xbud_sign.py

# 2) 拿点位
python fixed_points.py --dis 4000 --out points.json   # 系统分配点（走 get_fixed_point，带 sign）
python -c "import json;print(len(json.load(open('campus_points.json'))))"   # 校园全表 12 点

# 3) 模块侧提交一条带 N 个打卡点的记录
#    shot.cfg: enabled=1 / dist / dur / steps / start / points=[...]
adb -s <SERIAL>:5555 shell "am force-stop run.xbud.android"
adb -s <SERIAL>:5555 shell "am start -n run.xbud.android/.mvp.ui.other.SplashActivity"
adb -s <SERIAL>:5555 logcat -d -s NSKHOOK:I | Select-String 'CFG|4K.POINTS|4K.FIVE|4K.OK|4K.CONFIRM'

# 4) 回读判定（spec 是一个「列表」，每项 {tag,path,body}）
python -c "import json;print(json.dumps([{'tag':'det','path':'/v4/sport/run/get_record_detail','body':{'rrid':<RRID>}}]))" > spec_det.json
python oneattach.py spec_det.json
```

---

## 9. 相关文档

- [FINDINGS-SERVER.md](FINDINGS-SERVER.md) —— 服务端行为与实测矩阵（结论层）
- [USAGE-MODULE.md](USAGE-MODULE.md) §10 —— `shot.cfg` 协议全表（含 `points=`）
- [SHOT_USAGE.md](../SHOT_USAGE.md) —— 最短上手流程
- [STEALTH.md](STEALTH.md) —— 反检测面
