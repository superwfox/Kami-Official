package com.kami.qqbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 封装 QQ 机器人 HTTP 接口：access_token、gateway、发送群/单聊消息、上传富媒体。
 */
public class QQApi {

    /** 获取 access_token 的固定域名（与正式/沙箱无关）。 */
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";

    private final Config config;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicReference<String> accessToken = new AtomicReference<>("");
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "token-refresh");
        t.setDaemon(true);
        return t;
    });

    public QQApi(Config config) {
        this.config = config;
    }

    /** 获取首个 token，并安排到期前自动刷新。 */
    public void start() throws Exception {
        scheduleRefresh(refreshToken());
    }

    private void scheduleRefresh(int expiresInSeconds) {
        long delay = Math.max(30, expiresInSeconds - 60);
        scheduler.schedule(() -> {
            try {
                scheduleRefresh(refreshToken());
            } catch (Exception ex) {
                System.err.println("[QQApi] 刷新 token 失败, 30s 后重试: " + ex.getMessage());
                scheduleRefresh(30);
            }
        }, delay, TimeUnit.SECONDS);
    }

    /** 通过 AppID + AppSecret 换取 access_token，返回有效期（秒）。 */
    public int refreshToken() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("appId", config.appId);
        body.addProperty("clientSecret", config.clientSecret);

        JsonObject json = request(TOKEN_URL, false, body);
        accessToken.set(require(json, "access_token", "获取 access_token 失败"));
        int expiresIn = json.get("expires_in").getAsInt();
        System.out.println("[QQApi] access_token 已更新, expires_in=" + expiresIn + "s");
        return expiresIn;
    }

    public String authHeader() {
        return "QQBot " + accessToken.get();
    }

    /** 获取 WebSocket 网关地址。 */
    public String getGateway() throws Exception {
        return require(request(config.apiBase() + "/gateway", true, null), "url", "获取 gateway 失败");
    }

    // 下面这组接口对「群」和「单聊(C2C)」是同构的，只是路径不同：
    //   群:   messagesPath=/v2/groups/{group_openid}/messages  filesPath=/v2/groups/{group_openid}/files
    //   单聊: messagesPath=/v2/users/{user_openid}/messages     filesPath=/v2/users/{user_openid}/files
    // 因此统一用 path 参数化，由调用方拼好路径。

    /** 回复文本消息。msg_type=0。 */
    public void sendText(String messagesPath, String content, String msgId, int msgSeq) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.addProperty("msg_type", 0);
        body.addProperty("msg_id", msgId);
        body.addProperty("msg_seq", msgSeq);
        post(messagesPath, body);
    }

    /**
     * 上传富媒体并返回 file_info。
     * fileType: 1=图片 2=视频 3=语音 4=文件。
     * 注意：群/C2C 富媒体目前仅支持通过 url 上传，file_data(base64) 官方暂未开放。
     */
    public String uploadMedia(String filesPath, String url, int fileType) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("file_type", fileType);
        body.addProperty("url", url);
        body.addProperty("srv_send_msg", false);
        return require(post(filesPath, body), "file_info", "上传富媒体失败");
    }

    /**
     * 上传本地媒体（base64 直传），返回 file_info。
     * fileType: 1=图片 2=视频 3=语音 4=文件。
     * data 为媒体原始字节，内部做 base64（纯串，不带 data: 前缀、无换行）塞进 file_data。
     */
    public String uploadMediaData(String filesPath, byte[] data, int fileType) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("file_type", fileType);
        body.addProperty("file_data", Base64.getEncoder().encodeToString(data));
        body.addProperty("srv_send_msg", false);
        return require(post(filesPath, body), "file_info", "上传富媒体失败");
    }

    /** uploadMediaData 的便捷重载：直接读本地文件路径。 */
    public String uploadMediaFile(String filesPath, Path file, int fileType) throws Exception {
        return uploadMediaData(filesPath, Files.readAllBytes(file), fileType);
    }

    /**
     * 回复 Markdown + 按钮消息。msg_type=2。
     * markdown / keyboard 为已构造好的 JSON 节点（keyboard 可为 null）。
     * 注意：公域机器人发送原生 Markdown / 内联按钮需在开放平台申请权限并报备，
     * 且链接按钮的跳转域名需加入白名单，否则会报无权限或无法跳转。
     */
    public void sendMarkdown(String messagesPath, JsonObject markdown, JsonObject keyboard,
                             String msgId, int msgSeq) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", 2);
        body.add("markdown", markdown);
        if (keyboard != null) {
            body.add("keyboard", keyboard);
        }
        body.addProperty("msg_id", msgId);
        body.addProperty("msg_seq", msgSeq);
        post(messagesPath, body);
    }

    /**
     * 回复 Ark 模板消息。msg_type=3。
     * ark 为已构造好的 {@code {template_id, kv:[...]}} 节点。
     * 注意：Ark 需在开放平台为该机器人申请/开通对应模板，否则会报无权限。
     */
    public void sendArk(String messagesPath, JsonObject ark, String msgId, int msgSeq) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", 3);
        body.add("ark", ark);
        body.addProperty("msg_id", msgId);
        body.addProperty("msg_seq", msgSeq);
        post(messagesPath, body);
    }

    /** 回复富媒体消息。msg_type=7。 */
    public void sendMedia(String messagesPath, String fileInfo, String msgId, int msgSeq) throws Exception {
        sendMedia(messagesPath, fileInfo, null, msgId, msgSeq);
    }

    /** 回复富媒体消息，可附带 keyboard 按钮（为 null 则不带）。msg_type=7。 */
    public void sendMedia(String messagesPath, String fileInfo, JsonObject keyboard,
                          String msgId, int msgSeq) throws Exception {
        JsonObject media = new JsonObject();
        media.addProperty("file_info", fileInfo);

        JsonObject body = new JsonObject();
        body.addProperty("content", ""); // 实验：去掉占位空格，看富媒体图能否不带文字行（若报 40034 再改回 " "）
        body.addProperty("msg_type", 7);
        body.add("media", media);
        if (keyboard != null) {
            body.add("keyboard", keyboard);
        }
        body.addProperty("msg_id", msgId);
        body.addProperty("msg_seq", msgSeq);
        post(messagesPath, body);
    }

    /** 向开放接口 POST 一段 JSON（自动带鉴权），返回解析后的响应。 */
    private JsonObject post(String path, JsonObject body) throws Exception {
        return request(config.apiBase() + path, true, body);
    }

    /**
     * 统一的 HTTP 请求：body 为 null 时发 GET，否则发 POST；按需带 Authorization。
     * 返回解析后的 JSON 响应，并打印状态码与响应体便于排查。
     */
    private JsonObject request(String url, boolean auth, JsonObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");
        if (auth) {
            builder.header("Authorization", authHeader());
        }
        String method;
        if (body == null) {
            builder.GET();
            method = "GET";
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
            method = "POST";
        }

        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("[QQApi] " + method + " " + url + " -> " + resp.statusCode() + " " + resp.body());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** 取出必需的字符串字段，缺失或为 null 时带响应体抛错。 */
    private static String require(JsonObject json, String key, String errPrefix) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            throw new RuntimeException(errPrefix + ": " + json);
        }
        return json.get(key).getAsString();
    }
}
