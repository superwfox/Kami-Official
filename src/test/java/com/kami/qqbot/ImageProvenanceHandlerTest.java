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
        assertEquals(List.of("a", "b", "c"), ImageProvenanceHandler.imageUrls(message));
        assertTrue(ImageProvenanceHandler.imageUrls(json("{\"attachments\":null}")).isEmpty());
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
        }, (path, id, text) -> {
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
            assertEquals(List.of("/v2/groups/group/messages|original|图片由OpenAI生成，请注意识别⚠️"), replies);
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
        }, (path, id, text) -> replied.countDown())) {
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
        }, (path, id, text) -> replies.incrementAndGet())) {
            handler.check(List.of("negative", "error"), "/v2/users/user/messages", "id", System.currentTimeMillis());
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
        }, (path, id, text) -> replies.incrementAndGet())) {
            handler.check(List.of("a"), "/v2/groups/group/messages", "id", 0);
            assertEquals(0, checks.get());
            assertEquals(0, replies.get());
        }
    }

    @Test
    void noKeyDisablesOnlyImageFeature() {
        Config config = new Config();
        AtomicInteger replies = new AtomicInteger();
        try (ImageProvenanceHandler handler = ImageProvenanceHandler.create(config,
                (path, id, text) -> replies.incrementAndGet())) {
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
