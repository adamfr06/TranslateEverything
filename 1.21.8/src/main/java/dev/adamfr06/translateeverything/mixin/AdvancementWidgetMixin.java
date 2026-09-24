package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import net.minecraft.advancement.AdvancementDisplay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.advancement.AdvancementWidget;
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
    private AdvancementDisplay display;

    @Inject(method = "drawTooltip", at = @At("HEAD"))
    private void translateeverything$onTooltip(DrawContext context, int originX, int originY,
                                               float alpha, int x, int y, CallbackInfo ci) {
        CaptureManager.onAdvancement(display.getTitle(), display.getDescription());
    }
}
