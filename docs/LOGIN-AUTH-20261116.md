# 登录认证闭合：易盾验证码 + 双信封登录（2026-11-16）

> 目标：把「登录认证 未闭合 / 易盾验证码 / token 续期」这一环从“需要手机”变成“服务器可执行”。
> 结论：**登录请求与响应已全离线；易盾验证码用官方 H5 + 无头 Chrome 求解，不逆向验证码协议。**
> 工具：`goxbud/tools/loginauth/`（`captcha.py` + `client.py` + `crypto.py`）。

---

## 1. 一句话结论

登录只差“人”和“浏览器”两个东西，不再差密钥：

- 登录 body 是标准双信封：`AES-128-CBC(K0)` + `h=md5(P)`，`k=RSA-1024-PKCS1v15(N_REAL,K0)`；
- `neteaseValidate` 用 app 同款官方 H5 在无头 Chrome 里跑 `captchaVerify()` 拿到；
- 登录响应用 **header 信封的 keyData** 解，不是 body 的；
- 密码错误 `10116`、验证码错误 `10104`、成功 `10000` + token；
- token 失效后重新登录即可，不需要单独逆向 refresh 端点。

---

## 2. 登录端点与请求

| 项 | 值 |
|---|---|
| 端点 | `POST https://campusapi.xbud.run/v4/user/login/login` |
| 凭据 | `Authorization: Basic base64(username:password)` |
| body | `{"loginType":1,"device_model":"...","os_version":"15","mac_address":"...","imei":"","uuid":"<uuid>","neteaseValidate":"<validate>"}` |
| header 信封 | `uid="-1"`、`token=""`、`tokenSign=""`（未登录态） |
| appVersion | `2.6.6` |
| UA | `Dalvik/2.1.0 (Linux; U; Android 15; SM-A5260 Build/d8107ca.0)` |

body 与 header 都走同一套：

```
P = {"data": b64(inner)+"\n", keyDataOne..Four, platform:2, timeStamp:sec}
d = AES-128-CBC(K0, IV=0, PKCS7(P)),  K0 = bionic rand(sec) 取 94 字母表 16 字符
h = md5(P)
k = RSA-1024 PKCS#1 v1.5(N_REAL, e=65537, K0)
```

关键实测：

| 实验 | 结果 |
|---|---|
| `k` = 随机 128B | HTTP 200 + **0B**（信封被拒） |
| `k` = RSA(N_REAL, K0) | HTTP 200 + 389B（受理） |
| header `uid=-1` / `token=""` / `tokenSign=""` | 受理；无需先有会话 |
| 登录 body `P.timeStamp` 与 K0 同秒 | 离线可解，5/5 抓包验证 |

> 登录 `k` 不能用随机 128B，这一点与业务信封一致；早期“登录 k 可忽略”的推断作废。
> `.so` 内嵌 SPKI 仍是诱饵，登录也用 N_REAL。

---

## 3. 响应解密：用 header keyData

这是本轮最容易踩坑的一点：

- 业务请求的响应 = `F(body P.keyData)`；
- **登录请求的响应 = `F(header P.keyData)`**。

用 body keyData 解会得到 `None`；用 header keyData 解出：

```json
{"error":10116,"message":"密码错误，请重新输入密码","data":null}
```

错误码：

| error | 含义 |
|---|---|
| 10104 | 验证码错误或已过期 |
| 10116 | 密码错误 |
| 10000 | 成功；token 在 `data`（`UserInfoTable.token`） |

---

## 4. 易盾验证码：不逆向协议，跑官方 H5

app 使用 `com.netease.nis.captcha` 3.4.3，`MODE_INTELLIGENT_NO_SENSE`，最终加载：

```
https://cstaticdun.126.net/api/v2/mobile.v2.13.5.html
  ?captchaId=2451e9031eb340078cdb51c0e3691570
  &mode=bind&os=android&osVer=15&sdkVer=3.4.3
  &defaultFallback=true&errorFallbackCount=3&mobileTimeout=10000
```

页面通过 `window.JSInterface` 回调：

```
onReady(w,h) -> 调 window.captchaVerify()
onValidate(result, validate, msg, next)
```

`solve()` 注入同款 `JSInterface`，让页面自己的 JS 完成智能无感/挑战，捕获
`validate`（`CN31_...`，约 500~600 字符）。headless Chrome + Android UA + 移动视口，
通常第 1~2 次通过；内部重试 4 次。

实测：

| validate | 登录结果 |
|---|---|
| 有效 | `10116`（密码错误，说明验证码已过） |
| 空 | `10104` |
| `CN31_invalid...` | `10104` |
| 同一个有效 validate 连续 5 次 | 5 次都到密码校验（短窗口内可复用） |

> 这条路径把“易盾验证码”从硬阻塞降为“登录时起一个无头浏览器”。业务链不需要浏览器。

---

## 5. Token 续期

