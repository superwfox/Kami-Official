package com.kami.qqbot.BilibiliRelated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * B 站 Web 端公开的 WBI 签名。密钥来自未登录也可访问的 nav 接口 wbi_img，
 * 不是用户凭证。参见 bilibili-API-collect 中的 WBI 签名说明。
 */
final class BilibiliWbi {
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    private BilibiliWbi() {}

    /** 从 img_url / sub_url 的文件名取出 img_key / sub_key。 */
    static String imageKey(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.isEmpty() || !name.chars().allMatch(BilibiliWbi::isKeyChar)) {
            throw new IllegalArgumentException("wbi key");
        }
        return name;
    }

    static String mixinKey(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        StringBuilder mixed = new StringBuilder(32);
        for (int index : MIXIN_KEY_ENC_TAB) {
            if (index < 0 || index >= raw.length()) throw new IllegalArgumentException("wbi key");
            mixed.append(raw.charAt(index));
            if (mixed.length() == 32) break;
        }
        if (mixed.length() != 32) throw new IllegalArgumentException("wbi key");
        return mixed.toString();
    }

    /** 单个 bvid 或 aid 参数，输出已编码的 query（含 wts 与 w_rid）。 */
    static String signParameter(String parameter, String mixinKey, long wtsSeconds) {
        int eq = parameter.indexOf('=');
        if (eq <= 0 || eq == parameter.length() - 1) throw new IllegalArgumentException("parameter");
        Map<String, String> params = new LinkedHashMap<>();
        params.put(parameter.substring(0, eq), parameter.substring(eq + 1));
        return sign(params, mixinKey, wtsSeconds);
    }

    /** 按 key 排序，过滤值中的 !'()*，追加 wts 后对 query+mixinKey 做 MD5。w_rid 不参与摘要。 */
    static String sign(Map<String, String> params, String mixinKey, long wtsSeconds) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sorted.put(entry.getKey(), filter(entry.getValue()));
        }
        sorted.put("wts", Long.toString(wtsSeconds));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(encodeURIComponent(entry.getKey()))
                    .append('=')
                    .append(encodeURIComponent(entry.getValue()));
        }
        return query + "&w_rid=" + md5(query + mixinKey);
    }

    private static String filter(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '!' && c != '\'' && c != '(' && c != ')' && c != '*') out.append(c);
        }
        return out.toString();
    }

    /** 与浏览器 encodeURIComponent 一致，空格为 %20，百分号十六进制用大写。 */
    static String encodeURIComponent(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (isUnreserved(c)) out.append((char) c);
            else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isKeyChar(int ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*'
                || c == '\'' || c == '(' || c == ')';
    }

    private static String md5(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
