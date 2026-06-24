package com.kami.qqbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * TTDM 数据查询接口客户端（无鉴权 GET）。
 *   /api/query?name=&offset=  玩家对局历史（每页 8 条，含计分板 + 时间线）
 *   /api/leaderboard          推荐榜（每类 TOP 10）
 */
public class TTDMApi {

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public TTDMApi(Config config) {
        this.config = config;
    }

    /** 拉取玩家对局历史；name 支持玩家名或昵称（接口自动解析）。 */
    public JsonObject query(String name, int offset) throws Exception {
        String url = config.ttdmApiBase + "/api/query?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&offset=" + offset;
        return get(url);
    }

    /** 拉取全部榜单分类。 */
    public JsonObject leaderboard() throws Exception {
        return get(config.ttdmApiBase + "/api/leaderboard");
    }

    /**
     * 读取远端 PNG 的像素宽高（仅解析 IHDR 块，读取前 24 字节即可），失败返回 null。
     * 用于 QQ 原生 Markdown 图片语法所需的 {@code #宽px #高px}（卡片高度随榜单行数变化）。
     */
    public int[] pngSize(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) return null;
                byte[] head = in.readNBytes(24); // PNG 签名8 + 长度4 + "IHDR"4 + 宽4 + 高4
                if (head.length < 24) return null;
                int w = beInt(head, 16), h = beInt(head, 20);
                return (w > 0 && h > 0) ? new int[]{w, h} : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 读大端 4 字节无符号整数。 */
    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private JsonObject get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (resp.statusCode() != 200 || (json.has("ok") && !json.get("ok").getAsBoolean())) {
            String err = json.has("error") ? json.get("error").getAsString() : ("HTTP " + resp.statusCode());
            throw new RuntimeException("TTDM 接口错误: " + err);
        }
        return json;
    }
}
