# DeepSeek 账号登录

应用内「设置 → 账号」支持用 DeepSeek 开放平台账号登录，登录后对话直接花账号余额，
不必自己创建 API Key。本文记录这条链路是怎么走的、失败时看哪里。

## 协议从哪来

DeepSeek Harness 是开源的（`deepseek-ai/deepseek-harness`，MIT）。它的桌面客户端
`packages/account/deepseek-account` 就是「浏览器授权 + 回环回调」这一套。
本项目按同一套端点实现，没有逆向私有接口。

> 这不是公开文档的一部分。平台若收紧客户端校验，登录会明确失败并给出
> `code / biz_code`，API Key 方式不受影响。

## 三步

```text
0. 先在本机起一个回调服务，拿到实际端口（53682 起，被占就顺延）
   → redirect_uri = http://127.0.0.1:<port>/oauth/callback

1. POST https://platform.deepseek.com/auth-api/v0/dsh/auth_init
   body: {code_challenge, code_challenge_method:"S256", state,
          redirect_uri:"http://127.0.0.1:<port>/oauth/callback",
          locale:"zh_CN", login_source:"web"}
   resp: {authorize_url, authorize_id, expires_in}

2. 用系统浏览器打开 authorize_url，用户登录并授权
   平台 302 到 redirect_uri?code=…&state=… → 本机服务接住（不监听 0.0.0.0）

3. POST https://platform.deepseek.com/auth-api/v0/dsh/auth_exchange
   body: {code, code_verifier, redirect_uri, device_id, device_model, os_version}
   resp: {token, authorized_url, user?}
```

`token` 加密存进 `SecureKeyStore`（scope `deepseekAccount`），之后：

| 用途 | 请求 |
|---|---|
| 对话 | `POST https://api.deepseek.com/v1/chat/completions`，头 `x-dsh-auth-token: <token>` |
| 资料 | `GET /auth-api/v0/users/current` |
| 余额 | `GET /api/v0/users/get_user_summary` |
| 登出 | `POST /auth-api/v0/users/logout` |

**不是 `Authorization: Bearer`。** 账号 token 与 API Key 是两种凭据，发错头会被当成无效 Key。

## 必须带浏览器 UA（这是 429 的根因）

`platform.deepseek.com` 前面有 WAF。同一 IP、同一秒、只改 UA：

| UA | `auth_init` | `authorize_url` |
|---|---|---|
| `okhttp/4.12.0`（OkHttp 默认） | **429** + 一张 HTML 错误页 | **429** |
| 移动版 Chrome | 200 | 200 |

所以协议层固定发 `DeepSeekAccountApi.BROWSER_UA`。别把它当成「可有可无的美化」删掉 ——
删了就会出现「登录没有完成 / 平台返回 HTTP 429」，而且正文全是 HTML，看上去像平台挂了。

## 五个客户端头

`auth_init` / `auth_exchange` 都要求：

| 头 | 值 |
|---|---|
| `x-client-bundle-id` | 空串（官方桌面端也是空） |
| `x-client-platform` | `web` |
| `x-client-version` | 本 App 的 `versionName` |
| `x-client-locale` | `zh_CN` / `en_US` |
| `x-client-timezone-offset` | 本地时区偏移秒数（UTC+8 = `28800`） |

少一个，平台会把它当成未知客户端，`auth_init` 不给授权页地址。

## 回包结构

```json
{"code":0,"data":{"biz_code":0,"biz_data":{...}}}
```

- `code != 0` 或 `biz_code != 0`：业务拒绝。文案在服务端，客户端只能把数字带给用户。
- HTTP 401 或 `code == 40003`：token 失效，本地必须清掉，否则界面会一直显示「已登录」。
- 其它非 2xx / 非 JSON：按网络或协议错误处理，**不清 token**（可能只是这次网络抖了）。

## 为什么是系统浏览器（而不是只用 WebView）

参考实现是 dsh-Go（`ccch1mneyyy/dsh-Go`）：它先用 **Chrome Custom Tab**，再用默认浏览器兜底，
授权页从不进 WebView。原因有三条：

1. 平台页面在真浏览器里才有完整的登录态、验证码与密码管理器；
2. WebView 的 UA 带 `; wv`，部分风控会把它降级甚至直接拒；
3. 手机号 + 验证码在小屏 WebView 里几乎没法输入。

所以这里优先 `Intent.ACTION_VIEW` 打开系统浏览器，同时用 `LoopbackCallbackServer`
在 `127.0.0.1:<port>` 收回调。**只绑回环地址**，不绑 `0.0.0.0` —— 否则同一 Wi-Fi 下
谁都能往这个端口塞 code（虽然 `state` 还会再校验一次）。

没有浏览器的设备（手表）自动回退到 WebView：这时回调不监听端口，
由 `shouldOverrideUrlLoading` 直接拦下。两条路共用同一套 state 校验与换 token 逻辑。

## 失败排查

| 现象 | 大概率原因 |
|---|---|
| 「平台拒绝了这次请求（biz_code=…）」 | 客户端标识（版本 / platform）不被接受，或该账号被限制 |
| 「网络不通」 | 请求根本没发出去；看诊断页的日志 |
| 「平台暂时限制了登录请求（429）」 | WAF 按客户端特征拦。先确认 UA 是浏览器 UA；仍然 429 就等一分钟 |
| 浏览器回到应用但没反应 | 回调没到本机端口：看是否手动改过 `redirect_uri`，或被别的进程占了 53682 |
| 登录页白屏（WebView 兜底） | WebView 被 ROM 阉割；用手机版登录 |
| 登录成功但对话 401 | token 已失效（长时间未用 / 平台侧撤销），重新登录 |
| 余额显示「—」 | `get_user_summary` 失败，不影响聊天；点刷新重试 |

## 与 API Key 的关系

两条路并存：

- **账号登录**：令牌由平台授权，费用走账号余额；退出登录即可撤销本地凭据。
- **API Key**：自己建 Key 填进供应商，费用走同一份余额，但适合多设备、可精确吊销。

供应商页里的「DeepSeek（账号）」只是把第一条路包装成一个模型供应商：
地址固定官方 API、类型锁定，删除它不会退出登录。
