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

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QQApi api;
    private final Config config;
    /** msg_seq 在同一 msg_id 下需唯一，这里用全局自增保证唯一。 */
    private final AtomicInteger seqGen = new AtomicInteger(1);

    public MessageHandler(QQApi api, Config config) {
        this.api = api;
        this.config = config;
    }

    /**
     * 分发 intent 1&lt;&lt;25(group/c2c) 下的消息事件。
     * 群里 @ 机器人对应 GROUP_AT_MESSAGE_CREATE，单聊对应 C2C_MESSAGE_CREATE，
     * 二者指令一致，差异仅在消息/文件接口路径。其余事件（加群/退群/好友变更等）暂不处理。
     */
    public void onEvent(String type, JsonObject d) {
        try {
            switch (type) {
                case "GROUP_AT_MESSAGE_CREATE","GROUP_MESSAGE_CREATE": {
                    String groupOpenid = d.get("group_openid").getAsString();
                    handleCommand(d, "群:" + groupOpenid,
                            "/v2/groups/" + groupOpenid + "/messages",
                            "/v2/groups/" + groupOpenid + "/files");
                    break;
                }
                case "C2C_MESSAGE_CREATE": {
                    String userOpenid = d.getAsJsonObject("author").get("user_openid").getAsString();
                    handleCommand(d, "单聊:" + userOpenid,
                            "/v2/users/" + userOpenid + "/messages",
                            "/v2/users/" + userOpenid + "/files");
                    break;
                }
                default:
                    break;
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
            replyTime(messagesPath, msgId);
        } else if (content.equalsIgnoreCase("logo")) {
            replyLogo(messagesPath, filesPath, msgId);
        } else if (content.equals("官网")) {
            replyHomepage(messagesPath, msgId);
        }
    }

    /** “报时”：回复 UTC+8 时间与时间戳。 */
    private void replyTime(String messagesPath, String msgId) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.ofHours(8));
        String text = "🕗 北京时间(UTC+8)：" + now.format(TIME_FMT)
                + "\n时间戳：" + now.toInstant().getEpochSecond();
        api.sendText(messagesPath, text, msgId, seqGen.getAndIncrement());
    }

    /** “logo”：先上传图片拿到 file_info，再以富媒体消息发送。 */
    private void replyLogo(String messagesPath, String filesPath, String msgId) throws Exception {
        String fileInfo = api.uploadMedia(filesPath, config.logoUrl, 1);
        api.sendMedia(messagesPath, fileInfo, msgId, seqGen.getAndIncrement());
    }

    /** “官网”：回复 Markdown + 一组链接按钮。 */
    private void replyHomepage(String messagesPath, String msgId) throws Exception {
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
