package com.kami.qqbot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    void logsRequestIdsAndTransportProgressWithoutPayloadOrCredentials() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        ImageCheckTrace trace = new ImageCheckTrace(logs::add);
        AtomicReference<String> clientId = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/check", exchange -> {
            clientId.set(exchange.getRequestHeaders().getFirst("X-Client-Request-Id"));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("x-request-id", "req_test-123");
            exchange.getResponseHeaders().add("openai-processing-ms", "12");
            byte[] response = POSITIVE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(server))
                    .header("Authorization", "Bearer secret-key")
                    .header("X-Client-Request-Id", "client-test-123")
                    .POST(HttpRequest.BodyPublishers.ofString("private-image-payload")).build();
            assertEquals(200, api(server).send(request, 1024, 2, "openai_check", trace).statusCode());
            assertEquals("client-test-123", clientId.get());
            String text = String.join("\n", logs);
            assertTrue(text.contains("请求体交付完成"));
            assertTrue(text.contains("收到响应头 HTTP=200"));
            assertTrue(text.contains("request_id=req_test-123"));
            assertTrue(text.contains("client_request_id=client-test-123"));
            assertTrue(text.contains("openai_processing_ms=12"));
            assertTrue(text.contains("HTTP 完成"));
            assertTrue(logs.stream().allMatch(line -> line.contains("trace=" + trace.id)));
            assertFalse(text.contains("secret-key"));
            assertFalse(text.contains("private-image-payload"));
            assertFalse(text.contains("synthid")); // raw response body is not printed by transport
        } finally { server.stop(0); }
    }

    @Test
    void distinguishesNoHeadersFromStalledResponseBodyOnTimeout() throws Exception {
        for (boolean headers : List.of(false, true)) {
            List<String> logs = new CopyOnWriteArrayList<>();
            CountDownLatch release = new CountDownLatch(1);
            HttpServer server = server();
            server.createContext("/check", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    if (headers) {
                        exchange.getResponseHeaders().add("x-request-id", "req_slow");
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write(new byte[]{1});
                        exchange.getResponseBody().flush();
                    }
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint(server))
                        .POST(HttpRequest.BodyPublishers.ofString("private")).build();
                IOException error = assertThrows(IOException.class, () -> api(server).send(request, 1024, 1,
                        "openai_check", new ImageCheckTrace(logs::add)));
                String text = String.join("\n", logs);
                assertTrue(text.contains("HTTP 超时"));
                assertTrue(error.getMessage().contains(headers ? "phase=receiving_response_body" : "phase=waiting_response_headers"));
                assertTrue(error.getMessage().contains(headers ? "request_id=req_slow" : "request_id=unavailable"));
                if (headers) assertTrue(text.contains("response_bytes=1"));
                assertFalse(text.contains("private"));
            } finally { release.countDown(); server.stop(0); }
        }
    }

    @Test
    void emitsProgressWhileWaitingAndPreservesTimeoutBudget() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server();
        server.createContext("/check", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> api(server).send(HttpRequest.newBuilder(endpoint(server))
                    .POST(HttpRequest.BodyPublishers.ofString("private")).build(), 1024, 6,
                    "openai_check", new ImageCheckTrace(logs::add)));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(logs.stream().anyMatch(line -> line.contains("HTTP 仍在等待")
                    && line.contains("phase=waiting_response_headers")));
            assertTrue(elapsed >= 5800 && elapsed < 9000, "elapsed_ms=" + elapsed);
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void omitsServerErrorMessagesAndUnrecognizedFields() {
        String raw = "{\"error\":{\"type\":\"rate_limit_error\",\"code\":\"insufficient_quota\","
                + "\"message\":\"Bearer private-key https://gchat.qpic.cn/a?secret=1\",\"param\":\"private-image\"}}";
        assertEquals("error_type=rate_limit_error error_code=insufficient_quota",
                ImageProvenanceApi.safeError(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals("error_schema=unavailable", ImageProvenanceApi.safeError("not JSON".getBytes(StandardCharsets.UTF_8)));
        assertEquals("unavailable", ImageCheckTrace.header("unsafe\nheader"));
        assertEquals("IOException>IllegalStateException", ImageCheckTrace.causes(
                new IOException("private-key", new IllegalStateException("private-url"))));
    }

    private static URI endpoint(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/check?signature=private-url");
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
