package com.example.webboard.content.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BoardRegistryTest — exercises the registry's core operations. No MC, no Javalin.
 */
class BoardRegistryTest {

    @BeforeEach
    void clearRegistry() {
        BoardRegistry.get().clearAll();
    }

    @Test
    void put_thenGet_returnsContent() {
        BoardContent c = BoardContent.of("line1", "create_web_board:web_board", List.of("a", "b"));
        BoardRegistry.get().put(c);
        assertEquals(c, BoardRegistry.get().get("line1"));
    }

    @Test
    void remove_existingBoard_returnsIt() {
        BoardRegistry.get().put(BoardContent.of("x", "t", List.of("a")));
        BoardRegistry.get().remove("x");
        assertNull(BoardRegistry.get().get("x"));
    }

    @Test
    void remove_missingBoard_isNoOp() {
        BoardRegistry.get().remove("nonexistent"); // must not throw
    }

    @Test
    void put_firesListener() {
        AtomicInteger fired = new AtomicInteger();
        BoardRegistry.get().addListener(ev -> fired.incrementAndGet());
        BoardRegistry.get().put(BoardContent.of("a", "t", List.of()));
        assertEquals(1, fired.get());
    }

    @Test
    void remove_firesListenerOnlyIfExisted() {
        AtomicInteger fired = new AtomicInteger();
        BoardRegistry.get().addListener(ev -> fired.incrementAndGet());
        BoardRegistry.get().remove("missing");
        assertEquals(0, fired.get());
        BoardRegistry.get().put(BoardContent.of("a", "t", List.of()));
        assertEquals(1, fired.get());
        BoardRegistry.get().remove("a");
        assertEquals(2, fired.get());
    }

    @Test
    void clearAll_firesRemoveForEveryBoard() {
        AtomicInteger fired = new AtomicInteger();
        BoardRegistry.get().addListener(ev -> {
            if (ev instanceof BoardRegistry.ChangeEvent.Remove) fired.incrementAndGet();
        });
        BoardRegistry.get().put(BoardContent.of("a", "t", List.of()));
        BoardRegistry.get().put(BoardContent.of("b", "t", List.of()));
        BoardRegistry.get().put(BoardContent.of("c", "t", List.of()));
        assertEquals(0, fired.get());
        BoardRegistry.get().clearAll();
        assertEquals(3, fired.get());
        assertEquals(0, BoardRegistry.get().size());
    }

    @Test
    void all_returnsSnapshot() {
        BoardRegistry.get().put(BoardContent.of("a", "t", List.of("x")));
        BoardRegistry.get().put(BoardContent.of("b", "t", List.of("y")));
        var all = BoardRegistry.get().all();
        assertEquals(2, all.size());
        assertNotNull(all); // sanity
    }
}
