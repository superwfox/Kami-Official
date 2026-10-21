package com.kami.qqbot;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Claude-like dark cards: one dynamically sized PNG containing the complete README. */
public final class GitHubCardRenderer {
    private static final int WIDTH = 720, BODY_X = 48, BODY_WIDTH = WIDTH - 96;
    private static final Color BG = new Color(0x262624), PANEL = new Color(0x30302c), BORDER = new Color(0x45443f);
    private static final Color FG = new Color(0xe9e5dc), MUTED = new Color(0xaaa79e), GREEN = new Color(0xb6ceb8), RED = new Color(0xe3a199);
    private static final Font FONT = loadFont();
    interface ImageLoader { BufferedImage load(String url) throws InterruptedException; }
    private final ImageLoader imageLoader;

    public GitHubCardRenderer(GitHubApi api) { this(api, api == null ? url -> null : api::asset); }
    GitHubCardRenderer(GitHubApi api, ImageLoader imageLoader) { this.imageLoader = imageLoader; }

    private static Font loadFont() {
        try (InputStream input = GitHubCardRenderer.class.getResourceAsStream("/github-card/DingTalk-JinBuTi.ttf")) {
            if (input == null) throw new IOException("DingTalk font missing");
            Font font = Font.createFont(Font.TRUETYPE_FONT, input);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (Exception e) { throw new IllegalStateException("仓库卡片字体加载失败", e); }
    }
    private static Font font(float size) { return FONT.deriveFont(Font.PLAIN, size); }

    /** No excerpt, fixed height, or pagination: one PNG and one media reply per repository. */
    public byte[] render(GitHubRepository repository, int requestedScale) throws Exception {
        int scale = Math.max(1, Math.min(3, requestedScale));
        Path images = Files.createTempDirectory("kami-readme-");
        try {
            String html = documentHtml(repository, images);
            AtomicReference<byte[]> output = new AtomicReference<>();
            AtomicReference<Exception> error = new AtomicReference<>();
            Runnable paint = () -> {
                try { output.set(renderDocument(repository, html, scale)); }
                catch (Exception e) { error.set(e); }
            };
            if (SwingUtilities.isEventDispatchThread()) paint.run(); else SwingUtilities.invokeAndWait(paint);
            if (error.get() != null) throw error.get();
            return output.get();
        } finally {
            try (var files = Files.walk(images)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(file);
            }
        }
    }

    String documentHtml(GitHubRepository repository, Path images) throws Exception {
        String markdown = repository.readme;
        if (markdown.isBlank()) markdown = repository.description.isBlank()
                ? "此仓库暂无可读取的 README。" : repository.description + "\n\n此仓库暂无可读取的 README。";
        var extensions = List.of(TablesExtension.create());
        String rendered = HtmlRenderer.builder().extensions(extensions).build()
                .render(Parser.builder().extensions(extensions).build().parse(markdown));
        // Raw HTML is data: strip scripts, forms, CSS imports, embeds and arbitrary file URLs.
        Safelist allowed = Safelist.relaxed().addTags("h1", "h2", "h3", "h4", "h5", "h6", "del", "s", "input")
                .addAttributes("input", "checked", "type")
                .addAttributes("td", "width").addAttributes("th", "width");
        String baseUri = repository.url() + "/blob/" + repository.sha + "/" + repository.readmePath;
        Document document = Jsoup.parse(Jsoup.clean(rendered, baseUri, allowed.preserveRelativeLinks(true)));
        for (Element input : new ArrayList<>(document.select("input"))) input.replaceWith(new Element("span")
                .text(input.hasAttr("checked") ? "[x] " : "[ ] "));
        for (Element link : document.select("a[href]")) {
            String href = link.attr("href");
            try {
                URI target = URI.create(href);
                if (!target.isAbsolute()) target = URI.create(repository.url() + "/blob/" + repository.sha + "/" + repository.readmePath).resolve(target);
                if (!Set.of("https", "http", "mailto").contains(target.getScheme())) link.removeAttr("href");
                else link.attr("href", target.toString());
            } catch (IllegalArgumentException e) { link.removeAttr("href"); }
        }
        int imageIndex = 0;
        for (Element image : new ArrayList<>(document.select("img"))) {
            String destination = image.attr("src"), alt = image.attr("alt");
            URI uri = GitHubApi.imageUri(repository, destination);
            BufferedImage bitmap = uri == null ? null : imageLoader.load(uri.toString());
            if (bitmap == null) {
                image.replaceWith(new Element("span").text("[图片" + (alt.isBlank() ? "" : "：" + alt) + "] " + destination));
                continue;
            }
            int available = imageContainerWidth(image);
            int requestedWidth = length(image.attr("width"), available, available);
            int width = Math.max(1, Math.min(Math.min(available, requestedWidth), bitmap.getWidth()));
            int height = Math.max(1, Math.round((float) bitmap.getHeight() * width / bitmap.getWidth()));
            if (height > 1000) { width = Math.max(1, width * 1000 / height); height = 1000; }
            Path file = images.resolve("image-" + imageIndex++ + ".png");
            ImageIO.write(bitmap, "png", file.toFile());
            image.attr("src", file.toUri().toString()).attr("width", Integer.toString(width)).attr("height", Integer.toString(height));
        }
        for (Element table : document.select("table")) table.attr("width", "100%").attr("cellspacing", "0").attr("cellpadding", "6");
        // Swing's <pre> does not wrap long lines; insert visual line breaks without removing characters.
        BufferedImage metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = metricsImage.createGraphics(); graphics.setFont(font(14));
        for (Element pre : document.select("pre")) {
            String text = pre.wholeText();
            StringBuilder wrapped = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                for (String part : wrap(graphics.getFontMetrics(), line, Math.max(1, imageContainerWidth(pre) - 24))) wrapped.append(part).append('\n');
            }
            pre.text(wrapped.toString());
        }
        graphics.dispose();
        document.outputSettings().prettyPrint(false);
        return document.outerHtml();
    }

