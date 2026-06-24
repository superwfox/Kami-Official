# Kami QQ 机器人（最简测试版）

一个用于对接 **QQ 官方机器人**（群机器人）的最简 Java 实现，仅用于功能验证。

通过 WebSocket 连接 QQ 网关，处理两条群 @ 指令：

| 指令     | 行为                                              |
| -------- | ------------------------------------------------- |
| `报时`   | 回复 UTC+8（北京时间）的格式化时间 + 秒级时间戳   |
| `logo`   | 回复一张 png 图片（`config.yml` 中的 `logoUrl`）  |
| `官网`   | 回复 Markdown + 三个链接按钮（官网导航）          |

> 群里需 **@机器人** 触发（如 `@机器人 报时`）；**单聊(C2C)** 直接发送指令即可。
> 群 @ 消息与单聊消息走同一套指令逻辑（`MessageHandler.handleCommand`），仅接口路径不同：
> 群用 `/v2/groups/{group_openid}/...`，单聊用 `/v2/users/{user_openid}/...`。

## 目录结构

```
pom.xml                         Maven 构建文件
config.yml                      机器人配置（填入 AppID / AppSecret）
src/main/java/com/kami/qqbot/
  Main.java                     入口
  Config.java                   读取 config.yml
  QQApi.java                    HTTP 接口：token / gateway / 发消息 / 上传富媒体
  QQBotClient.java              WebSocket：鉴权、心跳、重连、事件分发
  MessageHandler.java           业务逻辑（报时 / logo）
```

## 配置

在 [QQ 开放平台](https://q.qq.com/) 创建机器人后，把 `AppID`、`AppSecret` 填入 `config.yml`：

```yaml
appId: "你的AppID"
clientSecret: "你的AppSecret"
sandbox: false
logoUrl: "https://github.com/superwfox/minecraft-dev/blob/master/public/silver.png?raw=true"
```

## 构建与运行

需要 JDK 11+ 与 Maven。

```bash
mvn clean package
java -jar target/kami-qqbot.jar          # 默认读取当前目录 config.yml
# 或指定配置路径：
java -jar target/kami-qqbot.jar /path/to/config.yml
```

启动后看到 `[WS] READY，鉴权成功，机器人已上线` 即表示连接成功。

## 在 GitHub Actions 上临时运行（测试用）

仓库带了两个工作流：

- `.github/workflows/build.yml`：push / PR 时自动 `mvn package`，并把
  `kami-qqbot.jar` 作为 artifact 上传，可在对应 run 页面下载。
- `.github/workflows/run.yml`：**手动触发**，在 Actions runner 上直接把机器人跑起来，方便临时测试。

使用 `run.yml`：

1. 在 仓库 **Settings → Secrets and variables → Actions** 添加：
   - `QQ_APP_ID`：机器人 AppID
   - `QQ_CLIENT_SECRET`：机器人 AppSecret
   - `QQ_LOGO_URL`：（可选）logo 图片直链，不填用默认值
2. 打开 **Actions → Run Bot → Run workflow**，可填运行时长（分钟，默认 60）。
3. 运行期间在 run 日志里能看到 `[WS] READY` 与收到的消息，到时自动停止。

> ⚠️ GitHub Actions 不是用来托管常驻服务的：单次任务**最长 6 小时**会被强制结束，
> 而且不保证稳定在线。它只适合临时拉起来测一测，**正式长期运行请部署到自己的服务器**
> （`java -jar kami-qqbot.jar`）。Secret 不会写进代码，仅在运行时生成 `config.yml`。

## 实现要点

- **鉴权**：用 `AppID + AppSecret` 调 `https://bots.qq.com/app/getAppAccessToken` 换取
  `access_token`（有效期约 7200s，程序内到期前自动刷新）。
- **网关**：`GET /gateway` 拿到 wss 地址后连接，先收 `op:10 Hello`，随后发送
  `op:2 Identify`（`intents = 1<<25`，即 `GROUP_AND_C2C_EVENT`），并按
  `heartbeat_interval` 周期发送 `op:1` 心跳。
- **收消息**：监听 `GROUP_AT_MESSAGE_CREATE`（群 @）与 `C2C_MESSAGE_CREATE`（单聊）事件，
  二者都包含在 `GROUP_AND_C2C_EVENT`（`1<<25`）这一 intent 内。
- **发文本**：`POST /v2/groups/{group_openid}/messages`，`msg_type=0`，带上收到的
  `msg_id` 作被动回复（5 分钟内、最多 5 条）。
- **发图片**：先 `POST /v2/groups/{group_openid}/files`（`file_type=1`、`url=图片直链`）
  拿到 `file_info`，再以 `msg_type=7` 的富媒体消息发送。

## 关于额外问题

### 1. 能否实现 Markdown + Button（按钮）？

**可以，但有门槛。** QQ 群消息支持：

- **Markdown 消息**：`msg_type=2`，body 里带 `markdown` 字段。
- **Button（按钮 / keyboard）**：在消息里附带 `keyboard` 字段，可用 `id`
  引用已审核的按钮模板，或用 `content` 内联自定义按钮。

注意限制：
- 公域机器人要发 Markdown / 按钮，**模板需要先在开放平台报备并通过审核**，
  原生 Markdown / 内联按钮也需要申请相应权限，否则接口会报无权限。
- 按钮的回调（用户点击）通过 `INTERACTION_CREATE` 互动事件回传，需要额外处理。

本仓库已内置一个示例：群里 @机器人 发送 **`官网`** 会回复一条
Markdown + 三个链接按钮的消息（见 `QQApi.sendGroupMarkdown` 与
`MessageHandler.buttonRow`），body 形如：

```json
{
  "msg_type": 2,
  "msg_id": "...",
  "msg_seq": 1,
  "markdown": { "content": "**官网导航**\n点击下方按钮前往对应站点：" },
  "keyboard": {
    "content": {
      "rows": [
        { "buttons": [ {
          "id": "1",
          "render_data": { "label": "泰坦陨落", "visited_label": "泰坦陨落", "style": 1 },
          "action": { "type": 0, "permission": { "type": 2 }, "data": "https://ttdm.space",
                      "unsupport_tips": "请升级QQ客户端" }
        } ] }
      ]
    }
  }
}
```

> ⚠️ 该消息能否成功发出 / 按钮能否跳转，取决于开放平台权限：公域机器人需
> **申请原生 Markdown + 内联按钮权限**，链接按钮的跳转域名（ttdm.space /
> doc.oasis.monster / tahai.xyz）需加入 **跳转链接白名单**，否则接口会报无权限
> 或点击无法跳转。`action.type=0` 为跳转按钮，`permission.type=2` 表示所有人可点击。

### 2. 能否 base64 编码消息再发送文件？

- **发送文本/Markdown 不需要 base64**，直接传字符串即可（UTF-8）。
- **发送文件/图片**：群、C2C 的富媒体上传接口
  （`/v2/groups/{openid}/files`）目前**只支持 `url` 直链**，
  其 `file_data`（base64）字段官方文档标注为「暂未支持」。
  所以本仓库的 `logo` 用的是图片直链方式。
- 若确实需要发送本地文件，可行的折中方案是：先把文件上传到任意可公网访问的对象
  存储 / 图床，拿到直链后再走上面的 `url` 上传流程。
  （频道 / 私信场景下另有 `multipart/form-data` 直传文件的接口，与群接口不同。）
