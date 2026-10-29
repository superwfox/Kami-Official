package com.kami.qqbot.BilibiliRelated;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** B 站视频链接、主页资料及动态接口客户端，复用 JDK HTTP 与已有 Gson。视频详情使用匿名 buvid 与 WBI 签名，不使用登录 Cookie。 */
public final class BilibiliApi {
    // 在消息中只提取受支持的视频地址；不吞入中文标点、QQ 隐形字符或 Markdown 闭合符。
    private static final Pattern VIDEO_LINK = Pattern.compile(
            "https://(?:b23\\.tv/[A-Za-z0-9]{1,64}"
                    + "|(?:www\\.|m\\.)?bilibili\\.com/video/(?:BV[A-Za-z0-9]{10}|av[1-9][0-9]{0,18}))"
                    + "(?=$|[/\\s\\p{Z}\\p{Cf}\\x{FFFC}?#<>)\\]，。；,:!])"
                    + "/?(?:\\?[A-Za-z0-9%_~&=+.-]*)?");
    private static final Pattern PROFILE_LINK = Pattern.compile("https://space\\.bilibili\\.com/([1-9][0-9]{0,18})(?![0-9])");
    private static final Pattern DYNAMIC_ID = Pattern.compile("[1-9][0-9]{0,31}");
    private static final Pattern VIDEO_PATH = Pattern.compile("^/video/(BV[A-Za-z0-9]{10}|av[1-9][0-9]{0,18})(?:/|$)");
    /** 公开 finger/spi 返回的匿名浏览器标识。拒绝分号等字符，避免把异常响应写进 Cookie。 */
    private static final Pattern ANON_ID = Pattern.compile("[A-Za-z0-9._+/=-]{16,256}");
    private static final Set<String> VIDEO_HOSTS = Set.of("www.bilibili.com", "m.bilibili.com", "bilibili.com");
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final String RATE_LIMIT_MESSAGE = "B 站暂时限制了请求，请稍后重试";
    private static final long SESSION_TTL_MS = Duration.ofMinutes(30).toMillis();
    private final HttpClient http;
    private final URI apiOrigin;
    private final Object sessionLock = new Object();
    private volatile String cookieHeader;
    private volatile String mixinKey;
    private volatile long sessionAt;

