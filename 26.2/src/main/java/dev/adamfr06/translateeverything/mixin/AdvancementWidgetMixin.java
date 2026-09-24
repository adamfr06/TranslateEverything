package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the advancement currently hovered in the advancements screen. */
@Mixin(AdvancementWidget.class)
public class AdvancementWidgetMixin {
    @Shadow
    @Final
    private DisplayInfo display;

    @Inject(method = "extractHover", at = @At("HEAD"))
    private void translateeverything$onTooltip(GuiGraphicsExtractor context, int originX, int originY,
                                               float alpha, int x, int y, CallbackInfo ci) {
        CaptureManager.onAdvancement(display.getTitle(), display.getDescription());
    }
}
