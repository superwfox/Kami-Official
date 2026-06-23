package com.kami.qqbot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QQ 机器人 WebSocket 客户端：负责鉴权(Identify)、心跳、断线重连与事件分发。
 */
public class QQBotClient {

    /** 订阅 group / c2c 事件的 intent，1 << 25。 */
    private static final int INTENT_GROUP_AND_C2C = 1 << 25;

    private final Config config;
    private final QQApi api;
    private final MessageHandler handler;
    private final Gson gson = new Gson();

    private WebSocketClient ws;
    private ScheduledExecutorService heartbeatExec;
    private final AtomicLong lastSeq = new AtomicLong(-1);
    private volatile boolean closing = false;

    public QQBotClient(Config config, QQApi api) {
        this.config = config;
        this.api = api;
        this.handler = new MessageHandler(api, config);
    }

    public void connect() throws Exception {
        String gateway = api.getGateway();
        System.out.println("[WS] gateway = " + gateway);

        ws = new WebSocketClient(new URI(gateway)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                System.out.println("[WS] 连接已建立");
            }

            @Override
            public void onMessage(String message) {
                handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("[WS] 连接关闭 code=" + code + " reason=" + reason);
                stopHeartbeat();
                if (!closing) {
                    reconnectLater();
                }
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("[WS] 错误: " + ex.getMessage());
            }
        };
        ws.connect();
    }

    private void handleMessage(String message) {
        JsonObject json = JsonParser.parseString(message).getAsJsonObject();

        if (json.has("s") && !json.get("s").isJsonNull()) {
            lastSeq.set(json.get("s").getAsLong());
        }

        int op = json.get("op").getAsInt();
        switch (op) {
            case 10: // Hello
                int interval = json.getAsJsonObject("d").get("heartbeat_interval").getAsInt();
                System.out.println("[WS] Hello, heartbeat_interval=" + interval + "ms");
                startHeartbeat(interval);
                identify();
                break;
            case 0: // Dispatch
                String t = json.has("t") && !json.get("t").isJsonNull() ? json.get("t").getAsString() : "";
                if ("READY".equals(t)) {
                    System.out.println("[WS] READY，鉴权成功，机器人已上线");
                }
                if (json.has("d") && json.get("d").isJsonObject()) {
                    handler.onEvent(t, json.getAsJsonObject("d"));
                }
                break;
            case 11: // Heartbeat ACK
                break;
            case 7:  // Reconnect
            case 9:  // Invalid Session
                System.out.println("[WS] 服务端要求重连 (op=" + op + ")");
                ws.close();
                break;
            default:
                break;
        }
    }

    private void identify() {
        JsonObject d = new JsonObject();
        d.addProperty("token", api.authHeader());
        d.addProperty("intents", INTENT_GROUP_AND_C2C);

        JsonArray shard = new JsonArray();
        shard.add(0);
        shard.add(1);
        d.add("shard", shard);
        d.add("properties", new JsonObject());

        JsonObject payload = new JsonObject();
        payload.addProperty("op", 2);
        payload.add("d", d);
        ws.send(gson.toJson(payload));
        System.out.println("[WS] 已发送 Identify");
    }

    private void startHeartbeat(int interval) {
        stopHeartbeat();
        heartbeatExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExec.scheduleAtFixedRate(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("op", 1);
                long s = lastSeq.get();
                if (s < 0) {
                    payload.add("d", JsonNull.INSTANCE);
                } else {
                    payload.addProperty("d", s);
                }
                ws.send(gson.toJson(payload));
            } catch (Exception e) {
                System.err.println("[WS] 心跳发送失败: " + e.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExec != null) {
            heartbeatExec.shutdownNow();
            heartbeatExec = null;
        }
    }

    private void reconnectLater() {
        new Thread(() -> {
            try {
                System.out.println("[WS] 5s 后尝试重连...");
                Thread.sleep(5000);
                connect();
            } catch (Exception e) {
                System.err.println("[WS] 重连失败: " + e.getMessage());
                reconnectLater();
            }
        }, "ws-reconnect").start();
    }
}
