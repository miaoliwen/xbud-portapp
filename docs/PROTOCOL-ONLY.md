# 脱离 App 纯协议上报 · 研发路线（PROTOCOL-ONLY）

> **2026-11-14 更新**：本文的 SM4 模型已被 `K-TRUTH-20261114.md` 的
> AES-128-CBC + md5(P) + k-oracle 取代；响应解密、save_record 写路径与 401 处理
> 见 K-TRUTH §13–15。本文保留为早期路线记录，不再作为实现依据。
>
> **2026-11-16 更新**：登录认证已闭合（`LOGIN-AUTH-20261116.md`）。易盾验证码不逆向协议，
> 用 app 同款官方 H5 + 无头 Chrome 注入 `JSInterface` 求解；登录双信封、RSA k、
> 响应 header keyData 均已实测。token 401 后重新登录即可，`goxbud/tools/loginauth/` 可直接调用。
>
> 目标终态：PC 侧自研 CLI（`xbud_proto`）完成「token → 信封 → save_record → 轨迹直传 COS」，
> App/LSPosed 模块只在换 token 时介入一次。
> 现状：结构全闭合（SM4-CBC + RSA-1024 + tokenSign + STS 明文 + 数据形态），
> **单点阻塞 = SM4 KEY/IV 不可得**（out_so/REPORT.md：非 .so 明文常量，运行时派生/注入）。

---

## 1. 为什么"押在 key 上"：依赖坍缩链

| 缺件 | 拿到 K 之后怎么闭合 |
|---|---|
| **prologue（32B）** | 直接离线解 `d`（SM4-CBC，IV 从同会话响应 `r` 前缀取；REPORT 实测 IV 每会话固定、双向同源）→ 明文原样到手，prologue 内容与结构一眼可见 |
| **`h` 算法** | 有 `(K, pt, h)` 三元组后，对候选构造穷举（hmac_md5(K,d)、md5(hex(k)+pt)、SM4-ECB(K,pt) 的 md5…），匹配即选中；每分钟数千组 |
| **RSA 公钥是否现役** | 自造信封 + 现成判据：`200+0B`=未过，**`401 明文`=信封全过**（token 失效只是掩护）。公钥错会永远 200+0B，与 h/IV 错同信号——有 K 后可用真样本交叉排除 |
| **响应解密** | `r = IV‖SM4(K,·)`，K 同源即可解，无第二把钥匙 |
| **请求 IV 来源** | 同会话响应已给出 IV 值；请求侧只需确认"同值"或"每请求派生"（用 K 解两条 d 对比即可判定） |

**结论：M0（拿 K）之后，M1–M3 都是"天级"工作；拿不到 K，全项目无法脱离。**

## 2. 关键路径：K 恢复的两条夹击线

### 线 A（零风险、纯离线，先跑）
**内存转储 × 已知明密对 × 响应 IV 的离线穷举。**

- 已有资产：`analysis/workdirs/_mem/nesec_seg*.bin`（模块 DumpTask 导出的 libnetseckit rw 段/堆，
  `HookMain.DumpTask` 走 `/proc/self/mem`，不触发壳的外部内存读取检测）；
  `analysis/workdirs/envelopes/`（app 自己生成的 `*.cipher.json` ↔ `*.body.json` 明密对，服务端一定收）。
- 方法：对每个转储的每个 16B 窗口当 K，取同会话响应里的 IV，SM4-CBC 加密已知 pt，
  与样本 `d` 逐字节比对 → 命中即 K。
- 成功条件：K 在转储时刻**连续存在**于 rw 段。失败也不损失（纯离线）。
- 增强：在 `SecruityInfo.encryptStringToServer` 触发瞬间抓一次转储（模块已有 DumpTask 能力，
  改成"加密回调触发抓取"即可，无需新逆向）。

### 线 B（确定性、需插桩，A 不中即上）
**进程内 hook `libnetseckit` 的关键点，直接取寄存器/栈上的材料。**

- 工具：Florida 17.9.1（已打完 apex 路径补丁）+ nespatch 五点护航；TECHDOC §2 实测
  **attach 模式注入长期稳定**（dexdump 10min+），native Interceptor 打内部偏移可行。
- 目标点（REPORT 已定位）：
  - `libnetseckit+0x95bcc`：`mov x1, x26 ; bl 0x39030`，**x1 = 16B SM4 key 指针**；
  - `libnetseckit+0x95378`：prologue 拼接缓冲（32B `x27`）；
  - `+0x3301c / +0x3334c`：SM4 CBC 加/解密跳板（可拿 IV）。
