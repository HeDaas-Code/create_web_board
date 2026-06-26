package com.example.webboard.content.httpserver;

/**
 * ServerConfig — HTTP server settings, immutable.
 *
 * <p>Defaults are localhost-only on port 8080 — never expose publicly. Issue #4 adds TOML
 * loading via {@link ConfigLoader}; the record itself stays minimal.
 */
public record ServerConfig(String host, int port, int maxWsConnections) {

    /** Default config: localhost-only on port 8080. */
    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", 8080, 16);
    }

    /** Returns a copy with a different port (used by tests for ephemeral port assignment). */
    public ServerConfig withPort(int newPort) {
        return new ServerConfig(host, newPort, maxWsConnections);
    }
}
