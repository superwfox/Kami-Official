package com.kami.qqbot;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 读取 config.yml 中的机器人配置。
 */
public class Config {

    public String appId;
    public String clientSecret;
    public boolean sandbox;
    public String logoUrl;

    public static Config load(String path) throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            Map<String, Object> data = yaml.load(in);
            if (data == null) {
                throw new IllegalStateException("config.yml 内容为空");
            }
            Config c = new Config();
            c.appId = str(data.get("appId"));
            c.clientSecret = str(data.get("clientSecret"));
            c.sandbox = Boolean.parseBoolean(str(data.getOrDefault("sandbox", "false")));
            c.logoUrl = str(data.getOrDefault("logoUrl",
                    "https://github.com/superwfox/minecraft-dev/blob/master/public/silver.png?raw=true"));
            return c;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** QQ 开放接口域名（正式 / 沙箱）。 */
    public String apiBase() {
        return sandbox ? "https://sandbox.api.sgroup.qq.com" : "https://api.sgroup.qq.com";
    }
}
