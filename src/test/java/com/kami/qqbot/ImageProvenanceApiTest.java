package com.kami.qqbot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ImageProvenanceApiTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 1, 2, 3};
    private static final String POSITIVE = "{\"object\":\"content_provenance_check\",\"results\":["
            + "{\"type\":\"synthid\",\"outcome\":\"detected\",\"model\":null}]}";

    @Test
    void verifiesOnlyConfirmedSupportedSignals() throws Exception {
        assertTrue(ImageProvenanceApi.hasOpenAISignal(POSITIVE));
        assertTrue(ImageProvenanceApi.hasOpenAISignal(c2pa("detected", "trusted", "OpenAI OpCo, LLC")));
        assertTrue(ImageProvenanceApi.hasOpenAISignal(c2pa("detected", "valid", "OpenAI")));
        assertFalse(ImageProvenanceApi.hasOpenAISignal(c2pa("detected", "invalid", "OpenAI OpCo, LLC")));
        assertFalse(ImageProvenanceApi.hasOpenAISignal(c2pa("not_detected", "valid", "Google")));
        assertFalse(ImageProvenanceApi.hasOpenAISignal(c2pa("detected", "valid", "Google")));
        assertFalse(ImageProvenanceApi.hasOpenAISignal(POSITIVE.replace("synthid", "unknown")));
        assertFalse(ImageProvenanceApi.hasOpenAISignal(POSITIVE.replace("\"detected\"", "\"not_detected\"")));
        assertThrows(IOException.class, () -> ImageProvenanceApi.hasOpenAISignal("{\"error\":{}}"));
        assertThrows(IOException.class, () -> ImageProvenanceApi.hasOpenAISignal("not JSON"));
    }

    @Test
    void acceptsQqCdnAndRejectsArbitraryOrCredentialBearingUrls() throws Exception {
        assertEquals("https://gchat.qpic.cn/image?key=a%2Fb",
                ImageProvenanceApi.qqImageUri("http://gchat.qpic.cn/image?key=a%2Fb").toString());
        assertEquals("https://multimedia.nt.qq.com/download",
                ImageProvenanceApi.qqImageUri("//multimedia.nt.qq.com/download").toString());
        assertEquals("https://multimedia.nt.qq.com.cn/download",
                ImageProvenanceApi.qqImageUri("https://multimedia.nt.qq.com.cn/download").toString());
        for (String url : Arrays.asList("file:///etc/passwd", "https://127.0.0.1/img",
                "https://gchat.qpic.cn.evil.example/img", "https://evilqpic.cn/img",
                "https://gchat.qpic.cn@evil.example/img", "https://user:secret@gchat.qpic.cn/img",
                "https://gchat.qpic.cn:8443/img", "ftp://gchat.qpic.cn/img", "not a URI")) {
            assertThrows(IOException.class, () -> ImageProvenanceApi.qqImageUri(url), url);
        }
    }

    @Test
    void preservesUploadedBytesAndCachesSuccessfulChecks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/check", exchange -> {
            calls.incrementAndGet();
            uploaded.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = POSITIVE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ImageProvenanceApi api = api(server);
            assertTrue(api.verify(PNG, "image/png"));
            assertTrue(api.verify(PNG, "image/png"));
            assertEquals(1, calls.get());
            assertEquals("Bearer test-key", authorization.get());
            String boundary = contentType.get().split("boundary=", 2)[1];
            byte[] body = uploaded.get();
            String asText = new String(body, StandardCharsets.ISO_8859_1);
            String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                    + "filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n";
            int offset = header.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(asText.startsWith(header));
            assertArrayEquals(PNG, Arrays.copyOfRange(body, offset, offset + PNG.length));
            assertTrue(asText.endsWith("\r\n--" + boundary + "--\r\n"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rateLimitDoesNotTurnIntoPositiveAndPausesMoreRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/check", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "60");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        try {
            ImageProvenanceApi api = api(server);
            assertThrows(IOException.class, () -> api.verify(PNG, "image/png"));
            assertThrows(IOException.class, () -> api.verify(PNG, "image/png"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedResponsesBeforeParsing() throws Exception {
        HttpServer server = server();
        server.createContext("/check", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = new byte[1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, 0); // chunked，不能只依赖 Content-Length。
            try {
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            assertThrows(IOException.class, () -> api(server).verify(PNG, "image/png"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void identifiesSupportedBytesInsteadOfTrustingFilename() {
        assertEquals("image/png", ImageProvenanceApi.imageMime(PNG));
        assertEquals("image/jpeg", ImageProvenanceApi.imageMime(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}));
        assertEquals("image/webp", ImageProvenanceApi.imageMime("RIFF1234WEBP".getBytes(StandardCharsets.US_ASCII)));
        assertNull(ImageProvenanceApi.imageMime("GIF89a".getBytes(StandardCharsets.US_ASCII)));
        assertNull(ImageProvenanceApi.imageMime("<html>login</html>".getBytes(StandardCharsets.US_ASCII)));
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static ImageProvenanceApi api(HttpServer server) {
        return new ImageProvenanceApi("test-key", URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/check"), HttpClient.newHttpClient());
    }

    private static String c2pa(String outcome, String state, String issuer) {
        return "{\"object\":\"content_provenance_check\",\"results\":[{\"type\":\"c2pa\",\"outcome\":\""
                + outcome + "\",\"validation_state\":\"" + state + "\",\"issuer\":\"" + issuer + "\"}]}";
    }
}
