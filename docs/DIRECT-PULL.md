# 小步点「直连拉取点位」全量结论

> 结论日期：2026-11-12
> 证据来源：2026-11-11 两份 ProxyPin 抓包（`ProxyPin11-11_15_27_59.har` 254 条、`ProxyPin11-11_15_46_44.har` 318 条）、
> 本工作区既有逆向产物（`FINDINGS_PROTOCOL.md` / `PROTOCOL_SALT_EVIDENCE.md` / `docs/FINDINGS-SERVER.md`）、
> 本轮对 `campusapi.xbud.run` 的活体探针、以及对 `libnetseckit-4.2.4.so` 的静态分析 + 真机内存实验。
>
> 标注约定：**【实测】** 有可复现命令与原始回执；**【代码】** 由反编译/反汇编结论推出；**【推断】** 未直接验证。
> 设备：MuMu 模拟器 `192.168.3.39:5555`（x86_64 + libhoudini），Android 15，app `run.xbud.android` 2.6.8，adb 已 root。

---

## 0. 一句话结论

**限制直连条件的是信封加密，接口本身并不构成限制。**

`campusapi.xbud.run` 的每个业务请求需要**两个** NetSecKit 信封（body 一个、`headerSign` 头一个），
每个成功响应也是信封；明文直连一律被网关静默丢弃（200 + 0 字节）。
信封的密码学结构已完整闭合（SM4-CBC / PKCS#7 + 内嵌 RSA-1024 公钥），
**唯一未闭合的是对称密钥与 `h` 算法，二者均为运行时派生，静态文件里不存在**。

而如果目标只是"跑路时点位对得上"，**根本不需要联网**：点位项在服务端恒判通过，
点位数据只进 COS 轨迹对象供地图回放，学校点位库是静态 12 点。见 §7。

---

## 1. 抓包补充的三条关键事实（此前完全漏掉的一层）

### 1.1 `headerSign` 请求头 —— 它本身就是一个信封

HAR 里每个 campusapi 请求都带 `headerSign`，长度 1484 字节，格式与 body 信封完全相同：

```http
POST /v1/university/get_campus_point_list HTTP/1.1
headerSign: {"d":"<928B 密文>","h":"<32hex>","k":"<128B 密文>","p":"101","t":"0"}
Accept: application/json
appVersion: 2.6.8
Content-Type: application/json;charset=UTF-8
```

`uid / token / timeStamp / nonce / tokenSign` 以及整套设备头（physicPixel、DeviceId、blMac、osVersion …）
**不在 HTTP 头里，全在 `headerSign` 信封内**。这解释了此前"明文 + 全套头直连"为什么永远拿不到数据。

### 1.2 网关三种行为【实测】

| 请求形态 | 结果 |
|---|---|
| 明文 body，无 `headerSign` | **200，0 字节**（静默丢弃，不报错、不 404、不 500） |
| 明文 body + 明文/垃圾 `headerSign` | **200，0 字节**（同上） |
| 抓包里的**真信封** body + 真 `headerSign` 回放 | **401 明文 JSON** |

第三行是决定性证据：服务端**成功解开了我回放的信封**，一路走到 token 校验才失败：

```json
{"timestamp":1794466275277,"status":401,"error":"Unauthorized",
 "exception":"run.xbud.server.campusapi.exception.ClientParameterException",
 "message":"token有误或缓存中的token不存在","path":"/v1/..."}
```

**可复用的调试判据**：`200+0B` = 信封没解开；`401 明文` = 信封与 `h` 全过、只是身份没过。
**没有有效 token 也能拿它当信封 oracle 用。**

### 1.3 错误体是明文，成功体才是信封

业务失败直接回明文 JSON（401/500），业务成功才回 `{"r","s","v":101}`。
所以"能不能读懂响应"取决于业务是否成功，与请求是否加密无关。

---

## 2. 信封结构（修正后的完整版）

```
请求 body / headerSign
  {"d": b64(密文), "h": 32hex, "k": b64(128B), "p": "101", "t": "0"}

响应
  {"r": b64(密文), "s": b64(128B), "v": 101}
```

