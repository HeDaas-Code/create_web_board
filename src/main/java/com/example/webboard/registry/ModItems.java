package com.example.webboard.registry;

/**
 * Placeholder for non-block items (ingots, gears, dusts, etc.).
 * ModBlocks already wires BlockItem generation via the Registrate fluent chain.
 *
 * Empty for the worked example; add items here as your addon grows.
 */
public final class ModItems {
    private ModItems() {}

    public static void register() {
        // nothing to register — see ModBlocks for block-item wiring
    }
}