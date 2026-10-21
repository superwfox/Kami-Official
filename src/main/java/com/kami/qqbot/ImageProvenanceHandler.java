package com.kami.qqbot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 收到图片后后台检查；仅确认来源时被动回复，同一条消息只提示一次。 */
final class ImageProvenanceHandler implements AutoCloseable {
    static final String WARNING = "图片由OpenAI生成，请注意识别⚠️";
    private static final long MAX_AGE_MS = Duration.ofMinutes(3).toMillis();
    private final Detector detector;
    private final WarningSender sender;
    private final ThreadPoolExecutor workers;
    private final Map<String, Long> seen = new LinkedHashMap<>();

    interface Detector {
        boolean isOpenAIImage(String url) throws Exception;
    }

    interface WarningSender {
        void send(String messagesPath, String msgId, String text) throws Exception;
    }

    static ImageProvenanceHandler create(Config config, WarningSender sender) {
        if (!config.aiImageCheckEnabled || config.openaiApiKey == null || config.openaiApiKey.isBlank()) {
            System.out.println("[AI Image] 检测未启用：请开启 aiImageCheckEnabled 并设置 OPENAI_API_KEY");
            return new ImageProvenanceHandler(null, sender);
        }
        ImageProvenanceApi api = new ImageProvenanceApi(config.openaiApiKey.trim());
        System.out.println("[AI Image] OpenAI 图片来源检测已启用");
        return new ImageProvenanceHandler(api::isOpenAIImage, sender);
    }

    ImageProvenanceHandler(Detector detector, WarningSender sender) {
        this.detector = detector;
        this.sender = sender;
        workers = detector == null ? null : new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), task -> {
                    Thread thread = new Thread(task, "ai-image-check");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    void onMessage(JsonObject message, String messagesPath) {
        int attachmentCount = attachments(message).size();
        if (attachmentCount == 0) return;
        String msgId = string(message, "id");
        if (msgId.isEmpty()) {
            System.err.println("[AI Image] 收到附件但缺少消息 ID，无法回复");
            return;
        }
        List<String> images = imageUrls(message);
        System.out.println("[AI Image] " + attachmentSummary(message) + " imageCandidates=" + images.size());
        if (workers == null) {
            System.out.println("[AI Image] 跳过附件：检测未启用，请检查 OPENAI_API_KEY / aiImageCheckEnabled");
            return;
        }
        if (images.isEmpty()) {
            System.out.println("[AI Image] 跳过附件：没有可下载的图片候选");
            return;
        }
        long receivedAt = System.currentTimeMillis();
        String key = messagesPath + ":" + msgId;
        synchronized (seen) {
            seen.entrySet().removeIf(entry -> receivedAt - entry.getValue() > Duration.ofMinutes(5).toMillis());
            if (seen.containsKey(key) || seen.size() >= 1024) {
                System.out.println("[AI Image] 跳过重复消息或已达消息去重上限");
                return;
            }
            seen.put(key, receivedAt);
        }
        try {
            workers.execute(() -> check(images, messagesPath, msgId, receivedAt));
            System.out.println("[AI Image] 已提交后台检测，images=" + images.size());
        } catch (RejectedExecutionException e) {
            System.err.println("[AI Image] 检测队列已满，跳过本条图片消息");
        }
    }

    void check(List<String> images, String messagesPath, String msgId, long receivedAt) {
        for (String url : images) {
            if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() - receivedAt > MAX_AGE_MS) {
                System.out.println("[AI Image] 跳过过期或已中断的图片任务");
                return;
            }
            boolean detected;
            try {
                detected = detector.isOpenAIImage(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("[AI Image] 检测未完成，跳过本图：" + failureReason(e));
                continue;
            }
            if (detected) {
                try {
                    sender.send(messagesPath, msgId, WARNING);
                    System.out.println("[AI Image] 检测到 OpenAI 来源，已回复原消息");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[AI Image] 检测提醒发送失败（" + e.getClass().getSimpleName() + "）");
                }
                return;
            }
            System.out.println("[AI Image] 本图未检出受支持的 OpenAI 来源信号，不发送提醒");
        }
    }

    static List<String> imageUrls(JsonObject message) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (JsonObject attachment : attachments(message)) {
            String url = string(attachment, "url");
            String type = string(attachment, "content_type").toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            String filename = string(attachment, "filename").toLowerCase(Locale.ROOT);
            boolean generic = type.isEmpty() || type.equals("file") || type.equals("application/octet-stream");
            // QQ 可能只标 image，也可能缺少类型/文件名。候选下载后仍以原始字节验证格式。
            boolean image = type.equals("image") || type.startsWith("image/")
                    || (generic && (filename.matches(".*\\.(png|jpe?g|webp)")
                    || (!filename.contains(".") && !hasNonImageExtension(url))));
            if (image && !url.isBlank()) urls.add(url);
        }
        return new ArrayList<>(urls);
    }

    private static boolean hasNonImageExtension(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            if (path == null) return false;
            String last = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            return last.contains(".") && !last.matches(".*\\.(png|jpe?g|webp)");
        } catch (IllegalArgumentException e) {
            return false; // 下载器会拒绝无效 URL。
        }
    }

    /** 兼容标准附件数组，以及部分回调/适配层给出的单个附件对象。 */
    private static List<JsonObject> attachments(JsonObject message) {
        List<JsonObject> result = new ArrayList<>();
        JsonElement value = message.get("attachments");
        if (value == null || value.isJsonNull()) return result;
        if (value.isJsonObject()) result.add(value.getAsJsonObject());
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonObject()) result.add(item.getAsJsonObject());
            }
        }
        return result;
    }

    /** 只记录类型及 URL 是否存在；不输出文件名、原图 URL 或其签名参数。 */
    static String attachmentSummary(JsonObject message) {
        List<JsonObject> items = attachments(message);
        List<String> types = new ArrayList<>();
        int urls = 0;
        for (JsonObject item : items) {
            String type = string(item, "content_type");
            String safeType = type.replaceAll("[^A-Za-z0-9/_.+-]", "_");
            types.add(type.isEmpty() ? "missing" : safeType.substring(0, Math.min(safeType.length(), 64)));
            if (!string(item, "url").isBlank()) urls++;
        }
        return "attachments=" + items.size() + " types=" + types + " urls=" + urls;
    }

    static String failureReason(Exception error) {
        return error instanceof ImageProvenanceApi.CheckFailure
                ? error.getMessage() : error.getClass().getSimpleName();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    public void close() {
        if (workers != null) workers.shutdownNow();
    }
}
