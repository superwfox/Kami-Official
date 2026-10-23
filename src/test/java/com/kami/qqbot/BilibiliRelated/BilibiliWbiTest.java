package com.kami.qqbot.BilibiliRelated;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BilibiliWbiTest {
    private static final String IMG = "7cd084941338484aae1ad9425b84077c";
    private static final String SUB = "4932caff0ff746eab6f01bf08b70ac45";

    @Test void mixinKeyMatchesThePublicWebTable() {
        assertEquals("ea1db124af3c7062474693fa704f4ff8", BilibiliWbi.mixinKey(IMG, SUB));
    }

    @Test void imageKeyIgnoresTheCdnPrefixAndQuery() {
        assertEquals(IMG, BilibiliWbi.imageKey("https://i0.hdslb.com/bfs/wbi/" + IMG + ".png"));
        assertEquals(SUB, BilibiliWbi.imageKey("https://i0.hdslb.com/bfs/wbi/" + SUB + ".png?s=0"));
    }

    @Test void signsAKnownBvidQuery() {
        assertEquals("bvid=BV1Ey7A6QEYj&wts=1700000000&w_rid=fd2238fc3b7aa3dae4920ffa6aa0baa0",
                BilibiliWbi.signParameter("bvid=BV1Ey7A6QEYj", BilibiliWbi.mixinKey(IMG, SUB), 1_700_000_000L));
    }

    @Test void signsAidAndSortsKeys() {
        assertEquals("aid=1&wts=1700000000&w_rid=9d0fc28d017325e72f652f180929ac2a",
                BilibiliWbi.signParameter("aid=1", BilibiliWbi.mixinKey(IMG, SUB), 1_700_000_000L));
    }

    @Test void stripsSignatureNoiseAndEncodesSpaceAndNonAscii() {
        Map<String, String> noisy = new LinkedHashMap<>();
        noisy.put("foo", "a!b'c(d)e*");
        noisy.put("bvid", "BV1test");
        assertEquals("bvid=BV1test&foo=abcde&wts=1700000000&w_rid=c58bb2c85fe47052aeb385531b8ee0dc",
                BilibiliWbi.sign(noisy, BilibiliWbi.mixinKey(IMG, SUB), 1_700_000_000L));

        Map<String, String> spaced = new LinkedHashMap<>();
        spaced.put("foo", "a b");
        spaced.put("bvid", "BV1");
        assertEquals("bvid=BV1&foo=a%20b&wts=1700000000&w_rid=f9bedb8d912463e963aad046c0078ea9",
                BilibiliWbi.sign(spaced, BilibiliWbi.mixinKey(IMG, SUB), 1_700_000_000L));

        Map<String, String> text = new LinkedHashMap<>();
        text.put("foo", "中");
        assertEquals("foo=%E4%B8%AD&wts=1700000000&w_rid=c278feee2a06e7d11ce30645543f9a9f",
                BilibiliWbi.sign(text, BilibiliWbi.mixinKey(IMG, SUB), 1_700_000_000L));
    }

    @Test void rejectsKeysThatAreNotAWebImageName() {
        assertThrows(IllegalArgumentException.class, () -> BilibiliWbi.mixinKey("short", "key"));
        assertThrows(IllegalArgumentException.class, () -> BilibiliWbi.imageKey("https://evil.example/../not a key.png"));
    }
}
