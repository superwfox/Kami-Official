package com.kami.qqbot;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Fetch and render off the WebSocket thread; duplicate delivery never produces duplicate cards. */
final class GitHubCardHandler implements AutoCloseable {
    interface Preview { byte[] render(GitHubApi.Target target) throws Exception; }
    interface Sender { void send(String messagesPath, String filesPath, String msgId, byte[] png) throws Exception; }
    interface ErrorSender { void send(String messagesPath, String msgId, String message) throws Exception; }
    private final Preview preview;
    private final Sender sender;
    private final ErrorSender errors;
    private final ThreadPoolExecutor workers;
    private final Map<String, Long> seen = new LinkedHashMap<>();

    static GitHubCardHandler create(Config config, Sender sender, ErrorSender errors) {
        if (!config.githubCardEnabled) return new GitHubCardHandler(null, sender, errors);
        GitHubApi api = new GitHubApi(config.githubToken);
        GitHubCardRenderer renderer = new GitHubCardRenderer(api);
        return new GitHubCardHandler(target -> target.user()
                ? renderer.render(api.fetchUser(target.login), config.renderScale)
                : renderer.render(api.fetch(target.repository), config.renderScale), sender, errors);
    }
    GitHubCardHandler(Preview preview, Sender sender, ErrorSender errors) {
        this.preview = preview; this.sender = sender; this.errors = errors;
        workers = preview == null ? null : new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread thread = new Thread(task, "github-repository-card"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    boolean onMessage(JsonObject message, String messagesPath, String filesPath, String content) {
        GitHubApi.Target target = GitHubApi.targetIn(content);
        if (workers == null || target == null) return false;
        if (!message.has("id") || message.get("id").isJsonNull()) return true;
        String msgId = message.get("id").getAsString(), key = messagesPath + ":" + msgId;
        synchronized (seen) {
            long now = System.currentTimeMillis();
            seen.entrySet().removeIf(entry -> now - entry.getValue() > Duration.ofMinutes(5).toMillis());
            if (seen.containsKey(key)) return true;
            if (seen.size() >= 1024) seen.remove(seen.keySet().iterator().next());
            seen.put(key, now);
        }
        try {
            workers.execute(() -> {
                try {
                    byte[] png = preview.render(target);
                    if (!Thread.currentThread().isInterrupted()) sender.send(messagesPath, filesPath, msgId, png);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) {
                    System.err.println(failureDetail(target.label(), e));
                    e.printStackTrace(System.err);
                    report(messagesPath, msgId, failureMessage(e));
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (seen) { seen.remove(key); }
            report(messagesPath, msgId, "GitHub 卡片正在排队，请稍后重试。");
        }
        return true;
    }
    static String failureDetail(String target, Throwable error) {
        GitHubHttpException http = httpFailure(error);
        StringBuilder line = new StringBuilder("[GitHub] 卡片处理失败")
                .append(" target=").append(target)
                .append(" path=").append(http == null ? "-" : http.path)
                .append(" status=").append(http == null ? "-" : Integer.toString(http.status))
                .append(" message=").append(String.valueOf(error.getMessage()));
        Throwable cause = error.getCause();
        if (cause != null) {
            line.append(" cause=").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) line.append(": ").append(cause.getMessage());
        }
        return line.toString();
    }

    /** 404 is a missing user or repository. Only rate limits, server errors and network failures ask for a retry. */
    static String failureMessage(Throwable error) {
        GitHubHttpException http = httpFailure(error);
        if (http != null) {
            if (http.status == 403 || http.status == 429 || http.status >= 500) return "GitHub 卡片生成失败，请稍后重试。";
            if (http.status == 404) return http.getMessage();
            return "GitHub 卡片生成失败（HTTP " + http.status + "）。";
        }
        if (retryableIo(error)) return "GitHub 卡片生成或发送失败，请稍后重试。";
        String detail = error.getMessage();
        if (detail == null || detail.isBlank()) return "GitHub 卡片生成失败。";
        return "GitHub 卡片生成失败：" + detail;
    }

    private static GitHubHttpException httpFailure(Throwable error) {
        for (int i = 0; error != null && i < 8; i++, error = error.getCause()) {
            if (error instanceof GitHubHttpException) return (GitHubHttpException) error;
        }
        return null;
    }

    private static boolean retryableIo(Throwable error) {
        boolean io = false;
        for (int i = 0; error != null && i < 8; i++, error = error.getCause()) {
            if (error instanceof GitHubHttpException) return false;
            if (error instanceof java.io.IOException) {
                String message = error.getMessage();
                if ("无效的 GitHub 仓库名称".equals(message) || "无效的 GitHub 用户名".equals(message)
                        || "仅支持公开的 GitHub 仓库".equals(message) || "GitHub 返回了无法解析的数据".equals(message)) return false;
                io = true;
            }
        }
        return io;
    }

    private void report(String path, String id, String message) {
        try { errors.send(path, id, message); }
        catch (Exception e) { System.err.println("[GitHub] 错误提示发送失败：" + e.getClass().getSimpleName()); }
    }
    public void close() { if (workers != null) workers.shutdownNow(); }
}
