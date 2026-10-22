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
import java.util.concurrent.atomic.AtomicLong;
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
        return isOpenAIImage(url, new ImageCheckTrace());
    }

    boolean isOpenAIImage(String url, ImageCheckTrace trace) throws IOException, InterruptedException {
        trace.log("validate", "检查接口可用性及 QQ HTTPS 附件地址");
        checkAvailable();
        URI uri = qqImageUri(url);
        trace.log("download", "开始下载原图 host=" + uri.getHost());
        byte[] image = download(uri, trace);
        String mime = imageMime(image);
        if (mime == null) {
            trace.log("format", "不支持的原图格式 bytes=" + image.length + " outcome=unsupported 跳过上传");
            return false;
        }
        trace.log("format", "原图下载及魔数校验完成 bytes=" + image.length + " mime=" + mime);
        return verify(image, mime, trace);
    }

    private byte[] download(URI uri, ImageCheckTrace trace) throws IOException, InterruptedException {
        for (int redirects = 0; redirects <= 3; redirects++) {
            // 只读取平台附件 CDN，限制重定向目标；绝不把 OpenAI Key 带给图片服务器。
            uri = qqImageUri(uri.toString());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Accept", "image/png,image/jpeg,image/webp").GET().build();
            HttpResponse<byte[]> response = send(request, MAX_IMAGE_BYTES, 15, "qq_download", trace);
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new CheckFailure("图片重定向缺少 Location"));
                try {
                    uri = uri.resolve(location);
                } catch (IllegalArgumentException e) {
                    throw new CheckFailure("图片重定向地址无效");
                }
                trace.log("redirect", "QQ CDN 重定向 hop=" + (redirects + 1) + "；下一跳重新校验白名单");
                continue;
            }
            if (status != 200) throw new CheckFailure("下载图片失败，HTTP " + status);
            return response.body();
        }
        throw new CheckFailure("图片重定向次数过多");
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
            throw new CheckFailure("图片地址不是受支持的 QQ HTTPS 附件 CDN");
        }
    }

    boolean verify(byte[] image, String mime) throws IOException, InterruptedException {
        return verify(image, mime, new ImageCheckTrace());
    }

    private boolean verify(byte[] image, String mime, ImageCheckTrace trace) throws IOException, InterruptedException {
        if (image.length == 0 || image.length > MAX_IMAGE_BYTES || mime == null || !mime.equals(imageMime(image))) {
            throw new CheckFailure("图片为空、过大或格式不受支持");
        }
        String hash;
        try {
            hash = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(image));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        trace.log("cache", "已计算内容摘要，查询 15 分钟结果缓存（不记录摘要）");
        synchronized (cache) {
            CacheEntry entry = cache.get(hash);
            if (entry != null && System.currentTimeMillis() - entry.createdAt < CACHE_TTL_MS) {
                trace.log("cache", "命中缓存 detected=" + entry.detected + "；不调用 OpenAI");
                return entry.detected;
            }
        }
        trace.log("cache", "缓存未命中，检查权限暂停/限流冷却状态");
        checkAvailable();
        String boundary = "kami-" + UUID.randomUUID();
        String filename = "image." + (mime.equals("image/jpeg") ? "jpg" : mime.substring(6));
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        String clientRequestId = UUID.randomUUID().toString();
        trace.log("openai_prepare", "准备 multipart 原图上传 image_bytes=" + image.length
                + " multipart_bytes=" + (prefix.length + image.length + suffix.length)
                + " client_request_id=" + clientRequestId);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("X-Client-Request-Id", clientRequestId)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(prefix, image, suffix)))
                .build();
        HttpResponse<byte[]> response = send(request, 1024 * 1024, 20, "openai_check", trace);
        if (response.statusCode() == 429) {
            long seconds = 60;
            try {
                seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"));
            } catch (NumberFormatException ignored) { }
            retryAfter = System.currentTimeMillis() + Math.max(1, Math.min(seconds, 300)) * 1000;
            trace.log("cooldown", "HTTP=429 冷却秒数=" + Math.max(1, Math.min(seconds, 300)));
        }
        if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404) {
            unauthorized = true;
            trace.log("access", "HTTP=" + response.statusCode() + "；已暂停检测，请检查 Key/项目/接口访问权限后重启");
        }
        if (response.statusCode() != 200) {
            trace.log("openai_error", "HTTP=" + response.statusCode() + " " + safeError(response.body()));
            // 错误响应可能包含上传信息，不把响应体或带签名的图片 URL 写进日志。
            throw new CheckFailure("OpenAI 来源检测失败，HTTP " + response.statusCode());
        }
        trace.log("parse", "开始解析来源信号 response_bytes=" + response.body().length);
        boolean detected = hasOpenAISignal(new String(response.body(), StandardCharsets.UTF_8));
        trace.log("result", "解析完成 detected=" + detected + " " + signalSummary(response.body()));
        synchronized (cache) {
            cache.put(hash, new CacheEntry(detected));
            while (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
        }
        return detected;
    }

    private void checkAvailable() throws IOException {
        if (unauthorized) throw new CheckFailure("OpenAI Key 或组织无接口权限，已暂停检测，检查权限后重启");
        if (System.currentTimeMillis() < retryAfter) throw new CheckFailure("OpenAI 检测限流冷却中");
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
            throw new CheckFailure("OpenAI 来源检测返回格式异常");
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

    /** Only expose fixed error categories, never server error.message/param or the raw response. */
    static String safeError(byte[] body) {
        try {
            JsonObject error = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("error");
            String type = string(error, "type"), code = string(error, "code");
            List<String> allowed = List.of("invalid_request_error", "authentication_error", "permission_error",
                    "rate_limit_error", "server_error", "invalid_api_key", "insufficient_quota",
                    "rate_limit_exceeded", "account_deactivated", "unsupported_country_region_territory",
                    "model_not_found", "access_denied");
            return "error_type=" + (allowed.contains(type) ? type : "omitted")
                    + " error_code=" + (allowed.contains(code) ? code : "omitted");
        } catch (RuntimeException e) { return "error_schema=unavailable"; }
    }

    private static String signalSummary(byte[] body) {
        StringBuilder summary = new StringBuilder("signals=");
        for (JsonElement item : JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("results")) {
            if (!item.isJsonObject()) continue;
            String type = string(item.getAsJsonObject(), "type"), outcome = string(item.getAsJsonObject(), "outcome");
            if (!List.of("c2pa", "synthid").contains(type)) type = "other";
            if (!List.of("detected", "not_detected", "unsupported", "error").contains(outcome)) outcome = "other";
            if (summary.length() > 256) break;
            summary.append(type).append(':').append(outcome).append(' ');
        }
        return summary.toString().trim();
    }

    HttpResponse<byte[]> send(HttpRequest request, int limit, int seconds, String stage, ImageCheckTrace trace)
            throws IOException, InterruptedException {
        HttpProgress progress = new HttpProgress(trace, stage, seconds);
        trace.log(stage, "HTTP 开始 method=" + request.method() + " host=" + request.uri().getHost()
                + " budget_ms=" + seconds * 1000L + " request_body_bytes="
                + request.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(0L)
                + " client_request_id=" + request.headers().firstValue("X-Client-Request-Id").orElse("none")
                + "；DNS/TCP/TLS 不单独计时");
        HttpRequest traced = request.bodyPublisher().isPresent()
                ? HttpRequest.newBuilder(request, (name, value) -> true)
                        .method(request.method(), counted(request.bodyPublisher().get(), progress)).build()
                : request;
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(traced, info -> {
            progress.status = info.statusCode();
            progress.requestId = ImageCheckTrace.header(info.headers().firstValue("x-request-id").orElse(null));
            trace.log(stage, "收到响应头 HTTP=" + info.statusCode() + " http_version=" + info.version()
                    + " request_id=" + progress.requestId + " http_elapsed_ms=" + progress.elapsedMs()
                    + " content_length=" + ImageCheckTrace.header(info.headers().firstValue("Content-Length").orElse(null))
                    + " content_type=" + ImageCheckTrace.header(info.headers().firstValue("Content-Type").orElse(null))
                    + " openai_processing_ms=" + ImageCheckTrace.header(info.headers().firstValue("openai-processing-ms").orElse(null))
                    + " retry_after=" + ImageCheckTrace.header(info.headers().firstValue("Retry-After").orElse(null))
                    + " remaining_requests=" + ImageCheckTrace.header(info.headers().firstValue("x-ratelimit-remaining-requests").orElse(null)));
            return new LimitedBody(limit, progress);
        });
        long deadline = progress.started + TimeUnit.SECONDS.toNanos(seconds);
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                try {
                    HttpResponse<byte[]> response = pending.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(5)), TimeUnit.NANOSECONDS);
                    trace.log(stage, "HTTP 完成 " + progress.snapshot());
                    return response;
                } catch (TimeoutException e) {
                    if (System.nanoTime() >= deadline) throw e;
                    trace.log(stage, "HTTP 仍在等待 " + progress.snapshot());
                }
            }
        } catch (TimeoutException e) {
            pending.cancel(true);
            trace.log(stage, "HTTP 超时，已取消客户端等待 " + progress.snapshot());
            throw new CheckFailure(stage + " HTTP 请求超时；" + progress.snapshot());
        } catch (InterruptedException e) {
            pending.cancel(true);
            trace.log(stage, "HTTP 已中断 " + progress.snapshot());
            throw e;
        } catch (ExecutionException e) {
            boolean timedOut = e.getCause() instanceof java.net.http.HttpTimeoutException;
            trace.log(stage, (timedOut ? "HTTP 超时" : "HTTP 失败") + " causes=" + ImageCheckTrace.causes(e)
                    + " " + progress.snapshot());
            if (e.getCause() instanceof CheckFailure) throw (CheckFailure) e.getCause();
            throw new CheckFailure(stage + " HTTP 请求失败（" + ImageCheckTrace.causes(e) + "）；" + progress.snapshot());
        }
    }

    private static HttpRequest.BodyPublisher counted(HttpRequest.BodyPublisher body, HttpProgress progress) {
        return new HttpRequest.BodyPublisher() {
            public long contentLength() { return body.contentLength(); }
            public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
                body.subscribe(new Flow.Subscriber<ByteBuffer>() {
                    public void onSubscribe(Flow.Subscription subscription) {
                        progress.trace.log(progress.stage, "请求体开始交付给 HttpClient（不代表服务端已收到）");
                        downstream.onSubscribe(subscription);
                    }
                    public void onNext(ByteBuffer buffer) {
                        progress.uploaded.addAndGet(buffer.remaining());
                        downstream.onNext(buffer);
                    }
                    public void onError(Throwable error) { downstream.onError(error); }
                    public void onComplete() {
                        progress.uploadComplete = true;
                        progress.trace.log(progress.stage, "请求体交付完成 client_body_bytes=" + progress.uploaded.get()
                                + " http_elapsed_ms=" + progress.elapsedMs() + "；不代表服务端已接收或处理");
                        downstream.onComplete();
                    }
                });
            }
        };
    }

    private static final class HttpProgress {
        final ImageCheckTrace trace;
        final String stage;
        final int seconds;
        final long started = System.nanoTime();
        final AtomicLong uploaded = new AtomicLong();
        final AtomicLong received = new AtomicLong();
        volatile boolean uploadComplete;
        volatile int status;
        volatile String requestId = "unavailable";
        HttpProgress(ImageCheckTrace trace, String stage, int seconds) {
            this.trace = trace; this.stage = stage; this.seconds = seconds;
        }
        long elapsedMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
        String snapshot() {
            return "phase=" + (status != 0 ? "receiving_response_body" : uploadComplete ? "waiting_response_headers" : "connecting_or_publishing_body")
                    + " http_elapsed_ms=" + elapsedMs() + " budget_ms=" + seconds * 1000L
                    + " client_body_bytes=" + uploaded.get() + " response_bytes=" + received.get()
                    + " HTTP=" + (status == 0 ? "none" : status) + " request_id=" + requestId;
        }
    }

    /** 只含受控错误信息，允许写入日志；原始网络异常不输出其 URL 或请求头。 */
    static final class CheckFailure extends IOException {
        CheckFailure(String message) { super(message); }
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
        private final HttpProgress progress;
        private long lastReported;
        LimitedBody(int limit, HttpProgress progress) { this.limit = limit; this.progress = progress; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    progress.trace.log(progress.stage, "响应超过限制 limit_bytes=" + limit);
                    subscription.cancel();
                    result.completeExceptionally(new CheckFailure("响应超过大小限制"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
                long received = progress.received.addAndGet(chunk.length);
                if (lastReported == 0 || received - lastReported >= 256 * 1024) {
                    progress.trace.log(progress.stage, "接收响应体 response_bytes=" + received + " http_elapsed_ms=" + progress.elapsedMs());
                    lastReported = received;
                }
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
