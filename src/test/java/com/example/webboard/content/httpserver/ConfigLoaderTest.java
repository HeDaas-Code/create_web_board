package com.example.webboard.content.httpserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ConfigLoaderTest — pure-Java tests for the hand-rolled TOML parser. No Javalin, no MC.
 */
class ConfigLoaderTest {

    @Test
    void emptyString_returnsAllDefaults() {
        ServerConfig c = ConfigLoader.parse("");
        assertEquals("127.0.0.1", c.host());
        assertEquals(8080, c.port());
        assertEquals(16, c.maxWsConnections());
    }

    @Test
    void noServerSection_returnsAllDefaults() {
        ServerConfig c = ConfigLoader.parse("# nothing here\n[other]\nfoo = 1\n");
        assertEquals("127.0.0.1", c.host());
        assertEquals(8080, c.port());
        assertEquals(16, c.maxWsConnections());
    }

    @Test
    void fullSection_overridesAllValues() {
        ServerConfig c = ConfigLoader.parse("""
                # sample config
                [server]
                host = "0.0.0.0"
                port = 9090
                maxWsConnections = 32
                """);
        assertEquals("0.0.0.0", c.host());
        assertEquals(9090, c.port());
        assertEquals(32, c.maxWsConnections());
    }

    @Test
    void partialSection_fallsBackPerKey() {
        ServerConfig c = ConfigLoader.parse("[server]\nport = 1234\n");
        assertEquals("127.0.0.1", c.host());          // default
        assertEquals(1234, c.port());                  // overridden
        assertEquals(16, c.maxWsConnections());       // default
    }

    @Test
    void commentsAndBlankLines_ignored() {
        ServerConfig c = ConfigLoader.parse("""
                # this is a comment
                # another comment

                [server]
                # inline comment
                port = 5555
                """);
        assertEquals(5555, c.port());
    }

    @Test
    void unknownKey_ignoredAndOtherValuesStillApplied() {
        ServerConfig c = ConfigLoader.parse("""
                [server]
                port = 7777
                unknownKey = "whatever"
                anotherUnknown = 99
                """);
        assertEquals(7777, c.port());
    }

    @Test
    void malformedNumber_fallsBackToDefaultForThatKey() {
        ServerConfig c = ConfigLoader.parse("""
                [server]
                port = "not-a-number"
                maxWsConnections = 8
                """);
        assertEquals(8080, c.port());         // default — bad parse
        assertEquals(8, c.maxWsConnections()); // still applied
    }

    @Test
    void hostMustBeQuoted_unquotedFallsBack() {
        ServerConfig c = ConfigLoader.parse("[server]\nhost = 0.0.0.0\n");
        assertEquals("127.0.0.1", c.host());   // unquoted rejected → default
    }
}