| 字段 | 实测事实 | 结论 |
|---|---|---|
| `d` | 长度恒为 16 的倍数，**无 IV 前缀**；同进程内同一明文多次请求，`d` **逐字节相同**（HAR 里 `get_task_list` 出现 3 次，`d` 完全一致） | 对称加密、固定 IV、固定密钥；同一明文必然同密文 |
| `r` | 长度恒为 16 的倍数；**同进程内所有响应的前 16 字节完全相同**，换进程就变（H1=`5d1dd1fa899fdca9a5917bc1877edbb6`，H2=`6f750597b49269ac83e0473bbe91b4c0`） | 不是"魔法头"，是 CBC 首块密文：所有响应明文首块都是 `{"error":10000,"`，同 key/IV 下首块必然相同。**密钥或 IV 以进程为粒度** |
| `s` | 26/7 条响应**全部恒为 128 字节**，且逐条不同 | 128B = RSA-1024 分组；每次不同 = PKCS#1 v1.5 随机填充 |
| `k` | 恒 128 字节，逐次不同 | 同上，RSA-1024 封装会话材料 |
| `h` | 32 hex；同明文同 `h`；**不等于** md5(明文)/md5(b64明文)/md5(d)/md5(k)/md5(k+d)/md5(d+k)/md5(salt+明文) | 带密钥摘要（HMAC 类）或含密钥前缀的 MD5 |
| `p` / `v` | 恒 `"101"` / `101` | 协议号；6 个 dex 里 `"101"` 0 命中，由 native 生成 |
| `t` | 恒 `"0"` | 类型位，对应 `SecruityInfo(...,0)` 的 `a()/b()` 分支 |

**两个已排除的误解**：

1. `r` 前 16 字节**不是固定魔法常量**（跨会话会变），当固定 key/IV 去爆破是白费；
2. 响应不是"key 在前、密文在后"的简单拼接，`s` 是密钥封装，与 `r` 的解密没有直接依赖。

### 2.1 明文里有一段固定 prologue【代码+实测推断】

| 输入 | `d` 长度 |
|---|---|
| `{}` | **168 B** |
| Base64(body) ≈ 1052 B | **恒 1248 B** |
| 789 B 的 save_record 明文（`envelopes/*.body.json`） | **1248 B** |

差值 ⇒ 加密区的明文不只是业务 body，前面还有约 **160~190 字节的固定头部 JSON**（appVersion/deviceId/nonce 之类）。
这是"自造信封全部被拒"的根因：只补 body 不补 prologue，服务端解不出合法明文。

---

## 3. 密码学：已闭合的部分

### 3.1 对称算法 = SM4-CBC + PKCS#7【代码，逐字节验证】

| 证据 | 位置 |
|---|---|
| SM4 S-box，与 GB/T 32907-2016 **逐字节相同** | file `0x171020`（vaddr `0x181020`），256B |
| SM4 密钥扩展，内联 FK/CK 立即数（`movk w17,#0x56aa` / `#0x677d` / `#0xbac6,#0xa3b1`；CK `0x00070e15`、`0x1c232a31`…） | vaddr `0x00039030` |
| 唯一调用点 | `0x00095bcc`：`mov x1, x26 ; bl 0x39030`（x1 = 16B key 指针） |
| CBC 跳板：`w5=1 → 0x3301c`（加密）/ `w5=0 → 0x3334c`（解密） | vaddr `0x00017d00` |
| 反证 | 全文件 AES S-box / AES T-table / MD5 IV / SHA1·SHA256 IV 常量 **0 命中** |

真机交叉验证【实测】：`memread 0x400021781000+0x20` 读回 `d690e9fecce13db716b614c228fb2c05`，
与 ELF 文件 `0x171020` 处内容完全一致（`+0x10` 处还能看到已重定位的指针 `…216fd264`，说明内存≠文件是对的）。

### 3.2 非对称 = RSA-1024 公钥内嵌【代码】

`libnetseckit-4.2.4.so` file `0x171990` 处是一把完整 SPKI DER：

```
30 81 9f 30 0d 06 09 2a 86 48 86 f7 0d 01 01 01 05 00
03 81 8d 00 30 81 89 02 81 81 00 <128B modulus> 02 03 01 00 01
```

