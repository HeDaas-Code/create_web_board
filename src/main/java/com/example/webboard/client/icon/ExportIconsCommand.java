package com.example.webboard.client.icon;

import com.example.webboard.CreateWebBoard;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * ExportIconsCommand — registers the {@code /webboard export-icons} client command.
 *
 * <p>Walks every registered item, renders each (via {@link ItemIconRenderer}, same code path the
 * auto-uploader uses), then writes the full icon pack as a zip at {@code webboard-icons.zip}
 * (next to the game's run directory). The zip layout matches what
 * {@code IconPackStorage.loadFromZip} expects: {@code names.json} + {@code <id>.png} at the
 * root. Drop the zip into a dedicated server's {@code config/} dir and the server loads it on
 * next start — no client connection required.
 *
 * <p><b>Why this is also the iconpack mod's whole job</b>: the standalone iconpack mod (for
 * operators who don't want to install the full dashboard mod on their client) just calls
 * {@link ItemIconRenderer#render(String)} for every item and {@code IconPackStorage.writeToZip}
 * — exactly what this command does. Keeping the render logic in one place means we only test
 * one code path; the iconpack mod is a thin wrapper around the same calls.
 *
 * <p><b>Rendering on-demand</b>: if the auto-uploader already rendered some items this session,
 * those are reused (cached in {@link ItemIconRenderer}). The rest are rendered synchronously
 * here, draining a few per tick until done. The command acknowledges with a "started" message;
 * completion is logged to chat when the zip is written.
 *
 * <p><b>Client command source type</b>: NeoForge's {@link RegisterClientCommandsEvent}
 * dispatcher is typed {@code CommandDispatcher<CommandSourceStack>} (client commands share
 * the server-side source type — the source is just wrapped client-side), so we build
 * commands with {@link Commands#literal(String)} just like server commands.
 */
@EventBusSubscriber(modid = CreateWebBoard.MOD_ID, value = Dist.CLIENT)
public final class ExportIconsCommand {

    private static final String OUTPUT_PATH = "webboard-icons.zip";
    private static final int RENDER_PER_TICK = 8;  // faster than the auto-uploader (user-initiated)

    private static boolean exporting = false;
    private static java.util.List<String> pending = null;

    private ExportIconsCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("webboard")
                        .then(Commands.literal("export-icons")
                                .executes(ctx -> startExport()))
        );
    }

    private static int startExport() {
        if (exporting) {
            feedback("§e[web_board] 导出已在进行中…");
            return 0;
        }
        exporting = true;
        // Snapshot all registered item ids and render any not yet cached.
        pending = new java.util.ArrayList<>();
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (key != null) pending.add(key.toString());
        }
        int already = (int) pending.stream().filter(ItemIconRenderer::alreadyRendered).count();
        feedback("§a[web_board] 开始导出 §e" + pending.size() + "§a 个物品图标（已缓存 " + already
                + "），完成后输出到 §e" + OUTPUT_PATH + "§a。每 tick 渲染 " + RENDER_PER_TICK + " 个。");
        return 1;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!exporting || pending == null) return;
        // Render a few items this tick (on the render thread).
        int renderedThisTick = 0;
        while (!pending.isEmpty() && renderedThisTick < RENDER_PER_TICK) {
            String id = pending.remove(pending.size() - 1);
            ItemIconRenderer.render(id);
            renderedThisTick++;
        }
        // All done — write the zip.
        if (pending.isEmpty()) {
            exporting = false;
            writeZip();
            pending = null;
        }
    }

    private static void writeZip() {
        try {
            Map<String, byte[]> icons = ItemIconRenderer.snapshotIcons();
            Map<String, String> names = ItemIconRenderer.snapshotNames();
            Path out = Path.of(OUTPUT_PATH);
            try (var os = Files.newOutputStream(out)) {
                com.example.webboard.content.items.IconPackStorage.writeToZip(os, names, icons);
            }
            feedback("§a[web_board] 导出完成：§e" + icons.size() + "§a 个图标 → §e" + out.toAbsolutePath());
            CreateWebBoard.LOGGER.info("[web_board] exported {} icons to {}", icons.size(), out.toAbsolutePath());
        } catch (Exception e) {
            feedback("§c[web_board] 导出失败: " + e.getMessage());
            CreateWebBoard.LOGGER.error("[web_board] export-icons failed: {}", e.toString(), e);
        }
    }

    /** Send a chat message to the local player (client-side feedback for the command). */
    private static void feedback(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
