package com.kami.qqbot;

/**
 * 程序入口：加载配置 -> 获取 access_token -> 建立 WebSocket 连接。
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config.yml";

        Config config = Config.load(configPath);
        System.out.println("[Main] 已加载配置, appId=" + config.appId + ", sandbox=" + config.sandbox);

        QQApi api = new QQApi(config);
        api.start();

        QQBotClient client = new QQBotClient(config, api);
        client.connect();

        // 主线程挂起，保持进程存活
        Thread.currentThread().join();
    }
}