- `n = ccb3a6c5…73a5c2d3c59`（1024 bit），`e = 65537`
- `k` / `s` 恒 128B，与 RSA-1024 分组精确吻合
- **客户端私钥没有内嵌**：全文件 `30 82`（私钥 DER 前缀）0 命中；用 (n,e) 对 33 条 `s` 做 `s^e mod n`，没有一条得出 PKCS#1 签名结构 ⇒ `s` 不是客户端能验的签名

**SM2 假设已排除**：SM2 的 p/a/b/n/Gx/Gy 在三份 so 里按 BE 字节串 / BE 32 位字数组 / LE 32 位字数组三种形态搜索 **全 0**；
48 个 `k`/`s` 在 offset 0/16/32/64、x‖y 与 y‖x 各判曲线方程 **0/384 成立**。
（`libnetseckit` 里确实残留 `F:/NetSecKit/MobSecEncrypt/MobSecNative/jni/../sm2.cpp` 路径串，但只是编译期遗留，未真正启用。）

### 3.3 唯一未闭合的两项【实测为负】

1. **16B SM4 key + 请求侧 IV 不在任何文件里**
   - 双 HAR 会话联立做 2×128-bit 判定（IV=r[:16], C0=r[16:32]），把 `libnetseckit` 全文件 1.5M 个字节窗口当候选：**SM4-128 / AES-128/192/256 全部 0 命中**
   - 84 个人类可猜派生候选（md5/sha1/sha256 of 包名、5 个 salt、netseckit 字样等）→ **0**
   - 我另跑 483 组（salt/md5/sha256 派生 × 3 种 IV 视法）→ **0**
   - 真机内存里也找不到：`libnetseckit` 的 `.data` 只有 1 页（4096B），已完整取回并做 4080 窗口扫描 → **0 命中**
   ⇒ key 在 native 堆，运行时派生
2. **`h` 的算法**
   - 已排除：md5(明文)、md5(Base64明文)、md5(d)、md5(k)、md5(k+d)、md5(d+k)、md5(salt4+明文)、md5(明文+salt4)、md5(明文+k)、md5(k+明文)
   - 已确证：同明文同 `h`，不随时间变化
   - 候选方向：HMAC-MD5(K, …)、含密钥前缀的 MD5、SM4-ECB(K,明文) 的 md5、md5(明文+IV)

---

## 4. 已经完全不需要逆的部分

| 项 | 状态 | 证据 |
|---|---|---|
| 接口路径 | 已知 | `/v1/sport/run/get_fixed_point/1/{distance}`、`/v1/university/get_campus_point_list`、`/v1/sport/run/get_config`、`/v4/sport/run/save_record`、`/v4/sport/run/get_record_detail` … |
| `get_fixed_point` 的 `sign` | **可离线复算** | `sign = md5(("https://campusapi.xbud.run"+path).replace("https","http") + salt4)`，`salt4 = 2slhe02lsfiwowlcixisla_sls-_slaor`；`uuid` 不参与校验；sign 错则 `{"error":10137,"message":"请求不合法"}` |
| 身份头 `uid/token/timeStamp/nonce/tokenSign` | **可离线算** | `xbud_sign.py` 9/9 PASS：`tokenSign = MD5( 排序 k=v&… + salt4 )`，排序按 `compareToIgnoreCase`，MD5 走 **char→byte 截断**（非 UTF-8），小写 hex |
| 5 个 salt | 明文已知 | `getSecretKey(1..5)` = `rltCvhT0Umga3tnq` / `xbudSport2020Run` / `2020810bluetooth` / `2slhe02lsfiwowlcixisla_sls-_slaor` / `pKXu#PHLOzx78x#%A55O4dzdhzBCqw7h` |
| 明文旁路端点 | **直连可用** | `POST /v2/util/sts`（无 headerSign、无 body、回明文 JSON，含 COS 临时凭证）、`verifyapi.xbud.run/api/v40/datacollection/verify`（心跳，明文）、COS `PUT/GET`（独立的 q-sign 签名） |
| `headerSign` 无新鲜度绑定 | 已证 | 24h 前的信封回放仍能走到 token 校验 |
| 关闭加密的开关 | **不存在** | `pu0.setBodyContent` 无条件加密（仅加密抛异常才回明文）；`lz0` 硬编码 `new fk0()`；manifest/arsc 里连 `SECRET_TYPE` 都没有（同源的运动世界 7.3.93 有 `@ApiCrypto` + 13 条明文白名单，本 App 没有） |