| 项 | 现状 |
|---|---|
| 登录成功 | 响应 `data` 里的 `token`（36 位 UUID），写入 MMKV `sp_request_token` |
| 401 | `{"status":401,"message":"token有误或缓存中token不存在",...}` |
| `GET /v4/user/account/get_info` | 冷启动会调用；**有效 token 实测返回 `error=10000`，响应里没有新 token** → 不是续期端点 |
| 独立 refresh 端点 | dex 里未发现 `/refresh_token` |
| 可靠路径 | **401 → 用 `loginauth.client login` 重新登录**（验证码 + 密码）；`get-info`/`refresh` 只用于观察 |

token TTL 尚未测：拿一次成功登录的 token，定时 health，记录首次 401 的时间。
如果 TTL 很长，日常只需要登录一次；如果很短，就用本工具自动重登。

### 5.1 登出

dex 里有 `/v1/user/login/logout`（`classes2.dex`）。实测：

```text
POST /v1/user/login/logout
header：与业务请求相同的 uid/token 双信封
body：{}
→ {"error":10000,"message":"成功","data":null}
```

调用成功后，同一 token 再 `get_info` 返回 **401**，说明厂商侧会话已释放。
服务器要换手机登录时，顺序应为：

1. `enabled=false`，停调度；
2. 用当前 token 调 logout（token 已从 sidecar 清掉的话，从备份 import 再调）；
3. sidecar `/invalidate` 清本地 token；
4. 验证 `data/tokens.json` 为空、`/submit` 对停用账号返回 409。

**只 drop 本地 token 不会吊销服务端会话**，手机再登录仍可能 `10121`；
必须调 logout（或等 token 自然过期）。

---

## 6. 工具用法

```bash
cd goxbud/tools

# 错误密码冒烟：10116 = 信封 + 验证码都通过
python -m loginauth.client probe --user 19551338692

# 正式登录
python -m loginauth.client login --user 19551338692 --password '******' \
    --token-out /tmp/xbud_token

# 只求验证码
python -m loginauth.captcha

# 用现有 token 观察 get_info 是否续期
python -m loginauth.client get-info --token <36位UUID> --uid 894824
python -m loginauth.client refresh  --token <36位UUID> --uid 894824
```

依赖：`requests`、`pycryptodome`、`playwright`（+ Chrome/Chromium）。

---

## 7. 验证记录

| # | 实验 | 结果 |
|---|---|---|
| 1 | 5 份登录 body 信封用 `K0FromSeed(P.timeStamp)` 解 | 5/5，`md5(P)=h` |
| 2 | 随机 k 登录 | 200+0B |
| 3 | RSA(N_REAL,K0) k 登录 | 200+389B |
| 4 | 有效 validate + 错误密码 | 10116 |
| 5 | 空/invalid validate | 10104 |
| 6 | 同一 validate 复用 5 次 | 5/5 到密码校验 |
| 7 | 登录响应 header keyData 解密 | 10116 明文 |
| 8 | 业务信封 + 假 token 对照 | 401“token有误”（信封构造正确） |
| 9 | 过期 token `get_info` | 401“token有误或缓存中token不存在” |
| 10 | 有效 token `get_info` | `error=10000`，响应无新 token（不是续期端点） |
| 11 | 正确密码登录（测试账号 19551338692） | `error=10000`、`message=成功`、`data` 为 dict、`data.token` 36 位、`_find_token` 命中 uid=894824 |
| 12 | sidecar `/token` 正确密码 | `cached=false`、token 36 位；`GET /token` 返回 `cached=true` |
| 13 | sidecar token → `xbudsrv probe` | `envelope=OK token=OK records=1`，`--points` 也 OK |
| 14 | 新登录后旧 token `get_info` | 401（新登录使旧 token 失效；服务端单会话/轮换信号） |
| 15 | 不同设备画像登录同一账号 | `10121 登录失败，此帐号已在其它设备登录`；换回绑定画像（V2417A~894824）后 `10000` |
| 16 | sidecar 接收 daemon 传入的绑定画像 | 登录/提交同画像，`probe`/`points` 正常 |

---

## 8. 边界

- 正确密码登录已实测跑通（`10000` + `data.token` + `_find_token` + sidecar 缓存 + `probe` 直用）。
- **设备画像必须与账号绑定画像一致**：不同画像登录会 `10121`；daemon 已把 `IdentityFor`
  的绑定画像随 `/token` 传给 sidecar。不要用 `DefaultIdentity` 登录已有画像的账号。
- token TTL 未长测；已观察到新登录后旧 token 401、不同设备登录 `10121`；
  401 后重新登录是必需路径。sidecar 现在会在 401 强制重登时记录旧 token 的
  `max_reject_age`（观测 TTL 下界），`/stats` 可查；仍不是精确 TTL。
- 验证码 validate 的复用窗口未测；正式调用建议每次新求。
- 无头浏览器是登录期依赖；业务提交仍是纯协议。
- app 升级后 captchaId / H5 版本 / 登录字段可能漂移，需重新对一次。
