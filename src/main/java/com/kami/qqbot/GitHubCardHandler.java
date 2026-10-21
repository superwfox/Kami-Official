package com.kami.qqbot;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Fetch and render off the WebSocket thread; duplicate delivery never produces duplicate cards. */
final class GitHubCardHandler implements AutoCloseable {
    interface Preview { byte[] render(String repository) throws Exception; }
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
        return new GitHubCardHandler(repository -> renderer.render(api.fetch(repository), config.renderScale), sender, errors);
    }
    GitHubCardHandler(Preview preview, Sender sender, ErrorSender errors) {
        this.preview = preview; this.sender = sender; this.errors = errors;
        workers = preview == null ? null : new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread thread = new Thread(task, "github-repository-card"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    boolean onMessage(JsonObject message, String messagesPath, String filesPath, String content) {
        String repository = GitHubApi.repositoryIn(content);
        if (workers == null || repository == null) return false;
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
                    byte[] png = preview.render(repository);
                    if (!Thread.currentThread().isInterrupted()) sender.send(messagesPath, filesPath, msgId, png);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) {
                    System.err.println("[GitHub] 仓库卡片处理失败：" + e.getClass().getSimpleName());
                    report(messagesPath, msgId, "GitHub 仓库卡片生成或发送失败，请稍后重试。");
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (seen) { seen.remove(key); }
            report(messagesPath, msgId, "GitHub 仓库卡片正在排队，请稍后重试。");
        }
        return true;
    }
    private void report(String path, String id, String message) {
        try { errors.send(path, id, message); }
        catch (Exception e) { System.err.println("[GitHub] 错误提示发送失败：" + e.getClass().getSimpleName()); }
    }
    public void close() { if (workers != null) workers.shutdownNow(); }
}