### 4.1 SecruityInfo 的真实身份【代码】

- 全名 `com.netease.NetSecKit.interfacejni.SecruityInfo`，定义在 `xbud_base.apk` 的 **`classes4.dex`**（该 dex 有 35 个 NetSecKit class_def）
- SDK = 网易易盾 **NetSecKit 4.2.4**（`a/b/b.java:29` 的 XOR 串可解出 `netseckit-4.2.4`；APK 内确有 `lib/arm64-v8a/libnetseckit-4.2.4.so`）
- 第二参数只有 0 / 1 两分支，其它值返回空串：

| 分支 | Java 方法 | JNI 符号 |
|---|---|---|
| 0（本 App 用） | `a()` 加密 | `w3270a03dafee4a0a` |
| 0 | `b()` 解密 | `w1228bcedf6204eeb` |
| 1 | `i()` / `j()` | `w30f61757150c7426` / `wee0ce70608106cfb` |

- **Java 层完全不解析 `r/s/v`**：整段响应体原样丢给 native 解密（两处调用点 `HttpUtil.java:260`、`j10.java:50`），两棵全量 jadx 树里 `getString("r")`、`optString("r")`、`has("r")` **0 命中**
- 入参先 `Base64(UTF-8)`，出参 `.trim()`

---

## 5. 取 key 的全部实验记录（含失败原因）

### 5.1 frida：两条路都堵死【实测】

| 方式 | 结果 | 根因 |
|---|---|---|
| `frida -f run.xbud.android -l hook.js`（spawn） | `Failed to spawn: unable to load libart.so: dlopen failed: library "libart.so" not found` | app 是 **arm64 二进制跑在 x86_64 模拟器的 houdini 翻译层**，x86_64 版 frida-server 解析不了它的 linker 命名空间 |
| `frida -H … -p <pid>`（attach） | `unable to write to process memory: No such process` | 壳的 ptrace 保护 |

`hook_key.js` / `hook_key2.js` 已写好（hook `0x39030` 取 key 指针、hook `0x17d00` 取进 CBC 的明文、并主动调 `lz0.do().do('{}')` 触发），一旦有可用的注入通道即可直接用。

### 5.2 外部读 `/proc/<pid>/mem`：能读，但会杀进程【实测】

| 工具/方式 | 结果 |
|---|---|
| `dd if=/proc/$P/mem bs=4096 skip=<page>` | **0 字节 / 全零**（本机 toybox dd 在 procfs 上不可用；`bs=1 iflag=skip_bytes` 同样 0 字节） |
| `/data/local/tmp/memread <pid> <addr> <len>` | **可用**，但单次 **≤256B** |

两个必须知道的坑：

1. **必须等 `libnetseckit` 出现在 `/proc/$P/maps` 之后再读**，否则只有 `read: I/O error`。我最早 30 次全 miss 就卡在这，加上等待条件后 16/16 块一次过；
2. 读之前要确认地址来自**当前**进程的 maps（模块 base 稳定在 `0x400021600000`，rw 页在 `0x400021781000`，只有 1 页 = 4096B）。

**读一次，进程就死一次**：`ActivityManager: Process run.xbud.android has died: fg TOP`，无 fatal signal、无 tombstone。

### 5.3 死因诊断：是 `open("/proc/self/mem")` 触发自毁，不是外部读取【实测】

| 实验 | 结果 |
|---|---|
| 外部 `memread` 读一次 | 立刻死 |
| **进程内**（LSPosed 模块 DumpTask）用 `RandomAccessFile("/proc/self/mem","r")` 读 | 也立刻死 |
| `kill -STOP <pid>` 冻结后再读 | **成功读到数据，`kill -CONT` 后进程存活** |

⇒ 触发器是"打开 `/proc/self/mem`"这个动作本身；冻结时看门狗线程跑不了，所以来得及读。

### 5.4 进程内 dump 任务（模块侧）已实现但被同一原因挡住

