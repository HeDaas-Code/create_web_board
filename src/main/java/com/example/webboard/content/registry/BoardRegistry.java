package com.example.webboard.content.registry;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * BoardRegistry — singleton in-memory store of {@link BoardContent} keyed by Display Link name.
 *
 * <p><b>Thread model</b>: writes happen on the MC game thread (DisplaySource tick),
 * reads/writes/listener calls happen on Javalin HTTP/WS threads (off-game-thread).
 * {@link ConcurrentHashMap} covers map safety; the {@link CopyOnWriteArraySet} for listeners
 * ensures iteration during concurrent add/remove doesn't ConcurrentModification.
 *
 * <p><b>Listener discipline</b>: listeners MUST be non-blocking and quick — they're invoked
 * synchronously from {@link #put} / {@link #remove}. A slow listener stalls every WS broadcast.
 * For anything heavier than a {@code session.send()}, dispatch off-thread inside the listener.
 */
public final class BoardRegistry {

    private static final BoardRegistry INSTANCE = new BoardRegistry();

    public static BoardRegistry get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, BoardContent> boards = new ConcurrentHashMap<>();
    private final Set<Consumer<ChangeEvent>> listeners = new CopyOnWriteArraySet<>();

    private BoardRegistry() {}

    /** Result of a mutating op, broadcast to listeners. */
    public sealed interface ChangeEvent {
        record Put(BoardContent content) implements ChangeEvent {}
        record Remove(String name) implements ChangeEvent {}
    }

    /** Insert or replace a board. Fires a {@link ChangeEvent.Put} to listeners. */
    public void put(BoardContent content) {
        boards.put(content.name(), content);
        listeners.forEach(l -> l.accept(new ChangeEvent.Put(content)));
    }

    /** Bulk insert/replace. Fires a {@link ChangeEvent.Put} for each entry. */
    public void putAll(Collection<BoardContent> contents) {
        for (BoardContent content : contents) {
            put(content);
        }
    }

    /** Remove a board by name. Fires a {@link ChangeEvent.Remove} if the board existed. */
    public void remove(String name) {
        BoardContent removed = boards.remove(name);
        if (removed != null) {
            listeners.forEach(l -> l.accept(new ChangeEvent.Remove(name)));
        }
    }

    /**
     * Remove all boards. Fires a {@link ChangeEvent.Remove} for every entry. Used at
     * server-stop so a fresh server start doesn't display stale board names from the
     * previous session.
     */
    public void clearAll() {
        // Snapshot keys first — listeners may call get()/all() during iteration.
        for (String name : List.copyOf(boards.keySet())) {
            BoardContent removed = boards.remove(name);
            if (removed != null) {
                listeners.forEach(l -> l.accept(new ChangeEvent.Remove(name)));
            }
        }
    }

    /** Lookup by name (returns null if missing). */
    public BoardContent get(String name) {
        return boards.get(name);
    }

    /** Snapshot of all boards (safe to iterate without locking). */
    public Collection<BoardContent> all() {
        return boards.values();
    }

    /** Number of boards currently tracked. */
    public int size() {
        return boards.size();
    }

    /** Subscribe to changes. Same listener registered twice fires twice — callers dedupe if needed. */
    public void addListener(Consumer<ChangeEvent> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<ChangeEvent> listener) {
        listeners.remove(listener);
    }
}