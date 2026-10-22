package com.kami.qqbot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    @Test void centeredReadmeTitleAndSeparateNavigationLinksArePreserved() throws Exception {
        String source = "<h1 align=\"center\">Ion</h1>\n<p align=\"center\"><strong>"
                + "<a href=\"https://github.com/r2ion/Ion/releases/latest\">Download</a> | "
                + "<a href=\"https://thunderstore.io/\">Thunderstore</a> | "
                + "<a href=\"https://discord.gg/example\">Discord</a></strong></p>";
        var html = Jsoup.parse(new GitHubCardRenderer(null).documentHtml(GitHubApiTest.fixture(source), directory));
        assertEquals("center",html.selectFirst("h1").attr("align"));
        assertEquals("center",html.selectFirst("p").attr("align"));
        assertEquals("Download | Thunderstore | Discord",html.selectFirst("p").text());
    }

    @Test void adjacentAttachmentImagesWrapInsteadOfWideningAndCroppingTheEntireReadme() throws Exception {
        String source = "# Centered title\n\n![banner](banner.png)\n<img src=\"small.webp\" align=\"left\">\n\n"
                + "This paragraph must wrap inside the card. ".repeat(20);
        GitHubCardRenderer renderer = new GitHubCardRenderer(null,url -> {
            int width=url.endsWith("banner.png")?720:320;
            BufferedImage image=new BufferedImage(width,100,BufferedImage.TYPE_INT_RGB);
            var g=image.createGraphics();g.setColor(new Color(width==720?0xff1122:0x1122ff));g.fillRect(0,0,width,100);g.dispose();
            return image;
        });
        var html=Jsoup.parse(renderer.documentHtml(GitHubApiTest.fixture(source),directory));
        assertEquals(1,html.select("p > br").size());
        BufferedImage output=ImageIO.read(new ByteArrayInputStream(renderer.render(GitHubApiTest.fixture(source),1)));
        int redBottom=-1,blueTop=output.getHeight(),bluePixels=0;
        for(int y=0;y<output.getHeight();y++)for(int x=0;x<output.getWidth();x++) {
            int color=output.getRGB(x,y)&0xffffff;
            if(color==0xff1122)redBottom=Math.max(redBottom,y);
            if(color==0x1122ff){blueTop=Math.min(blueTop,y);bluePixels++;assertTrue(x>=48 && x<672);}
        }
        assertTrue(blueTop>redBottom);assertEquals(32000,bluePixels);
    }

    @Test void contributorRowsUseSevenThenTwelveAndNeverDropTheRemainder() {
        assertEquals(List.of(),GitHubCardRenderer.contributorRows(0));
        assertEquals(List.of(3),GitHubCardRenderer.contributorRows(3));
        assertEquals(List.of(7),GitHubCardRenderer.contributorRows(7));
        assertEquals(List.of(7,1),GitHubCardRenderer.contributorRows(8));
        assertEquals(List.of(7,12,1),GitHubCardRenderer.contributorRows(20));
        assertEquals(101,GitHubCardRenderer.contributorRows(101).stream().mapToInt(Integer::intValue).sum());
    }

    @Test void codeBlocksKeepTheirLanguageAndIntentionalWhitespaceWithoutAnExtraEmptyRow() throws Exception {
        String source = "```bash title=example\n  echo first\n\n  echo last\n```\n\n```\nplain\n```";
        var html = Jsoup.parse(new GitHubCardRenderer(null).documentHtml(GitHubApiTest.fixture(source), directory));
        var blocks = html.select("pre");
        assertEquals(2, blocks.size());
        assertEquals("BASH", blocks.get(0).attr("title"));
        assertEquals("  echo first\n\n  echo last", blocks.get(0).wholeText());
        assertEquals("文本", blocks.get(1).attr("title"));
        assertEquals("plain", blocks.get(1).wholeText());
    }

    @Test void unbrokenLongTextWrapsWithinOneTallImage() throws Exception {
        String text = "abcdefghijklmnopqrstuvwxyz".repeat(600) + "END_OF_LONG_LINE";
        GitHubCardRenderer renderer = new GitHubCardRenderer(null);
        assertTrue(renderer.documentHtml(GitHubApiTest.fixture(text), directory).contains("END_OF_LONG_LINE"));
        assertTrue(ImageIO.read(new ByteArrayInputStream(renderer.render(GitHubApiTest.fixture(text), 1))).getHeight() > 2000);
    }

    @Test void profilePicturesChooseDarkArtworkAndKeepTheRightImageBesideTheText() throws Exception {
        String source = "<img align=\"right\" width=\"47%\" src=\"art.webp\" alt=\"Art\">\n\n"
                + "<picture><source media=\"(prefers-color-scheme: dark)\" srcset=\"dark.svg\"><img width=\"48%\" src=\"light.svg\" alt=\"Wordmark\"></picture>\n\n"
                + "## AI SYSTEMS\n\nPlanning · Memory · Telemetry  \nRecovery · Delivery\n\n"
                + "<br clear=\"right\">\n\n---\n\nEND_OF_README";
        List<String> requests = new ArrayList<>();
        GitHubCardRenderer renderer = new GitHubCardRenderer(null, url -> {
            requests.add(url);
            if (url.endsWith("dark.svg")) {
                try { return GitHubImageDecoder.decode("<svg xmlns='http://www.w3.org/2000/svg' width='120' height='20'><rect width='120' height='20' fill='#00cbd6'/></svg>".getBytes(StandardCharsets.UTF_8)); }
                catch (java.io.IOException e) { throw new AssertionError(e); }
            }
            BufferedImage image = new BufferedImage(300,250,BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); graphics.setColor(new Color(0x1122ff)); graphics.fillRect(0,0,300,250); graphics.dispose();
            return image;
        });
        var html = Jsoup.parse(renderer.documentHtml(GitHubApiTest.fixture(source),directory));
        assertEquals(1,html.select("table.readme-float").size());
        assertEquals(2,html.select("img").size());
        assertTrue(requests.stream().anyMatch(url -> url.endsWith("dark.svg")));
        assertFalse(requests.stream().anyMatch(url -> url.endsWith("light.svg")));
        assertTrue(html.text().contains("END_OF_README"));
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(renderer.render(GitHubApiTest.fixture(source),1)));
        int cyanRight = -1, blueLeft = output.getWidth(), blueRight = -1, bluePixels = 0;
        for(int y=0;y<output.getHeight();y++)for(int x=0;x<output.getWidth();x++){
            int color=output.getRGB(x,y)&0xffffff;
            if(color==0x00cbd6)cyanRight=Math.max(cyanRight,x);
            if(color==0x1122ff){blueLeft=Math.min(blueLeft,x);blueRight=Math.max(blueRight,x);bluePixels++;}
        }
        assertTrue(cyanRight>0); assertTrue(cyanRight<blueLeft);
        assertTrue(blueRight<672); assertTrue(bluePixels>40000);
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

    @Test void paragraphsAfterAShortFloatReturnToTheFullBodyWidth() throws Exception {
        String source = "<img align=\"right\" width=\"47%\" src=\"banner.png\">\n\n"
                + "## Heading beside the image\n\nFull-width paragraph after the image.\n\nAnother complete paragraph.\n\n<br clear=\"right\">";
        GitHubCardRenderer renderer = new GitHubCardRenderer(null, url -> new BufferedImage(300,30,BufferedImage.TYPE_INT_ARGB));
        var html = Jsoup.parse(renderer.documentHtml(GitHubApiTest.fixture(source),directory));
        var layout = html.selectFirst("table.readme-float");
        assertNotNull(layout);
        assertTrue(layout.text().contains("Heading beside the image"));
        assertFalse(layout.text().contains("Full-width paragraph"));
        assertTrue(layout.nextElementSibling().text().contains("Full-width paragraph"));
        assertEquals(1,html.select("img").size());
    }
}
