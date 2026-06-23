package com.kami.qqbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务逻辑：处理群 @ 消息与单聊(C2C)消息，指令相同。
 *   报时  -> 回复 UTC+8 时间 + 时间戳
 *   logo  -> 回复 png 图片
 *   官网  -> 回复 Markdown + 链接按钮
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
                // 群 @ 消息
                String groupOpenid = d.get("group_openid").getAsString();
                handleCommand(d, "群:" + groupOpenid,
                        "/v2/groups/" + groupOpenid + "/messages",
                        "/v2/groups/" + groupOpenid + "/files");
            } else if ("C2C_MESSAGE_CREATE".equals(type)) {
                // 单聊消息
                String userOpenid = d.getAsJsonObject("author").get("user_openid").getAsString();
                handleCommand(d, "单聊:" + userOpenid,
                        "/v2/users/" + userOpenid + "/messages",
                        "/v2/users/" + userOpenid + "/files");
            }
        } catch (Exception e) {
            System.err.println("[MSG] 处理事件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 群与单聊共用的指令处理；差异仅在消息/文件接口路径。 */
    private void handleCommand(JsonObject d, String source, String messagesPath, String filesPath) throws Exception {
        String content = d.has("content") ? d.get("content").getAsString().trim() : "";
        String msgId = d.get("id").getAsString();
        System.out.println("[MSG] " + source + " content=[" + content + "]");

        if (content.equals("报时")) {
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.ofHours(8));
            String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long timestamp = now.toInstant().getEpochSecond();
            String text = "🕗 北京时间(UTC+8)：" + formatted + "\n时间戳：" + timestamp;
            api.sendText(messagesPath, text, msgId, seqGen.getAndIncrement());

        } else if (content.equalsIgnoreCase("logo")) {
            // 1) 先上传图片拿到 file_info
            String fileInfo = api.uploadMedia(filesPath, config.logoUrl, 1);
            // 2) 再以富媒体消息发送
            api.sendMedia(messagesPath, fileInfo, msgId, seqGen.getAndIncrement());

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

            api.sendMarkdown(messagesPath, markdown, keyboard, msgId, seqGen.getAndIncrement());
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
