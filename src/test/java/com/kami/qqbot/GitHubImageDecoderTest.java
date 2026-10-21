package com.kami.qqbot;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class GitHubImageDecoderTest {
    @Test void svgBadgesKeepTheirLogicalSizeAndAreRasterizedAtHighResolution() throws Exception {
        var image = GitHubImageDecoder.decode(("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 120 20\">"
                + "<rect width=\"120\" height=\"20\" rx=\"4\" fill=\"#333333\"/>"
                + "<rect x=\"70\" width=\"50\" height=\"20\" fill=\"#11ee33\"/></svg>").getBytes(StandardCharsets.UTF_8));
        assertEquals(120, GitHubImageDecoder.width(image));
        assertEquals(20, GitHubImageDecoder.height(image));
        assertEquals(360, image.getWidth()); assertEquals(60, image.getHeight());
        assertEquals(0x11ee33, image.getRGB(300,30) & 0xffffff);
        assertEquals(0, image.getRGB(0,0) >>> 24);
    }
    @Test void webpDecodesUsingThePackagedPureJavaProvider() throws Exception {
        var image = GitHubImageDecoder.decode(Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4TBcAAAAvD8ACEA8wdxMx3fMf8Lj/f0T/w///HwA="));
        assertEquals(16, image.getWidth()); assertEquals(12, image.getHeight());
        assertEquals(0x1177dd, image.getRGB(8,6) & 0xffffff);
        assertEquals(0, image.getRGB(0,0) >>> 24);
    }
    @Test void svgCannotResolveXmlEntitiesOrLoadExternalImages() {
        String entity = "<!DOCTYPE svg [<!ENTITY file SYSTEM 'file:///etc/passwd'>]><svg xmlns='http://www.w3.org/2000/svg'><text>&file;</text></svg>";
        assertThrows(IOException.class, () -> GitHubImageDecoder.decode(entity.getBytes(StandardCharsets.UTF_8)));
        String external = "<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' width='40' height='40'><image width='40' height='40' xlink:href='file:///etc/passwd'/></svg>";
        assertThrows(IOException.class, () -> GitHubImageDecoder.decode(external.getBytes(StandardCharsets.UTF_8)));
    }
}