- 产物：每次加密请求的十元组 `(pt, prologue, K, IV, d, h, k, p, t)` → 落 `samples/` 作回归库。

### 判定优先级
1. A 跑 1-2 天（离线，零成本）；
2. A 不中 → B 一次插桩会话即可拿齐十元组；
3. B 拿到 K 后回头验证 A 的失败原因（K 是否每请求重生），决定产品形态。

## 3. 决定产品形态的未知：K 的生命周期

拿到 K 后必须测绘（决定"一次性脱离"还是"设备低频伴跑"）：

| 实验 | 判读 |
|---|---|
| 同一 app 进程内两次请求 | K 相同 → 每会话固定 |
| force-stop 重启 app 后再加密 | K 相同 → 每安装固定（**最优：一次提取永久用**） |
| 清数据/重装后再测 | K 相同 → 常量/KDF 固定；不同 → 设备或首启下发绑定 |
| app 升级（2.6.6→2.6.8）后 | 观察 K 与 `p`/`t` 是否漂移（当前 2.6.8 已见 `p=101,t=0`） |

- 「每安装固定」→ 纯 PC CLI 可长期独立跑，设备只在换装/升级时介入。
- 「每会话派生」→ 产品形态 = **PC CLI + 一次性会话头（设备或模块注入）**：每次启动从设备取一次
  `(K, IV)`，业务链仍全在 PC（模块退化为"密钥代理"，不再承担提交）。

## 4. token 供给（不阻塞、方案明确）

- 短期：`diag=1` 跑一次 app，ENC1 日志含加密前明文 header（**token 明文在其中**），收割一次；
  token 每次登录轮换，低频登录下可长期使用。
- 中期（可选）：协议登录 = 登录请求亦为信封 + 密码加密 + 设备注册——信封闭合后才值得做；
  在那之前不投入。
- 换 token 判定：任何 401 明文响应 = token 失效（信封已过的信号，语义干净）。

## 5. 工程分层与里程碑

```
xbud_proto/                 # 新目录，与现有 xbudtool 解耦
  envelope.py               # 收编 out_so/envelope.py（SM4/RSA 已实现，补 K/IV/prologue/h 插槽）
  sign.py                   # tokenSign（SALT4 已解）+ headerSign
  client.py                 # 信封请求/响应、错误码语义（复用 FINDINGS 全表）
  cos.py                    # STS 明文 GET + q-sign（复用 cos_client.py）
  cli.py                    # records / submit / track 三个子命令
  samples/                  # 十元组样本库（回归用）
  oracle.py                 # 200+0B vs 401 判据（复用 analysis/probes/_forge_probe.py）
```

| 里程碑 | 内容 | 验收 | 估量 |
|---|---|---|---|
| **M0** | K 恢复（线 A→B） | 离线用 K 成功解出 app 抓到的 `d`，prologue 明文可见 | 2-5 天 |
| **M1** | `h` 闭合 + RSA 验证 | 自造信封收到 **401 明文**（非 200+0B） | 1-2 天 |
| **M2** | 只读接口打通 | PC 直发 `get_record_list` 拿到解密后的真实记录（零模块） | 1 天 |
| **M3** | 写接口打通 | 自造 `save_record` 入库，`4K.CONFIRM` 等价判据（list 里出现 uuid） | 1-2 天 |
| **M4** | 轨迹与稳定化 | PC 直传 COS + 记录同源自洽；K 生命周期测绘结论落档；CLI 可用 | 2-3 天 |

> 总量：**1-2 周**（不含意外）。卡点集中在 M0 的线 A 命中率；线 B 是确定性兜底。

## 6. 降级方案（若 K 确认为每会话派生且插桩不可持续）

保留现有模块，但职责收敛为「密钥代理 oracle」：
PC CLI 发起提交前，经模块通道取一次 `(K, IV)`（或直接让模块代算信封，PC 只发 HTTP），
业务参数、轨迹、记账、闸门全部留在 PC。**业务链已脱离软件，只剩密钥获取这一点设备依赖。**
这其实就是当前架构的再切分，工程量小于 M0 失败后的任何重方案。

## 7. 边界

全部研发只对自有账号做协议一致性验证（401/200 判据不触碰他人数据）；
不实现登录协议之前不碰他人凭据。风控面（画像/UA/行为闸门）已具备，纯协议侧直接复用。
