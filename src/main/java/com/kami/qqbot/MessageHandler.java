package com.kami.qqbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务逻辑：处理群 @ 消息与单聊(C2C)消息，指令相同。
 *   报时             -> 回复 UTC+8 时间 + 时间戳
 *   logo             -> 回复 png 图片
 *   官网             -> 回复 Markdown + 链接按钮
 *   查查 &lt;玩家名&gt;    -> 战绩总览卡
 *   ttdm &lt;玩家名&gt;   -> 最近一局 TDM 对局卡
 *   att &lt;玩家名&gt;    -> 最近一局 ATT 对局卡
 *   &lt;泰坦中文&gt;排行榜 -> 该泰坦命均/时长榜
 * 群消息会 @ 发送者（单聊不 @）。
 */
public class MessageHandler {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QQApi api;
    private final Config config;
    private final TTDMApi ttdm;
    /** msg_seq 在同一 msg_id 下需唯一，这里用全局自增保证唯一。 */
    private final AtomicInteger seqGen = new AtomicInteger(1);

    public MessageHandler(QQApi api, Config config) {
        this.api = api;
        this.config = config;
        this.ttdm = new TTDMApi(config);
        CardRenderer.loadFont(config.assetDir);
    }

    /**
     * 分发 intent 1&lt;&lt;25(group/c2c) 下的消息事件。
     * 群里 @ 机器人对应 GROUP_AT_MESSAGE_CREATE，单聊对应 C2C_MESSAGE_CREATE，
     * 二者指令一致，差异仅在消息/文件接口路径与是否 @ 发送者。
     */
    public void onEvent(String type, JsonObject d) {
        try {
            switch (type) {
                case "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE": {
                    String groupOpenid = d.get("group_openid").getAsString();
                    handleCommand(d, "群:" + groupOpenid,
                            "/v2/groups/" + groupOpenid + "/messages",
                            "/v2/groups/" + groupOpenid + "/files", memberOpenid(d));
                    break;
                }
                case "C2C_MESSAGE_CREATE": {
                    String userOpenid = d.getAsJsonObject("author").get("user_openid").getAsString();
                    handleCommand(d, "单聊:" + userOpenid,
                            "/v2/users/" + userOpenid + "/messages",
                            "/v2/users/" + userOpenid + "/files", null);
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

    /** 群事件里取发送者 member_openid（用于 @）；缺失返回 null。 */
    private static String memberOpenid(JsonObject d) {
        if (d.has("author") && d.get("author").isJsonObject()) {
            JsonObject author = d.getAsJsonObject("author");
            if (author.has("member_openid") && !author.get("member_openid").isJsonNull()) {
                return author.get("member_openid").getAsString();
            }
        }
        return null;
    }

    /** 群与单聊共用的指令处理；差异仅在消息/文件接口路径与 @ 目标。 */
    private void handleCommand(JsonObject d, String source, String messagesPath, String filesPath,
                               String atOpenid) throws Exception {
        String content = d.has("content") ? d.get("content").getAsString().trim() : "";
        String msgId = d.get("id").getAsString();
        System.out.println("[MSG] " + source + " content=[" + content + "]");

        // 原有静态指令
        if (content.equals("报时")) {
            replyTime(messagesPath, msgId);
            return;
        }
        if (content.equalsIgnoreCase("logo")) {
            replyLogo(messagesPath, filesPath, msgId);
            return;
        }
        if (content.equals("官网")) {
            replyHomepage(messagesPath, msgId);
            return;
        }
        if (content.equals("命令大全") || content.equals("命令")
                || content.equals("帮助") || content.equalsIgnoreCase("help")) {
            replyHelp(messagesPath, msgId);
            return;
        }

        // 战绩查询（渲染卡片 + @ 发送者）
        String lower = content.toLowerCase();
        if (content.startsWith("查查")) {
            String name = content.substring("查查".length()).trim();
            if (name.isEmpty()) {
                trySendError(messagesPath, atOpenid, msgId, "用法：查查 <玩家名>");
            } else {
                replySummary(messagesPath, filesPath, atOpenid, msgId, name);
            }
            return;
        }
        if (lower.equals("ttdm") || lower.startsWith("ttdm ")) {
            replyMatchByName(messagesPath, filesPath, atOpenid, msgId, content.substring(4).trim(), false);
            return;
        }
        if (lower.equals("att") || lower.startsWith("att ")) {
            replyMatchByName(messagesPath, filesPath, atOpenid, msgId, content.substring(3).trim(), true);
            return;
        }
        if (content.endsWith("排行榜")) {
            String cn = content.substring(0, content.length() - "排行榜".length()).trim();
            replyLeaderboard(messagesPath, filesPath, atOpenid, msgId, cn);
            return;
        }
        // 其它内容忽略
    }

    // ── 战绩查询指令 ───────────────────────────────────────────────

    /** “查查 名”：战绩总览卡。 */
    private void replySummary(String messagesPath, String filesPath, String atOpenid, String msgId, String name) {
        try {
            JsonObject data = ttdm.query(name, 0);
            JsonArray matches = arr(data, "matches");
            if (matches == null || matches.size() == 0) {
                trySendError(messagesPath, atOpenid, msgId, "未找到玩家「" + name + "」的对局记录");
                return;
            }
            JsonObject markdown = new JsonObject();
            markdown.addProperty("content", CardRenderer.summaryMarkdown(matches, actualName(data, name)));
            api.sendMarkdown(messagesPath, markdown, ttdmKeyboard(), msgId, seqGen.getAndIncrement());
        } catch (Exception e) {
            fail(messagesPath, atOpenid, msgId, "查询失败", e);
        }
    }

    /** “ttdm 名 / att 名”：最近一局对应模式的对局卡。 */
    private void replyMatchByName(String messagesPath, String filesPath, String atOpenid, String msgId,
                                  String name, boolean att) {
        if (name.isEmpty()) {
            trySendError(messagesPath, atOpenid, msgId, "用法：" + (att ? "att" : "ttdm") + " <玩家名>");
            return;
        }
        try {
            JsonObject data = ttdm.query(name, 0);
            JsonArray matches = arr(data, "matches");
            if (matches == null || matches.size() == 0) {
                trySendError(messagesPath, atOpenid, msgId, "未找到玩家「" + name + "」的对局记录");
                return;
            }
            JsonObject match = firstByMode(matches, att);
            if (match == null) {
                trySendError(messagesPath, atOpenid, msgId,
                        "玩家「" + name + "」最近没有 " + (att ? "ATT" : "TDM") + " 对局");
                return;
            }
            byte[] png = CardRenderer.match(config, match, actualName(data, name), att);
            sendImage(messagesPath, filesPath, png, atOpenid, msgId);
        } catch (Exception e) {
            fail(messagesPath, atOpenid, msgId, "查询失败", e);
        }
    }

    /**
     * “<泰坦>排行榜”：发 Markdown 消息，用原生 Markdown 图片语法内嵌 ttdm.space 的排行榜图，并附按钮。
     * 图由站点按当前榜单数据渲染并缓存（重算后首次拉取时更新），机器人只需给出图片 URL。
     */
    private void replyLeaderboard(String messagesPath, String filesPath, String atOpenid, String msgId, String cn) {
        String key = CardRenderer.titanKeyForChinese(cn);
        if (key == null) {
            trySendError(messagesPath, atOpenid, msgId,
                    "未知泰坦「" + cn + "」，可用：军团/浪人/北极星/烈焰/强力/帝王/离子");
            return;
        }
        try {
            String name = CardRenderer.titanName(key);
            // 图 URL 带小时级时间桶，规避 QQ 对 Markdown 图片按 URL 的强缓存（每小时刷新一次）
            String bucket = ZonedDateTime.now(ZoneOffset.ofHours(8))
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
            String img = config.ttdmApiBase + "/api/card/" + key + ".png?t=" + bucket;

            // QQ 原生 Markdown 图片必须带 #宽px #高px；卡片高度随榜单行数变化，发送前取真实尺寸
            int[] size = ttdm.pngSize(img);
            int w = size != null ? size[0] : 720;
            int h = size != null ? size[1] : 528;

            JsonObject markdown = new JsonObject();
            markdown.addProperty("content",
                    "![" + name + "排行榜 #" + w + "px #" + h + "px](" + img + ")");
            api.sendMarkdown(messagesPath, markdown, ttdmKeyboard(), msgId, seqGen.getAndIncrement());
        } catch (Exception e) {
            fail(messagesPath, atOpenid, msgId, "榜单获取失败", e);
        }
    }

    /** 上传 PNG 取 file_info，再以富媒体消息发送并 @ 发送者。 */
    private void sendImage(String messagesPath, String filesPath, byte[] png, String atOpenid, String msgId)
            throws Exception {
        // 富媒体消息不渲染 keyboard（仅 Markdown 会），正常会再单发一条带按钮的 Markdown。
        String fileInfo = api.uploadMediaData(filesPath, png, 1);
        api.sendMedia(messagesPath, fileInfo, msgId, seqGen.getAndIncrement());
        // 【临时实验】只发纯图，确认富媒体图本身是否还被气泡包裹；验证后恢复下面这条按钮消息。
        // try {
        //     JsonObject md = new JsonObject();
        //     md.addProperty("content", "完整战绩与榜单 → ttdm.space");
        //     api.sendMarkdown(messagesPath, md, ttdmKeyboard(), msgId, seqGen.getAndIncrement());
        // } catch (Exception e) {
        //     System.err.println("[MSG] 按钮消息发送失败（不影响图片）: " + e.getMessage());
        // }
    }

    /** 构造仅含「前往 ttdm.space」一个链接按钮的 keyboard。 */
    private JsonObject ttdmKeyboard() {
        JsonArray rows = new JsonArray();
        rows.add(buttonRow("0", " 打开官网查看 ", "https://ttdm.space"));
        JsonObject content = new JsonObject();
        content.add("rows", rows);
        JsonObject keyboard = new JsonObject();
        keyboard.add("content", content);
        return keyboard;
    }

    /** 取最近一局指定模式的对局：att=true 取 mode==att，否则取非 att（含旧数据空 mode）。 */
    private static JsonObject firstByMode(JsonArray matches, boolean att) {
        for (JsonElement e : matches) {
            JsonObject m = e.getAsJsonObject();
            boolean isAtt = "att".equals(str(m, "mode"));
            if (isAtt == att) return m;
        }
        return null;
    }

    /** 接口返回了昵称解析（actual_name）则用真实名，否则用输入名。 */
    private static String actualName(JsonObject data, String input) {
        String actual = str(data, "actual_name");
        return actual != null && !actual.isEmpty() ? actual : input;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private void fail(String messagesPath, String atOpenid, String msgId, String prefix, Exception e) {
        System.err.println("[MSG] " + prefix + ": " + e.getMessage());
        trySendError(messagesPath, atOpenid, msgId, prefix + "，请稍后重试");
    }

    /** 回复纯文本错误提示，发送失败仅记录不抛出。 */
    private void trySendError(String messagesPath, String atOpenid, String msgId, String text) {
        try {
            api.sendText(messagesPath, text, msgId, seqGen.getAndIncrement());
        } catch (Exception ex) {
            System.err.println("[MSG] 发送错误提示失败: " + ex.getMessage());
        }
    }

    // ── 原有静态指令 ───────────────────────────────────────────────

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
        markdown.addProperty("content", "# 官网导航\n---\n点击下方按钮前往对应站点：");

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

    /** “命令大全”：回复 Markdown 功能列表（供群友查看可用指令）。 */
    private void replyHelp(String messagesPath, String msgId) throws Exception {
        String content = "# KAMI 功能列表\n\n---\n\n"
                + "- `查查 玩家名` — 战绩总览\n"
                + "- `ttdm 玩家名` — 最近一局 TDM 对局\n"
                + "- `att 玩家名` — 最近一局 ATT 对局\n"
                + "- `官网` — 常用站点导航\n"
                + "- `泰坦排行榜` — 该泰坦命均 & 时长榜（「泰坦」替换为下列之一）：\n"
                + "  - 军团\n"
                + "  - 浪人\n"
                + "  - 北极星\n"
                + "  - 烈焰\n"
                + "  - 强力\n"
                + "  - 帝王\n"
                + "  - 离子\n";
        JsonObject markdown = new JsonObject();
        markdown.addProperty("content", content);
        api.sendMarkdown(messagesPath, markdown, ttdmKeyboard(), msgId, seqGen.getAndIncrement());
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
