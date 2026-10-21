# Kami QQ 官方机器人

Java 17 / Maven。使用 `config.yml` 配置 QQ AppID、AppSecret，执行 `mvn clean verify` 后运行：

```bash
java -jar target/kami-qqbot.jar
```

## 自动检测图片来源

收到群聊或单聊消息中的 PNG、JPEG、WebP 附件后，机器人在后台下载原图，将未修改的字节上传至 OpenAI Content Provenance API。检测到受支持的 OpenAI 来源信号时，按原消息 `msg_id` 被动回复：

> 图片由OpenAI生成，请注意识别⚠️

没有检测到信号时不回复；请求失败、限流或格式不受支持时也不发送“真人创作”等结论。一条消息即使有多张图片，也只提醒一次。重复投递的消息会去重，相同图片的成功检测结果缓存 15 分钟。

### 配置

在运行环境设置 `OPENAI_API_KEY`，并保留以下配置：

```yaml
aiImageCheckEnabled: true
```

Key 不写入仓库或日志。没有 Key 时只禁用这项检测，其它指令照常工作。设为 `false` 可关闭检测。若通过 GitHub Actions 的 **Run Bot** 启动，在仓库 **Settings → Secrets and variables → Actions** 新增名为 `OPENAI_API_KEY` 的 Secret；工作流会将其注入运行环境。

### 范围与平台限制

- 当前实际接入 **OpenAI**，使用 `POST https://api.openai.com/v1/content_provenance_checks`，检测 C2PA 和 SynthID 来源信号。接口当前免费，有使用限额。没有来源信号不代表没有使用 AI，旧模型或处理过的图片可能无法检出。
- SynthID 是 Google 的水印技术，但 OpenAI 接口只验证 OpenAI 支持的来源信号。检测到 SynthID 时不能因此将图片标为 Google 生成。本实现不会虚构 Google 或其它 AI 来源。
- Google 的公众检测网页与开发者 API 是不同入口。Google AI Content Detection API 当前仍需申请 Private Preview 权限，因此本次没有接入 Google 检测；需要获得该接口权限和正式调用文档后再添加。
- QQ 平台必须实际推送这条图片消息，机器人才能处理。普通群图片自动检测需要开放平台及群内的“接收全部群消息”权限；仅有 @ 消息权限时，只会检测 @ 机器人时收到的附件。代码已兼容 `GROUP_MESSAGE_CREATE`、`GROUP_AT_MESSAGE_CREATE`、`C2C_MESSAGE_CREATE`。
- 按腾讯官方文档，群/C2C 的可见 `message_reference` 引用卡片仍标注为暂未支持；这里按原 `msg_id` 被动回复，不承诺客户端显示引用卡片。
- 图片仅从 QQ 附件 CDN 的 HTTPS 地址下载，不接受消息文本中的任意 URL。允许 `*.qpic.cn`、`multimedia.nt.qq.com`、`multimedia.nt.qq.com.cn`；重定向也遵守该限制。单图上限 10 MiB，GIF 等格式不转码，避免破坏来源信号。
- 检测最多 2 个并发任务、8 条排队任务，排队过久或队列满时跳过。429 会按 `Retry-After` 冷却；鉴权/访问返回 401、403、404 后暂停检测，检查 Key 和组织的接口权限后重启可恢复。

### 验证

`mvn clean verify` 会运行本地 HTTP 模拟服务测试，检查原图 multipart 上传、来源结果判定、响应大小限制、限流冷却、消息去重和原消息回复。测试不会调用 OpenAI，也不发送真实 QQ 消息；上线前仍需用有效 Key 和 QQ 测试群验证实际权限及回调。

参考：[OpenAI API 文档](https://developers.openai.com/api/docs/guides/content-provenance)、[OpenAI 免费与使用限额说明](https://help.openai.com/en/articles/8912793-provenance-signals-in-openai-generated-content)、[Google API 开放范围](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/ai-content-detection)、[QQ 消息发送文档](https://github.com/tencent-connect/bot-docs/blob/main/docs/develop/api-v2/server-inter/message/send-receive/send.md)。
