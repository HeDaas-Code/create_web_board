package com.example.webboard.content.httpserver;

/**
 * ServerConfig — simple holder for HTTP server settings.
 *
 * <p>Issue #4 will replace this with a TOML-backed config (using NeoForge's ConfigBuilder or
 * JomlConfig). For #2, hardcoded defaults are fine — they're trivially overridable later.
 */
public record ServerConfig(String host, int port) {

    /** Default config: localhost-only on port 8080. */
    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", 8080);
    }
}