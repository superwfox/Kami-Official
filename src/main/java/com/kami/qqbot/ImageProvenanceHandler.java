package com.kami.qqbot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
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
        if (workers == null) return;
        String msgId = string(message, "id");
        if (msgId.isEmpty()) return;
        List<String> images = imageUrls(message);
        if (images.isEmpty()) return;
        long receivedAt = System.currentTimeMillis();
        String key = messagesPath + ":" + msgId;
        synchronized (seen) {
            seen.entrySet().removeIf(entry -> receivedAt - entry.getValue() > Duration.ofMinutes(5).toMillis());
            if (seen.containsKey(key) || seen.size() >= 1024) return;
            seen.put(key, receivedAt);
        }
        try {
            workers.execute(() -> check(images, messagesPath, msgId, receivedAt));
        } catch (RejectedExecutionException e) {
            System.err.println("[AI Image] 检测队列已满，跳过本条图片消息");
        }
    }

    void check(List<String> images, String messagesPath, String msgId, long receivedAt) {
        for (String url : images) {
            if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() - receivedAt > MAX_AGE_MS) return;
            boolean detected;
            try {
                detected = detector.isOpenAIImage(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("[AI Image] 检测未完成，跳过本图（" + e.getClass().getSimpleName() + "）");
                continue;
            }
            if (detected) {
                try {
                    sender.send(messagesPath, msgId, WARNING);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[AI Image] 检测提醒发送失败（" + e.getClass().getSimpleName() + "）");
                }
                return;
            }
        }
    }

    static List<String> imageUrls(JsonObject message) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        JsonElement attachments = message.get("attachments");
        if (attachments == null || !attachments.isJsonArray()) return new ArrayList<>();
        for (JsonElement item : attachments.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject attachment = item.getAsJsonObject();
            String url = string(attachment, "url");
            String type = string(attachment, "content_type").toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            String filename = string(attachment, "filename").toLowerCase(Locale.ROOT);
            boolean image = type.equals("image/png") || type.equals("image/jpeg") || type.equals("image/webp")
                    || ((type.isEmpty() || type.equals("file") || type.equals("application/octet-stream"))
                    && filename.matches(".*\\.(png|jpe?g|webp)"));
            if (image && !url.isBlank()) urls.add(url);
        }
        return new ArrayList<>(urls);
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
