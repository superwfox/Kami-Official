# Kami QQ 官方机器人

Java 17 / Maven。使用 `config.yml` 配置 QQ AppID、AppSecret，执行 `mvn clean verify` 后运行：

```bash
java -jar target/kami-qqbot.jar
```

## 自动检测图片来源

收到群聊或单聊消息中的 PNG、JPEG、WebP 附件后，机器人在后台下载原图，将未修改的字节上传至 OpenAI Content Provenance API。检测到受支持的 OpenAI 来源信号时，引用原图片消息回复：

> 图片由AI生成，请注意识别⚠️

没有检测到信号时不回复；请求失败、限流或格式不受支持时也不发送“真人创作”等结论。一条消息即使有多张图片，也只提醒一次。重复投递的消息会去重，相同图片的成功检测结果缓存 15 分钟。

接收附件兼容 `image`、`image/*` 类型和单个附件对象；文件名/类型不完整的普通附件会先下载，再通过文件头识别 PNG/JPEG/WebP，明确的文档/视频不进入检测。GIF 等图片格式会记录为不支持，保持原样跳过，不转码。

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
- 检测提醒通过 `message_reference` 引用原图片消息，文案为「图片由AI生成，请注意识别⚠️」。`msg_id` 保留原消息 ID；引用优先使用回调 `message_scene.ext` 的 `msg_idx`，缺失时按腾讯官方 Python SDK 使用原消息 ID。发送失败只记录日志，不降级发送普通文本。实际引用展示需在 QQ 客户端验证。
- 图片仅从 QQ 附件 CDN 的 HTTPS 地址下载，不接受消息文本中的任意 URL。允许 `*.qpic.cn`、`multimedia.nt.qq.com`、`multimedia.nt.qq.com.cn`；重定向也遵守该限制。单图上限 10 MiB，GIF 等格式不转码，避免破坏来源信号。
- 检测最多 2 个并发任务、8 条排队任务，排队过久或队列满时跳过。429 会按 `Retry-After` 冷却；鉴权/访问返回 401、403、404 后暂停检测，检查 Key 和组织的接口权限后重启可恢复。

### 验证

排查收图问题时，查看日志中的 `event=... attachments=... types=... urls=...` 与 `[AI Image]` 阶段日志：候选图片数 → 后台任务 → 原图下载 → OpenAI API → 检测结果/回复。`content=[]` 只表示没有文本，图片数据位于 `attachments`。如收到附件却无 Key、候选被跳过、下载失败或 API 访问失败，现在会分别记录原因；日志不包含文件名、图片完整 URL、URL 签名或 Key。若完全没有相应的消息事件日志，应先检查 QQ 的事件推送权限。

`mvn clean verify` 会运行本地 HTTP 模拟服务测试，检查原图 multipart 上传、来源结果判定、响应大小限制、限流冷却、消息去重和原消息回复。测试不会调用 OpenAI，也不发送真实 QQ 消息；上线前仍需用有效 Key 和 QQ 测试群验证实际权限及回调。