    private static int imageContainerWidth(Element element) {
        List<Element> cells = new ArrayList<>();
        for (Element ancestor = element.parent(); ancestor != null; ancestor = ancestor.parent())
            if (ancestor.normalName().equals("td") || ancestor.normalName().equals("th")) cells.add(ancestor);
        Collections.reverse(cells);
        int available = BODY_WIDTH - 12;
        for (Element cell : cells) {
            Element table = cell.closest("table");
            if (table == null) continue;
            int columns = 1;
            for (Element row : table.select("tr")) {
                if (row.closest("table") != table) continue;
                int rowColumns = 0;
                for (Element peer : row.children())
                    if (peer.normalName().equals("td") || peer.normalName().equals("th")) rowColumns += span(peer);
                columns = Math.max(columns, rowColumns);
            }
            int share = available * Math.min(span(cell), columns) / columns;
            int declared = length(cell.attr("width"), available, share);
            available = Math.max(1, Math.min(share, declared) - 18); // padding, borders and table spacing
        }
        return available;
    }
    private static int span(Element cell) {
        try { return Math.max(1, Math.min(1000, Integer.parseInt(cell.attr("colspan")))); }
        catch (NumberFormatException e) { return 1; }
    }
    private static int length(String value, int reference, int fallback) {
        try {
            String normalized = value.trim();
            double number = Double.parseDouble(normalized.replace("%", "").replace("px", ""));
            double pixels = normalized.endsWith("%") ? reference * number / 100 : number;
            return Double.isFinite(pixels) && pixels > 0 ? Math.max(1, (int) Math.min(reference, pixels)) : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }

    private byte[] renderDocument(GitHubRepository repository, String html, int scale) throws Exception {
        JEditorPane pane = new JEditorPane();
        HTMLEditorKit kit = new HTMLEditorKit() {
            private final ViewFactory factory = new HTMLFactory() {
                @Override public View create(javax.swing.text.Element element) {
                    View view = super.create(element);
                    if (view instanceof ImageView) ((ImageView) view).setLoadsSynchronously(true);
                    if (view instanceof InlineView) return new InlineView(element) {
                        @Override public float getMinimumSpan(int axis) {
                            return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                        }
                    };
                    return view;
                }
            };
            @Override public ViewFactory getViewFactory() { return factory; }
        };
        StyleSheet css = kit.getStyleSheet();
        String family = FONT.getFamily(Locale.ROOT);
        css.addRule("body { margin: 0; font-family: '" + family + "'; font-size: 14pt; color: #ccc8be; background-color: #30302c; }");
        css.addRule("h1 { font-size: 25pt; font-weight: normal; color: #e9e5dc; margin-top: 7px; margin-bottom: 14px; }");
        css.addRule("h2 { font-size: 20pt; font-weight: normal; color: #e9e5dc; margin-top: 24px; margin-bottom: 12px; }");
        css.addRule("h3, h4, h5, h6 { font-size: 17pt; color: #e9e5dc; margin-top: 22px; margin-bottom: 10px; }");
        css.addRule("p { margin-top: 0px; margin-bottom: 17px; }");
        css.addRule("a { color: #d4a186; } strong, b { color: #e9e5dc; }");
        css.addRule("code, pre { font-family: '" + family + "'; font-size: 13pt; color: #d2b09b; background-color: #252523; }");
        css.addRule("pre { padding: 12px; margin-top: 9px; margin-bottom: 18px; }");
        css.addRule("blockquote { margin-left: 12px; margin-right: 8px; color: #ddc1a9; }");
        css.addRule("ul, ol { margin-left: 23px; } li { margin-bottom: 7px; }");
        css.addRule("td, th { border: 1px solid #45443f; padding: 6px; } hr { color: #45443f; }");
        pane.setEditorKit(kit); pane.setEditable(false); pane.setOpaque(true); pane.setBackground(PANEL);
        pane.setForeground(new Color(0xccc8be));
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setFont(font(14)); pane.setText(html); pane.setSize(BODY_WIDTH, Integer.MAX_VALUE / 64);
        int documentHeight = Math.max(1, pane.getPreferredSize().height);
        pane.setSize(BODY_WIDTH, documentHeight);
        int bodyTop = firstBodyTop(repository);
        int height = Math.addExact(Math.addExact(bodyTop, documentHeight), 54);
        BufferedImage canvas = new BufferedImage(Math.multiplyExact(WIDTH, scale), Math.multiplyExact(height, scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(BG); g.fillRect(0, 0, WIDTH, height);
        int frameTop = titleHeight(repository);
        panel(g, 14, frameTop, WIDTH - 28, height - frameTop - 14, 14, new Color(0x292926));
        drawHeader(g, repository);
        int readmeTop = bodyTop - 44;
        panel(g, 24, readmeTop, WIDTH - 48, height - readmeTop - 24, 11, PANEL);
        text(g, repository.readmePath, 12, MUTED, BODY_X, readmeTop + 24);
        Shape clip = g.getClip();
        g.clip(new Rectangle(BODY_X, bodyTop, BODY_WIDTH, documentHeight));
        g.translate(BODY_X, bodyTop); pane.paint(g); g.translate(-BODY_X, -bodyTop);
        g.setClip(clip);
        text(g, "github.com / " + repository.fullName(), 11, MUTED, BODY_X, height - 34);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(canvas, "png", bytes);
        return bytes.toByteArray();
    }

    private static int titleHeight(GitHubRepository repository) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.setFont(font(36));
        List<String> lines = wrap(g.getFontMetrics(), repository.name, WIDTH - 64);
        boolean inline = inlineBadges(g, repository, lines.get(lines.size() - 1)); g.dispose();
        return 36 + lines.size() * 44 + (hasBadges(repository) && !inline ? 30 : 0);
    }
    private static boolean inlineBadges(Graphics2D g, GitHubRepository repo, String lastLine) {
        int titleWidth = font(36).getStringBounds(lastLine, g.getFontRenderContext()).getBounds().width;
        int badgeWidth = 0;
        for (String label : List.of(repo.archived ? "Archived" : "", repo.stars > 1000 ? "HOT" : "", isNew(repo) ? "NEW" : ""))
            if (!label.isEmpty()) badgeWidth += font(12).getStringBounds(label, g.getFontRenderContext()).getBounds().width + 25;
        return titleWidth + 16 + badgeWidth <= WIDTH - 64;
    }
    private static boolean hasBadges(GitHubRepository repo) { return repo.archived || repo.stars > 1000 || isNew(repo); }
    private static boolean isNew(GitHubRepository repo) {
        if (repo.committedAt == null) return false;
        Duration age = Duration.between(repo.committedAt, repo.fetchedAt);
        return !age.isNegative() && age.compareTo(Duration.ofHours(18)) <= 0;
    }
    private static int firstBodyTop(GitHubRepository repository) {
        int overflowRows = (Math.max(0, repository.contributors.size() - 7) + 11) / 12;
        return titleHeight(repository) + 124 + overflowRows * 45 + (repository.archived ? 44 : 0) + 44;
    }
    static List<Integer> contributorRows(int count) {
        List<Integer> rows = new ArrayList<>();
        if (count > 0) rows.add(Math.min(7, count));
        for (int remaining = count - 7; remaining > 0; remaining -= 12) rows.add(Math.min(12, remaining));
        return rows;
    }
    private static void drawHeader(Graphics2D g, GitHubRepository repo) {
        g.setFont(font(36)); int y = 54;
        List<String> titleLines = wrap(g.getFontMetrics(), repo.name, WIDTH - 64);
        for (String line : titleLines) { text(g, line, 36, FG, 30, y); y += 44; }
        String last = titleLines.get(titleLines.size() - 1);
        boolean inline = inlineBadges(g, repo, last);
        int tagX = inline ? 46 + g.getFontMetrics().stringWidth(last) : 30, tagY = inline ? y - 62 : y - 20;
        if (repo.archived) tagX = badge(g, "Archived", tagX, tagY, new Color(0x453d2e), new Color(0xe0c18f));
        if (repo.stars > 1000) tagX = badge(g, "HOT", tagX, tagY, new Color(0x45332b), new Color(0xe5b294));
        if (isNew(repo)) badge(g, "NEW", tagX, tagY, new Color(0x303c33), GREEN);
        int top = titleHeight(repo);
        String time = relativeTime(repo.committedAt, repo.fetchedAt);
        String stars = compact(repo.stars);
        g.setFont(font(14)); int right = WIDTH - 36;
        int timeWidth = g.getFontMetrics().stringWidth(time);
        text(g, time, 14, MUTED, right - timeWidth, top + 27); clock(g, right - timeWidth - 23, top + 15);
        right -= timeWidth + 48;
        int starWidth = g.getFontMetrics().stringWidth(stars);
        text(g, stars, 14, MUTED, right - starWidth, top + 27); star(g, right - starWidth - 23, top + 15);
        right -= starWidth + 50;
        String hash = repo.sha.isBlank() ? "暂无提交" : repo.sha.substring(0, Math.min(7, repo.sha.length()));
        if (repo.buildState != GitHubRepository.BuildState.NONE) { drawBuildMark(g, repo.buildState, right - 22, top + 10); right -= 30; }
        int hashWidth = g.getFontMetrics().stringWidth(hash);
        text(g, hash, 14, MUTED, right - hashWidth, top + 27); commit(g, right - hashWidth - 23, top + 15);
        int ownerTop = top + 55;
        text(g, "所有者 | 贡献者", 12, MUTED, 36, ownerTop + 8);
        text(g, repo.owner, 20, FG, 36, ownerTop + 34);
        int index = 0, row = 0;
        for (int size : contributorRows(repo.contributors.size())) {
            int rowY = ownerTop - 3 + row * 45;
            int x = WIDTH - 36 - (size * 38 + (size - 1) * 7);
            for (int i = 0; i < size; i++) { avatar(g, repo.contributors.get(index++), x, rowY); x += 45; }
            row++;
        }
        if (repo.archived) {
            int noticeY = firstBodyTop(repo) - 85;
            panel(g, 24, noticeY, WIDTH - 48, 32, 7, new Color(0x393329));
            text(g, "此仓库已归档，目前为只读状态。", 13, new Color(0xddc294), 38, noticeY + 21);
        }
    }

    /** Distinct long ascending stroke, not a symmetric V. */
    static void drawBuildMark(Graphics2D g, GitHubRepository.BuildState state, int x, int y) {
        g.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D mark = new Path2D.Double();
        if (state == GitHubRepository.BuildState.SUCCESS) {
            g.setColor(GREEN); mark.moveTo(x + 1, y + 13); mark.lineTo(x + 7, y + 19); mark.lineTo(x + 24, y + 1);
        } else {
            g.setColor(RED); mark.moveTo(x + 3, y + 3); mark.lineTo(x + 19, y + 19); mark.moveTo(x + 19, y + 3); mark.lineTo(x + 3, y + 19);
        }
        g.draw(mark);
    }
    private static void panel(Graphics2D g, int x, int y, int w, int h, int arc, Color color) {
        g.setColor(color); g.fillRoundRect(x, y, w, h, arc, arc); g.setColor(BORDER); g.setStroke(new BasicStroke(1)); g.drawRoundRect(x, y, w, h, arc, arc);
    }
    private static int badge(Graphics2D g, String label, int x, int y, Color bg, Color fg) {
        g.setFont(font(12)); int width = g.getFontMetrics().stringWidth(label) + 18;
        panel(g, x, y, width, 23, 6, bg); text(g, label, 12, fg, x + 9, y + 16); return x + width + 7;
    }
    private static void avatar(Graphics2D g, GitHubRepository.Contributor person, int x, int y) {
        Shape clip = g.getClip(); g.clip(new RoundRectangle2D.Double(x, y, 38, 38, 11, 11));
        g.setColor(new Color(0x4a493f)); g.fillRect(x, y, 38, 38);
        if (person.avatar != null) g.drawImage(person.avatar, x, y, 38, 38, null);
        else text(g, person.login.substring(0, 1).toUpperCase(Locale.ROOT), 16, FG, x + 13, y + 26);
        g.setClip(clip);
    }
    private static void text(Graphics2D g, String text, int size, Color color, int x, int baseline) { g.setFont(font(size)); g.setColor(color); g.drawString(text, x, baseline); }
    private static void clock(Graphics2D g, int x, int y) { g.setColor(MUTED); g.setStroke(new BasicStroke(1.3f)); g.drawOval(x, y, 13, 13); g.drawLine(x + 7, y + 2, x + 7, y + 7); g.drawLine(x + 7, y + 7, x + 10, y + 8); }
    private static void commit(Graphics2D g, int x, int y) { g.setColor(MUTED); g.setStroke(new BasicStroke(1.3f)); g.drawOval(x + 5, y + 3, 7, 7); g.drawLine(x, y + 7, x + 5, y + 7); g.drawLine(x + 12, y + 7, x + 17, y + 7); }
    private static void star(Graphics2D g, int x, int y) {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < 10; i++) { double angle = -Math.PI / 2 + i * Math.PI / 5, radius = i % 2 == 0 ? 7.5 : 3.3; double px = x + 8 + Math.cos(angle) * radius, py = y + 7 + Math.sin(angle) * radius; if (i == 0) path.moveTo(px, py); else path.lineTo(px, py); }
        path.closePath(); g.setColor(MUTED); g.setStroke(new BasicStroke(1.2f)); g.draw(path);
    }
    static List<String> wrap(FontMetrics metrics, String source, int width) {
        List<String> lines = new ArrayList<>(); StringBuilder line = new StringBuilder();
        for (int index = 0; index < source.length();) {
            int codepoint = source.codePointAt(index); String character = new String(Character.toChars(codepoint));
            if (line.length() > 0 && metrics.stringWidth(line + character) > width) { lines.add(line.toString()); line.setLength(0); }
            line.append(character); index += Character.charCount(codepoint);
        }
        lines.add(line.toString()); return lines;
    }
    private static String compact(long value) { return value < 1000 ? Long.toString(value) : String.format(Locale.ROOT, "%.1f%s", value / (value >= 1_000_000 ? 1_000_000.0 : 1000.0), value >= 1_000_000 ? "m" : "k"); }
    private static String relativeTime(Instant committed, Instant now) {
        if (committed == null) return "暂无提交";
        Duration age = Duration.between(committed, now);
        if (age.isNegative() || age.toDays() >= 30) return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.ofHours(8)).format(committed);
        if (age.toMinutes() < 1) return "刚刚";
        if (age.toHours() < 1) return age.toMinutes() + " 分钟前";
        if (age.toDays() < 1) return age.toHours() + " 小时前";
        return age.toDays() + " 天前";
    }
}
