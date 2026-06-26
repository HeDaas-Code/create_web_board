package com.example.webboard.registry;

import com.example.webboard.CreateWebBoard;
import com.example.webboard.content.blocks.GrinderWheelBlock;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

import static com.example.webboard.CreateWebBoard.REGISTRATE;

/**
 * Block entity for the Grinder Wheel — uses Create's built-in SimpleKineticBlockEntity,
 * which gives us rotation propagation, stress impact, and visual updates for free.
 *
 * To upgrade later (custom processing logic, inventory, etc.):
 *   1. Subclass KineticBlockEntity directly
 *   2. Override getStressImpact() (e.g. return super.getStressImpact().multiply(2.0))
 *   3. Override tick() if you need per-tick work
 *   4. Replace SimpleKineticBlockEntity::new below with your class's BlockEntity factory
 */
public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static final BlockEntityEntry<SimpleKineticBlockEntity> GRINDER_WHEEL =
        REGISTRATE.blockEntity("grinder_wheel", SimpleKineticBlockEntity::new)
            .validBlocks(ModBlocks.GRINDER_WHEEL)  // BlockEntry reference — Registrar resolves at register time
            .register();

    public static void register() {
        // Registration via fluent chain above; method body empty intentionally.
    }
}