给 LSPosed 模块 `com.harness.nsk` 加的 `DumpTask`：

- 通过 `shot.cfg` 的 `task=dump` 选择（默认仍走 FourKTask）
- 睡 7 秒等 `SecruityInfo.init` 完成，再解析 `/proc/self/maps`，挑 `libnetseckit` 全部段 + `[anon:libc_malloc]` / `[anon:.bss]`，总量上限 `dump_cap_mb`（默认 96MB）
- 每段写 `/data/user/0/run.xbud.android/files/nsk_dump/<idx>_<start>_<len>.bin`，并写 `index.tsv`，日志 `DUMPROW | …`
- 构建产物：`cacert/xbud/lsposed/stubs/m57.apk`（javac → d8 → aapt → apksigner，工具链见 `build_stealth.ps1`）

实测：`INIT | task=dump -> DumpTask` 正常派发，但 7 秒后一开读，进程立刻 `has died`（§5.3）。

### 5.5 让 App 稳定存活（当前状态）【实测】

**死因不是 App 本身，是 `task=dump`。** 处理：

```
/data/user/0/run.xbud.android/files/shot.cfg
  改前: enabled=0 / task=dump / dump_cap_mb=96
  改后: enabled=0            ← 只留这一行
```

`task=` 缺失时走默认 FourKTask，`enabled=0` → 只做只读校验，不碰内存。

验证结果：

| 项 | 值 |
|---|---|
| pid | 54427，连续 120 秒未变 |
| 前台 Activity | `run.xbud.android/.mvp.ui.other.MainActivity`（`topResumedActivity`） |
| 崩溃缓冲 | 0 条 |
| 登录态 | 正常，`uid 1191253`、`token 6900ea0c-…` 有效 |

日志里 `HOOKMISS | …SecruityInfo.decryptStringFromServer` / `pu0.setBodyContent` 等属**良性**：那是首轮 hook 时类还没被加载，后续 `Watcher` 会在类加载时补挂。

**副产物（很有价值）**：模块已经 hook 到 `JNI` 层，日志里成对出现

```
ENC | arg0={"unid":1001104,"createdTime":"2024-02-28 14:24:59"} arg1=0
    ret={"d":"QfiRMJflWOXbyc7r…","h":"f8a5dd10444c17e3d9de24a0b3378a4e",
         "k":"BW1jFR9rl/YBaHXu7pzgq6JQJewkFcC5…","p":"101","t":"0"}
```

**明文 ↔ 密文对可以随时刷出来**。这比盲扫内存好用得多：拿到一对已知明文，扫内存时只需一次单块比对即可判定候选 key，误报率可忽略。

### 5.6 之后正确 dump 的姿势

1. `kill -STOP <pid>` 冻结
2. 用设备上现成的 `memdump <pid> <start_hex> <len> <out>` 批量读（`memdump_all <pid> <out>` 可整进程；`memread` 256B/次太慢）
3. 目标：`libnetseckit` rw 页（已知 1 页）+ `[anon:libc_malloc]` 若干区间
4. `kill -CONT <pid>` 恢复
5. PC 侧用 `envelope_verify.py` / `_memkey.py sweep` 拿已知明文对扫 key

---

## 6. 我这轮做过的"自造信封"实验（结论：不够）

用已提取的 RSA-1024 公钥，自选 `K`，构造 `k = RSA_PKCS1v15(n,e,K)`，`d = SM4-CBC(K, IV, 明文)`，
`h` 三种（md5(明文)/md5(d)/hmac-md5(K,明文)），IV 五种（全零/K/`5d1dd1fa…`/salt4[:16]/0..15），
输入两种（Base64(明文) / 裸明文），body 与 headerSign 同时发 —— **30 组全部 200 + 0 字节**。

根因不是 key 错，而是 §2.1 的 **prologue**：`d` 的明文区前面还有 160~190B 固定头部，
不同时复刻它，服务端解不出合法明文。

---

## 7. 为什么"点位"其实可以完全不联网

三条独立证据说明"拉点位"对业务结果没有影响：

