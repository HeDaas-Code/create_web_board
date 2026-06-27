package com.example.webboard.mixin;

import java.util.List;

import com.example.webboard.content.mirror.WebMirror;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps {@link DisplaySource#provideText} inside {@code DisplaySource#transferData} so the
 * produced text is mirrored to the web dashboard for every DisplaySource — not just our own.
 *
 * <p>{@code remap = false} because {@code transferData} and {@code provideText} are Create
 * (mod) members, not vanilla mappings.
 */
@Mixin(DisplaySource.class)
public abstract class DisplaySourceTransferMixin {

    @WrapOperation(
            method = "transferData",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/api/behaviour/display/DisplaySource;provideText(Lcom/simibubi/create/content/redstone/displayLink/DisplayLinkContext;Lcom/simibubi/create/content/redstone/displayLink/target/DisplayTargetStats;)Ljava/util/List;"
            )
    )
    private static List<MutableComponent> createWebBoard$mirrorProvideText(
            DisplaySource self, DisplayLinkContext context, DisplayTargetStats stats,
            Operation<List<MutableComponent>> original) {
        List<MutableComponent> result = original.call(self, context, stats);
        WebMirror.mirror(context, self, result);
        return result;
    }
}
