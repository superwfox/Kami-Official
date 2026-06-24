package com.kami.qqbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 战绩卡片渲染：泰坦表 + 数据计算 + Java2D 绘制 + 四类卡片（总览 / TDM / ATT / 排行榜）。
 * 配色、泰坦色、图表与指标口径均对齐 ttdm-review 网页实现。资源（raw.ttf / 泰坦图标）
 * 优先从 classpath 读取（随 jar 打包），找不到再退回文件系统 assetDir。
 */
public final class CardRenderer {

    // ── 配色（= 网页 CSS 变量） ──
    private static final Color BG = new Color(44, 54, 57);     // --bg-rgb / --o-deep #2C3639
    private static final Color O_MID = new Color(63, 78, 79);  // --o-mid  #3F4E4F 卡底
    private static final Color O_BRIGHT = new Color(162, 123, 92); // --o-bright #A27B5C 强调
    private static final Color FG = new Color(220, 215, 201);  // --fg-rgb  #DCD7C9 文字
    private static final Color KILL = new Color(0xFF, 0x9E, 0xCF);
    private static final Color GREEN = new Color(0x9e, 0xff, 0x9e);
    private static final Color RED = new Color(0xff, 0x52, 0x52);
    private static final Color YELLOW = new Color(0xff, 0xeb, 0x3b);
    private static final Color GOLD = new Color(0xff, 0xd7, 0x00);
    private static final Color[] BAR_SHADES = {
            new Color(0xA2, 0x7B, 0x5C), new Color(0xC9, 0xA2, 0x7F), new Color(0x7A, 0x5A, 0x40)
    };

    private static final double ARC = 12;

    // ── 泰坦表（颜色/名取自网页 titans.js；中文用简体作命令词+标题） ──
    static final String[] BAR_TITANS = {"legion", "ronin", "northstar", "scorch", "tone", "monarch", "ion"};
    private static final Map<String, String> TITAN_CN = new LinkedHashMap<>();
    private static final Map<String, Color> TITAN_COLOR = new HashMap<>();
    private static final Map<String, String> CN_TO_KEY = new LinkedHashMap<>();

    static {
        defTitan("legion", "军团", 0x00e5ff, "軍團");
        defTitan("ronin", "浪人", 0xffd600, "浪人");
        defTitan("northstar", "北极星", 0x448aff, "北極星");
        defTitan("scorch", "烈焰", 0xff9100, "烈焰");
        defTitan("tone", "强力", 0xc6ff00, "強力");
        defTitan("monarch", "帝王", 0xd500f9, "帝王");
        defTitan("ion", "离子", 0xff1744, "離子");
        TITAN_COLOR.put("pilot", new Color(0xaaaaaa));
        TITAN_COLOR.put("unknown", new Color(0x333333));
    }

    private static void defTitan(String key, String cn, int rgb, String alias) {
        TITAN_CN.put(key, cn);
        TITAN_COLOR.put(key, new Color(rgb));
        CN_TO_KEY.put(cn, key);
        CN_TO_KEY.put(alias, key); // 繁体别名也可触发命令
    }

    private static Color titanColor(String key) {
        return TITAN_COLOR.getOrDefault(key, new Color(0x333333));
    }

    public static String titanName(String key) {
        return TITAN_CN.getOrDefault(key, key);
    }

    /** 中文名 → 泰坦 key（用于「<中文>排行榜」指令）；未匹配返回 null。 */
    public static String titanKeyForChinese(String cn) {
        return cn == null ? null : CN_TO_KEY.get(cn);
    }

    // ── 字体 ──
    private static volatile Font baseFont;
    private static volatile boolean fontLoaded;

