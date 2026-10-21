package com.kami.qqbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GitHubCardMessageTest {
    @ParameterizedTest
    @ValueSource(strings={"GROUP_AT_MESSAGE_CREATE","GROUP_MESSAGE_CREATE","C2C_MESSAGE_CREATE"})
    void realDispatcherSendsExactlyOneFullImageWithoutDuplicateDelivery(String event) throws Exception {
        Config config = new Config(); config.assetDir="public"; config.aiImageCheckEnabled=false;
        List<String> messages=new CopyOnWriteArrayList<>(); List<Integer> sequences=new CopyOnWriteArrayList<>();
        CountDownLatch sent=new CountDownLatch(1); AtomicInteger queries=new AtomicInteger();
        boolean c2c=event.equals("C2C_MESSAGE_CREATE"); String path=c2c?"/v2/users/user":"/v2/groups/group";
        QQApi api=new QQApi(config){
            @Override public String uploadMediaData(String filesPath,byte[] data,int type){assertEquals(path+"/files",filesPath);assertEquals(1,type);return "file-"+data[0];}
            @Override public void sendMedia(String messagesPath,String fileInfo,String id,int seq){assertEquals(path+"/messages",messagesPath);assertEquals("original",id);messages.add(fileInfo);sequences.add(seq);sent.countDown();}
        };
        try(MessageHandler handler=new MessageHandler(api,config,null,repository->{
            assertEquals("superwfox/Kami-Official",repository);queries.incrementAndGet();return new byte[]{1};
        })){
            JsonObject message=JsonParser.parseString("{\"id\":\"original\",\"group_openid\":\"group\",\"author\":{\"member_openid\":\"user\",\"user_openid\":\"user\"},\"content\":\"看看 https://github.com/superwfox/Kami-Official\"}").getAsJsonObject();
            handler.onEvent(event,message);handler.onEvent(event,message);
            assertTrue(sent.await(4,TimeUnit.SECONDS));assertEquals(List.of("file-1"),messages);assertEquals(1,sequences.stream().distinct().count());assertEquals(1,queries.get());
        }
    }
}
