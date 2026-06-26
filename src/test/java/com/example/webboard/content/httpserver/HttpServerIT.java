package com.example.webboard.content.httpserver;

import com.example.webboard.content.registry.BoardContent;
import com.example.webboard.content.registry.BoardRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpServerIT — end-to-end integration tests against a real running Javalin server.
 * Skips the MC event bus entirely; constructs HttpServer directly so the test is decoupled
 * from MC's runtime and CI can run it without a GPU.
 *
 * <p>Uses an ephemeral port (0) so CI does not collide with other test runs / operators'
 * local 8080 servers. The actual bound port is exposed via {@link HttpServer#port()}.
 */
class HttpServerIT {

    private HttpServer server;
    private BoardRegistry registry;
    private HttpClient http;
    // Field initializer runs per JUnit5 test instance (per-method), so each test gets a fresh
    // port. The ServerSocket inside pickFreePort is closed before start() runs — there's a
    // small race where another process could grab the same port, but it's vanishingly rare
    // in CI; if it ever happens, the test fails fast with a BindException, easy to spot.
    private final int port = pickFreePort();

    @BeforeEach
    void start() {
        registry = BoardRegistry.get();
        registry.clearAll();
        server = new HttpServer(ServerConfig.defaults().withPort(port), registry);
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @Test
    void healthEndpoint_returns200AndJson() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"status\":\"ok\""), "body was: " + r.body());
        assertTrue(r.body().contains("\"boards\":0"));
    }

    @Test
    void boardsEndpoint_emptyRegistry_returnsEmptyArray() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("[]", r.body().trim());
    }

    @Test
    void boardsEndpoint_afterPut_returnsBoard() throws Exception {
        registry.put(BoardContent.of("factory", "create_web_board:web_board", List.of("line 1", "line 2")));

        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"name\":\"factory\""), "body was: " + r.body());
        assertTrue(r.body().contains("\"line 1\""));
    }

    @Test
    void boardByName_existing_returnsContent() throws Exception {
        registry.put(BoardContent.of("alpha", "create_web_board:web_board", List.of("a-row")));

        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards/alpha"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"name\":\"alpha\""));
    }

    @Test
    void boardByName_missing_returns404() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards/nope"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, r.statusCode());
    }

    @Test
    void staticIndex_served() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Create Web Board"), "body was: " + r.body().substring(0, Math.min(80, r.body().length())));
    }

    @Test
    void websocketConnects_andReceivesSnapshot() throws Exception {
        CountDownLatch snapshotReceived = new CountDownLatch(1);
        AtomicReference<String> firstMessage = new AtomicReference<>();

        // Pre-populate so snapshot has something to deliver.
        registry.put(BoardContent.of("ws-board", "create_web_board:web_board", List.of("hi")));

        java.net.http.WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"),
                        new java.net.http.WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                                firstMessage.compareAndSet(null, data.toString());
                                snapshotReceived.countDown();
                                webSocket.request(1);
                                return null;
                            }
                        })
                .get(2, TimeUnit.SECONDS);

        boolean got = snapshotReceived.await(3, TimeUnit.SECONDS);
        ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);

        assertTrue(got, "did not receive WS snapshot within 3s; got: " + firstMessage.get());
        String msg = firstMessage.get();
        assertTrue(msg.contains("\"type\":\"snapshot\""), "msg was: " + msg);
        assertTrue(msg.contains("\"name\":\"ws-board\""));
    }

    // ---- helpers ----

    private static int pickFreePort() {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("could not pick free port", e);
        }
    }
}