参考：[OpenAI API 文档](https://developers.openai.com/api/docs/guides/content-provenance)、[OpenAI 免费与使用限额说明](https://help.openai.com/en/articles/8912793-provenance-signals-in-openai-generated-content)、[Google API 开放范围](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/ai-content-detection)、[QQ 消息发送文档](https://github.com/tencent-connect/bot-docs/blob/main/docs/develop/api-v2/server-inter/message/send-receive/send.md)。

## GitHub 仓库卡片

在群聊（含 @ 机器人）或 C2C 私聊中分享 `https://github.com/owner/repo`，机器人会在后台生成钉钉进步体、暖灰暗色的仓库图片卡片。分支、文件、Issue 等仓库子链接同样识别为对应仓库，使用该仓库默认分支的数据。个人主页链接 `https://github.com/username`（包括末尾斜杠和 `?tab=...`）解析为同名的 `username/username` 个人主页仓库；该仓库需要公开存在。GitHub 登录、设置等站点页面不会触发。一条消息解析第一个可识别的 GitHub 链接，重复投递不会重复回复。

卡片包含仓库名称、`所有者 | 贡献者`、圆角矩形头像、短 commit hash、Star 和最后提交时间。所有者优先显示 GitHub 昵称或组织名称，未设置或暂时不可读取时回退到用户名；仓库地址仍使用原始用户名。头像首行最多 7 个，后续每行最多 12 个并右对齐；20 人为 `7 + 12 + 1`，继续读取 GitHub 返回的后续贡献者页。Star **严格大于 1,000** 时显示 `HOT`，最后提交距查询时不超过 **18 小时**时显示 `NEW`。归档仓库显示 `Archived` 和只读提示，空仓库显示“暂无提交”，缺失或暂时不可读取的 README 使用仓库描述及说明。

commit hash 后的绿色长尾勾表示该提交的构建成功，红叉表示失败。优先读取名称含 Build / CI / Test / Verify / Check / Lint 或“构建 / 测试 / 检查”的工作流，并按工作流选择最新运行及重试；这使本仓库的 `Build` 与常驻的 `Run Bot` 分开判断。没有匹配的构建工作流时合并 Check Runs 和传统 Commit Status。没有结果、权限不足、仍在运行、取消或全部跳过时不显示成功标记。README 和构建结果都固定到所显示的同一个 commit。

**README 不做字符数、段落数或高度截断，也不拆成多张图。** 仓库标题及 README 各级标题加粗；Markdown 标题、段落、列表、引用、代码块和表格转换为图片。代码块使用紧凑的圆角面板并显示围栏指定的语言，未指定时标为“文本”；长代码自动折行，超长正文在同一张长图内完整呈现。图片高度由完整正文计算，一条消息只上传并发送一张 PNG，不用省略号或“摘选”代替正文。并列表格图片按各自单元格宽度等比缩放，保留全部列，避免撑宽表格后裁掉右侧图片与正文。README 图片只从 GitHub 图片域名和 shields.io 获取；无法读取的图片显示替代文字和原地址，HTML 脚本、嵌入和本地文件不执行、不读取。

SVG（含徽章、字标和流程图）使用 Batik 转绘，WebP 使用 TwelveMonkeys ImageIO 解码，依赖随 JAR 打包，运行仍只需 Java，不需要安装浏览器或本地图像程序。保留透明背景、图片比例和徽章原始尺寸；`picture` 优先选择暗色配图。`align="left/right"` 与 `br clear` 转成无边框图文布局，图片结束后的段落恢复正文宽度。图片允许的来源还包括 GitHub Camo、badgen.net；SVG 不执行脚本，不读取外部文件和资源。动画图片取静态帧。

默认配置：

```yaml
githubCardEnabled: true
```

可以在运行环境设置 `GITHUB_TOKEN` 提升公开仓库的 API 额度；不把 Token 写进配置、卡片或日志，也不在群聊中展示私有仓库。`Run Bot` 工作流自动提供只读 Token（`contents: read`、`actions: read`）。未设置 Token 时使用 GitHub 的匿名额度。仓库快照缓存 5 分钟，后台最多 2 个任务、8 个排队任务。

本地用真实仓库预览，不启动 QQ 连接、不发送消息：

```bash
mvn clean verify
java -Djava.awt.headless=true -cp target/kami-qqbot.jar \
  com.kami.qqbot.GitHubCardPreview superwfox/Kami-Official target/github-preview
```

输出目录含一张完整的 `card.png` 及未经截断的 `README-source.md`。Linux 无图形界面同样可用，字体随 JAR 打包，不依赖 Chromium 或宿主机预装字体。群聊和私聊分发、完整 README、贡献者分页、CI 状态与超长单图渲染和三列并排图片在本地模拟 HTTP / QQ 接口中测试，QQ 客户端的最终富媒体显示仍需在测试群验证。
