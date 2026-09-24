package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.client.toast.AdvancementToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures advancement pop-up toasts the moment they are created. */
@Mixin(AdvancementToast.class)
public class AdvancementToastMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void translateeverything$onCreated(AdvancementEntry advancement, CallbackInfo ci) {
        advancement.value().display().ifPresent(display ->
                CaptureManager.onAdvancement(display.getTitle(), display.getDescription()));
    }
}