| 证据 | 内容 |
|---|---|
| 判定层 | 服务端 `reasonList[0]`（点位项）**恒 `complete=true`**，真人记录亦然 |
| 数据层 | `get_record_detail.fivePointJson` **恒为空串**；点位只存在于 COS 轨迹对象（`stable/<rrid//1e6>/<rrid>.json`，gzip）里，供地图回放 |
| 数据源 | 学校点位库是**静态 12 点**（`campus_points.json`，字段含 `pid/unid/lat/lon/isFixed/relatedPids/createdTime/serialNum`），`get_fixed_point` 只是按 `distance` 从库里挑若干点，`isFixed=1` 的点每次随机 |

客户端选点逻辑也已还原（`zy0.java`）：

```
就近起点 → 按 relatedPids 随机下一个 → 避开上一点 → 无关联则回退就近
```

因此**本地复刻这个算法即可产出与真实下发无法区分的点位序列**：零网络、零信封、零封号面扩大。

> 只有当需要"真实调用记录"（例如想让服务端 access log 里出现 `get_fixed_point`）时，才需要 §8 的 A/B/C。

---

## 8. 五条路径（按性价比）

### D（首选，今天可用）：本地点位复刻
零网络。见 §7。需要"真实调用记录"时才往下走。

### C（次选）：复用现有 LSPosed 模块当信封 oracle
`pipeline.py` / `fixed_points.py` 已跑通"app 加密 → PC HTTP → app 解密"，业务照旧在 PC 侧做。
缺点：每次要连手机，不满足"直连"。

### A（真直连的终点）：一次提 key → 之后永久离线
抓手已全部定位：

| 目标 | 地址/符号 |
|---|---|
| SM4 key（16B） | hook `libnetseckit+0x39030` 的 `x1` |
| 进 CBC 的明文（含 prologue） | hook `libnetseckit+0x17d00` 的 `x1`（`w5=1` 加密 / `0` 解密） |
| 加密/解密的 JNI 入口 | `w3270a03dafee4a0a` / `w1228bcedf6204eeb` |
| 已知明文对 | 模块日志 `ENC | arg0=… ret=…` 现成 |

拿 key 的两条可行通道：
1. **`kill -STOP` + `memdump`**（§5.6，已验证技术上可行）
2. **换 arm64 frida-server / arm64 gadget 走 spawn-gating**（现有 `hook_key.js` 可直接用）

拿到后：`envelope.set_key(key, iv)` 或写 `out_so/key.txt "<keyhex> <ivhex>"`，`python out_so/verify.py` 一键验 26 条真机响应。

### B（纯 PC 但要逆向）：Unicorn 模拟 `libnetseckit`
`libnesec-x86.so` 是 **x86_64 ELF**（1.5MB，与本机架构一致），理论上可在 PC 侧用 Unicorn 跑起
`w3270a03dafee4a0a`/`w1228bcedf6204eeb`，只需伪造 JNIEnv/JavaVM 与最小 Context 桩。
成本约为 A 的 5～10 倍。

### E（已死，别再花时间）：抓包回放
token 24h 失效、响应仍加密、`d` 与 path/参数绑定（`distance` 在 path 里、`sign` 随 path 变）。

---

## 9. 已验证 / 未闭合 清单

**已验证（可直接依赖）**

- [x] 网关三态：200+0B / 401 明文 / 成功信封
- [x] `headerSign` 是信封且承载全部身份字段
- [x] 信封无新鲜度绑定（24h 回放仍过信封层）
- [x] `d` 确定性、`r` 首块按进程一致、`s`/`k` 恒 128B
- [x] 对称算法 = SM4-CBC/PKCS#7（S-box + FK/CK + 唯一调用点 + 反证）
- [x] 非对称 = RSA-1024（SPKI @ `0x171990`，e=65537，私钥未内嵌，`s` 非签名）
- [x] SM2 未启用（曲线常量三形态全 0，48 个 k/s 曲线判定 0/384）
- [x] `sign` / `tokenSign` 可离线复算（9/9 PASS）
- [x] `sts` / 心跳 / COS 可明文直连
- [x] 无"关加密"开关
- [x] key 不在任何静态文件（1.5M 窗口 × SM4/AES 全 0）
- [x] 读 `/proc/self/mem` 会触发自毁；`SIGSTOP` 期间可读
- [x] 去掉 `task=dump` 后 app 稳定（120s、MainActivity、crash 0）

