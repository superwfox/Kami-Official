# B 站视频与主页解析

收到的群聊或私聊消息包含 `https://b23.tv/` 短链或 `https://www.bilibili.com/video/BV…`、`/video/av…` 长链接时，就解析其中第一个视频链接（也支持 `bilibili.com`、`m.bilibili.com`），并回复一条 QQ 原生 Markdown：

```text
【封面图片】
## 视频标题
> UP : [昵称](https://space.bilibili.com/UID)

## 赞：25.0w 评论：6.8k 收藏：8.0w 分享：1.5w

【按钮：直接观看】
```

通过 Java 内置 `HttpClient` 请求 B 站自身的 Web 接口
`https://api.bilibili.com/x/web-interface/wbi/view`，复用已有 Gson 解析 JSON。
运行时不执行 curl，也不需要添加 Maven 依赖、API Key 或用户自己的 Cookie。

这是 B 站 Web 接口，不是正式开放平台的开发者 API；不能保证长期匿名访问或固定调用配额。
视频详情会先向公开的 `/x/frontend/finger/spi` 领取匿名 `buvid3`（浏览器标识，不是 `SESSDATA` 等登录 Cookie），
再从 `/x/web-interface/nav` 读取 `wbi_img`（未登录时 `code` 为 -101，仍带有密钥）并按 Web 端规则附上 `wts` 与 `w_rid`。
签名或风控失败时，会再试不签名的 `wbi/view`，以及 `/x/web-interface/view/detail` 中的 `View`。
失效链接、不可见视频、限流或风控会返回原来的提示。任一备用请求被 HTTP 412/403/429 或 code -412/-352 拦住时，
仍提示「B 站暂时限制了请求，请稍后重试」。机房 IP 若连这些公开接口一并拦截，匿名标识也无法绕过该限制。
机器人只能解析已有消息订阅及权限允许它收到的事件；不改变 QQ 事件订阅。

- 信息取自 `title`、`pic`、`owner.name`、`owner.mid`，以及 `stat.like/reply/favorite/share`。
- 千用 `k`、万用 `w`、百万用 `m`。缩写后不足 100 保留一位小数，100 起显示整数；四舍五入后跨阈值则升级单位。极大计数继续用 `b/t/q/Q`，数字部分始终不超过三位；缺失统计显示 `—`。
- 长链接直接提取 BV/AV 编号，无需访问视频网页；保留 `p` 分 P 参数，分享追踪参数不传递到视频接口或播放器。
- 短链最多跟随三次跳转，只访问 B 站域名；拿到 BV/AV 编号后不再请求视频网页，不保留分享追踪参数。
- 匿名 buvid 与 WBI 密钥缓存约 30 分钟，且只附在 `api.bilibili.com` 请求上；封面 CDN 与短链不携带该 Cookie。全部视频接口都失败时丢弃缓存，下一条消息会重新领取标识。
- 不读取配置文件中的登录态，也不会把含分号等异常字符的标识写入 Cookie。
- 封面统一使用 HTTPS，仅接受 B 站图片 CDN。读取图片头以获得 QQ 图片语法所需的尺寸，失败时使用 672×378；不解码整张图片。
- 请求设有超时，视频 JSON 和封面响应分别限制为 1 MiB、4 MiB。标题、UP 昵称进行 Markdown 转义。
- 视频解析消息附带 QQ 原生 keyboard 链接按钮“直接观看”，所有查看消息的用户均可点击；正文不重复展示观看链接。
- 按钮根据视频编号打开 B 站官方独立播放器页面：`https://player.bilibili.com/player.html?bvid=BV号&autoplay=1`；旧 AV 地址使用 `aid` 参数，多 P 分享保留 `p` 参数。
- 播放器页面打开后由 B 站加载视频流；机器人只提供页面链接，不请求、缓存、代理或下载 MP4 地址。
- `autoplay=1` 请求自动播放；是否自动播放及进入系统全屏由 QQ、浏览器和 B 站播放器控制。参数说明见 [B 站官方播放器文档](https://player.bilibili.com/)。

分享消息示例：`【来纽约，拍到了些怪东西-哔哩哔哩】 https://b23.tv/bcLqG7g`。

## 主页链接

收到 `https://space.bilibili.com/UID` 时，回复一条主页 Markdown；原有视频短链解析保留封面和视频资料。

```text
【头像】
## [昵称](主页链接)

## 粉丝：800 上传视频：85

[最新动态](动态链接)
```

- `/x/web-interface/card?mid=UID` 提供昵称、头像、粉丝数与 `archive_count` 投稿视频数；不把 `article_count` 专栏数当成视频数。
- `/x/polymer/web-dynamic/v1/feed/space?host_mid=UID` 提供公开动态列表。按发布时间选最新动态，避免列表中的置顶旧动态被误选；动态链接使用 `https://t.bilibili.com/动态ID`。
- 主页和动态请求复用同一份匿名 buvid。动态接口在机房 IP 上仍可能返回 HTTP 412；失败时保留头像和主页统计，显示“最新动态：暂时无法获取”及“查看主页动态”链接。动态列表为空时显示“暂无公开动态”。
- 日志输出请求域名、路径、HTTP 状态及接口错误码，便于查看 GitHub Actions 环境的访问情况；不输出接口查询串和响应正文。
- 主页示例：`https://space.bilibili.com/177826289`。本地查询得到 Sudark、粉丝 800、投稿视频 85；动态接口返回 HTTP 412。以上只是查询快照，不代表 GitHub Actions 中的请求结果。
