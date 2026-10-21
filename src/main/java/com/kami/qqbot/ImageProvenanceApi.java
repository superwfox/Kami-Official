package com.kami.qqbot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 下载 QQ 原图并检查 OpenAI 来源信号；不解码、压缩或重绘图片。 */
final class ImageProvenanceApi {
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/content_provenance_checks");
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final long CACHE_TTL_MS = Duration.ofMinutes(15).toMillis();

    private final String apiKey;
    private final URI endpoint;
    private final HttpClient http;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();
    private volatile long retryAfter;
    private volatile boolean unauthorized;

    ImageProvenanceApi(String apiKey) {
        this(apiKey, ENDPOINT, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** 测试时注入本地 HTTP 服务，生产只使用官方端点。 */
    ImageProvenanceApi(String apiKey, URI endpoint, HttpClient http) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.http = http;
    }

    boolean isOpenAIImage(String url) throws IOException, InterruptedException {
        checkAvailable();
        byte[] image = download(qqImageUri(url));
        String mime = imageMime(image);
        if (mime == null) return false; // GIF 等不支持的格式保留原样跳过。
        return verify(image, mime);
    }

    private byte[] download(URI uri) throws IOException, InterruptedException {
        for (int redirects = 0; redirects <= 3; redirects++) {
            // 只读取平台附件 CDN，限制重定向目标；绝不把 OpenAI Key 带给图片服务器。
            uri = qqImageUri(uri.toString());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Accept", "image/png,image/jpeg,image/webp").GET().build();
            HttpResponse<byte[]> response = send(request, MAX_IMAGE_BYTES, 15);
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("图片重定向缺少 Location"));
                try {
                    uri = uri.resolve(location);
                } catch (IllegalArgumentException e) {
                    throw new IOException("图片重定向地址无效");
                }
                continue;
            }
            if (status != 200) throw new IOException("下载图片失败，HTTP " + status);
            return response.body();
        }
        throw new IOException("图片重定向次数过多");
    }

    static URI qqImageUri(String raw) throws IOException {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            String normalized = raw.trim();
            if (normalized.startsWith("//")) normalized = "https:" + normalized;
            if (normalized.startsWith("http://")) normalized = "https://" + normalized.substring(7);
            URI uri = URI.create(normalized);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean qqCdn = host.equals("qpic.cn") || host.endsWith(".qpic.cn")
                    || host.equals("multimedia.nt.qq.com") || host.equals("multimedia.nt.qq.com.cn");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !qqCdn
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("图片地址不是受支持的 QQ HTTPS 附件 CDN");
        }
    }

    boolean verify(byte[] image, String mime) throws IOException, InterruptedException {
        if (image.length == 0 || image.length > MAX_IMAGE_BYTES || mime == null || !mime.equals(imageMime(image))) {
            throw new IOException("图片为空、过大或格式不受支持");
        }
        String hash;
        try {
            hash = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(image));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        synchronized (cache) {
            CacheEntry entry = cache.get(hash);
            if (entry != null && System.currentTimeMillis() - entry.createdAt < CACHE_TTL_MS) {
                return entry.detected;
            }
        }
        checkAvailable();
        String boundary = "kami-" + UUID.randomUUID();
        String filename = "image." + (mime.equals("image/jpeg") ? "jpg" : mime.substring(6));
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(prefix, image, suffix)))
                .build();
        HttpResponse<byte[]> response = send(request, 1024 * 1024, 20);
        if (response.statusCode() == 429) {
            long seconds = 60;
            try {
                seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"));
            } catch (NumberFormatException ignored) { }
            retryAfter = System.currentTimeMillis() + Math.max(1, Math.min(seconds, 300)) * 1000;
        }
        if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404) {
            unauthorized = true;
            System.err.println("[AI Image] OpenAI 检测鉴权/访问失败，HTTP " + response.statusCode()
                    + "；检查 Key 和组织的 Content Provenance API 权限后重启");
        }
        if (response.statusCode() != 200) {
            System.err.println("[AI Image] OpenAI 检测返回 HTTP " + response.statusCode());
            // 错误响应可能包含上传信息，不把响应体或带签名的图片 URL 写进日志。
            throw new IOException("OpenAI 来源检测失败，HTTP " + response.statusCode());
        }
        boolean detected = hasOpenAISignal(new String(response.body(), StandardCharsets.UTF_8));
        synchronized (cache) {
            cache.put(hash, new CacheEntry(detected));
            while (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
        }
        return detected;
    }

    private void checkAvailable() throws IOException {
        if (unauthorized) throw new IOException("OpenAI Key 或组织无接口权限，已暂停检测，检查权限后重启");
        if (System.currentTimeMillis() < retryAfter) throw new IOException("OpenAI 检测限流冷却中");
    }

    static boolean hasOpenAISignal(String body) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!"content_provenance_check".equals(string(root, "object"))
                    || !root.has("results") || !root.get("results").isJsonArray()) {
                throw new IllegalArgumentException();
            }
            for (JsonElement item : root.getAsJsonArray("results")) {
                if (!item.isJsonObject()) continue;
                JsonObject signal = item.getAsJsonObject();
                if (!"detected".equals(string(signal, "outcome"))) continue;
                if ("synthid".equals(string(signal, "type"))) return true;
                if ("c2pa".equals(string(signal, "type"))) {
                    String state = string(signal, "validation_state");
                    String issuer = string(signal, "issuer");
                    if (("trusted".equals(state) || "valid".equals(state))
                            && ("OpenAI".equalsIgnoreCase(issuer) || "OpenAI OpCo, LLC".equalsIgnoreCase(issuer))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            throw new IOException("OpenAI 来源检测返回格式异常");
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    static String imageMime(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) {
            return "image/png";
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private HttpResponse<byte[]> send(HttpRequest request, int limit, int seconds)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new LimitedBody(limit));
        try {
            return pending.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("图片验证 HTTP 请求超时");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("图片验证 HTTP 请求失败");
        }
    }

    private static final class CacheEntry {
        final boolean detected;
        final long createdAt = System.currentTimeMillis();
        CacheEntry(boolean detected) { this.detected = detected; }
    }

    /** 在接收过程中限制大小，防止无 Content-Length 的响应耗尽内存。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("响应超过大小限制"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
