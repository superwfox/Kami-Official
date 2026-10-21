package com.kami.qqbot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.awt.image.BufferedImage;
import java.awt.Color;
import org.jsoup.Jsoup;
import static org.junit.jupiter.api.Assertions.*;

class GitHubCardRendererTest {
    @TempDir Path directory;
    @Test void fullMarkdownIsKeptInOneLongImage() throws Exception {
        String source = "# 这是完整正文\n\n" + "正文需要完整保留，一张长图也不能省略任何段落。\n\n".repeat(180) + "全文最后一段：END_OF_README";
        GitHubCardRenderer renderer = new GitHubCardRenderer(null);
        String html = renderer.documentHtml(GitHubApiTest.fixture(source), directory);
        assertTrue(html.contains("END_OF_README"));
        assertEquals(180, html.split("正文需要完整保留",-1).length-1);
        byte[] png = renderer.render(GitHubApiTest.fixture(source),1);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image); assertEquals(720,image.getWidth()); assertTrue(image.getHeight() > 2000);
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

    @Test void unbrokenLongTextWrapsWithinOneTallImage() throws Exception {
        String text = "abcdefghijklmnopqrstuvwxyz".repeat(600) + "END_OF_LONG_LINE";
        GitHubCardRenderer renderer = new GitHubCardRenderer(null);
        assertTrue(renderer.documentHtml(GitHubApiTest.fixture(text), directory).contains("END_OF_LONG_LINE"));
        assertTrue(ImageIO.read(new ByteArrayInputStream(renderer.render(GitHubApiTest.fixture(text), 1))).getHeight() > 2000);
    }

    @Test void threeImagesInOneHtmlTableRowAreAllVisibleWithoutHorizontalCropping() throws Exception {
        String source = "# Three images\n\n<table><tr>"
                + "<td width=\"33.33%\"><img src=\"red.png\" width=\"100%\"></td>"
                + "<td width=\"33.33%\"><img src=\"green.png\" width=\"100%\"></td>"
                + "<td width=\"33.33%\"><img src=\"blue.png\" width=\"100%\"></td>"
                + "</tr></table>\n\nText after the three images. END_OF_README";
        int[] colors = {0xff1122,0x11ee33,0x1122ff};
        GitHubCardRenderer renderer = new GitHubCardRenderer(null, url -> {
            int color = url.endsWith("red.png") ? colors[0] : url.endsWith("green.png") ? colors[1] : colors[2];
            BufferedImage image = new BufferedImage(300,450,BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); graphics.setColor(new Color(color)); graphics.fillRect(0,0,300,450); graphics.dispose();
            return image;
        });
        var html = Jsoup.parse(renderer.documentHtml(GitHubApiTest.fixture(source),directory));
        assertEquals(3,html.select("img").size());
        html.select("img").forEach(image -> assertTrue(Integer.parseInt(image.attr("width")) <= 186));
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(renderer.render(GitHubApiTest.fixture(source),1)));
        int previousRight = 0, previousTop = -1;
        for(int color : colors) {
            int left=output.getWidth(),right=-1,top=output.getHeight(),pixels=0;
            for(int y=0;y<output.getHeight();y++)for(int x=0;x<output.getWidth();x++)if((output.getRGB(x,y)&0xffffff)==color){
                left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);pixels++;
            }
            assertTrue(pixels > 40000,"Each image must be fully visible, including the second and third");
            assertTrue(left>previousRight);assertTrue(right<672);
            if(previousTop>=0)assertEquals(previousTop,top);
            previousRight=right;previousTop=top;
        }
    }
}
