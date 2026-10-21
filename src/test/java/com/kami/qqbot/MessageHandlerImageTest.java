package com.kami.qqbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 穿过实际的事件分发、消息处理和 QQ 回复封装入口，避免只测试附件提取函数。 */
class MessageHandlerImageTest {
    @ParameterizedTest
    @ValueSource(strings = {"GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE"})
    void imageOnlyGroupEventReachesDetectorAndRepliesToOriginalId(String event) throws Exception {
        Config config = config();
        CountDownLatch replied = new CountDownLatch(1);
        List<String> urls = new CopyOnWriteArrayList<>();
        List<String> replies = new CopyOnWriteArrayList<>();
        QQApi api = capturingApi(config, replies, replied);
        try (MessageHandler handler = new MessageHandler(api, config, url -> {
            urls.add(url);
            return true;
        })) {
            JsonObject data = json("{\"id\":\"image-msg\",\"group_openid\":\"test-group\","
                    + "\"author\":{\"member_openid\":\"test-user\"},\"content\":null,"
                    + "\"attachments\":[{\"content_type\":\"image\",\"url\":\"https://gchat.qpic.cn/original\"}]}");
            handler.onEvent(event, data);
            assertTrue(replied.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("https://gchat.qpic.cn/original"), urls);
            assertEquals(List.of("/v2/groups/test-group/messages|image-msg|图片由OpenAI生成，请注意识别⚠️"), replies);
        }
    }

    @Test
    void c2cSingleGenericAttachmentWithoutTextReachesDetector() throws Exception {
        Config config = config();
        CountDownLatch replied = new CountDownLatch(1);
        List<String> urls = new CopyOnWriteArrayList<>();
        List<String> replies = new CopyOnWriteArrayList<>();
        try (MessageHandler handler = new MessageHandler(capturingApi(config, replies, replied), config, url -> {
            urls.add(url);
            return true;
        })) {
            handler.onEvent("C2C_MESSAGE_CREATE", json("{\"id\":\"private-msg\","
                    + "\"author\":{\"user_openid\":\"test-user\"},"
                    + "\"attachments\":{\"content_type\":\"file\",\"url\":\"https://multimedia.nt.qq.com.cn/download\"}}"));
            assertTrue(replied.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("https://multimedia.nt.qq.com.cn/download"), urls);
            assertEquals(List.of("/v2/users/test-user/messages|private-msg|图片由OpenAI生成，请注意识别⚠️"), replies);
        }
    }

    private static Config config() {
        Config config = new Config();
        config.assetDir = "public";
        return config;
    }

    private static QQApi capturingApi(Config config, List<String> replies, CountDownLatch replied) {
        return new QQApi(config) {
            @Override
            public void sendText(String path, String text, String id, int sequence) {
                replies.add(path + "|" + id + "|" + text);
                replied.countDown();
            }
        };
    }

    private static JsonObject json(String source) {
        return JsonParser.parseString(source).getAsJsonObject();
    }
}
