# JDCKApp 京东CK助手

一个零依赖（无 Gradle/AGP/第三方库）的安卓小工具：内嵌京东移动登录页，登录后自动提取 Cookie 中的 `pt_key` / `pt_pin`，支持一键推送到青龙面板环境变量 `JD_COOKIE`（按 `pt_pin` 智能合并：同账号覆盖、其它账号保留、无变量则新建）。

## 功能

- **登录**：内嵌京东官方移动登录页（扫码 / 短信 / 密码）
- **自动提取**：页面加载后自动检测 `pt_key`/`pt_pin`，状态栏显示账号
- **复制CK**：一键复制 `pt_key=...;pt_pin=...;` 格式
- **推送青龙**：青龙 OpenAPI 合并写入 `JD_COOKIE`，多账号互不覆盖
- **配套服务端脚本**：`dingtalk_ck_bot.py`（可选）——钉钉机器人接收 CK 消息并合并更新青龙，Stream 模式常驻运行

## 构建

### 本地（Windows，无需 Gradle）

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

要求：JDK 17、Android SDK（build-tools 34.0.0 + platform android-34）、Python 3。

### CI（GitHub Actions）

推送到 `main` 或手动触发 workflow 即自动构建；构建产物在 Actions 的 Artifacts 中下载（`JDCK-apk`）。打 `v*` 标签推送时会自动创建 Release 并附带 APK。

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## 服务端：钉钉机器人接收 CK（可选）

这套链路解决的是：**不想打开 App，在钉钉里发一条消息就能更新青龙里的 Cookie**。

```
钉钉群里 @机器人 / 私聊发 CK
        ↓ Stream 长连接（不需要公网 IP、域名、SSL 证书）
dingtalk_ck_bot.py（常驻 Python 进程，解析 pt_pin）
        ↓ 青龙 OpenAPI，按 pt_pin 智能合并
JD_COOKIE 环境变量（同号覆盖、异号保留、无变量则新建）
        ↓
机器人回复「✅ 已更新 <pt_pin>」
```

### 前置条件

| 条件 | 说明 |
|---|---|
| 钉钉权限 | 组织管理员或开发者（个人可免费创建自己的团队，创建后即为管理员） |
| 运行环境 | 一台能常驻跑 Python 3 的机器，与青龙同机/同容器最省事 |
| 青龙版本 | 2.24+ 认证为 `GET /open/auth/token`；旧版是 `POST /open/auth`，需自行改脚本 |
| 依赖 | `pip install dingtalk-stream requests` |

### 一、创建钉钉应用与机器人

