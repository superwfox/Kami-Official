package com.kami.qqbot.BilibiliRelated;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliApiTest {
    private static final String IMG = "7cd084941338484aae1ad9425b84077c";
    private static final String SUB = "4932caff0ff746eab6f01bf08b70ac45";
    private static final String BUVID3 = "F0654A7B-882E-1332-FFD0-00DB9EECD27B68382infoc";
    private static final String BUVID4 = "D19B0C71-9462-5545-6B31-D1473CAD723068382-026102310-zfd48vhuDMetKLu5Fcl+cw==";
    private static final String BVID = "bvid=BV1Ey7A6QEYj";

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private volatile int navStatus = 200;
    private volatile int viewStatus = 200;
    private volatile int viewCode = 0;
    private volatile int detailStatus = 200;
    private volatile int detailCode = 0;
    private volatile String buvid3 = BUVID3;
    private volatile String buvid4 = BUVID4;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            hits.add(path + "?" + (query == null ? "" : query) + "\n" + (cookie == null ? "" : cookie));
            int status = 200;
            String body;
            if ("/x/frontend/finger/spi".equals(path)) {
                JsonObject data = new JsonObject();
                data.addProperty("b_3", buvid3);
                data.addProperty("b_4", buvid4);
                JsonObject root = new JsonObject();
                root.addProperty("code", 0);
                root.add("data", data);
                body = root.toString();
            } else if ("/x/web-interface/nav".equals(path)) {
                status = navStatus;
                body = "{\"code\":-101,\"message\":\"账号未登录\",\"data\":{\"isLogin\":false,\"wbi_img\":{"
                        + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/" + IMG + ".png\","
                        + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/" + SUB + ".png?s=0\"}}}";
            } else if ("/x/web-interface/wbi/view".equals(path)) {
                status = viewStatus;
                body = videoJson(viewCode, false);
            } else if ("/x/web-interface/view/detail".equals(path)) {
                status = detailStatus;
                body = videoJson(detailCode, true);
            } else {
                status = 404;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    @Test void signsTheViewRequestWithAnAnonymousBuvidAndCachesIt() throws Exception {
        BilibiliApi api = api();
        JsonObject data = api.fetchVideoData(BVID);
        assertEquals("测试视频", data.get("title").getAsString());
        assertEquals(7, data.getAsJsonObject("stat").get("like").getAsInt());

        String query = queryOn("/x/web-interface/wbi/view");
        long wts = Long.parseLong(query.split("&")[1].substring("wts=".length()));
        assertTrue(Math.abs(System.currentTimeMillis() / 1000L - wts) < 120);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", "BV1Ey7A6QEYj");
        assertEquals(BilibiliWbi.sign(params, BilibiliWbi.mixinKey(IMG, SUB), wts), query);

        String cookie = cookieOn("/x/web-interface/wbi/view");
        assertTrue(cookie.startsWith("buvid3=" + BUVID3 + "; buvid4=" + BUVID4 + "; b_nut="));
        assertFalse(cookie.contains("SESSDATA"));
        assertFalse(cookie.contains("DedeUserID"));
        assertFalse(cookie.contains("bili_jct"));
        assertEquals("", cookieOn("/x/frontend/finger/spi"));
        assertEquals(1, count("/x/frontend/finger/spi"));
        assertEquals(0, count("/x/web-interface/view/detail"));

        api.fetchVideoData(BVID);
        assertEquals(1, count("/x/frontend/finger/spi"));
        assertEquals(1, count("/x/web-interface/nav"));
        assertEquals(2, count("/x/web-interface/wbi/view"));

        HttpRequest image = api.request(URI.create("https://i0.hdslb.com/bfs/archive/abc.jpg"), 4);
        HttpRequest shortLink = api.request(URI.create("https://b23.tv/hAosUvf"), 4);
        assertTrue(image.headers().firstValue("Cookie").isEmpty());
        assertTrue(image.headers().firstValue("Origin").isEmpty());
        assertTrue(shortLink.headers().firstValue("Cookie").isEmpty());
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x/web-interface/wbi/view?bvid=BV1");
        String apiCookie = api.request(origin, 4).headers().firstValue("Cookie").orElse("");
        assertTrue(apiCookie.contains("buvid3=" + BUVID3));
        assertFalse(apiCookie.contains("SESSDATA"));
    }

    @Test void fallsBackToViewDetailWhenWbiViewReturnsHttp412() throws Exception {
        viewStatus = 412;
        JsonObject data = api().fetchVideoData(BVID);
        assertEquals("测试视频", data.get("title").getAsString());
        assertEquals(7, data.getAsJsonObject("stat").get("like").getAsInt());
        assertEquals(3, count("/x/web-interface/wbi/view"));
        assertEquals(1, count("/x/web-interface/view/detail"));
        assertTrue(cookieOn("/x/web-interface/view/detail").contains("buvid3=" + BUVID3));
        assertFalse(cookieOn("/x/web-interface/view/detail").contains("SESSDATA"));
    }

    @Test void keepsTheRateLimitMessageWhenEveryPublicEndpointIsBlocked() {
        viewStatus = 412;
        detailStatus = 500;
        BilibiliApi api = api();
        IOException error = assertThrows(IOException.class, () -> api.fetchVideoData(BVID));
        assertEquals("B 站暂时限制了请求，请稍后重试", error.getMessage());
        int spi = count("/x/frontend/finger/spi");
        assertTrue(spi >= 1);
        IOException again = assertThrows(IOException.class, () -> api.fetchVideoData(BVID));
        assertEquals("B 站暂时限制了请求，请稍后重试", again.getMessage());
        assertTrue(count("/x/frontend/finger/spi") > spi);
    }

    @Test void treatsJsonCode412AsTheSameRateLimit() {
        viewCode = -412;
        detailCode = -412;
        IOException error = assertThrows(IOException.class, () -> api().fetchVideoData(BVID));
        assertEquals("B 站暂时限制了请求，请稍后重试", error.getMessage());
        assertEquals(1, count("/x/web-interface/view/detail"));
    }

    @Test void doesNotFallBackWhenTheVideoDoesNotExistOrIsInReview() {
        viewCode = -404;
        IOException missing = assertThrows(IOException.class, () -> api().fetchVideoData(BVID));
        assertEquals("视频不存在或不可见，无法解析", missing.getMessage());
        assertEquals(0, count("/x/web-interface/view/detail"));
        assertEquals(1, count("/x/web-interface/wbi/view"));

        hits.clear();
        viewCode = 62004;
        IOException review = assertThrows(IOException.class, () -> api().fetchVideoData(BVID));
        assertEquals("视频正在审核中，暂时无法解析", review.getMessage());
        assertEquals(0, count("/x/web-interface/view/detail"));
    }

    @Test void keepsTheLoginMessageWhenTheVideoIsNotPublic() {
        viewCode = -403;
        detailCode = -403;
        IOException error = assertThrows(IOException.class, () -> api().fetchVideoData(BVID));
        assertEquals("该视频需要登录或没有访问权限，无法解析", error.getMessage());
        assertFalse(error.getMessage().contains("暂时限制"));
    }

    @Test void dropsAnonymousIdsThatCannotBeStoredAsCookies() throws Exception {
        buvid3 = "bad;SESSDATA=not-a-user-cookie";
        buvid4 = "also;bili_jct=nope";
        JsonObject data = api().fetchVideoData(BVID);
        assertEquals("测试视频", data.get("title").getAsString());
        assertEquals("", cookieOn("/x/web-interface/wbi/view"));
        assertFalse(String.join("\n", hits).contains("SESSDATA"));
    }

    @Test void stillReadsTheVideoWhenWbiKeysAreUnavailable() throws Exception {
        navStatus = 500;
        JsonObject data = api().fetchVideoData(BVID);
        assertEquals("测试视频", data.get("title").getAsString());
        assertEquals("bvid=BV1Ey7A6QEYj", queryOn("/x/web-interface/wbi/view"));
        assertEquals(0, count("/x/web-interface/view/detail"));
        assertTrue(cookieOn("/x/web-interface/wbi/view").contains("buvid3=" + BUVID3));
    }

    private BilibiliApi api() {
        return new BilibiliApi(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static String videoJson(int code, boolean wrap) {
        if (code != 0) return "{\"code\":" + code + ",\"message\":\"no\",\"data\":null}";
        String video = "{\"title\":\"测试视频\",\"pic\":\"http://i1.hdslb.com/bfs/archive/abc.jpg\","
                + "\"owner\":{\"name\":\"UP\",\"mid\":325084995},"
                + "\"stat\":{\"like\":7,\"reply\":8,\"favorite\":9,\"share\":10}}";
        return wrap ? "{\"code\":0,\"data\":{\"View\":" + video + "}}" : "{\"code\":0,\"data\":" + video + "}";
    }

    private int count(String path) {
        int count = 0;
        for (String hit : hits) if (hit.startsWith(path + "?")) count++;
        return count;
    }

    private String queryOn(String path) {
        String hit = first(path);
        String line = hit.substring(0, hit.indexOf('\n'));
        return line.substring(path.length() + 1);
    }

    private String cookieOn(String path) {
        String hit = first(path);
        return hit.substring(hit.indexOf('\n') + 1);
    }

    private String first(String path) {
        for (String hit : hits) if (hit.startsWith(path + "?")) return hit;
        throw new AssertionError("missing " + path);
    }
}
