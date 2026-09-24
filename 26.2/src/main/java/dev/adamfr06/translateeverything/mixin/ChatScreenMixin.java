package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.InputTranslator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Outgoing chat translation: live preview bar + send interception. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen implements dev.adamfr06.translateeverything.capture.ChatDraftAccess {
    public String translateeverything$draft() { return input == null ? "" : input.getValue(); }

    @Shadow
    protected EditBox input;

    protected ChatScreenMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void translateeverything$applyPending(CallbackInfo ci) {
        if (input != null && InputTranslator.pendingChatInsert != null) {
            input.setValue(InputTranslator.pendingChatInsert);
            InputTranslator.pendingChatInsert = null;
        }
        dev.adamfr06.translateeverything.config.TEConfig cfg = dev.adamfr06.translateeverything.config.TEConfig.get();
        if (cfg.enabled) {
            if (input != null) { input.setWidth(Math.max(40, this.width - 110)); input.setY(this.height - 14); }
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                            net.minecraft.network.chat.Component.literal("Context"),
                            b -> Minecraft.getInstance().setScreenAndShow(
                                    new dev.adamfr06.translateeverything.gui.ContextScreen((Screen) (Object) this)))
                    .bounds(this.width - 100, this.height - 18, 48, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal(
                            "Chat lines the translator may use")))
                    .build());
        }
        if (InputTranslator.active() && cfg.inputMode != dev.adamfr06.translateeverything.config.TEConfig.InputMode.SELECTION) {
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                            net.minecraft.network.chat.Component.literal("Review"),
                            b -> InputTranslator.openConfirm(Minecraft.getInstance(),
                                    (Screen) (Object) this, input == null ? "" : input.getValue()))
                    .bounds(this.width - 48, this.height - 18, 42, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal(
                            "Check this message before it sends")))
                    .build());
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void translateeverything$renderPreview(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (input != null) {
            if (this.getFocused() != input) {
                this.setFocused(input);
            }
            InputTranslator.noteDraft(input.getValue());
            InputTranslator.renderPreviewBar(context, input.getValue(), this.width, this.height);
        }
    }

    @Inject(method = "handleChatInput(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true)
    private void translateeverything$onSend(String message, boolean addToHistory, CallbackInfo ci) {
        if (InputTranslator.interceptSend(Minecraft.getInstance(), message)) {
            ci.cancel();
        }
    }
}
