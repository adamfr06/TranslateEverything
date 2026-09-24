package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.ChatTranslator;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes incoming chat lines through the chat translator (hold/replace/append). */
@Mixin(ChatHud.class)
public class ChatHudMixin {
    @org.spongepowered.asm.mixin.injection.Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;getScaledWindowHeight()I"))
    private int translateeverything$reservePreview(net.minecraft.client.gui.DrawContext context) {
        return context.getScaledWindowHeight() - dev.adamfr06.translateeverything.capture.InputTranslator.chatPreviewSpace();
    }
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "toChatLineY", at = @At("HEAD"), argsOnly = true)
    private double translateeverything$shiftHitArea(double y) {
        return y + dev.adamfr06.translateeverything.capture.InputTranslator.chatPreviewSpace();
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), cancellable = true)
    private void translateeverything$onAddMessage(Text message, MessageSignatureData signatureData,
                                                  MessageIndicator indicator, CallbackInfo ci) {
        if (ChatTranslator.onChatMessage(message, signatureData, indicator)) {
            ci.cancel();
        }
    }
}
