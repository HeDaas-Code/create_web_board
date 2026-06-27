package com.example.webboard.content.mirror;

import java.util.ArrayList;
import java.util.List;

import com.example.webboard.content.registry.BoardContent;
import com.example.webboard.content.registry.BoardRegistry;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Mirrors a Display Link's outgoing text to the {@link BoardRegistry} when the link has
 * the "WebSynced" flag enabled in its source config.
 *
 * <p>Called from {@code DisplaySourceTransferMixin} which wraps {@code DisplaySource#provideText}
 * inside {@code transferData}. This way <em>any</em> DisplaySource (Create built-in or addon)
 * is mirrored — the player just toggles the option on the Display Link UI.
 *
 * <p>The toggle state is stored in the link's source-config CompoundTag under {@link #NBT_KEY},
 * so it piggy-backs on Create's existing {@code DisplayLinkConfigurationPacket} + NBT
 * persistence — no custom networking or BlockEntity fields required.
 */
public final class WebMirror {

    /** NBT key inside the Display Link's source-config tag. */
    public static final String NBT_KEY = "WebSynced";

    private WebMirror() {}

    public static void mirror(DisplayLinkContext context, DisplaySource source, List<MutableComponent> text) {
        Level level = context.level();
        if (level != null && level.isClientSide) return;

        BlockEntity be = context.blockEntity();
        if (be == null) return;

        String name = boardName(be);
        CompoundTag cfg = context.sourceConfig();
        if (cfg == null || !cfg.getBoolean(NBT_KEY)) {
            // Toggle off (or never set): drop any previously mirrored board for this link.
            BoardRegistry.get().remove(name);
            return;
        }

        List<String> lines = new ArrayList<>(text.size());
        for (MutableComponent mc : text) lines.add(mc.getString());

        ResourceLocation id = CreateBuiltInRegistries.DISPLAY_SOURCE.getKey(source);
        String sourceType = id != null ? id.toString() : "unknown";

        BoardRegistry.get().put(BoardContent.of(name, sourceType, lines));
    }

    private static String boardName(BlockEntity be) {
        BlockPos pos = be.getBlockPos();
        return "Board @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