    /** 优先从 classpath /raw.ttf 加载，否则扫描 assetDir 下首个 .ttf/.otf；都没有回退微软雅黑。 */
    public static synchronized void loadFont(String assetDir) {
        if (fontLoaded) return;
        fontLoaded = true;
        try (InputStream in = CardRenderer.class.getResourceAsStream("/raw.ttf")) {
            if (in != null) {
                baseFont = Font.createFont(Font.TRUETYPE_FONT, in);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(baseFont);
                System.out.println("[Render] 已从 classpath 加载字体 raw.ttf");
                return;
            }
        } catch (Exception e) {
            System.err.println("[Render] classpath 字体加载失败: " + e.getMessage());
        }
        try {
            Path dir = Path.of(assetDir);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    Path f = s.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".ttf") || n.endsWith(".otf");
                    }).findFirst().orElse(null);
                    if (f != null) {
                        baseFont = Font.createFont(Font.TRUETYPE_FONT, f.toFile());
                        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(baseFont);
                        System.out.println("[Render] 已加载字体: " + f.getFileName());
                        return;
                    }
                }
            }
            System.out.println("[Render] 未找到字体，回退系统字体");
        } catch (Exception e) {
            System.err.println("[Render] 字体加载失败: " + e.getMessage());
        }
    }

    private static Font font(int style, float size) {
        return baseFont != null ? baseFont.deriveFont(style, size)
                : new Font("Microsoft YaHei", style, Math.round(size));
    }

    // ── 资源加载（classpath 优先，文件系统兜底） ──
    private static final Map<String, BufferedImage> IMG = new ConcurrentHashMap<>();
    private static final BufferedImage MISS = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private static InputStream open(Config c, String rel) {
        InputStream in = CardRenderer.class.getResourceAsStream("/" + rel);
        if (in != null) return in;
        try {
            Path p = Path.of(c.assetDir, rel);
            if (Files.isReadable(p)) return Files.newInputStream(p);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static BufferedImage img(Config c, String rel) {
        if (rel == null) return null;
        BufferedImage v = IMG.computeIfAbsent(rel, k -> {
            try (InputStream in = open(c, k)) {
                if (in == null) return MISS;
                BufferedImage i = ImageIO.read(in);
                return i != null ? i : MISS;
            } catch (Exception e) {
                return MISS;
            }
        });
        return v == MISS ? null : v;
    }

    private static BufferedImage icon(Config c, String key) {
        return img(c, "titans/" + key + "_s.png");
    }

    private static BufferedImage banner(Config c, String key) {
        return key == null ? null : img(c, "titans/" + key + "_b.png");
    }

    // ───────────────────────────────────────────────────────────────
    // 公共渲染入口
    // ───────────────────────────────────────────────────────────────

    /** 战绩总览卡：泰坦使用时长（柱状）+ 各局表现（折线）。 */
    public static byte[] summary(Config config, JsonArray matches, String name) {
        int w = 560, h = 420, pad = 28;
        BufferedImage out = canvas(config, w, h);
        Graphics2D g = begin(config, out, w, h);
        Font titleF = font(Font.PLAIN, 12), labelF = font(Font.PLAIN, 12), valueF = font(Font.PLAIN, 10);

        str(g, "泰坦使用时长", titleF, fg(0.55), pad, 40, LEFT);
        double[] seconds = new double[BAR_TITANS.length];
        for (JsonElement me : matches) {
            JsonArray tl = arr(me.getAsJsonObject(), "timeline");
            if (tl == null) continue;
            for (JsonElement pe : tl) {
                String tt = gs(pe.getAsJsonObject(), "titan_type");
                for (int i = 0; i < BAR_TITANS.length; i++) {
                    if (BAR_TITANS[i].equals(tt)) {
                        seconds[i] += 0.5;
                        break;
                    }
                }
            }
        }
        String[] barLabels = new String[BAR_TITANS.length];
        String[] barValues = new String[BAR_TITANS.length];
        for (int i = 0; i < BAR_TITANS.length; i++) {
            barLabels[i] = titanName(BAR_TITANS[i]);
            barValues[i] = seconds[i] > 0 ? formatDuration(seconds[i]) : "";
        }
        bars(g, pad, 54, w - 2 * pad, 150, barLabels, seconds, barValues, labelF, valueF);

        str(g, "各局表现", titleF, fg(0.55), pad, 252, LEFT);
        List<double[]> stats = new ArrayList<>(); // {kind(0=tdm,1=att), avgOrRank, diff}
        double maxAvg = 0;
        for (JsonElement me : matches) {
            JsonObject m = me.getAsJsonObject();
            if ("att".equals(gs(m, "mode"))) {
                stats.add(new double[]{1, gi(m, "score_rank"), 0});
            } else {
                int[] ps = playerStat(m, name);
                if (ps == null) continue;
                int taken = damageTaken(arr(m, "timeline"));
                stats.add(new double[]{0, ps[4], ps[2] - taken});
                maxAvg = Math.max(maxAvg, ps[4]);
            }
        }
        if (maxAvg <= 0) maxAvg = 5000;
        java.util.Collections.reverse(stats); // 旧 → 新
        double[] yVals = new double[stats.size()];
        Color[] colors = new Color[stats.size()];
        for (int i = 0; i < stats.size(); i++) {
            double[] s = stats.get(i);
            if (s[0] == 1) {
                int r = (int) s[1] > 0 ? (int) s[1] : 12;
                yVals[i] = Math.round((12 - r) / 12.0 * maxAvg);
                colors[i] = rankColor(r);
            } else {
                yVals[i] = s[1];
                double diff = s[2];
                colors[i] = diff > 5000 ? GREEN : diff < -5000 ? RED : YELLOW;
            }
        }
        if (yVals.length > 0) {
            perfLine(g, pad, 266, w - 2 * pad, 138, yVals, colors);
        } else {
            str(g, "暂无可用对局", font(Font.PLAIN, 13), fg(0.3), pad, 330, LEFT);
        }

        g.dispose();
        return toPng(out);
    }

    /** 战绩总览的 Markdown 文本（替代图片渲染）：泰坦使用时长 + 近期命均/排名 + 平均命均。 */
    public static String summaryMarkdown(JsonArray matches, String name) {
        // 泰坦使用时长（按时长降序，仅列非零）
        double[] sec = new double[BAR_TITANS.length];
        for (JsonElement me : matches) {
            JsonArray tl = arr(me.getAsJsonObject(), "timeline");
            if (tl == null) continue;
            for (JsonElement pe : tl) {
                String tt = gs(pe.getAsJsonObject(), "titan_type");
                for (int i = 0; i < BAR_TITANS.length; i++) {
                    if (BAR_TITANS[i].equals(tt)) {
                        sec[i] += 0.5;
                        break;
                    }
                }
            }
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < BAR_TITANS.length; i++) if (sec[i] > 0) idx.add(i);
        idx.sort((a, b) -> Double.compare(sec[b], sec[a]));

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(" · 战绩总览\n> ## **").append(name.toUpperCase());

        sb.append("**\n\n---\n\n**泰坦使用时长**\n");
        if (idx.isEmpty()) {
            sb.append("- 暂无泰坦时间线\n");
        } else {
            for (int i : idx) {
                sb.append("- ").append(titanName(BAR_TITANS[i]))
                        .append("　`").append(formatDuration(sec[i])).append("`\n");
            }
        }

        sb.append("\n---\n\n**近 ").append(matches.size()).append(" 局表现**\n");
        long sumAvg = 0;
        int cntAvg = 0;
        for (JsonElement me : matches) {
            JsonObject m = me.getAsJsonObject();
            String when = beijing(gs(m, "uploaded_at"));
            sb.append("- ").append(when).append(" - ");
            if ("att".equals(gs(m, "mode"))) {
                sb.append("`").append(rankText(gi(m, "score_rank"))).append("`\n");
            } else {
                int[] ps = playerStat(m, name);
                int avg = ps != null ? ps[4] : 0;
                sb.append("`").append(formatStat(avg)).append("`\n");
                if (ps != null) {
                    sumAvg += avg;
                    cntAvg++;
                }
            }
        }

        if (cntAvg > 0) {
            sb.append("\n---\n\n**平均命均**　`")
                    .append(formatStat(Math.round((double) sumAvg / cntAvg))).append("`");
        }
        return sb.toString();
    }

    public static byte[] match(Config config, JsonObject m, String name, boolean att) {
        return att ? renderAtt(config, m, name) : renderTdm(config, m, name);
    }

    private static byte[] renderTdm(Config config, JsonObject m, String name) {
        int w = 520, h = 360, pad = 28;
        BufferedImage out = canvas(config, w, h);
        Graphics2D g = begin(config, out, w, h);
        JsonArray tl = arr(m, "timeline");
        String primary = primaryTitan(tl);

        bannerBg(config, g, primary, w, h, h, 0.45f); // banner 铺满整卡
        matchHeader(g, m, pad, 46);

        int[] ps = playerStat(m, name);
        int avg = ps != null ? ps[4] : 0;
        Font avgF = font(Font.BOLD, 46);
        str(g, String.valueOf(avg), avgF, FG, pad, 116, LEFT);
        str(g, "命均", font(Font.PLAIN, 14), fg(0.33), pad + tw(g, String.valueOf(avg), avgF) + 10, 116, LEFT);

        List<String> used = usedTitans(tl);
        double size = 52, gap = 8;
        double ix = w - pad - used.size() * size - (used.size() - 1) * gap;
        for (String tt : used) {
            titanIcon(config, g, tt, ix, 66, size);
            ix += size + gap;
        }

        if (ps != null) {
            statItem(g, pad, 150, String.valueOf(ps[0]), "KILLS", null);
            statItem(g, pad + 140, 150, String.valueOf(ps[1]), "DEATHS", null);
            int taken = damageTaken(tl);
            String dmgSuffix = null;
            if (taken > 0) {
                String cmp = ps[2] > taken ? ">" : ps[2] < taken ? "<" : "=";
                dmgSuffix = cmp + formatStat(taken);
            }
            statItem(g, pad + 280, 150, formatStat(ps[2]), "DAMAGE", dmgSuffix);
        }

        if (tl != null && tl.size() > 0) {
            timelineHp(g, pad, 206, w - 2 * pad, 134, tl, true);
        } else {
            str(g, "该玩家未上传 Timeline", font(Font.PLAIN, 14), fg(0.2), pad, 260, LEFT);
        }

        g.dispose();
        return toPng(out);
    }

    private static byte[] renderAtt(Config config, JsonObject m, String name) {
        int w = 520, h = 400, pad = 28;
        BufferedImage out = canvas(config, w, h);
        Graphics2D g = begin(config, out, w, h);
        JsonArray tl = arr(m, "timeline");
        String primary = primaryTitan(tl);

        bannerBg(config, g, primary, w, h, h, 0.42f); // banner 铺满整卡

        matchHeader(g, m, pad, 46);

        // ── 指标（口径同网页 computeATTMetrics） ──
        int[] me = playerStat(m, name);
        int rank = gi(m, "score_rank");
        Double pvp = (me != null && me[3] > 0) ? (me[0] * 5.0) / me[3] : null;
        String ttft = "—";
        Double uptime = null, doom = null;
        Integer velocity = null;
        if (tl != null && tl.size() > 0) {
            int total = tl.size(), titanCount = 0;
            for (JsonElement e : tl) {
                JsonObject t = e.getAsJsonObject();
                String tt = gs(t, "titan_type");
                boolean isTitan = tt != null && !tt.equals("pilot") && !tt.equals("unknown");
                if (isTitan) {
                    titanCount++;
                    if (ttft.equals("—")) ttft = formatTtft(gi(t, "sample_num") * 0.5);
                }
            }
            uptime = titanCount * 100.0 / total;

            boolean inDoom = false;
            int doomEvents = 0, survives = 0;
            for (JsonElement e : tl) {
                JsonObject t = e.getAsJsonObject();
                boolean doomed = gbool(t, "is_doomed");
                String tt = gs(t, "titan_type");
                if (doomed && !inDoom) {
                    inDoom = true;
                    doomEvents++;
                } else if (inDoom && !doomed && gi(t, "health") > 0 && !"pilot".equals(tt)) {
                    survives++;
                    inDoom = false;
                } else if (inDoom && "pilot".equals(tt)) {
                    inDoom = false;
                }
            }
            if (doomEvents > 0) doom = survives * 100.0 / doomEvents;

            Map<Integer, Integer> buckets = new HashMap<>();
            for (JsonElement e : tl) {
                JsonObject t = e.getAsJsonObject();
                int minute = (int) Math.floor(gi(t, "sample_num") * 0.5 / 60);
                buckets.merge(minute, gi(t, "delta_score"), Integer::sum);
            }
            for (int v : buckets.values()) velocity = velocity == null ? v : Math.max(velocity, v);
        }

        double colW = (w - 2.0 * pad) / 3;
        metric(g, pad, 66, rankText(rank), rankColor(rank), "排名");
        metric(g, pad + colW, 66, pvp != null ? String.format("%.2f", pvp) : "—", FG, "PvP 得分比");
        metric(g, pad + colW * 2, 66, ttft, FG, "首泰坦用时");
        metric(g, pad, 128, uptime != null ? String.format("%.1f%%", uptime) : "—", FG, "泰坦时长");
        metric(g, pad + colW, 128, doom != null ? String.format("%.1f%%", doom) : "—", FG, "残血生还");
        metric(g, pad + colW * 2, 128, velocity != null ? velocity + " /min" : "—", FG, "峰值得分速度");

        if (tl != null && tl.size() > 0) {
            timelineHp(g, pad, 196, w - 2 * pad, 184, tl, false);
        } else {
            str(g, "该玩家未上传 Timeline", font(Font.PLAIN, 14), fg(0.2), pad, 250, LEFT);
        }

        g.dispose();
        return toPng(out);
    }

    /** 单泰坦排行榜卡：banner + 图标/中文名 + 命均 TOP / 时长 TOP 两列。高度按行数自适应。 */
    public static byte[] leaderboard(Config config, String key, JsonObject boards) {
        int w = 720, pad = 32;
        JsonArray avgRows = arr(boards, "avg_dmg:" + key);
        JsonArray playRows = arr(boards, "playtime:" + key);
        int cntA = avgRows == null ? 0 : Math.min(10, avgRows.size());
        int cntB = playRows == null ? 0 : Math.min(10, playRows.size());
        int rows = Math.max(2, Math.max(cntA, cntB)); // 至少留两行高度给“敬请期待”
        int top = 152;
        int h = top + rows * 36 + 16;

        BufferedImage out = canvas(config, w, h);
        Graphics2D g = begin(config, out, w, h);

        bannerBg(config, g, key, w, h, h, 0.3f);
        titanIcon(config, g, key, pad, 30, 52);
        str(g, titanName(key), font(Font.BOLD, 30), FG, pad + 52 + 14, 66, LEFT);
        str(g, "命均 & 时长排行榜", font(Font.PLAIN, 13), fg(0.5), pad + 52 + 14, 88, LEFT);

        double gap = 24, colW = (w - 2 * pad - gap) / 2;
        boardColumn(g, pad, 138, colW, "命均 TOP", avgRows, false);
        boardColumn(g, pad + colW + gap, 138, colW, "时长 TOP", playRows, true);

        g.dispose();
        return toPng(out);
    }

    // ───────────────────────────────────────────────────────────────
    // 绘制工具
    // ───────────────────────────────────────────────────────────────

    private static final int LEFT = 0, CENTER = 1, RIGHT = 2;

    private static BufferedImage canvas(Config c, int w, int h) {
        double s = c.renderScale;
        return new BufferedImage((int) Math.round(w * s), (int) Math.round(h * s), BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D begin(Config c, BufferedImage out, int w, int h) {
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(c.renderScale, c.renderScale);
        fillRound(g, 0, 0, w, h, ARC, O_MID);          // 卡底 = --o-mid
        strokeRound(g, 0.5, 0.5, w - 1, h - 1, ARC, alpha(O_BRIGHT, 0.35), 1f); // 边框 = --o-bright 0.35
        return g;
    }

    private static void fillRound(Graphics2D g, double x, double y, double w, double h, double arc, Paint fill) {
        g.setPaint(fill);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
    }

    private static void strokeRound(Graphics2D g, double x, double y, double w, double h, double arc, Color c, float s) {
        g.setColor(c);
        g.setStroke(new BasicStroke(s));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
    }

    private static int tw(Graphics2D g, String s, Font f) {
        g.setFont(f);
        return g.getFontMetrics().stringWidth(s == null ? "" : s);
    }

    private static void str(Graphics2D g, String s, Font f, Color color, double x, double baseline, int align) {
        if (s == null) s = "";
        g.setFont(f);
        g.setColor(color);
        int w = g.getFontMetrics().stringWidth(s);
        double dx = align == RIGHT ? x - w : align == CENTER ? x - w / 2.0 : x;
        g.drawString(s, (float) dx, (float) baseline);
    }

    private static Color alpha(Color c, double a) {
        int al = Math.max(0, Math.min(255, (int) Math.round(a * 255)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), al);
    }

    private static Color fg(double a) {
        return alpha(FG, a);
    }

    /** banner 背景：高度铺满、宽度等比、右对齐向左淡出，叠顶部暗化；裁剪到圆角卡内。 */
    private static void bannerBg(Config config, Graphics2D g, String key, int w, int cardH, int bannerH, float maxAlpha) {
        BufferedImage b = banner(config, key);
        if (b == null) return;
        Shape oldClip = g.getClip();
        g.setClip(new RoundRectangle2D.Double(0, 0, w, cardH, ARC, ARC));

        int sw = Math.max(1, Math.round(b.getWidth() * (bannerH / (float) b.getHeight())));
        BufferedImage tmp = new BufferedImage(w, bannerH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = tmp.createGraphics();
        tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        tg.drawImage(b, w - sw, 0, sw, bannerH, null);
        // 左→右 alpha 渐变实现向左淡出；起点锚定 banner 自身左缘，使其在左缘恰好淡为 0，避免硬边界
        tg.setComposite(AlphaComposite.DstIn);
        tg.setPaint(new GradientPaint(w - sw, 0, new Color(255, 255, 255, 0), w, 0, new Color(255, 255, 255, 255)));
        tg.fillRect(0, 0, w, bannerH);
        tg.dispose();

        java.awt.Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, maxAlpha));
        g.drawImage(tmp, 0, 0, null);
        g.setComposite(oc);

        g.setPaint(new GradientPaint(0, 0, alpha(BG, 0.55), 0, bannerH, alpha(BG, 0)));
        g.fill(new Rectangle2D.Double(0, 0, w, bannerH));
        g.setClip(oldClip);
    }

    /** 泰坦小图标（方形 cover + 圆角，无边框）。 */
    private static void titanIcon(Config config, Graphics2D g, String key, double x, double y, double size) {
        BufferedImage ic = icon(config, key);
        if (ic == null) return;
        Shape oldClip = g.getClip();
        g.clip(new RoundRectangle2D.Double(x, y, size, size, 8, 8));
        double sc = Math.max(size / ic.getWidth(), size / ic.getHeight());
        double sw = ic.getWidth() * sc, sh = ic.getHeight() * sc;
        g.drawImage(ic, (int) Math.round(x + (size - sw) / 2), (int) Math.round(y + (size - sh) / 2),
                (int) Math.round(sw), (int) Math.round(sh), null);
        g.setClip(oldClip);
    }

    private static void statItem(Graphics2D g, double x, double yTop, String value, String label, String suffix) {
        Font vF = font(Font.PLAIN, 20);
        str(g, value, vF, FG, x, yTop + 18, LEFT);
        if (suffix != null) {
            str(g, suffix, font(Font.PLAIN, 13), fg(0.3), x + tw(g, value, vF) + 4, yTop + 18, LEFT);
        }
        str(g, label, font(Font.PLAIN, 11), fg(0.27), x, yTop + 36, LEFT);
    }

    private static void metric(Graphics2D g, double x, double yTop, String value, Color color, String label) {
        str(g, value, font(Font.BOLD, 26), color, x, yTop + 24, LEFT);
        str(g, label, font(Font.PLAIN, 11), fg(0.65), x, yTop + 44, LEFT);
    }

    private static void matchHeader(Graphics2D g, JsonObject m, double x, double baseline) {
        Font f13 = font(Font.PLAIN, 13), fb = font(Font.BOLD, 14);
        double cx = x;
        String time = beijing(gs(m, "uploaded_at"));
        str(g, time, f13, fg(0.55), cx, baseline, LEFT);
        cx += tw(g, time, f13) + 16;
        String map = mapName(m);
        if (!map.isEmpty()) {
            str(g, map, f13, fg(0.85), cx, baseline, LEFT);
            cx += tw(g, map, f13) + 16;
        }
        String r = gs(m, "result");
        Color rc = "win".equals(r) ? GREEN : "loss".equals(r) ? RED : "draw".equals(r) ? YELLOW : null;
        if (rc != null) {
            String rt = "win".equals(r) ? "胜利" : "loss".equals(r) ? "失败" : "平局";
            str(g, rt, fb, rc, cx, baseline, LEFT);
            cx += tw(g, rt, fb) + 16;
        }
        JsonArray fs = arr(m, "final_score");
        if (fs != null && fs.size() >= 2 && !fs.get(0).isJsonNull() && !fs.get(1).isJsonNull()) {
            str(g, fs.get(0).getAsInt() + " : " + fs.get(1).getAsInt(), f13, fg(0.75), cx, baseline, LEFT);
        }
    }

    private static void boardColumn(Graphics2D g, double x, double titleBaseline, double colW,
                                    String title, JsonArray rows, boolean isTime) {
        str(g, title, font(Font.PLAIN, 12), fg(0.6), x, titleBaseline, LEFT);
        if (rows == null || rows.size() == 0) {
            str(g, "敬请期待", font(Font.PLAIN, 13), fg(0.45), x + colW / 2, titleBaseline + 40, CENTER);
            return;
        }
        double max = Math.max(1, rows.get(0).getAsJsonObject().get("value").getAsDouble());
        double rowH = 28, gap = 8, top = titleBaseline + 14;
        int count = Math.min(10, rows.size());
        Font rankF = font(Font.BOLD, 12), nameF = font(Font.PLAIN, 12), valF = font(Font.BOLD, 12);
        for (int i = 0; i < count; i++) {
            JsonObject r = rows.get(i).getAsJsonObject();
            double y = top + i * (rowH + gap);
            double value = r.get("value").getAsDouble();
            fillRound(g, x, y, colW, rowH, 6, alpha(BG, 0.55));
            double fillW = Math.max(0.03, Math.min(1.0, value / max)) * colW;
            Shape oldClip = g.getClip();
            g.clip(new RoundRectangle2D.Double(x, y, colW, rowH, 6, 6));
            g.setPaint(new GradientPaint((float) x, 0, fg(0.40), (float) (x + fillW), 0, fg(0.26)));
            g.fill(new RoundRectangle2D.Double(x, y, fillW, rowH, 6, 6));
            g.setClip(oldClip);
            strokeRound(g, x, y, colW, rowH, 6, fg(0.08), 1f);

            double baseline = y + rowH / 2 + 4;
            int rank = r.has("rank") ? r.get("rank").getAsInt() : (i + 1);
            str(g, String.valueOf(rank), rankF, fg(0.55), x + 14, baseline, LEFT);
            String valText = isTime ? formatDuration(value) : formatStat(Math.round(value));
            double valW = tw(g, valText, valF);
            str(g, valText, valF, FG, x + colW - 12, baseline, RIGHT);
            double nameMax = (x + colW - 12 - valW - 8) - (x + 34);
            str(g, truncate(g, boardName(r), nameF, nameMax), nameF, FG, x + 34, baseline, LEFT);
        }
    }

    // ── 图表 ──

    private static double adjustHp(JsonObject t) {
        double hp = gi(t, "health");
        String tt = gs(t, "titan_type");
        if (!gbool(t, "is_doomed") && tt != null && !tt.equals("pilot") && !tt.equals("unknown")) hp += 2500;
        return hp > 65000 ? 0 : hp;
    }

    /**
     * 时间线血量图：按 titan_type 分段着色（面积+描线）。
     * tdm=true：叠加粉色累计伤害线（右轴），击杀点落在伤害线上；
     * tdm=false（ATT）：击杀点直接落在血量线上。
     */
    private static void timelineHp(Graphics2D g, double px, double py, double pw, double ph, JsonArray tl, boolean tdm) {
        int n = tl.size();
        double[] hp = new double[n];
        String[] type = new String[n];
        boolean[] kill = new boolean[n];
        double maxHp = 0;
        boolean hasDelta = false;
        for (int i = 0; i < n; i++) {
            JsonObject t = tl.get(i).getAsJsonObject();
            hp[i] = adjustHp(t);
            type[i] = gs(t, "titan_type");
            kill[i] = gi(t, "delta_kills") > 0;
            if (gi(t, "delta_damage") > 0) hasDelta = true;
            maxHp = Math.max(maxHp, hp[i]);
        }
        if (maxHp <= 0) maxHp = 1000;
        double mx = Math.max(1, n - 1), myHp = maxHp * 1.05;
        gridY(g, px, py, pw, ph, 4, fg(0.05));

        double baseY = py + ph;
        int start = 0;
        List<int[]> segs = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            if (!eq(type[i], type[start])) {
                segs.add(new int[]{start, i});
                start = i;
            }
        }
        segs.add(new int[]{start, n - 1});
        for (int[] seg : segs) {
            Color col = titanColor(type[seg[0]]);
            GeneralPath area = new GeneralPath();
            area.moveTo(px + seg[0] / mx * pw, baseY);
            for (int i = seg[0]; i <= seg[1]; i++) area.lineTo(px + i / mx * pw, py + ph - hp[i] / myHp * ph);
            area.lineTo(px + seg[1] / mx * pw, baseY);
            area.closePath();
            g.setColor(alpha(col, 0.06));
            g.fill(area);
            GeneralPath line = new GeneralPath();
            line.moveTo(px + seg[0] / mx * pw, py + ph - hp[seg[0]] / myHp * ph);
            for (int i = seg[0] + 1; i <= seg[1]; i++) line.lineTo(px + i / mx * pw, py + ph - hp[i] / myHp * ph);
            g.setColor(alpha(col, 0.95));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(line);
        }

        if (tdm && hasDelta) {
            double[] cum = new double[n];
            double run = 0;
            for (int i = 0; i < n; i++) {
                run += gi(tl.get(i).getAsJsonObject(), "delta_damage");
                cum[i] = run;
            }
            double y1Max = Math.max(1, Math.ceil(run / 0.75));
            GeneralPath dmg = new GeneralPath();
            dmg.moveTo(px, py + ph - cum[0] / y1Max * ph);
            for (int i = 1; i < n; i++) dmg.lineTo(px + i / mx * pw, py + ph - cum[i] / y1Max * ph);
            g.setColor(KILL);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(dmg);
            for (int i = 0; i < n; i++) {
                if (!kill[i]) continue;
                double cx = px + i / mx * pw, cy = py + ph - cum[i] / y1Max * ph;
                g.fill(new Ellipse2D.Double(cx - 4, cy - 4, 8, 8));
            }
        } else if (!tdm) {
            g.setColor(KILL);
            for (int i = 0; i < n; i++) {
                if (!kill[i]) continue;
                double cx = px + i / mx * pw, cy = py + ph - hp[i] / myHp * ph;
                g.fill(new Ellipse2D.Double(cx - 4, cy - 4, 8, 8));
            }
        }
    }

    private static final double GUT = 46; // 图表左侧纵轴刻度留白

    private static void bars(Graphics2D g, double px, double py, double pw, double ph,
                             String[] labels, double[] values, String[] valueLabels, Font labelF, Font valueF) {
        int n = values.length;
        double maxV = 0;
        for (double v : values) maxV = Math.max(maxV, v);
        if (maxV <= 0) maxV = 1;
        double top = maxV * 1.15;
        double plotX = px + GUT, plotW = pw - GUT;
        gridY(g, plotX, py, plotW, ph, 4, alpha(O_BRIGHT, 0.10));
        yAxis(g, plotX - 8, py, ph, 4, top, true);
        double colW = plotW / n, barW = Math.min(62, colW * 0.82);
        for (int i = 0; i < n; i++) {
            double cx = plotX + colW * (i + 0.5);
            double bh = values[i] / top * ph, by = py + ph - bh;
            g.setColor(BAR_SHADES[i % BAR_SHADES.length]);
            g.fill(new RoundRectangle2D.Double(cx - barW / 2, by, barW, Math.max(bh, 0.5), 4, 4));
            if (valueLabels[i] != null && !valueLabels[i].isEmpty() && bh > 6) {
                str(g, valueLabels[i], valueF, fg(0.6), cx, by - 4, CENTER);
            }
            str(g, labels[i], labelF, fg(0.7), cx, py + ph + 16, CENTER);
        }
    }

    /** 折线图：分段按右端点色着色、加粗；端点描色点；左侧带纵轴刻度。 */
    private static void perfLine(Graphics2D g, double px, double py, double pw, double ph,
                                 double[] values, Color[] colors) {
        int n = values.length;
        double maxV = 0;
        for (double v : values) maxV = Math.max(maxV, v);
        if (maxV <= 0) maxV = 1;
        double my = maxV * 1.1, plotX = px + GUT, plotW = pw - GUT, mx = Math.max(1, n - 1);
        gridY(g, plotX, py, plotW, ph, 4, alpha(O_BRIGHT, 0.10));
        yAxis(g, plotX - 8, py, ph, 4, my, false);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i < n; i++) {
            double x0 = plotX + (i - 1) / mx * plotW, y0 = py + ph - values[i - 1] / my * ph;
            double x1 = plotX + i / mx * plotW, y1 = py + ph - values[i] / my * ph;
            g.setColor(colors[i % colors.length]);
            g.draw(new Line2D.Double(x0, y0, x1, y1));
        }
        for (int i = 0; i < n; i++) {
            double cx = n == 1 ? plotX + plotW / 2 : plotX + i / mx * plotW;
            double cy = py + ph - values[i] / my * ph;
            g.setColor(colors[i % colors.length]);
            g.fill(new Ellipse2D.Double(cx - 4, cy - 4, 8, 8));
        }
    }

    /** 纵轴刻度：从上到下 lines+1 个标签，右对齐到 labelRight。duration=true 显示时长，否则千分位整数。 */
    private static void yAxis(Graphics2D g, double labelRight, double py, double ph, int lines, double axisMax, boolean duration) {
        Font f = font(Font.PLAIN, 10);
        for (int i = 0; i <= lines; i++) {
            double yy = py + ph * i / lines + 3;
            double val = axisMax * (lines - i) / lines;
            String s = duration ? formatDurAxis(val) : formatStat(Math.round(val));
            str(g, s, f, fg(0.4), labelRight, yy, RIGHT);
        }
    }

    private static String formatDurAxis(double seconds) {
        long s = Math.round(seconds), h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? h + "h" + m + "m" : m + "m";
    }

    private static void gridY(Graphics2D g, double px, double py, double pw, double ph, int lines, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= lines; i++) {
            double yy = py + ph * i / lines;
            g.draw(new Line2D.Double(px, yy, px + pw, yy));
        }
    }

    private static byte[] toPng(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("PNG 导出失败: " + e.getMessage(), e);
        }
    }

    // ───────────────────────────────────────────────────────────────
    // 数据计算
    // ───────────────────────────────────────────────────────────────

    /** {kills,deaths,damage,score,avg}；找不到返回 null。avg=deaths>0?round(dmg/deaths):dmg。 */
    private static int[] playerStat(JsonObject match, String name) {
        JsonArray players = arr(match, "players");
        if (players == null || name == null) return null;
        for (JsonElement e : players) {
            JsonObject p = e.getAsJsonObject();
            if (name.equalsIgnoreCase(gs(p, "name"))) {
                int k = gi(p, "kills"), d = gi(p, "deaths"), dmg = gi(p, "damage"), sc = gi(p, "score");
                int avg = d > 0 ? Math.round((float) dmg / d) : dmg;
                return new int[]{k, d, dmg, sc, avg};
            }
        }
        return null;
    }

    /** 承伤：相邻采样 adjustHp 下降量之和（口径同网页 calcDamageTaken）。 */
    private static int damageTaken(JsonArray tl) {
        if (tl == null || tl.size() < 2) return 0;
        int total = 0;
        double prev = adjustHp(tl.get(0).getAsJsonObject());
        for (int i = 1; i < tl.size(); i++) {
            double cur = adjustHp(tl.get(i).getAsJsonObject());
            if (cur < prev) total += (int) (prev - cur);
            prev = cur;
        }
        return total;
    }

    private static String primaryTitan(JsonArray tl) {
        if (tl == null) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonElement e : tl) {
            String tt = gs(e.getAsJsonObject(), "titan_type");
            if (tt == null || tt.equals("pilot") || tt.equals("unknown")) continue;
            counts.merge(tt, 1, Integer::sum);
        }
        String best = null;
        int max = 0;
        for (Map.Entry<String, Integer> en : counts.entrySet()) {
            if (en.getValue() > max) {
                max = en.getValue();
                best = en.getKey();
            }
        }
        return best;
    }

    private static List<String> usedTitans(JsonArray tl) {
        List<String> out = new ArrayList<>();
        if (tl == null) return out;
        for (JsonElement e : tl) {
            String tt = gs(e.getAsJsonObject(), "titan_type");
            if (tt == null || tt.equals("pilot") || tt.equals("unknown")) continue;
            if (!out.contains(tt)) out.add(tt);
        }
        return out;
    }

    private static String boardName(JsonObject r) {
        String name = gs(r, "player_name"), nick = gs(r, "nickname");
        if (nick != null && !nick.isEmpty() && !nick.equals(name)) return nick + "（" + name + "）";
        return name == null ? "" : name;
    }

    private static String rankText(int n) {
        if (n < 1) return "—";
        if (n == 1) return "1st";
        if (n == 2) return "2nd";
        if (n == 3) return "3rd";
        return n + "th";
    }

    private static Color rankColor(int rank) {
        if (rank < 1) return FG;
        if (rank == 1) return GOLD;
        if (rank <= 3) return GREEN;
        if (rank <= 6) return YELLOW;
        return RED;
    }

    private static String mapName(JsonObject m) {
        String raw = gs(m, "map");
        if (raw == null || raw.isEmpty()) return "";
        return raw.replaceFirst("^mp_", "").replace('_', ' ').toUpperCase();
    }

    private static String formatTtft(double seconds) {
        if (seconds < 0) return "—";
        int m = (int) Math.floor(seconds / 60), s = (int) Math.floor(seconds % 60);
        return m + "m" + (s < 10 ? "0" + s : s) + "s";
    }

    private static final DateTimeFormatter IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static String beijing(String utc) {
        if (utc == null || utc.isEmpty()) return "";
        try {
            return LocalDateTime.parse(utc, IN).plusHours(8).format(OUT);
        } catch (Exception e) {
            return utc;
        }
    }

    private static String formatStat(long n) {
        return String.format("%,d", n);
    }

    private static String formatDuration(double seconds) {
        if (seconds < 0) return "—";
        long s = Math.round(seconds), h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? h + "h" + m + "m" : m + "m" + (s % 60) + "s";
    }

    private static String truncate(Graphics2D g, String s, Font f, double maxW) {
        if (s == null) return "";
        if (tw(g, s, f) <= maxW) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (tw(g, sb.toString() + s.charAt(i) + "…", f) > maxW) break;
            sb.append(s.charAt(i));
        }
        return sb + "…";
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ── gson 安全取值 ──

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
    }

    private static int gi(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String gs(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static boolean gbool(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private CardRenderer() {
    }
}
