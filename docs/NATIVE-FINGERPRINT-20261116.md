# Native 指纹：SecruityInfo.getFingerPrint / getMobileData / getCheckValue

> 目标：复刻 `com.netease.NetSecKit.interfacejni.SecruityInfo` 的三个 native 指纹出口。
> 2026-11-16 已闭合 `getFingerPrint`：明文、AES key、RSA 公钥与 `rk` 生成全部拿到，
> `fp.py --gen` 生成的 `rdata` 与 App 逐字节一致。但复核确认 **App 自身不调用这三个
> getter**（只有 `fk0` 调 `encryptStringToServer/decryptStringFromServer`），所以
> `aimt_datas` 不在业务流量/评分链里。`getMobileData` / `getCheckValue` 仍受
> `+0x78` 检查和 0x9873c 内部异常阻塞，属研究项。

---

## 1. 调用链（已定位）

```
SecruityInfo.getFingerPrint()  -> a.a.b.a()  -> factory.a.a()
  -> JNIFactory.w7aa090cda25562b5(ctx)  -> native 0x21534
SecruityInfo.getMobileData()   -> a.a.b.b()  -> factory.a.b()
  -> JNIFactory.w01c166e77807dbf0(ctx)  -> native 0x21e70
SecruityInfo.getCheckValue()   -> a.a.b.c()  -> factory.a.c()
  -> JNIFactory.w734672804aee66ac(ctx)  -> native 0x20e08
```

`getFingerPrint` 内部：

```
0x21534  new FingerprintBuilder(0xb0)
  -> vtable+0x70  0x96b44       环境/初始化检查
  -> vtable+0x18  0x925c4
       -> vtable+0xe0  0xd39a0  收集设备字段 -> 明文 JSON
       -> vtable+0xe8  0x91298  生成 key / RSA 包裹 / AES / 拼 aimt_datas
            -> vtable+0xc8 0x97d08        生成 16B key
            -> provider+0x18 0x348b8      RSA-1024 PKCS#1 v1.5(key)
                 -> provider+0x40 0x352e0 选公钥（selector=9）并还原 DER
                 -> 0x34a30 / 0xf8754     解析 DER
                 -> 0xf8464               modpow(EM, e, n)
            -> provider+0x00 0x34594/0x34360  AES-128-ECB + PKCS#7
            -> provider+0x28 0x345d4      base64
```

`getFingerPrint` 在 native 里创建实现对象（`0x984b0`），走对象 vtable `+0x70`、
`+0x18`；`0x9873c`（vtable `+0xb0`）是另一条 AES 指纹路径，本次 `getFingerPrint`
未触发（应属 `getMobileData` / `getCheckValue` 或旧路径）。

---

## 2. 抓取通道（模块，原有）

模块 `com.harness.nsk`：

| 入口 | 行为 |
|---|---|
| 广播 `com.harness.nsk.FINGERPRINT` | 直接 new `SecruityInfo`，调三个 getter，落 `FP.*` 日志 |
| 广播 extra `name` | 只调指定方法，例如 `--es name getCheckValue` |
| cfg `fp=getFingerPrint` | app 启动时自动调一次（`FpBootTask`） |
| cfg `fp=off` | 启动时不调（默认关闭，避免指纹落日志） |

```bash
adb shell am broadcast -a com.harness.nsk.FINGERPRINT -p run.xbud.android \
    --es name getFingerPrint
adb shell logcat -d -s NSKHOOK | grep 'FP\.'
```

---

## 3. 算法（已闭合，实测）

### 3.1 输出

```json
{"datatype":"aimt_datas","id_ver":"Android_1.0.1",
 "rdata":"<b64,144B>","rk":"<b64,128B>"}
```

### 3.2 明文 JSON（133B，含尾部 `\n`）

```json
{"a":"9193b49139984398a43d5edd1ffa1ece","b":"15","c":"SM-A5260","d":"x86_64",
 "e":"","f":"","g":"2.6.6","h":1794809615,"i":"Samsung"}
```

| 字段 | 含义（实测推断） |
|---|---|
| `a` | 32 hex，设备/安装 ID（同一设备跨进程稳定） |
| `b` | Android 版本号（`15`） |
| `c` | `Build.MODEL` |
| `d` | ABI（MuMu 为 `x86_64`，真机 `arm64-v8a`） |
| `e`,`f` | 空；可能为 manufacturer / build 指纹位 |
| `g` | SDK/壳版本（`2.6.6`） |
| `h` | Unix 时间戳 |
| `i` | `Build.BRAND` |

### 3.3 AES

- 算法：**AES-128-ECB + PKCS#7**（不是 CBC；此前按 CBC 猜是错的）。
- key：16 个可打印 ASCII 字符（`0x21..0x7e`），每次调用由 `0x97d08` 生成。
- `rdata = b64(AES-ECB(key, pad(明文)))`，133B 明文 -> 144B。

### 3.4 RSA / `rk`

