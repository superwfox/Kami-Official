package com.kami.qqbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImageProvenanceHandlerTest {
    @Test
    void extractsImageAttachmentsIncludingFileTypedImages() {
        JsonObject message = json("{\"attachments\":[null,{},"
                + "{\"url\":\"a\",\"content_type\":\"image/png\"},"
                + "{\"url\":\"a\",\"content_type\":\"image/png\"},"
                + "{\"url\":\"b\",\"content_type\":\"file\",\"filename\":\"photo.JPG\"},"
                + "{\"url\":\"c\",\"content_type\":\"image/webp; charset=binary\"},"
                + "{\"url\":\"d\",\"content_type\":\"image/gif\"},"
                + "{\"url\":\"e\",\"content_type\":\"application/pdf\",\"filename\":\"x.png\"}]}");
        // image/gif 也是图片候选，下载后由字节嗅探决定是否支持上传。
        assertEquals(List.of("a", "b", "c", "d"), ImageProvenanceHandler.imageUrls(message));
        assertTrue(ImageProvenanceHandler.imageUrls(json("{\"attachments\":null}")).isEmpty());
    }

    @Test
    void acceptsGenericImageTypesAndSniffsUnknownMediaInsteadOfDroppingIt() {
        JsonObject message = json("{\"attachments\":["
                + "{\"url\":\"https://gchat.qpic.cn/a\",\"content_type\":\"image\"},"
                + "{\"url\":\"https://gchat.qpic.cn/b\",\"content_type\":\"image/jpg\"},"
                + "{\"url\":\"https://gchat.qpic.cn/c.png?signature=secret\"},"
                + "{\"url\":\"https://multimedia.nt.qq.com.cn/download?file=opaque\",\"content_type\":\"file\"},"
                + "{\"url\":\"https://gchat.qpic.cn/document.pdf\",\"content_type\":\"file\"},"
                + "{\"url\":\"https://gchat.qpic.cn/video\",\"content_type\":\"video/mp4\"}]}");
        assertEquals(List.of("https://gchat.qpic.cn/a", "https://gchat.qpic.cn/b",
                        "https://gchat.qpic.cn/c.png?signature=secret", "https://multimedia.nt.qq.com.cn/download?file=opaque"),
                ImageProvenanceHandler.imageUrls(message));
    }

    @Test
    void acceptsSingleAttachmentObjectAndDiagnosticsDoNotExposeUrlsOrFilenames() {
        JsonObject message = json("{\"attachments\":{\"url\":\"https://gchat.qpic.cn/image?token=secret\","
                + "\"content_type\":\"image\",\"filename\":\"private-file.png\"}}");
        assertEquals(1, ImageProvenanceHandler.imageUrls(message).size());
        String summary = ImageProvenanceHandler.attachmentSummary(message);
        assertEquals("attachments=1 types=[image] urls=1", summary);
        assertFalse(summary.contains("secret"));
        assertFalse(summary.contains("private-file"));
        assertFalse(ImageProvenanceHandler.attachmentSummary(json("{\"attachments\":{\"content_type\":\"image\\n🔐\"}}"))
                .contains("\n"));
    }

    @Test
    void onlyControlledErrorsCanExposeReasonsInLogs() {
        assertEquals("下载图片失败，HTTP 403", ImageProvenanceHandler.failureReason(
                new ImageProvenanceApi.CheckFailure("下载图片失败，HTTP 403")));
        assertEquals("IllegalArgumentException", ImageProvenanceHandler.failureReason(
                new IllegalArgumentException("bad secret key or signed URL")));
    }

    @Test
    void checksInBackgroundAndRepliesOnceToOriginalMessage() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch replied = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        List<String> replies = new CopyOnWriteArrayList<>();
        try (ImageProvenanceHandler handler = new ImageProvenanceHandler(url -> {
            checks.incrementAndGet();
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return true;
        }, (path, id, referenceId, text) -> {
            replies.add(path + "|" + id + "|" + text);
            replied.countDown();
        })) {
            JsonObject message = message("original", "a", "b");
            handler.onMessage(message, "/v2/groups/group/messages");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            handler.onMessage(message, "/v2/groups/group/messages"); // 网关重复投递。
            assertTrue(replies.isEmpty());
            release.countDown();
            assertTrue(replied.await(3, TimeUnit.SECONDS));
            assertEquals(1, checks.get());
            assertEquals(List.of("/v2/groups/group/messages|original|图片由AI生成，请注意识别⚠️"), replies);
        } finally {
            release.countDown();
        }
    }

    @Test
    void oneFailedImageDoesNotPreventCheckingOtherImages() throws Exception {
        CountDownLatch replied = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        try (ImageProvenanceHandler handler = new ImageProvenanceHandler(url -> {
            checks.incrementAndGet();
            if (url.equals("bad")) throw new IOException("temporary failure");
            return true;
        }, (path, id, referenceId, text) -> replied.countDown())) {
            handler.onMessage(message("id", "bad", "good"), "/v2/users/user/messages");
            assertTrue(replied.await(3, TimeUnit.SECONDS));
            assertEquals(2, checks.get());
        }
    }

    @Test
    void negativeAndErrorResultsRemainSilentInC2c() throws Exception {
        AtomicInteger replies = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        try (ImageProvenanceHandler handler = new ImageProvenanceHandler(url -> {
            checks.incrementAndGet();
            if (url.equals("error")) throw new IOException();
            return false;
        }, (path, id, referenceId, text) -> replies.incrementAndGet())) {
            handler.check(List.of("negative", "error"), "/v2/users/user/messages", "id", "id", System.currentTimeMillis());
            assertEquals(2, checks.get());
            assertEquals(0, replies.get());
        }
    }

    @Test
    void expiredMessagesAreNotDownloadedOrRepliedTo() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger replies = new AtomicInteger();
        try (ImageProvenanceHandler handler = new ImageProvenanceHandler(url -> {
            checks.incrementAndGet();
            return true;
        }, (path, id, referenceId, text) -> replies.incrementAndGet())) {
            handler.check(List.of("a"), "/v2/groups/group/messages", "id", "id", 0);
            assertEquals(0, checks.get());
            assertEquals(0, replies.get());
        }
    }

    @Test
    void noKeyDisablesOnlyImageFeature() {
        Config config = new Config();
        AtomicInteger replies = new AtomicInteger();
        try (ImageProvenanceHandler handler = ImageProvenanceHandler.create(config,
                (path, id, referenceId, text) -> replies.incrementAndGet())) {
            handler.onMessage(message("id", "a"), "/v2/groups/group/messages");
            assertEquals(0, replies.get());
        }
    }

    private static JsonObject message(String id, String... urls) {
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        com.google.gson.JsonArray attachments = new com.google.gson.JsonArray();
        for (String url : urls) {
            JsonObject attachment = new JsonObject();
            attachment.addProperty("url", url);
            attachment.addProperty("content_type", "image/png");
            attachments.add(attachment);
        }
        message.add("attachments", attachments);
        return message;
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
