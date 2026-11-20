# 信封解密：下一轮思路（2026-11-12）

> 承接 `docs/DIRECT-PULL.md`。本轮**全部结论离线产出**，不发网络、不碰设备，
> 依据：`cap/nsk.log`（21 条 ENC 真机已知对）、两份 HAR、`envelopes/` 密文集、内嵌 RSA 公钥。
> 标注：**【实测】** 有可复现脚本；**【推断】** 由实测推出未直接验证。

---

## 0. 一句话结论

**DIRECT-PULL 里"唯一未闭合的是 SM4 key + h"的判断要修正为三处结构性错误；其中最致命的一处让既有的 key 扫描工具在正确的 key 就在内存里时也扫不出来。**

| # | 文档原判断 | 本轮实测修正 | 影响 |
|---|---|---|---|
| M1 | `r` 带 IV 前缀（`RESPONSE_HAS_IV_PREFIX=True`），扫描用 `iv=r[:16]` | **两种布局都成立，但"IV 前缀"几乎不可能**：若服务端 26 个响应复用同一 IV，是实现级失误；更合理解释是**首块是明文块**（所有响应首块都是 `{"error":10000,"`） | **既有 `_memkey.py sweep` / `envelope.bruteforce_memory` 只支持布局 B** → 布局 A 下即使 key 在内存也扫不到。必须换 IV-free 扫描器 |
| M2 | 加密区前有"**约 160~190 字节的固定头部 JSON**" | **长度精确 = 177 字节；但内容不是固定的** —— body 族请求两两密文公共前缀 = **0 字节**（明文从第 0 字节就分叉）→ 这 177B 头部**每请求变化** | 文档"固定头部"的措辞误导；真值是"定长变内容" |
| M3 | key/IV 缺失，怀疑"运行时派生" | **key/IV 是「每进程」粒度、进程内恒定**：同进程同明文两次加密 → `d` 与 `h` **逐字节相同**（pid 4191, `{"type":1}`） | 内存 dump 必须与密文**同一进程**；跨会话比对必然 0 命中 |

---

## 1. 本轮新增实测（全部有脚本可复现）

| 编号 | 事实 | 依据 |
|---|---|---|
| E1 | `d` 原始长度恒为 16 的倍数（192/240/256）；`k` 恒 128B | `_env_probe.py A` |
| E2 | **加密开销精确 177 字节**：由 5 组已知对（明文体长 10/14/48/52/63）反推区间交集 = [177,177] | `_env_probe.py A` |
| E3 | 请求体信封的 177B 头部**每请求不同**：同进程内 9 条 body 族请求，两两密文公共前缀 **0 字节** | `_env_probe2.py` |
| E4 | headerSign 族（长 d，12 条）两两密文公共前缀 = **192 字节 = 12 块** → 其加密明文前 192 字节是静态的，变化字段在 192 字节之后 | `_env_probe2.py` |
| E5 | **进程内确定性**：同进程同明文 → `d`、`h` 全同；`k` 全不同（每请求重新 RSA 封装） | `_env_probe5.py` |
| E6 | HAR 中 15 条请求的 `k` **15/15 全不同**；`get_task_list` 3 次请求 → `d` 唯一值 2 个（明文不同才不同） | `_env_probe5.py` |
| E7 | 33 条响应 `r` 原始长度恒 16 倍数；会话内首块常量（`6f7505…` 7 条 / `5d1dd1…` 26 条） | `_env_probe.py B` |
| E8 | `k^e mod n`、`s^e mod n` **既不是 PKCS#1 签名(00 01)也不是 PKCS#1 加密(00 02)** → `k` 是公钥加密（无私钥不可逆）；**`s` 用的不是这把私钥**（或为无填充原始签名） | `_env_probe.py C` |
| E9 | `n` 无法分解：小素数试除(≤2e6) / Fermat(2e6) / Pollard p-1(B=2e5) / Pollard rho(2e5) **全部失败** → 私钥路线关闭 | `_env_probe4.py` |
| E10 | **`h` 大空间搜索无命中**：23 种成分（明文/b64明文/d/k/salt1-5/各 16 字节与 md5 形式…）的 1~2 阶 md5 全排列 + hmac-md5 = **11 684 个构造，0 命中** → `h` 必含密钥成分（文档只试了 ~10 个） | `_env_probe.py D` |
| E11 | HAR 中**没有任何 NetSecKit 初始化/下发密钥的明文端点**：153 条明文响应全是第三方 SDK（友盟/穿山甲/广点通/极光…）+ xbud 自己的 `sts` 与 `verify` → "服务端下发 secret 派生 key"假设**排除** | `_env_probe3.py` |
| E12 | **对现有 8 份内存 dump（合计 ~1.6MB）实扫：强过滤器 HIT=0**，且每份 dump 的"会话同一性"检查均为**未发现已知明文/密文片段** → 这些 dump 与 HAR/nsk.log 的密文**不同会话**，0 命中属 M3 的必然结果，**不能读作"key 不在内存"** | `keyhunt2.py sweep`（日志 `_keyhunt2_sweep.log`） |