    public BilibiliApi() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), URI.create("https://api.bilibili.com"));
    }

    BilibiliApi(HttpClient http, URI apiOrigin) {
        this.http = http;
        this.apiOrigin = apiOrigin;
    }

    /** 入口与解析器使用相同识别规则，避免长链接被消息分发遗漏。 */
    public static boolean hasVideoLink(String content) {
        return content != null && VIDEO_LINK.matcher(content).find();
    }

    /** 读取消息中第一个视频链接；长链接直接提取 BV/AV，短链保持原有跳转流程。 */
    public BilibiliVideo parse(String content) throws IOException, InterruptedException {
        ResolvedVideo target = resolveVideo(videoLinkIn(content));
        String videoId = target.id;
        String parameter = videoId.startsWith("BV") ? "bvid=" + videoId : "aid=" + videoId.substring(2);
        JsonObject data = fetchVideoData(parameter);

        String title;
        URI cover;
        String uploaderName;
        long uploaderId;
        JsonObject statistics;
        try {
            JsonObject owner = data.getAsJsonObject("owner");
            title = requiredString(data, "title");
            cover = imageUri(requiredString(data, "pic"));
            uploaderName = requiredString(owner, "name");
            uploaderId = count(owner, "mid");
            if (uploaderId <= 0) throw new IllegalArgumentException("invalid uploader");
            statistics = data.has("stat") && data.get("stat").isJsonObject()
                    ? data.getAsJsonObject("stat") : new JsonObject();
        } catch (RuntimeException e) {
            throw new IOException("B 站返回的视频信息不完整，请稍后重试", e);
        }

        int[] size = coverSize(cover);
        String playerUrl = "https://player.bilibili.com/player.html?" + parameter + "&autoplay=1"
                + (target.page > 1 ? "&p=" + target.page : "");
        return new BilibiliVideo(title, cover.toASCIIString(), size[0], size[1], uploaderName, uploaderId,
                count(statistics, "like"), count(statistics, "reply"),
                count(statistics, "favorite"), count(statistics, "share"), playerUrl);
    }

    static URI videoLinkIn(String content) throws IOException {
        Matcher link = VIDEO_LINK.matcher(content == null ? "" : content);
        if (!link.find()) throw new IOException("未找到有效的 B 站视频链接");
        URI uri = URI.create(link.group());
        // 短链分享参数沿用原有处理方式，不带入重定向请求。
        return "b23.tv".equals(uri.getHost()) ? URI.create("https://b23.tv" + uri.getPath()) : uri;
    }

    /**
     * 先用匿名 buvid 和 WBI 签名请求 wbi/view。签名或风控失败时，再试不签名的 wbi/view，
     * 以及 view/detail 里的 View 对象。不存在、审核中等明确结果不切换接口。
     */
    JsonObject fetchVideoData(String parameter) throws IOException, InterruptedException {
        IOException last = null;
        IOException access = null;
        String signed = null;
        boolean rateLimited = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            refreshSession(attempt > 0);
            signed = videoViewUrl(parameter, true);
            try {
                return readView(signed, false);
            } catch (RetryableViewException e) {
                last = noteViewRetry(signed, e);
                rateLimited |= e instanceof LimitedException;
                if (e instanceof AccessException) access = e;
            }
        }
        String plain = api("/x/web-interface/wbi/view?" + parameter);
        if (!plain.equals(signed)) {
            try {
                return readView(plain, false);
            } catch (RetryableViewException e) {
                last = noteViewRetry(plain, e);
                rateLimited |= e instanceof LimitedException;
                if (e instanceof AccessException) access = e;
            }
        }
        String detail = api("/x/web-interface/view/detail?" + parameter);
        try {
            return readView(detail, true);
        } catch (RetryableViewException e) {
            last = noteViewRetry(detail, e);
            rateLimited |= e instanceof LimitedException;
            if (e instanceof AccessException) access = e;
        }
        markSessionStale();
        if (rateLimited) throw new LimitedException();
        if (access != null) throw access;
        if (last == null) throw new IOException("B 站视频信息获取失败，请稍后重试");
        throw last;
    }

    private static IOException noteViewRetry(String url, RetryableViewException error) {
        logViewRetry(url, error);
        return error;
    }

    private JsonObject readView(String url, boolean detail) throws IOException, InterruptedException {
        JsonObject data = getData(url, "视频");
        if (!detail) return data;
        JsonObject view = object(data, "View");
        if (view != null && view.has("title")) return view;
        if (data.has("title") && data.has("owner")) return data;
        throw new IOException("B 站返回的视频信息不完整，请稍后重试");
    }

    private String videoViewUrl(String parameter, boolean sign) {
        String plain = api("/x/web-interface/wbi/view?" + parameter);
        String mixin = mixinKey;
        if (!sign || mixin == null) return plain;
        return api("/x/web-interface/wbi/view?"
                + BilibiliWbi.signParameter(parameter, mixin, System.currentTimeMillis() / 1000L));
    }

    /** buvid 与 WBI 密钥都成功时缓存 30 分钟。nav 未登录也会返回 wbi_img，失败则退回不签名请求。 */
    private void refreshSession(boolean force) throws InterruptedException {
        if (!force && sessionFresh()) return;
        synchronized (sessionLock) {
            if (!force && sessionFresh()) return;
            String cookie = fetchAnonymousCookie();
            if (cookie != null) cookieHeader = cookie;
            String mixin = fetchMixinKey();
            if (mixin != null) mixinKey = mixin;
            if (cookieHeader != null && mixinKey != null) sessionAt = System.currentTimeMillis();
        }
    }

    private boolean sessionFresh() {
        return cookieHeader != null && mixinKey != null
                && System.currentTimeMillis() - sessionAt < SESSION_TTL_MS;
    }

    private void markSessionStale() {
        synchronized (sessionLock) {
            sessionAt = 0;
        }
    }

    /** /x/frontend/finger/spi 发放的 buvid3 是匿名浏览器标识，不是 SESSDATA 等登录 Cookie。 */
    private String fetchAnonymousCookie() throws InterruptedException {
        try {
            JsonObject data = getData(api("/x/frontend/finger/spi"), "标识");
            String buvid3 = anonymousId(data, "b_3");
            if (buvid3 == null) {
                System.err.println("[Bilibili] 匿名标识格式无效，继续尝试视频接口");
                return null;
            }
            StringBuilder cookie = new StringBuilder("buvid3=").append(buvid3);
            String buvid4 = anonymousId(data, "b_4");
            if (buvid4 != null) cookie.append("; buvid4=").append(buvid4);
            cookie.append("; b_nut=").append(System.currentTimeMillis() / 1000L);
            return cookie.toString();
        } catch (IOException e) {
            System.err.println("[Bilibili] 获取匿名标识失败: " + e.getMessage());
            return null;
        }
    }

    private static String anonymousId(JsonObject data, String key) {
        JsonElement value = data == null ? null : data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String id = value.getAsString();
        return ANON_ID.matcher(id).matches() ? id : null;
    }

    private String fetchMixinKey() throws InterruptedException {
        try {
            HttpResponse<String> response = send(request(URI.create(api("/x/web-interface/nav")), 8),
                    limitedBody(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 1024 * 1024));
            if (response.statusCode() != 200) {
                System.err.println("[Bilibili] WBI 密钥接口 HTTP " + response.statusCode());
                return null;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject wbi = object(object(json, "data"), "wbi_img");
            if (wbi == null) return null;
            return BilibiliWbi.mixinKey(BilibiliWbi.imageKey(requiredString(wbi, "img_url")),
                    BilibiliWbi.imageKey(requiredString(wbi, "sub_url")));
        } catch (IOException | RuntimeException e) {
            System.err.println("[Bilibili] 获取 WBI 密钥失败: " + e.getMessage());
            return null;
        }
    }

    private String api(String pathAndQuery) {
        String origin = apiOrigin.toASCIIString();
        if (origin.endsWith("/")) origin = origin.substring(0, origin.length() - 1);
        return origin + pathAndQuery;
    }

    private static void logViewRetry(String url, IOException error) {
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            path = "view";
        }
        System.err.println("[Bilibili] " + path + " 未获取到视频: " + error.getMessage());
    }

    /** 主页数值读取 card 接口；archive_count 是投稿视频数，不使用专栏数 article_count。 */
    public BilibiliProfile parseProfile(String content) throws IOException, InterruptedException {
        Matcher link = PROFILE_LINK.matcher(content);
        if (!link.find()) throw new IOException("未找到有效的 B 站主页链接");
        long uid;
        try {
            uid = Long.parseLong(link.group(1));
        } catch (NumberFormatException e) {
            throw new IOException("B 站主页 UID 无效", e);
        }
        refreshSession(false);
        JsonObject data = getData(api("/x/web-interface/card?mid=" + uid), "主页");
        String name;
        URI avatar;
        long followers;
        long videos;
        try {
            JsonObject card = data.getAsJsonObject("card");
            name = requiredString(card, "name");
            avatar = imageUri(requiredString(card, "face"));
            followers = count(data, "follower");
            if (followers < 0) followers = count(card, "fans");
            videos = count(data, "archive_count");
        } catch (RuntimeException e) {
            throw new IOException("B 站返回的主页信息不完整，请稍后重试", e);
        }

        String latestDynamicUrl = null;
        String dynamicError = null;
        try {
            latestDynamicUrl = latestDynamicUrl(uid);
        } catch (IOException e) {
            dynamicError = e.getMessage();
            System.err.println("[Bilibili] 获取最新动态失败: " + dynamicError);
        }
        return new BilibiliProfile(uid, name, avatar.toASCIIString(), followers, videos,
                latestDynamicUrl, dynamicError);
    }

    /** 比较发布时间，避免空间列表首项是置顶旧动态；时间缺失时按动态编号比较。 */
    private String latestDynamicUrl(long uid) throws IOException, InterruptedException {
        JsonObject data = getData(api("/x/polymer/web-dynamic/v1/feed/space?host_mid="
                + uid + "&features=itemOpusStyle"), "动态");
        if (!data.has("items") || !data.get("items").isJsonArray()) {
            throw new IOException("B 站返回的动态信息不完整，请稍后重试");
        }
        JsonArray items = data.getAsJsonArray("items");
        if (items.size() == 0) return null;
        BigInteger latestId = null;
        long latestTimestamp = -1;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonElement idElement = item.get("id_str");
            if (idElement == null || !idElement.isJsonPrimitive()) continue;
            String id = idElement.getAsString();
            if (!DYNAMIC_ID.matcher(id).matches()) continue;
            BigInteger candidateId = new BigInteger(id);
            JsonObject modules = object(item, "modules");
            long timestamp = count(object(modules, "module_author"), "pub_ts");
            boolean newer = latestId == null;
            if (latestId != null) {
                if (timestamp >= 0 && latestTimestamp >= 0 && timestamp != latestTimestamp) {
                    newer = timestamp > latestTimestamp;
                } else {
                    newer = candidateId.compareTo(latestId) > 0;
                }
            }
            if (newer) {
                latestId = candidateId;
                latestTimestamp = timestamp;
            }
        }
        if (latestId == null) throw new IOException("B 站动态列表中没有可识别的动态链接");
        return "https://t.bilibili.com/" + latestId;
    }

    private JsonObject getData(String url, String kind) throws IOException, InterruptedException {
        HttpResponse<String> response = send(request(URI.create(url), 8),
                limitedBody(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 1024 * 1024));
        int status = response.statusCode();
        if (status == 412 || status == 429 || status == 403) throw new LimitedException();
        if (status != 200) throw new UnavailableException();
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("code")) throw new IllegalArgumentException("missing code");
            int code = json.get("code").getAsInt();
            if (code != 0) {
                System.err.println("[Bilibili] " + kind + "接口 code=" + code);
                if (code == -352 || code == -412) throw new LimitedException();
                if (code == -101 || code == -403 || code == 62012) throw new AccessException(apiError(code, kind));
                throw new IOException(apiError(code, kind));
            }
            JsonObject data = object(json, "data");
            if (data == null) throw new IllegalArgumentException("missing data");
            return data;
        } catch (RetryableViewException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("B 站返回的" + kind + "信息不完整，请稍后重试", e);
        }
    }

    /** 手动跟随短链跳转，只允许 B 站域名；一旦取得视频编号就停止请求网页。 */
    private ResolvedVideo resolveVideo(URI uri) throws IOException, InterruptedException {
        for (int hop = 0; hop <= 3; hop++) {
            if (!trustedVideoUri(uri)) throw new IOException("该短链接没有指向可解析的 B 站视频");
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (VIDEO_HOSTS.contains(host)) {
                Matcher video = VIDEO_PATH.matcher(uri.getPath());
                if (video.find()) return new ResolvedVideo(video.group(1), pageNumber(uri));
                throw new IOException("该 B 站链接不是视频，暂不支持解析");
            }
            if (hop == 3) break;
            HttpResponse<Void> response = send(request(uri, 5), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 404 || status == 410) throw new IOException("B 站短链接已失效");
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                requireSuccess(status);
                throw new IOException("B 站短链接已失效或不是视频链接");
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("B 站短链接未返回视频地址"));
            try {
                uri = uri.resolve(location);
            } catch (IllegalArgumentException e) {
                throw new IOException("B 站短链接返回了无效地址", e);
            }
        }
        throw new IOException("B 站短链接跳转次数过多，暂时无法解析");
    }

    private static int pageNumber(URI uri) throws IOException {
        if (uri.getRawQuery() == null) return 1;
        for (String parameter : uri.getRawQuery().split("&")) {
            if (!parameter.startsWith("p=")) continue;
            try {
                int page = Integer.parseInt(URLDecoder.decode(parameter.substring(2), StandardCharsets.UTF_8));
                if (page > 0) return page;
            } catch (IllegalArgumentException e) {
                throw new IOException("视频分 P 编号无效", e);
            }
            throw new IOException("视频分 P 编号无效");
        }
        return 1;
    }

    private static final class ResolvedVideo {
        private final String id;
        private final int page;

        private ResolvedVideo(String id, int page) {
            this.id = id;
            this.page = page;
        }
    }

    private static boolean trustedVideoUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return "b23.tv".equals(host) || VIDEO_HOSTS.contains(host);
    }

    /** 封面及头像仅接受 B 站图片 CDN，统一使用 HTTPS。 */
    private static URI imageUri(String value) {
        URI uri = URI.create(value.startsWith("//") ? "https:" + value : value);
        String host = uri.getHost();
        if (host == null || !host.toLowerCase(java.util.Locale.ROOT).endsWith(".hdslb.com")
                || uri.getRawUserInfo() != null || uri.getPort() != -1
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("invalid image URL");
        }
        return "http".equalsIgnoreCase(uri.getScheme())
                ? URI.create("https:" + uri.toASCIIString().substring(uri.getScheme().length() + 1)) : uri;
    }

    /** QQ 图片需要尺寸：只读图片头中的宽高，不解码像素；获取失败仍可发送封面。 */
    private int[] coverSize(URI cover) throws InterruptedException {
        int[] fallback = {672, 378};
        try {
            HttpResponse<byte[]> response = send(request(cover, 4),
                    limitedBody(HttpResponse.BodyHandlers.ofByteArray(), 4 * 1024 * 1024));
            if (response.statusCode() != 200) return fallback;
            try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(
                    new ByteArrayInputStream(response.body()))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) return fallback;
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    return width > 0 && width <= 10_000 && height > 0 && height <= 10_000
                            ? new int[]{width, height} : fallback;
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    HttpRequest request(URI uri, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();
        if (sameHost(uri, apiOrigin)) {
            builder.header("Accept", "application/json, text/plain, */*");
            builder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            builder.header("Origin", "https://www.bilibili.com");
            if (cookieHeader != null) builder.header("Cookie", cookieHeader);
        }
        return builder.build();
    }

    private static boolean sameHost(URI left, URI right) {
        return left.getHost() != null && right.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        if ("https".equalsIgnoreCase(uri.getScheme())) return 443;
        if ("http".equalsIgnoreCase(uri.getScheme())) return 80;
        return -1;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        try {
            HttpResponse<T> response = http.send(request, handler);
            // 不输出查询串或响应正文；GitHub 环境下可从日志判断具体哪个接口被拒绝。
            System.out.println("[Bilibili] " + request.uri().getHost() + request.uri().getPath()
                    + " -> HTTP " + response.statusCode());
            return response;
        } catch (HttpTimeoutException e) {
            throw new IOException("B 站请求超时，请稍后重试", e);
        } catch (IOException e) {
            throw new IOException("B 站请求失败，请稍后重试", e);
        }
    }

    /** 在接收阶段限制响应大小，超限立即取消，避免整个响应已读入内存后才检查。 */
    private static <T> HttpResponse.BodyHandler<T> limitedBody(HttpResponse.BodyHandler<T> handler, int maxBytes) {
        return info -> {
            HttpResponse.BodySubscriber<T> delegate = handler.apply(info);
            return new HttpResponse.BodySubscriber<T>() {
                private Flow.Subscription subscription;
                private long received;
                private boolean done;

                @Override
                public CompletionStage<T> getBody() { return delegate.getBody(); }

                @Override
                public void onSubscribe(Flow.Subscription value) {
                    subscription = value;
                    delegate.onSubscribe(value);
                }

                @Override
                public void onNext(List<ByteBuffer> buffers) {
                    if (done) return;
                    for (ByteBuffer buffer : buffers) received += buffer.remaining();
                    if (received > maxBytes) {
                        subscription.cancel();
                        onError(new IOException("B 站响应内容过大"));
                    } else {
                        delegate.onNext(buffers);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (done) return;
                    done = true;
                    delegate.onError(error);
                }

                @Override
                public void onComplete() {
                    if (done) return;
                    done = true;
                    delegate.onComplete();
                }
            };
        };
    }

    private static void requireSuccess(int status) throws IOException {
        if (status == 412 || status == 429 || status == 403) throw new LimitedException();
        if (status != 200) throw new IOException("B 站接口暂时不可用，请稍后重试");
    }

    private static String apiError(int code, String kind) {
        switch (code) {
            case -404: case 62002: return kind + "不存在或不可见，无法解析";
            case -101: case -403: case 62012: return "该" + kind + "需要登录或没有访问权限，无法解析";
            case 62004: return "视频正在审核中，暂时无法解析";
            case -352: case -412: return RATE_LIMIT_MESSAGE;
            default: return "B 站" + kind + "信息获取失败，请稍后重试";
        }
    }

    /** 换接口或刷新匿名标识后仍可能成功的失败。最终提示仍使用原有文案。 */
    private static class RetryableViewException extends IOException {
        private RetryableViewException(String message) { super(message); }
    }

    private static final class LimitedException extends RetryableViewException {
        private LimitedException() { super(RATE_LIMIT_MESSAGE); }
    }

    private static final class AccessException extends RetryableViewException {
        private AccessException(String message) { super(message); }
    }

    private static final class UnavailableException extends RetryableViewException {
        private UnavailableException() { super("B 站接口暂时不可用，请稍后重试"); }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String requiredString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing " + key);
        }
        String value = object.get(key).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException("empty " + key);
        return value;
    }

    private static long count(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        try {
            long count = value.getAsBigDecimal().longValueExact();
            return count >= 0 ? count : -1;
        } catch (ArithmeticException | NumberFormatException e) {
            return -1;
        }
    }
}
