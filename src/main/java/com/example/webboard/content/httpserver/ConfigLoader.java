package com.example.webboard.content.httpserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ConfigLoader — hand-rolled TOML reader for the tiny {@code [server]} section we expose.
 *
 * <p><b>Why hand-rolled</b>: NeoForge's built-in config system targets Java code generation
 * (annotation-processed classes) and binds the config to the mod loader lifecycle, which
 * makes it untestable in isolation. For 3 keys we don't need a TOML library — a 50-line
 * scanner is enough, dependency-free, and trivially testable.
 *
 * <p>Format expected (one optional section, key=value pairs, {@code #} comments):
 * <pre>
 * # comment
 * [server]
 * host = "0.0.0.0"
 * port = 9090
 * maxWsConnections = 32
 * </pre>
 * Unknown keys are silently ignored (forward compat). Missing keys fall back to the defaults
 * from {@link ServerConfig#defaults()}. Missing file → defaults. Malformed line → defaults
 * for the whole file (logged) — a typo in one line should not brick the server.
 */
public final class ConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {}

    /** Loads from the given path, or returns defaults if the file is missing/unreadable. */
    public static ServerConfig load(Path tomlFile) {
        if (tomlFile == null || !Files.exists(tomlFile)) {
            LOGGER.debug("[web_board] no config file at {}; using defaults", tomlFile);
            return ServerConfig.defaults();
        }
        try {
            String text = Files.readString(tomlFile);
            return parse(text);
        } catch (IOException e) {
            LOGGER.warn("[web_board] failed to read config {}: {}; using defaults",
                    tomlFile, e.toString());
            return ServerConfig.defaults();
        }
    }

    /** Parse a TOML string. Visible for testing. */
    public static ServerConfig parse(String toml) {
        String host = "127.0.0.1";
        int port = 8080;
        int maxWs = 16;
        boolean inServerSection = false;
        boolean sawAnyKey = false;

        for (String rawLine : toml.split("\\R")) {
            String line = rawLine.strip();
            // Skip blanks + comments
            if (line.isEmpty() || line.startsWith("#")) continue;
            // Section header
            if (line.startsWith("[")) {
                inServerSection = line.equals("[server]");
                continue;
            }
            if (!inServerSection) continue;
            // key = value
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();

            try {
                switch (key) {
                    case "host" -> {
                        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                            host = value.substring(1, value.length() - 1);
                            sawAnyKey = true;
                        } else {
                            LOGGER.warn("[web_board] config: host must be a quoted string, got {}", value);
                        }
                    }
                    case "port" -> {
                        port = Integer.parseInt(value);
                        sawAnyKey = true;
                    }
                    case "maxWsConnections" -> {
                        maxWs = Integer.parseInt(value);
                        sawAnyKey = true;
                    }
                    default -> LOGGER.debug("[web_board] config: ignoring unknown key {}", key);
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("[web_board] config: bad value for {}: {} ({}); using default",
                        key, value, e.toString());
            }
        }

        if (!sawAnyKey) {
            LOGGER.debug("[web_board] config: [server] section empty or missing; using all defaults");
        }
        return new ServerConfig(host, port, maxWs);
    }
}
