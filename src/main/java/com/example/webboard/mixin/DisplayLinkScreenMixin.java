package com.example.webboard.mixin;

import com.example.webboard.content.mirror.WebMirror;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
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
 *
 * <p><b>Why this mixin extends {@link Screen}</b>: {@code addRenderableWidget} is a
 * protected method declared on vanilla {@code Screen} and inherited by
 * {@code DisplayLinkScreen} (via {@code AbstractSimiScreen}). Shadowing an inherited
 * method with {@code @Shadow} is unreliable — when the refmap fails to load
 * (issue #7: "No refMap loaded"), the SRG→deobf name lookup fails and the mixin
 * crashes the whole Create ecosystem. By extending {@code Screen} directly, the
 * method is callable without any {@code @Shadow} or refmap involvement.
 *
 * <p>{@code guiLeft}/{@code guiTop} are mod-member fields ({@code AbstractSimiScreen})
 * so they keep {@code @Shadow(remap = false)} — mod members don't need the refmap.
 */
@Mixin(DisplayLinkScreen.class)
public abstract class DisplayLinkScreenMixin extends Screen {

    private DisplayLinkScreenMixin() {
        super(Component.empty());
    }

    @Shadow(remap = false)
    private AllGuiTextures background;

    @Shadow(remap = false)
    private DisplayLinkBlockEntity blockEntity;

    @Shadow(remap = false)
    protected int guiLeft;

    @Shadow(remap = false)
    protected int guiTop;

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
