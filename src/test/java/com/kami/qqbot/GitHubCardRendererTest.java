package com.kami.qqbot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GitHubCardRendererTest {
    @TempDir Path directory;
    @Test void fullMarkdownIsKeptAndLongDocumentsProduceEveryPage() throws Exception {
        String source = "# 这是完整正文\n\n" + "正文需要完整保留，连续分页也不能省略任何段落。\n\n".repeat(180) + "全文最后一段：END_OF_README";
        GitHubCardRenderer renderer = new GitHubCardRenderer(null);
        String html = renderer.documentHtml(GitHubApiTest.fixture(source), directory);
        assertTrue(html.contains("END_OF_README"));
        assertEquals(180, html.split("正文需要完整保留",-1).length-1);
        List<byte[]> pages = renderer.render(GitHubApiTest.fixture(source),1);
        assertTrue(pages.size() > 1);
        for (byte[] png : pages) { var image = ImageIO.read(new ByteArrayInputStream(png)); assertNotNull(image); assertEquals(720,image.getWidth()); assertTrue(image.getHeight() > 100); }
    }
    @Test void scriptsAndFileImagesCannotBeLoadedAndCodeDoesNotLoseCharacters() throws Exception {
        String code = "abc123".repeat(160);
        String source = "# Header\n\n<script>alert('bad')</script>\n\n<img src=\"file:///etc/passwd\" alt=\"image\">\n\n```text\n" + code + "\n```\n\n最后的正文";
        String html = new GitHubCardRenderer(null).documentHtml(GitHubApiTest.fixture(source),directory);
        assertFalse(html.contains("<script")); assertFalse(html.contains("file:/")); assertTrue(html.contains("最后的正文"));
        assertTrue(org.jsoup.Jsoup.parse(html).selectFirst("pre").wholeText().replace("\n", "").contains(code));
    }
    @Test void contributorRowsUseSevenThenTwelveAndNeverDropTheRemainder() {
        assertEquals(List.of(),GitHubCardRenderer.contributorRows(0));
        assertEquals(List.of(3),GitHubCardRenderer.contributorRows(3));
        assertEquals(List.of(7),GitHubCardRenderer.contributorRows(7));
        assertEquals(List.of(7,1),GitHubCardRenderer.contributorRows(8));
        assertEquals(List.of(7,12,1),GitHubCardRenderer.contributorRows(20));
        assertEquals(101,GitHubCardRenderer.contributorRows(101).stream().mapToInt(Integer::intValue).sum());
    }

    @Test void unbrokenLongTextWrapsAcrossPagesRatherThanBeingClippedHorizontally() throws Exception {
        String text = "abcdefghijklmnopqrstuvwxyz".repeat(600) + "END_OF_LONG_LINE";
        GitHubCardRenderer renderer = new GitHubCardRenderer(null);
        assertTrue(renderer.documentHtml(GitHubApiTest.fixture(text), directory).contains("END_OF_LONG_LINE"));
        assertTrue(renderer.render(GitHubApiTest.fixture(text), 1).size() > 1);
    }
}
