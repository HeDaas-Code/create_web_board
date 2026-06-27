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

    // ---- Tags API ----
    // These exercise the product-tagging feature added in v0.5.0. The dashboard clusters cards
    // by tag, and the modal's tag editor PUTs here. setTags fires a Put event so the WS hub
    // rebroadcasts the updated tag array to all connected browsers.

    @Test
    void putTags_existingBoard_returnsUpdatedTags() throws Exception {
        registry.put(BoardContent.of("tagged", "create_web_board:web_board", List.of("x")));

        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards/tagged/tags"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"tags\":[\"冶炼\",\"一楼\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"冶炼\""), "body should contain tag 冶炼: " + r.body());
        assertTrue(r.body().contains("\"一楼\""));
        // The registry should now reflect the update.
        assertEquals(List.of("冶炼", "一楼"), registry.get("tagged").tags());
    }

    @Test
    void putTags_missingBoard_returns404() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards/nope/tags"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"tags\":[\"a\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, r.statusCode());
    }

    @Test
    void putTags_emptyArray_clearsTags() throws Exception {
        registry.put(BoardContent.of("clear-me", "create_web_board:web_board", List.of("x")));

        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards/clear-me/tags"))
                .timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"tags\":[\"temp\"]}")).build(),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards/clear-me/tags"))
                        .timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"tags\":[]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"tags\":[]"), "tags should be empty: " + r.body());
        assertTrue(registry.get("clear-me").tags().isEmpty());
    }

    // ---- Product items API ----
    // v0.5.0 added multi-item selection to the modal — a board can represent a line producing
    // several items. PUT /api/boards/{name}/items replaces the whole item-id list.

    @Test
    void putItems_existingBoard_returnsUpdatedItemIds() throws Exception {
        registry.put(BoardContent.of("producer", "create_web_board:web_board", List.of("x")));

        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards/producer/items"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"itemIds\":[\"minecraft:iron_ingot\",\"create:cogwheel\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("minecraft:iron_ingot"), "body: " + r.body());
        assertTrue(r.body().contains("create:cogwheel"));
        assertEquals(List.of("minecraft:iron_ingot", "create:cogwheel"),
                registry.get("producer").itemIds());
    }

    @Test
    void putItems_missingBoard_returns404() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/boards/nope/items"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"itemIds\":[\"minecraft:stick\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, r.statusCode());
    }

    // ---- Icon-pack status ----
    // Without a client uploading rendered icons, IconPackStorage is uninitialized — the status
    // endpoint must report count=0 + initialized=false so the dashboard can show its "no icons
    // yet" hint. (When a client connects and uploads, count goes up; that path needs a live MC
    // client and is covered by the field test instead.)

    @Test
    void iconPackStatus_whenUninitialized_returnsZeroCount() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/icon-pack/status"))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        // IconPackStorage is not init'd in the test JVM (no MC server running), so this is the
        // "pure dedicated server, no client yet" state.
        assertTrue(r.body().contains("\"count\":0"), "body: " + r.body());
        assertTrue(r.body().contains("\"initialized\":false"), "body: " + r.body());
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
