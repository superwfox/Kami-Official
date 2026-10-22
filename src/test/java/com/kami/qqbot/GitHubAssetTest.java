package com.kami.qqbot;

import org.junit.jupiter.api.Test;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubAssetTest {
    private static final String ATTACHMENT = "https://github.com/user-attachments/assets/example";
    private static final String STORAGE = "https://github-production-user-asset-6210df.s3.amazonaws.com/123/image.webp?signature=example";
    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4TBcAAAAvD8ACEA8wdxMx3fMf8Lj/f0T/w///HwA=");

    @Test void githubAttachmentRedirectsDecodeWithoutForwardingApiCredentials() throws Exception {
        Client client = new Client(); client.locations.put(ATTACHMENT, STORAGE);
        var image = api(client).asset(ATTACHMENT);
        assertNotNull(image); assertEquals(16, image.getWidth()); assertEquals(12, image.getHeight());
        assertEquals(List.of(ATTACHMENT, STORAGE), client.requests);
        assertEquals(2, client.closed);
    }
    @Test void everyRedirectMustStayOnAnExplicitHttpsImageHost() throws Exception {
        for (String destination : List.of("https://attacker.example/image.png", "http://github.com/image.png",
                "https://127.0.0.1/image.png", "https://github.com:8443/image.png", "https://user@github.com/image.png",
                "https://github-production-user-asset-6210df.s3.amazonaws.com.attacker.example/image.png",
                "https://unrelated-bucket.s3.amazonaws.com/image.png", "file:///etc/passwd")) {
            Client client = new Client(); client.locations.put(ATTACHMENT, destination);
            assertNull(api(client).asset(ATTACHMENT), destination);
            assertEquals(List.of(ATTACHMENT), client.requests);
            assertEquals(1, client.closed);
        }
    }
    @Test void missingLocationLoopsAndLongRedirectChainsStopWithoutDecoding() throws Exception {
        Client missing = new Client(); missing.locations.put(ATTACHMENT, "");
        assertNull(api(missing).asset(ATTACHMENT)); assertEquals(1, missing.requests.size());
        Client loop = new Client(); loop.locations.put(ATTACHMENT, ATTACHMENT);
        assertNull(api(loop).asset(ATTACHMENT)); assertEquals(1, loop.requests.size());
        Client chain = new Client(); String current = ATTACHMENT;
        for (int i=0;i<5;i++) { String next = "https://github.com/image/"+i; chain.locations.put(current,next); current=next; }
        assertNull(api(chain).asset(ATTACHMENT)); assertEquals(4,chain.requests.size()); assertEquals(4,chain.closed);
    }
    @Test void relativeRedirectsAreResolvedAndTransientErrorsStillRetry() throws Exception {
        Client client = new Client(); client.locations.put(ATTACHMENT, "/image.webp"); client.failures = 1;
        assertNotNull(api(client).asset(ATTACHMENT));
        assertEquals(List.of(ATTACHMENT, ATTACHMENT, "https://github.com/image.webp"), client.requests);
        assertEquals(3,client.closed);
    }
    private static GitHubApi api(Client client) {
        return new GitHubApi(client, URI.create("https://api.github.com/"), "private-api-token", Clock.systemUTC());
    }
    private static final class Client extends HttpClient {
        final Map<String,String> locations = new HashMap<>();
        final List<String> requests = new ArrayList<>();
        int closed, failures;
        @Override @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            assertTrue(request.headers().firstValue("Authorization").isEmpty());
            String url = request.uri().toString(); requests.add(url);
            int status = failures-- > 0 ? 503 : locations.containsKey(url) ? 302 : 200;
            Map<String,List<String>> headers = status == 302 && !locations.get(url).isEmpty()
                    ? Map.of("Location",List.of(locations.get(url))) : Map.of();
            InputStream body = new ByteArrayInputStream(status == 200 ? WEBP : new byte[0]) {
                @Override public void close() throws IOException { closed++; super.close(); }
            };
            return new HttpResponse<T>() {
                public int statusCode() { return status; }
                public HttpRequest request() { return request; }
                public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return HttpHeaders.of(headers,(a,b)->true); }
                public T body() { return (T) body; }
                public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            };
        }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
    }
}