- `rk = base64(RSA-1024-PKCS1v15(n, e=65537, key))`，128B。
- 公钥 `n` 是**静态**的，来自 `libnetseckit` 内 13 条 140B 记录表
  （`0x281270 + idx*0x8c`）的第 9 条；`0x352e0` 还原后交给 `0xf8754` 解析成 DER。
  不是服务端 init 下发，也不是运行时动态公钥；同一 App 版本内固定。
- DER（140B，PKCS#1 RSAPublicKey）：

```
30 81 89 02 81 81 00
b7bb80451ceb9667d6863885f26120d9be1e28230f43db963d14d9a1f6deb2d991
890a840fd2adc3e1067584826ef806f6d4fe8d34af5942ed27c4f2dcd05758cf
487342ea1dec81dacd7338324daaf61598dad078b75fdf08a9176e0e585168af
10766ef24ae2d20721b8303b4d6079650f74aef15d0a89ba996c72a5cb269b
02 03 01 00 01
```

- 验证：打桩捕获的 EM（`00 02 || PS || 00 || key`）与同一次调用的 `rk` 满足
  `pow(EM, 65537, n) == rk`，逐字节一致。
- 服务器持有 `d`；客户端只需要公钥即可生成 `rk`，无需私钥。

---

## 4. 打桩工具 `fpcap.py`

源码 `goxbud/tools/fingerprint/fpcap.py` + `_keycap/fpcap.s`。

### 4.1 补丁点

| 地址 | 原指令 | LR | 捕获 |
|---|---|---|---|
| `0x9162c` | `blr x6` | `0x91630` | RSA 前的 16B key、keyid、out 指针 |
| `0x34a30` | `bl 0xf8754` | `0x34a34` | 公钥 DER（140B） |
| `0x91a6c` | `blr x7` | `0x91a70` | AES: key16 + 明文 + 长度 |
| `0x9b854` | `blr x7` | `0x9b858` | `FUN_0019873c` 的 AES（本次未触发） |
| `0xf8428` | `blr x6` | `0xf842c` | RSA 引擎的 EM、key context、8 个 bignum 导出 |

### 4.2 代码洞

`0x16c000`（LOAD1 末页）。原 `p_filesz=0x16beec` 只映射到 `0x16c000`；把 ph0
`p_filesz/p_memsz` 扩到 `0x16cb20`（LOAD2 文件起点），该页即可由 houdini 翻译。
`0x17c000`（LOAD2 页首填充）虽然可读，但 houdini 不按代码翻译，会 SIGSEGV。

### 4.3 环形区

`base+0x3f0000`（lib BSS 保留区内的匿名 rw，实测可写）：

```
G+0x00                 u64 counter
G+0x40+i*0x1040        slot i（i = counter & 7）
  +0x00 LR   +0x08 x0  +0x10 ptr1  +0x18 len1
  +0x20 ptr2 +0x28 len2/keyid  +0x30 key16
  +0x40 data1[0x400]  +0x440 data2[0x400]  +0x840 data3[0x800]
```

`data3` 是 site E 用 crypto table 的 `+0x90`（取字节长）/`+0x98`（大端导出）
对 key context 8 个 bignum 字段的导出结果；实测 `field0=e=010001`、
`field2=n`（与 DER 一致），其余为 0（公钥 context）。

### 4.4 命令

```bash
python goxbud/tools/fingerprint/fpcap.py build      # 汇编 + 打补丁
python goxbud/tools/fingerprint/fpcap.py emutest    # Unicorn 四个 site 单测
python goxbud/tools/fingerprint/fpcap.py install    # 替换设备 so + 重启 app
python goxbud/tools/fingerprint/fpcap.py poll --probe --method getFingerPrint
python goxbud/tools/fingerprint/fpcap.py parse
python goxbud/tools/fingerprint/fpcap.py restore    # 还原原件
```

`poll` 每 0.5s 读一次 counter，变化即快照整个 ring（进程若崩溃也能保住最后一份）。
`install` 先备份 `nsk_orig.so`、校验 sha、启 `np_loop`；`restore` 还原并停掉。

---

## 5. 生成器 `fp.py --gen`

```bash
# 用捕获的画像 + key 复现（rdata 逐字节一致）
python goxbud/tools/fingerprint/fp.py --gen \
  --profile goxbud/tools/fingerprint/_devdata/_prof.json \
  --key 2d59364b4c7b4f222b45372b2f74467c

# 任意画像；不传 --key 则随机 16 个可打印字符
python goxbud/tools/fingerprint/fp.py --gen \
  --profile '{"a":"...","b":"15","c":"SM-A5260","d":"x86_64","g":"2.6.6","i":"Samsung"}' \
  --out aimt.json

# 解已有样本
python goxbud/tools/fingerprint/fp.py sample.json --decrypt --key <16B|32hex>

# 从打桩结果导出公钥
python goxbud/tools/fingerprint/fp.py --extract-pubkey fpcap_slots.json
```

生成结果：

```json
{"datatype":"aimt_datas","id_ver":"Android_1.0.1","rdata":"<b64>","rk":"<b64>"}
```

