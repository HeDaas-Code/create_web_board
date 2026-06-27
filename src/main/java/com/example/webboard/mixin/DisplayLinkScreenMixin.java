package com.example.webboard.mixin;

import com.example.webboard.content.mirror.WebMirror;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Web: ON/OFF" toggle button to the Display Link configuration screen. The state
 * is stashed in the link's source-config ({@link WebMirror#NBT_KEY}) by mutating the
 * {@code sourceData} CompoundTag right before Create builds its configuration packet in
 * {@code onClose} — reusing Create's own packet, no custom networking.
 */
@Mixin(DisplayLinkScreen.class)
public abstract class DisplayLinkScreenMixin {

    @Shadow(remap = false)
    private AllGuiTextures background;

    @Shadow(remap = false)
    private DisplayLinkBlockEntity blockEntity;

    @Shadow(remap = false)
    protected int guiLeft;

    @Shadow(remap = false)
    protected int guiTop;

    @Shadow
    protected abstract <T extends GuiEventListener & Renderable> T addRenderableWidget(T widget);

    @Unique
    private boolean createWebBoard$webSynced = false;

    @Inject(method = "init", at = @At("RETURN"))
    private void createWebBoard$addWebToggle(CallbackInfo ci) {
        createWebBoard$webSynced = blockEntity.getSourceConfig().getBoolean(WebMirror.NBT_KEY);

        int w = 66;
        int x = guiLeft + background.getWidth() - 33 - w - 2;
        int y = guiTop + background.getHeight() - 23;

        Button btn = Button.builder(createWebBoard$label(), b -> {
            createWebBoard$webSynced = !createWebBoard$webSynced;
            b.setMessage(createWebBoard$label());
        }).bounds(x, y, w, 18).build();

        addRenderableWidget(btn);
    }

    @Inject(method = "onClose", at = @At(value = "NEW", target = "Lcom/simibubi/create/content/redstone/displayLink/DisplayLinkConfigurationPacket;"))
    private void createWebBoard$markWebSynced(CallbackInfo ci, @Local(ordinal = 0) CompoundTag sourceData) {
        sourceData.putBoolean(WebMirror.NBT_KEY, createWebBoard$webSynced);
    }

    @Unique
    private Component createWebBoard$label() {
        return Component.translatable("web_board.toggle." + (createWebBoard$webSynced ? "on" : "off"));
    }
}
