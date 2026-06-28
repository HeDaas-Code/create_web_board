package com.example.webboard.content.network;

import java.util.List;

/**
 * NetworkDefinition — a user-created "stress network" that aggregates multiple Display Link
 * boards into a unified view with role-based categorization.
 *
 * <p>Each member board is assigned a role:
 * <ul>
 *   <li>{@code producer} — generates stress units (water wheels, engines, etc.)</li>
 *   <li>{@code consumer} — consumes stress units (crushers, saws, etc.)</li>
 *   <li>{@code storage} — stores stress units (flywheels, buffers, etc.)</li>
 * </ul>
 *
 * <p>The dashboard computes aggregates locally: total production, total consumption, surplus
 * (= production − consumption), and total storage. Trend charts aggregate each member's
 * persisted history by timestamp.
 *
 * <p>Value extraction: each member specifies a {@code lineIndex} (default 0) indicating which
 * line of the board's output to extract the numeric value from. The first number in that line
 * (matched by {@code /-?\d+(?:\.\d+)?/}) is used.
 *
 * <p>Stored in {@code config/webboard-networks.json} by {@link NetworkStorage}.
 */
public record NetworkDefinition(
        String id,
        String name,
        List<NetworkMember> members
) {
    /**
     * One board's membership in a network, with its role and value-extraction config.
     *
     * @param boardName the stable position-based key of the board (matches {@code BoardContent.name})
     * @param role      "producer", "consumer", or "storage"
     * @param label     optional friendly name shown in the network card (null = use board's effectiveName)
     * @param lineIndex which line of the board's output to extract the value from (0-based)
     */
    public record NetworkMember(
            String boardName,
            String role,
            String label,
            int lineIndex
    ) {}

    /** Create a copy with a different name and/or members. */
    public NetworkDefinition with(String newName, List<NetworkMember> newMembers) {
        return new NetworkDefinition(this.id, newName, List.copyOf(newMembers));
    }
}