自校验：`aes_ecb_decrypt(key, rdata) == 明文`；`pow(EM, e, n) == rk`。

---

## 6. App 实际使用面（2026-11-16 复核）

- App 的 smali 里只有 `fk0.smali` 引用 `SecruityInfo`，且只调
  `encryptStringToServer` / `decryptStringFromServer`；三个 getter
  （`getFingerPrint` / `getMobileData` / `getCheckValue`）没有任何 Java 调用点。
- `libnetseckit` 内也没有 `getFingerPrint` / `getMobileData` / `getCheckValue`
  字符串，不会通过 JNI 回调 Java getter。
- 结论：`aimt_datas` **不在 App 的正常流量里**，是 NetSecKit 的对外 API。
  App 的风控/加密输入走 `encryptStringToServer` 的 header 明文，不是这三个 getter。
- 因此“找消费端 / 服务端接受度”对当前 App 不成立：没有消费端。生成的 blob
  只能用于研究或其他接入方，不会影响小步点业务请求的评分。

---

## 7. getMobileData / getCheckValue（研究性）

- `getMobileData`：`+0x70` 后走 `+0xa8`（0x927e8）→ `+0x130`（0xd5920，
  ~4KB 栈、大量设备字段）→ `+0xe8`（0x91298）。在 MuMu 上 `+0x130` 不返回。
- `getCheckValue`：`+0x70` 后走 `+0x78`（0x90d74，检查 `iVar2==4` + 时间戳）→
  `+0xb0`（0x9873c）。试了两种 `+0x78` 旁路：
  1. `fpcap.py build --bypass78`：把 0x90d74 入口改成 `mov w0,#1; ret`；
  2. soft patch：只把 0x90e5c 的 `cset w0, ge` 改成 `mov w0,#1`，保留函数体/free。
  两种都能越过 `+0x78`，但都在到达 `0x9b854` AES 桩前挂住（ring counter=0）。
- 动态观测（原版 libnetseckit）：`FP.CALL` 后约 1s，`:channel` / `:pushservice`
  的 `nsk-fp` 线程处于 `nanosleep`（x86_64 syscall 35），栈上是 10ms 轮询
  （`0x989680` ns），不含 libnetseckit 帧；主进程的 `nsk-fp` 线程采样到
  `wait4`（等子进程）和 `futex_wait`，但栈页被 ART/`libunwindstack` 污染，
  没拿到干净的 libnetseckit 调用链。`libnetseckit` 本身不 import
  `nanosleep/usleep`，也没有 raw nanosleep 的 `svc`，sleep/wait 更可能来自
  loader/JVM/依赖库或 native `system()` 子进程。
- 广播会送到所有 app 进程；`:channel` / `:pushservice` 没有 libnetseckit，
  仍会在 loader/初始化里轮询，进一步放大 watchdog 风险。
- `Bugly-ThreadMon` 在 `FP.CALL` 后约 **5.5s** 通过 `libBugly_Native+0x94a8`
  的 PLT `kill` 杀进程。试过：对 `Bugly-ThreadMon` 的 tid 发 SIGSTOP——
  SIGSTOP 是线程组级停止，整进程所有线程都变 T，无法只冻 watchdog；把 `kill`
  调用 nop 掉，app 启动即 abort（watchdog 循环/完整性检查）。两条路都不可用。
- 要真正闭合这两个 getter，需要真机或系统级调试器（simpleperf/debuggerd）来
  unwind `nsk-fp` 的 `nanosleep` 调用栈，并补完 `0xd5920` 的静态反编译
  （Ghidra 对它的 decompile 只返回函数头）。`0x9b854` 的桩已保留，真机/patch 后可直接用。
- 两个方法 App 都不调用；继续追只对“完整复刻 SDK”有意义。

---

## 8. 结论与边界

- `getFingerPrint` 已完全闭合：设备画像 -> 同构 `aimt_datas`，PC 侧可生成。
- `rk` 不需要私钥；用静态 DER 公钥做 RSA-1024 PKCS#1 v1.5 即可。
- 该 blob 是本 App 未使用的 SDK API，不进入业务请求/评分链；不要把它当成
  当前纯协议提交的风险面对齐项。
- `getMobileData` / `getCheckValue` 未闭合，且 App 不调用，属研究项。
- 业务信封（`save_record` / `get_record_list` / `get_fixed_point`）不需要这三个值。

---

## 8. 工具与文件

| 文件 | 作用 |
|---|---|
| `goxbud/tools/fingerprint/fpcap.py` | build/emutest/install/poll/parse/restore |
| `goxbud/tools/fingerprint/_keycap/fpcap.s` | 五站点捕获桩 |
| `goxbud/tools/fingerprint/fp.py` | 解析 / 解密 / 生成器 |
| `goxbud/tools/fingerprint/_devdata/fpcap_slots.json` | 最近一次捕获（key/明文/公钥/EM） |
| `deploy/module/src/com/harness/nsk/HookMain.java` | `FINGERPRINT` 广播、`fp=` cfg |
| `analysis/fingerprint/` | Ghidra 工程与反编译日志 |