1. 打开 [钉钉开放平台](https://open.dingtalk.com) → 「应用开发」→「创建应用」（企业内部应用），填名称、图标
2. 「应用能力」→ 添加「**机器人**」，填机器人名称/简介，上传必填的预览图
3. 「消息接收模式」选 **Stream 模式**
   - ⚠️ 不要选 HTTP 模式：它会要求填公网可访问的 HTTPS 回调地址并做可达性校验，家用/内网服务器过不了
   - 全流程都不要碰 HTTP 选项，否则后台可能残留旧地址导致发布失败
4. 「机器人接收消息类型」勾选**文本消息**
5. 「版本管理与发布」→ 创建新版本 → 选可见范围（自己或全员）→ **发布**（未发布的应用，机器人收不到消息）
6. 「事件与回调」→「事件订阅」→ 推送方式选「Stream 模式推送」。脚本跑起来后，这里「已连接 Stream」列表应出现一条连接，订阅类型含「机器人事件回调」

### 二、获取两组凭据

| 凭据 | 位置 |
|---|---|
| `AppKey` / `AppSecret` | 钉钉应用 →「凭证与基础信息」 |
| `Client ID` / `Client Secret` | 青龙 →「系统设置」→「应用设置」→ 新建应用，权限勾 **环境变量**（要建看门狗再加「定时任务」） |

> 钉钉的是「企业内部应用」，不是群里的「自定义机器人 / webhook」。后者只能往群里发通知，**收不到任何消息**。两者别混。

### 三、部署脚本

把仓库里的 `dingtalk_ck_bot.py`、`create_cron.py` 放到服务器，例如 `/ql/data/bot/`：

```bash
mkdir -p /ql/data/bot && cd /ql/data/bot
pip install dingtalk-stream requests

cat > config.json <<'EOF'
{
  "ql_url": "http://127.0.0.1:5700",
  "ql_client_id": "<青龙 Client ID>",
  "ql_client_secret": "<青龙 Client Secret>",
  "dt_app_key": "<钉钉 AppKey>",
  "dt_app_secret": "<钉钉 AppSecret>"
}
EOF

nohup python3 dingtalk_ck_bot.py >> bot.log 2>&1 &
tail -f bot.log
```

- `ql_url` 是青龙的访问地址；脚本与青龙在同一容器时填 `http://127.0.0.1:5700`
- 也可以用环境变量代替 `config.json`（环境变量优先）：`DT_APP_KEY`、`DT_APP_SECRET`、`QL_URL`、`QL_CLIENT_ID`、`QL_CLIENT_SECRET`
- **`config.json` 千万不要提交进 Git**（本仓库 `.gitignore` 已排除它）
- 日志出现 `钉钉 Stream 机器人已启动` + `endpoint is {...}` 即表示长连接建立成功

**常驻与自愈**：执行 `python3 create_cron.py` 会在青龙里创建一条每 5 分钟的看门狗任务（进程不在就自动拉起）；也可以用 systemd / supervisor 托管容器外的部署方式。

### 四、把机器人加进钉钉

- 群聊：群设置 → 机器人 → 添加机器人 → 搜索你的应用名 → 添加，发送时 **@机器人**
- 私聊：钉钉顶部搜索机器人名 → 进入会话，直接发消息即可（不用 @）

### 五、消息格式与行为

| 发送内容 | 结果 |
|---|---|
| `pt_key=xxx;pt_pin=xxx;` | 合并写入 `JD_COOKIE`，回复 `✅ 已更新 <pt_pin>` |
| 多账号：`pt_key=A;pt_pin=a;&pt_key=B;pt_pin=b;`（或换行分隔） | 逐条合并，互不影响 |
| 其它任意文本（如 `test`） | 回复格式提示，可用于验证链路是否通 |

合并规则：按 `pt_pin` 匹配，同账号覆盖、其它账号保留；青龙里没有 `JD_COOKIE` 时自动新建。

### 六、常见问题排查

| 现象 | 原因 / 处理 |
|---|---|
| 发布时提示「消息接收地址校验失败」 | 之前选过 HTTP 并填过地址，后台有残留。切到 HTTP → 清空地址 → 切回 Stream → **保存** → F5 刷新确认 → 重新发布；还不行就新建一个应用，全程只选 Stream |
| 发消息完全没反应，日志里只有 `ping` 心跳 | 常见三种：发给了 webhook 自定义机器人（只发不收）、应用未发布或可见范围不含自己、群里没 @。逐项核对 |
| 连接列表显示在线，但收不到消息 | 确认机器人配置的接收模式**保存后**仍是 Stream；确认「事件订阅」的订阅类型包含「机器人事件回调」 |
| 青龙认证返回 401 | 青龙 2.24+ 用 `GET /open/auth/token?client_id=...&client_secret=...`，不是旧版 `POST /open/auth` |
| 消息收到了但环境变量没变 | 检查青龙应用的权限是否包含「环境变量」，并查看 `bot.log` 的报错 |
| 容器重启后机器人不工作 | 配置看门狗任务或 systemd，确保进程被自动拉起 |
| 机器人回复报错 | 确认 `dingtalk-stream` SDK 版本；本脚本按 `ChatbotMessage.from_dict()` / `reply_text()` / `(STATUS_OK, 'OK')` 这套 API 编写 |

### 安全建议

- `AppSecret`、青龙 Client Secret、Cookie 都等同账号凭证，不要进 Git、不要发到公开群
- 部署完成后可在钉钉后台重置一次 AppSecret，再同步更新 `config.json`
- 生产使用建议在 `process()` 里按 `sender_staff_id` 加一层发送者白名单，避免组织内其他人误发/滥用

## 注意

- 仓库中的 `debug.keystore` 仅用于本地调试签名（密码 `android`），正式发布请使用自己的密钥
- Cookie 等同账号凭证，请勿外传
