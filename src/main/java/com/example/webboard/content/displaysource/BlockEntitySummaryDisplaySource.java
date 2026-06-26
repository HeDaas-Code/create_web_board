package com.example.webboard.content.displaysource;

import java.util.ArrayList;
import java.util.List;

import com.example.webboard.content.registry.BoardContent;
import com.example.webboard.content.registry.BoardRegistry;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * BlockEntitySummaryDisplaySource — second DisplaySource added by create_web_board.
 *
 * <p>Unlike {@link WebDisplaySource} which is a placeholder, this one actually reads
 * the source {@link BlockEntity} and produces a small multi-line summary that the
 * web dashboard can render:
 *
 * <pre>
 * Furnace                    // block display name (translated)
 * pos: 12,64,-7              // block position for traceability
 * minecraft:furnace          // block-entity type id
 * </pre>
 *
 * <p>This demonstrates an ID-based DisplaySource with a real (non-placeholder)
 * implementation, alongside {@link WebDisplaySource}. Both write to the same
 * {@link BoardRegistry} so the dashboard sees them identically.
 *
 * <p>Design choice: we deliberately read only stable BlockEntity API (no version-
 * specific fields), so the source keeps working as MC evolves. For richer per-BE
 * status, write dedicated sources (e.g. {@code FurnaceStatusDisplaySource} that
 * reads {@code AbstractFurnaceBlockEntity} once the 1.21 API stabilises).
 */
public class BlockEntitySummaryDisplaySource extends DisplaySource {

    /** DisplaySource registry key. */
    public static final String ID = "be_summary";

    private static final List<MutableComponent> NO_SOURCE = List.of(
            CreateLang.translateDirect("web_board.be_summary.no_source")
    );

    private final BoardRegistry registry = BoardRegistry.get();

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        BlockEntity be = context.getSourceBlockEntity();
        if (be == null) {
            mirrorToWeb(context, "Unknown", NO_SOURCE);
            return NO_SOURCE;
        }

        // Line 1: block display name (e.g. "Furnace", "Blast Furnace")
        MutableComponent blockName = Component.translatable(be.getBlockState().getBlock().getDescriptionId());

        // Line 2: position for traceability — useful when the operator has 20 boards on screen
        var pos = be.getBlockPos();
        MutableComponent posLine = CreateLang.translateDirect(
                "web_board.be_summary.pos",
                pos.getX(), pos.getY(), pos.getZ()
        );

        // Line 3: block-entity type's registry id (e.g. "minecraft:furnace") — best stable
        // identifier since BlockEntityType has no getDescriptionId() method in 1.21.
        ResourceLocation beTypeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        MutableComponent beType = Component.literal(beTypeId != null ? beTypeId.toString() : "unknown");

        List<MutableComponent> lines = List.of(blockName, posLine, beType);

        mirrorToWeb(context, blockName.getString(), lines);
        return lines;
    }

    /** Override the default 100-tick refresh to 1 second — same as WebDisplaySource. */
    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }

    private void mirrorToWeb(DisplayLinkContext context, String name, List<MutableComponent> lines) {
        List<String> plain = new ArrayList<>(lines.size());
        for (MutableComponent mc : lines) plain.add(mc.getString());
        String boardName = name + " @ " + context.getSourcePos().toShortString();
        registry.put(BoardContent.of(boardName, "create_web_board:" + ID, plain));
    }
}
