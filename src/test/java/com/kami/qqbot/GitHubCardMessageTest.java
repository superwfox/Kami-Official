package com.kami.qqbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GitHubCardMessageTest {
    @ParameterizedTest
    @CsvSource({
            "GROUP_AT_MESSAGE_CREATE, https://github.com/superwfox/Kami-Official, superwfox/Kami-Official",
            "GROUP_MESSAGE_CREATE, https://github.com/superwfox/Kami-Official, superwfox/Kami-Official",
            "C2C_MESSAGE_CREATE, https://github.com/superwfox/Kami-Official, superwfox/Kami-Official",
            "GROUP_AT_MESSAGE_CREATE, https://github.com/superwfox, @superwfox",
            "GROUP_MESSAGE_CREATE, https://github.com/superwfox, @superwfox",
            "C2C_MESSAGE_CREATE, https://github.com/superwfox, @superwfox",
            "GROUP_AT_MESSAGE_CREATE, https://github.com/R2Ion/Ion\u2060\uFFFC, R2Ion/Ion",
            "GROUP_MESSAGE_CREATE, https://github.com/R2Ion/Ion\u2060\uFFFC, R2Ion/Ion",
            "C2C_MESSAGE_CREATE, https://github.com/R2Ion/Ion\u2060\uFFFC, R2Ion/Ion"
    })
    void realDispatcherSendsExactlyOneFullImageWithoutDuplicateDelivery(String event, String link, String expectedRepository) throws Exception {
        Config config = new Config(); config.assetDir="public"; config.aiImageCheckEnabled=false;
        List<String> messages=new CopyOnWriteArrayList<>(); List<Integer> sequences=new CopyOnWriteArrayList<>();
        CountDownLatch sent=new CountDownLatch(1); AtomicInteger queries=new AtomicInteger();
        boolean c2c=event.equals("C2C_MESSAGE_CREATE"); String path=c2c?"/v2/users/user":"/v2/groups/group";
        QQApi api=new QQApi(config){
            @Override public String uploadMediaData(String filesPath,byte[] data,int type){assertEquals(path+"/files",filesPath);assertEquals(1,type);return "file-"+data[0];}
            @Override public void sendMedia(String messagesPath,String fileInfo,String id,int seq){assertEquals(path+"/messages",messagesPath);assertEquals("original",id);messages.add(fileInfo);sequences.add(seq);sent.countDown();}
        };
        try(MessageHandler handler=new MessageHandler(api,config,null,target->{
            assertEquals(expectedRepository,target.label());queries.incrementAndGet();return new byte[]{1};
        })){
            JsonObject message=JsonParser.parseString("{\"id\":\"original\",\"group_openid\":\"group\",\"author\":{\"member_openid\":\"user\",\"user_openid\":\"user\"},\"content\":\"看看 " + link + "\"}").getAsJsonObject();
            handler.onEvent(event,message);handler.onEvent(event,message);
            assertTrue(sent.await(4,TimeUnit.SECONDS));assertEquals(List.of("file-1"),messages);assertEquals(1,sequences.stream().distinct().count());assertEquals(1,queries.get());
        }
    }

    @Test void failureReplyDependsOnStatusAndTheLogKeepsTheRequest() throws Exception {
        GitHubHttpException missing = new GitHubHttpException(404, "users/southstar", "GitHub 用户不存在，或没有访问权限");
        assertEquals("GitHub 用户不存在，或没有访问权限", GitHubCardHandler.failureMessage(missing));
        assertFalse(GitHubCardHandler.failureMessage(missing).contains("请稍后重试"));
        assertEquals("GitHub 卡片生成失败，请稍后重试。", GitHubCardHandler.failureMessage(new GitHubHttpException(403, "users/southstar", "limited")));
        assertEquals("GitHub 卡片生成失败，请稍后重试。", GitHubCardHandler.failureMessage(new GitHubHttpException(429, "repos/a/b", "limited")));
        assertEquals("GitHub 卡片生成失败，请稍后重试。", GitHubCardHandler.failureMessage(new GitHubHttpException(502, "users/southstar", "GitHub 请求失败，HTTP 502")));
        assertEquals("GitHub 卡片生成失败（HTTP 401）。", GitHubCardHandler.failureMessage(new GitHubHttpException(401, "users/southstar", "GitHub 请求失败，HTTP 401")));
        assertEquals("GitHub 卡片生成或发送失败，请稍后重试。", GitHubCardHandler.failureMessage(new IOException("connection reset")));
        assertFalse(GitHubCardHandler.failureMessage(new IOException("仅支持公开的 GitHub 仓库")).contains("请稍后重试"));
        Exception wrapped = new Exception("outer", missing);
        String detail = GitHubCardHandler.failureDetail("@southstar", wrapped);
        assertTrue(detail.contains("target=@southstar"));
        assertTrue(detail.contains("path=users/southstar"));
        assertTrue(detail.contains("status=404"));
        assertTrue(detail.contains("message=outer"));
        assertTrue(detail.contains("cause=GitHubHttpException"));
        assertEquals(missing.getMessage(), GitHubCardHandler.failureMessage(wrapped));

        List<String> replies = new CopyOnWriteArrayList<>();
        CountDownLatch sent = new CountDownLatch(1);
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try (GitHubCardHandler handler = new GitHubCardHandler(target -> { throw missing; }, (a, b, c, d) -> {}, (path, id, text) -> {
            replies.add(text);
            sent.countDown();
        })) {
            JsonObject message = JsonParser.parseString("{\"id\":\"m1\"}").getAsJsonObject();
            assertTrue(handler.onMessage(message, "/v2/groups/g/messages", "/v2/groups/g/files", "https://github.com/southstar"));
            assertTrue(sent.await(4, TimeUnit.SECONDS));
        } finally { System.setErr(original); }
        assertEquals(List.of("GitHub 用户不存在，或没有访问权限"), replies);
        String log = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("target=@southstar"));
        assertTrue(log.contains("path=users/southstar"));
        assertTrue(log.contains("status=404"));
        assertTrue(log.contains("message=GitHub 用户不存在，或没有访问权限"));
        assertTrue(log.contains("GitHubCardMessageTest.failureReplyDependsOnStatusAndTheLogKeepsTheRequest"));
    }
}
