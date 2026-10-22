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
    static final String WARNING = "图片由AI生成，请注意识别⚠️";
    private static final long MAX_AGE_MS = Duration.ofMinutes(3).toMillis();
    private final Detector detector;
    private final WarningSender sender;
    private final ThreadPoolExecutor workers;
    private final Map<String, Long> seen = new LinkedHashMap<>();

    interface Detector {
        boolean isOpenAIImage(String url) throws Exception;
        default boolean isOpenAIImage(String url, ImageCheckTrace trace) throws Exception { return isOpenAIImage(url); }
    }

    interface WarningSender {
        void send(String messagesPath, String msgId, String referenceId, String text) throws Exception;
    }

    static ImageProvenanceHandler create(Config config, WarningSender sender) {
        if (!config.aiImageCheckEnabled || config.openaiApiKey == null || config.openaiApiKey.isBlank()) {
            System.out.println("[AI Image] 检测未启用：请开启 aiImageCheckEnabled 并设置 OPENAI_API_KEY");
            return new ImageProvenanceHandler(null, sender);
        }
        ImageProvenanceApi api = new ImageProvenanceApi(config.openaiApiKey.trim());
        System.out.println("[AI Image] OpenAI 图片来源检测已启用 connect_timeout_s=5 download_budget_s=15 openai_budget_s=20 workers=2 queue_capacity=8 progress_interval_s=5");
        return new ImageProvenanceHandler(new Detector() {
            public boolean isOpenAIImage(String url) throws Exception { return api.isOpenAIImage(url); }
            public boolean isOpenAIImage(String url, ImageCheckTrace trace) throws Exception {
                return api.isOpenAIImage(url, trace);
            }
        }, sender);
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
        ImageCheckTrace trace = new ImageCheckTrace();
        List<String> images = imageUrls(message);
        trace.log("received", attachmentSummary(message) + " imageCandidates=" + images.size());
        if (workers == null) {
            trace.log("skip", "检测未启用，请检查 OPENAI_API_KEY / aiImageCheckEnabled");
            return;
        }
        if (images.isEmpty()) {
            trace.log("skip", "没有可下载的图片候选");
            return;
        }
        long receivedAt = System.currentTimeMillis();
        String key = messagesPath + ":" + msgId;
        synchronized (seen) {
            seen.entrySet().removeIf(entry -> receivedAt - entry.getValue() > Duration.ofMinutes(5).toMillis());
            if (seen.containsKey(key) || seen.size() >= 1024) {
                trace.log("skip", "重复消息或已达消息去重上限");
                return;
            }
            seen.put(key, receivedAt);
        }
        try {
            String referenceId = referenceId(message);
            trace.log("queue", "提交后台检测 images=" + images.size() + " active_workers=" + workers.getActiveCount()
                    + " queued=" + workers.getQueue().size() + " queue_capacity=8 workers=2");
            workers.execute(() -> check(images, messagesPath, msgId, referenceId, receivedAt, trace));
        } catch (RejectedExecutionException e) {
            trace.log("rejected", "检测队列已满或已关闭，跳过本条消息 active_workers=" + workers.getActiveCount()
                    + " queued=" + workers.getQueue().size());
        }
    }

    void check(List<String> images, String messagesPath, String msgId, String referenceId, long receivedAt) {
        check(images, messagesPath, msgId, referenceId, receivedAt, new ImageCheckTrace());
    }

    private void check(List<String> images, String messagesPath, String msgId, String referenceId,
                       long receivedAt, ImageCheckTrace trace) {
        int checked = 0, failed = 0;
        String outcome = "no_supported_signal";
        trace.log("worker", "开始处理 queue_wait_ms=" + trace.elapsedMs() + " images=" + images.size()
                + " age_ms=" + Math.max(0, System.currentTimeMillis() - receivedAt));
        try {
            for (int index = 0; index < images.size(); index++) {
                if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() - receivedAt > MAX_AGE_MS) {
                    outcome = "expired_or_interrupted";
                    trace.log("skip", "任务已过期或被中断 image=" + (index + 1) + "/" + images.size());
                    return;
                }
                trace.log("image", "开始处理 image=" + (index + 1) + "/" + images.size());
                boolean detected;
                try {
                    detected = detector.isOpenAIImage(images.get(index), trace);
                    checked++;
                } catch (InterruptedException e) {
                    outcome = "interrupted";
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    failed++;
                    trace.log("image_failed", "检测未完成 image=" + (index + 1) + "/" + images.size()
                            + " reason=" + failureReason(e) + "；跳过本图，不把失败视为未检出");
                    continue;
                }
                if (detected) {
                    outcome = "detected_reply_pending";
                    trace.log("reply", "已确认来源信号，开始引用原消息回复");
                    long replyStarted = System.nanoTime();
                    try {
                        sender.send(messagesPath, msgId, referenceId, WARNING);
                        outcome = "detected_reply_sent";
                        trace.log("reply", "引用回复完成 reply_elapsed_ms="
                                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - replyStarted));
                    } catch (InterruptedException e) {
                        outcome = "reply_interrupted";
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        outcome = "reply_failed";
                        trace.log("reply_failed", "causes=" + ImageCheckTrace.causes(e));
                    }
                    return;
                }
                trace.log("image_result", "本图未检出受支持的来源信号，不发送提醒");
            }
            if (failed > 0) outcome = checked == 0 ? "all_checks_failed" : "partial_checks_failed";
        } finally {
            trace.log("finished", "outcome=" + outcome + " checked=" + checked + " failed=" + failed
                    + " total_images=" + images.size());
        }
    }

    /** 引用当前消息自身的索引，不能使用 ref_msg_idx（它指向用户引用的旧消息）。 */
    static String referenceId(JsonObject message) {
        JsonElement scene = message.get("message_scene");
        if (scene != null && scene.isJsonObject()) {
            JsonElement ext = scene.getAsJsonObject().get("ext");
            if (ext != null && ext.isJsonArray()) {
                for (JsonElement item : ext.getAsJsonArray()) {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) continue;
                    String value = item.getAsString();
                    String index = value.startsWith("msg_idx=") ? value.substring(8).trim()
                            : value.startsWith("msgIdx:") ? value.substring(7).trim() : "";
                    if (!index.isEmpty()) return index;
                }
            }
        }
        // 官方 Python SDK 的 reply_to 使用原消息 ID；没有索引时仍显式携带引用字段。
        return string(message, "id");
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
