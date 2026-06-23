package com.kami.qqbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务逻辑：处理群 @ 消息。
 *   报时  -> 回复 UTC+8 时间 + 时间戳
 *   logo  -> 回复 png 图片
 */
public class MessageHandler {

    private final QQApi api;
    private final Config config;
    /** msg_seq 在同一 msg_id 下需唯一，这里用全局自增保证唯一。 */
    private final AtomicInteger seqGen = new AtomicInteger(1);

    public MessageHandler(QQApi api, Config config) {
        this.api = api;
        this.config = config;
    }

    public void onEvent(String type, JsonObject d) {
        try {
            if ("GROUP_AT_MESSAGE_CREATE".equals(type)) {
                handleGroupAtMessage(d);
            }
        } catch (Exception e) {
            System.err.println("[MSG] 处理事件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleGroupAtMessage(JsonObject d) throws Exception {
        String content = d.has("content") ? d.get("content").getAsString().trim() : "";
        String groupOpenid = d.get("group_openid").getAsString();
        String msgId = d.get("id").getAsString();
        System.out.println("[MSG] 群消息 group=" + groupOpenid + " content=[" + content + "]");

        if (content.equals("报时")) {
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.ofHours(8));
            String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long timestamp = now.toInstant().getEpochSecond();
            String text = "🕗 北京时间(UTC+8)：" + formatted + "\n时间戳：" + timestamp;
            api.sendGroupText(groupOpenid, text, msgId, seqGen.getAndIncrement());

        } else if (content.equalsIgnoreCase("logo")) {
            // 1) 先上传图片拿到 file_info
            String fileInfo = api.uploadGroupMedia(groupOpenid, config.logoUrl, 1);
            // 2) 再以富媒体消息发送
            api.sendGroupMedia(groupOpenid, fileInfo, msgId, seqGen.getAndIncrement());

        } else if (content.equals("官网")) {
            JsonObject markdown = new JsonObject();
            markdown.addProperty("content", "**官网导航**\n点击下方按钮前往对应站点：");

            JsonArray rows = new JsonArray();
            rows.add(buttonRow("1", "泰坦陨落", "https://ttdm.space"));
            rows.add(buttonRow("2", "服务器文档", "https://doc.oasis.monster"));
            rows.add(buttonRow("3", "踏海｜MC开发工具", "https://tahai.xyz"));

            JsonObject keyboardContent = new JsonObject();
            keyboardContent.add("rows", rows);
            JsonObject keyboard = new JsonObject();
            keyboard.add("content", keyboardContent);

            api.sendGroupMarkdown(groupOpenid, markdown, keyboard, msgId, seqGen.getAndIncrement());
        }
    }

    /** 构造仅含一个链接按钮的按钮行。 */
    private JsonObject buttonRow(String id, String label, String url) {
        JsonObject renderData = new JsonObject();
        renderData.addProperty("label", label);
        renderData.addProperty("visited_label", label);
        renderData.addProperty("style", 1); // 1=蓝色线框

        JsonObject permission = new JsonObject();
        permission.addProperty("type", 2); // 2=所有人可点击

        JsonObject action = new JsonObject();
        action.addProperty("type", 0);     // 0=跳转按钮(链接/scheme)
        action.add("permission", permission);
        action.addProperty("data", url);
        action.addProperty("unsupport_tips", "请升级QQ客户端");

        JsonObject button = new JsonObject();
        button.addProperty("id", id);
        button.add("render_data", renderData);
        button.add("action", action);

        JsonArray buttons = new JsonArray();
        buttons.add(button);
        JsonObject row = new JsonObject();
        row.add("buttons", buttons);
        return row;
    }
}