**未闭合（需要真机一次提权）**

- [ ] 16B SM4 key + 请求侧 IV（运行时派生，在 native 堆）
- [ ] `h` 的精确算法（带密钥摘要，具体输入/顺序未知）
- [ ] prologue 的确切字节（可用 hook `0x17d00` 直接打印）
- [ ] `s` 到底封装了什么（不影响 `r` 的解密，可暂缓）

---

## 10. 复现命令集

```powershell
# 0) 模块构建（含 DumpTask）
cd cacert\xbud\lsposed\stubs
powershell -ExecutionPolicy Bypass -File build_stealth.ps1        # 产出 m<max+1>.apk 并 install -r

# 1) 切换任务（不做内存读时保持只有 enabled=0）
adb -s 192.168.3.39:5555 shell "printf 'enabled=0\n' > /data/user/0/run.xbud.android/files/shot.cfg"
adb -s 192.168.3.39:5555 shell "am force-stop run.xbud.android; am start -n run.xbud.android/.mvp.ui.other.SplashActivity"

# 2) 看信封明文↔密文对（模块已 hook JNI 层）
adb -s 192.168.3.39:5555 shell "logcat -d | grep -E 'NSKHOOK : (ENC|DEC)' | tail -20"

# 3) 冻结式内存读（唯一已知可行姿势）
adb -s 192.168.3.39:5555 shell "kill -STOP <pid>"
adb -s 192.168.3.39:5555 shell "/data/local/tmp/memdump <pid> 400021781000 1000 /data/local/tmp/rw.bin"
adb -s 192.168.3.39:5555 shell "kill -CONT <pid>"

# 4) 用已知明文对扫 key（PC 侧）
python envelope_verify.py --keyfile key.bin        # 26 条真机响应 + 12 组已知对
python out_so\verify.py                            # 结构断言 + 解密验证

# 5) 自造信封探测（用 401 当 oracle）
python _forge_probe.py                             # 30 组组合，401 即命中
```

---

## 11. 产物索引

| 文件 | 内容 |
|---|---|
| `docs/DIRECT-PULL.md` | 本文（全量结论） |
| `out_so/envelope.py` | 纯 stdlib SM4（国标向量 + 1e6 轮自测 PASS）、CBC/PKCS7、RSA-1024、`decrypt_response`/`encrypt_request`、key 注入槽（`set_key` / 环境变量 / `key.txt`）、`bruteforce_memory()`；无 key 时抛 `KeyNotRecovered` |
| `out_so/verify.py` | A 段结构断言 **8 PASS / 0 FAIL**；B 段解密待 key（退出码 3） |
| `out_so/REPORT.md` | 21KB，全部 offset/指令/排除项/下一条命令 |
| `out_so/_tools/hook_key.js`、`hook_key2.js` | frida hook：`0x39030` 取 key、`0x17d00` 取明文、主动触发加密 |
| `out_java/REPORT.md`、`candidates.json` | SecruityInfo 归属、两处解密调用点、Java 层 0 解析 r/s/v、无内嵌密钥、无开关 |
| `envelope_verify.py` | 候选 key/iv 验证台（26 条响应 + 12 组已知对 + 483 组内建字典 sweep） |
| `_memkey.py`、`_grab_rw.ps1` | 真机 rw 页取回 / 跨启动拼块 |
| `_forge_probe.py` | 自造信封 + 401 oracle 探测 |
| `_direct_probe.py`、`_gw_probe.py`、`_hdr_probe.py` | 网关三态、headerSign 变体、明文集探针 |
| `cacert/xbud/lsposed/stubs/` | LSPosed 模块源码（HookMain.java 含 DumpTask）+ `build_stealth.ps1` + `m57.apk` |
| `campus_points.json` / `campus_ntsf.json` | 静态 12 点位库（含 `relatedPids`） |

---

## 12. 一句话行动建议

- **只是跑路要点位对得上** → 走 D，别碰信封；
- **要真实调用记录 / 要打通提交链** → 走 A：`kill -STOP` + `memdump` 拿 16B key，或换 arm64 frida 注入，拿到后 `envelope_verify.py` 验 26 条响应，全绿即闭环。
