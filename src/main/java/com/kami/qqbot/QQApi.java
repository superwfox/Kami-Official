package com.kami.qqbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 封装 QQ 机器人 HTTP 接口：access_token、gateway、发送群消息、上传富媒体。
 */
public class QQApi {

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
        int expiresIn = refreshToken();
        scheduleRefresh(expiresIn);
    }

    private void scheduleRefresh(int expiresInSeconds) {
        long delay = Math.max(30, expiresInSeconds - 60);
        scheduler.schedule(() -> {
            try {
                int e = refreshToken();
                scheduleRefresh(e);
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

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://bots.qq.com/app/getAppAccessToken"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!json.has("access_token")) {
            throw new RuntimeException("获取 access_token 失败: " + resp.body());
        }
        accessToken.set(json.get("access_token").getAsString());
        int expiresIn = Integer.parseInt(json.get("expires_in").getAsString());
        System.out.println("[QQApi] access_token 已更新, expires_in=" + expiresIn + "s");
        return expiresIn;
    }

    public String authHeader() {
        return "QQBot " + accessToken.get();
    }

    /** 获取 WebSocket 网关地址。 */
    public String getGateway() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.apiBase() + "/gateway"))
                .header("Authorization", authHeader())
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!json.has("url")) {
            throw new RuntimeException("获取 gateway 失败: " + resp.body());
        }
        return json.get("url").getAsString();
    }

    /** 回复群文本消息。msg_type=0。 */
    public void sendGroupText(String groupOpenid, String content, String msgId, int msgSeq) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.addProperty("msg_type", 0);
        body.addProperty("msg_id", msgId);
        body.addProperty("msg_seq", msgSeq);
        post("/v2/groups/" + groupOpenid + "/messages", body);
    }

    /**
     * 上传群富媒体并返回 file_info。
     * fileType: 1=图片 2=视频 3=语音 4=文件。
     * 注意：群/C2C 富媒体目前仅支持通过 url 上传，file_data(base64) 官方暂未开放。
     */
    public String uploadGroupMedia(String groupOpenid, String url, int fileType) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("file_type", fileType);
        body.addProperty("url", url);
        body.addProperty("srv_send_msg", false);
        String resp = post("/v2/groups/" + groupOpenid + "/files", body);
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
        if (!json.has("file_info")) {
            throw new RuntimeException("上传富媒体失败: " + resp);
        }
        return json.get("file_info").getAsString();
    }

    /**
     * 回复群 Markdown + 按钮消息。msg_type=2。
     * markdown / keyboard 为已构造好的 JSON 节点（keyboard 可为 null）。
     * 注意：公域机器人发送原生 Markdown / 内联按钮需在开放平台申请权限并报备，
     * 且链接按钮的跳转域名需加入白名单，否则会报无权限或无法跳转。
     */
    public void sendGroupMarkdown(String groupOpenid, JsonObject markdown, JsonObject keyboard,
                                  String msgId, int msgSeq) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", 2);
        body.add("markdown", markdown);
        if (keyboard != null) {
            body.add("keyboard", keyboard);
        }
        body.addProperty("msg_id", msgId);
        body.addProperty("msg_seq", msgSeq);
        post("/v2/groups/" + groupOpenid + "/messages", body);
    }

    /** 回复群富媒体消息。msg_type=7。 */
    public void sendGroupMedia(String groupOpenid, String fileInfo, String msgId, int msgSeq) throws Exception {
        JsonObject media = new JsonObject();
        media.addProperty("file_info", fileInfo);

        JsonObject body = new JsonObject();
        body.addProperty("content", " ");
        body.addProperty("msg_type", 7);
        body.add("media", media);
        body.addProperty("msg_id", msgId);
        body.addProperty("msg_seq", msgSeq);
        post("/v2/groups/" + groupOpenid + "/messages", body);
    }

    private String post(String path, JsonObject body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.apiBase() + path))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[QQApi] POST " + path + " -> " + resp.statusCode() + " " + resp.body());
        return resp.body();
    }
}
