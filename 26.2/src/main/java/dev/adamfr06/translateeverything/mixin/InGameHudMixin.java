package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures titles, subtitles and action-bar messages the moment the server sets them. */
@Mixin(Hud.class)
public class InGameHudMixin {
    @Inject(method = "setTitle", at = @At("HEAD"), cancellable = true)
    private void translateeverything$onSetTitle(Component title, CallbackInfo ci) {
        if (title != null) {
            CaptureManager.onTitle(title, ci);
        }
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), cancellable = true)
    private void translateeverything$onSetSubtitle(Component subtitle, CallbackInfo ci) {
        if (subtitle != null) {
            CaptureManager.onSubtitle(subtitle, ci);
        }
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void translateeverything$onSetOverlayMessage(Component message, boolean tinted, CallbackInfo ci) {
        if (message != null) {
            CaptureManager.onActionBar(message, ci);
        }
    }
}