> E12 的操作含义：**扫之前先看"会话同一性"那一行**。它是负结果与"工具/会话配对错误"的分水岭。
> 弱过滤器（仅可打印）在这 1.6MB 上出了 920 个候选（≈1/1700 窗口）——**全是噪声**，必须由强过滤器/verify 收口。

---

## 2. 模型修正后的信封结构（当前最佳解释）

```
请求 body / headerSign
  {"d": b64(SM4-CBC(K, IV, PKCS7( [177B 变长头] || 明文体 ))),   <- 无 IV 前缀
   "h": 32hex(带密钥摘要),
   "k": b64(RSA-1024-PKCS1v15(K||…)),   <- 每请求重新封装(随机填充)，
   "p": "101", "t": "0"}

响应
  {"r": b64(SM4-CBC(K', IV', PKCS7(JSON))),  "s": b64(128B), "v": 101}
```

- `K / IV`：**每进程**生成，进程内恒定（E5）→ 同一进程内同明文同密文；
- `k`：每请求重新 RSA 封装（E5/E6）→ 服务端用私钥解开得到会话材料；
- 177B 头部：**定长、变内容**（E3），headerSign 族另有 192B 静态前缀（E4）。

---

## 3. 三条新路径（按性价比）

### 路径 1（工具级修正，必做）：IV-free / 布局无关 / 已知明文尾 的 key 扫描

**原理**：CBC 下 `P_i = D_K(C_i) ^ C_{i-1}`。对响应密文 `r`：

```
X = D_K(r[32:48]) ^ r[16:32]
```

- 布局 A（`r = C1|C2|C3…`，IV 不在密文里）→ X = 明文第 3 块
- 布局 B（`r = IV|C1|C2|C3…`）→ X = 明文第 2 块

**两式右端完全相同** ⇒ 一个表达式同时覆盖两种布局，**且完全不依赖 IV**。

请求侧还有更强的过滤器：明文 = `[177B 头][body]` ⇒ 明文总长 `T = 177 + len(body)`，
PKCS#7 补 `padlen` 字节，末块起点 `(T//16)*16`，末块里落在 body 区的字节**完全已知**：

```
P_last = D_K(C_last) ^ C_{last-1}      # 末块期望值 = body 尾 + padlen 个 pad 字节
```

直接比对 ⇒ **误报率 ~2^-120**，且与 IV / 布局**都无关**。

**工具**：`keyhunt2.py`（已交付，已过合成自测）
```powershell
python _keyhunt2_selftest.py                # 合成自测：植入 key 到假内存 -> 必须命中正确偏移
python keyhunt2.py list                     # 看语料
python keyhunt2.py sweep _mem\netseckit_rw.bin _mem\nesec_seg0.bin …
python keyhunt2.py verify <keyhex> [ivhex]  # 拿到 key 后一键验证 + 自动试 IV 派生
```
自测结论【实测】：`SELFTEST PASS` —— 植入 `0x1a3f7` 的请求 key 被强过滤器精确命中；
植入 `0x777` 的响应 key 被 IV-free 过滤器命中（布局 A / 布局 B 都成立）；
`verify()` 在布局 A（IV 由明文前缀反推）与布局 B（IV=r[:16]）下**都能解回原文**。

> 弱过滤器（仅可打印）的误报率约 1/5000 窗口，**只能当候选**，最终以强过滤器/verify 判定。

> **前提（E3/E5 的直接推论）**：dump 必须与被解密的密文**来自同一进程**。
> 现有 `_mem/*.bin` 与两份 HAR **不是同一会话**，扫到 0 命中属预期 —— 不是"key 不在内存"。

### 路径 2（新增，替代"内存 dump + 反调试"）：patch-to-known-key

**思路反转**：不去"偷"key，而是**把 app 的 key 换成我们自己指定的**，再让 app 照常发包。

