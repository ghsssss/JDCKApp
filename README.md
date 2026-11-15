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

`dingtalk_ck_bot.py`：钉钉开放平台创建企业内部应用（机器人能力，消息接收模式选 **Stream 模式**）后部署：

```bash
pip install dingtalk-stream requests
# 配置环境变量或同目录 config.json：
#   DT_APP_KEY / DT_APP_SECRET    钉钉应用 AppKey / AppSecret
#   QL_URL                        青龙地址
#   QL_CLIENT_ID / QL_CLIENT_SECRET  青龙 OpenAPI 应用（需"环境变量"权限）
python3 dingtalk_ck_bot.py
```

在钉钉群里 @机器人 或私聊发送 `pt_key=xxx;pt_pin=xxx;` 即自动合并更新青龙，机器人回复执行结果。多账号用 `&` 或换行分隔。

> 青龙 2.24+ 认证端点为 `GET /open/auth/token`，脚本已适配。

## 注意

- 仓库中的 `debug.keystore` 仅用于本地调试签名（密码 `android`），正式发布请使用自己的密钥
- Cookie 等同账号凭证，请勿外传