- 手上有 `lib_arm64-v8a_libnetseckit-4.2.4.so`（1.5MB）与 SM4 key schedule 入口
  （文档 §3.1：key schedule @ `0x39030`，唯一调用点 @ `0x95bcc`，`x1` = 16B key 指针）；
- 在 `0x39030` 入口插 5 条指令：`adrp/add` 取常量地址 → `ldp` 取 16B → `stp` 写回 `[x1]`，
  常量放进 `.data` 的空隙；
- **同时**要让 `k` 字段封装同一个 key（否则服务端用 `k` 解不开 `d`，回 200+0B）：
  我们有内嵌公钥 → 离线算 `RSA_enc(K_ours)` 写进 `k` 的封装点；
- 结果：app 用我们的 key 加密，**PC 侧完整解密全部流量**，prologue / `h` 全部自曝。

代价：需要定位 `k` 的 RSA 封装点 + 处理"改了 so 会不会被完整性校验发现"（文档 §4 提到有篡改检测，
需要先确认 so 是从 APK 解包加载还是从 `/data/app/.../lib/arm64` 加载，以及校验点是否覆盖该文件）。

### 路径 3（并行，成本极低）：`h` 的离线构造搜索器

E10 已经把"无密钥成分"这条路走死。拿到 `K` 之后，把搜索空间扩成
`{K, IV, 177B 头, 明文, b64明文, d, k, salt1..5, "101", "0"}` 的 1~3 阶组合 + HMAC-MD5，
用 12 组已知对逐条判定 —— `h` 必在里面（它不可能既含密钥又不是标准摘要）。

### 兜底（不用 key 就能做的事）

- **进程内解密 oracle**：模块已 hook `SecruityInfo.decryptStringFromServer` → 把抓到的 `{"r","s","v"}`
  直接喂回 app，让 app 自己解 → **不需要 key 就能读当次会话的响应**（文档路径 C）。
- **本地点位复刻**（文档路径 D）：零网络，点位对得上即可，与信封无关。

---

## 4. 优先级与成本

| 序 | 动作 | 成本 | 成功判据 |
|---|---|---|---|
| 1 | 同一进程内同时"抓包 + memdump"（`kill -STOP` → `memdump_all` → `kill -CONT`） | 一次真机 | dump 里能搜到该次请求的**明文字节**（=同会话） |
| 2 | `python keyhunt2.py sweep <该 dump>` | 分钟级 | 打印 `HIT ... known-tail` 且明文尾与 body 原文一致 |
| 3 | `python keyhunt2.py verify <key>` | 秒级 | 响应解出 JSON、请求解出 `{"…177B 头…}` |
| 4 | `h` 搜索器跑一遍 | 秒级 | 12/12 全中 |
| 5 | 若 1~4 卡住 → 路径 2（patch-to-known-key） | 半天 | app 用我们的 key 发包，PC 端全量解密 |
| 6 | 仍不行 → 路径 B（Unicorn 跑 x86_64 版 SDK）/ `process_vm_readv` 绕开 `/proc/self/mem` 看门狗 | 天级 | — |

---

## 5. 复现命令集（本轮新增）

```powershell
cd D:\XbudWork\draft-d46d7dde-489f-4cb1-8e0d-53d8c89f12b6
python _env_probe.py  all      # E1/E2/E7/E8/E10
python _env_probe2.py          # E3/E4/E5
python _env_probe3.py          # E11
python _env_probe4.py          # E9
python _env_probe5.py          # E5/E6
python keyhunt2.py list
python keyhunt2.py sweep _mem\netseckit_rw.bin _mem\nesec_seg0.bin
python keyhunt2.py verify <keyhex>
```

## 6. 未闭合

- [ ] `K`（每进程 16B SM4 key）—— 需要一次同会话 dump，或路径 2
- [ ] 请求侧 IV —— 拿到 K 后由 `verify` 的派生候选表自动判定
- [ ] 177B 变长头的**字段构成** —— 拿到 K 后解一次即得；再用两次请求 diff 出可变字段
- [ ] `h` 的精确算法 —— 拿到 K 后离线搜索
- [ ] `s` 用的是哪把私钥 —— `s^e mod n` 无 PKCS#1 结构，需在 so 里找**第二把 RSA 公钥**
- [ ] `r` 的布局 A/B 终判 —— 拿到 K 后一次判定（布局 A ⇒ IV 由明文前缀反推；布局 B ⇒ IV=r[:16]）
