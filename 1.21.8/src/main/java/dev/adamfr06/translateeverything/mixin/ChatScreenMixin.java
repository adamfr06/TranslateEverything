package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.InputTranslator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Outgoing chat translation: live preview bar + send interception. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen implements dev.adamfr06.translateeverything.capture.ChatDraftAccess {
    public String translateeverything$draft() { return chatField == null ? "" : chatField.getText(); }

    @Shadow
    protected TextFieldWidget chatField;

    protected ChatScreenMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void translateeverything$resetPreview(CallbackInfo ci) {
        InputTranslator.resetPreview();
        if (chatField != null && InputTranslator.pendingChatInsert != null) {
            chatField.setText(InputTranslator.pendingChatInsert);
            InputTranslator.pendingChatInsert = null;
        }
        dev.adamfr06.translateeverything.config.TEConfig cfg = dev.adamfr06.translateeverything.config.TEConfig.get();
        if (cfg.enabled) {
            if (chatField != null) { chatField.setWidth(Math.max(40, this.width - 110)); chatField.setY(this.height - 14); }
            addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
                            net.minecraft.text.Text.literal("Context"),
                            b -> MinecraftClient.getInstance().setScreen(
                                    new dev.adamfr06.translateeverything.gui.ContextScreen((Screen) (Object) this)))
                    .dimensions(this.width - 100, this.height - 18, 48, 16)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(net.minecraft.text.Text.literal(
                            "Chat lines the translator may use")))
                    .build());
        }
        if (InputTranslator.active() && cfg.inputMode != dev.adamfr06.translateeverything.config.TEConfig.InputMode.SELECTION) {
            addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
                            net.minecraft.text.Text.literal("Review"),
                            b -> InputTranslator.openConfirm(MinecraftClient.getInstance(),
                                    (Screen) (Object) this, chatField == null ? "" : chatField.getText()))
                    .dimensions(this.width - 48, this.height - 18, 42, 16)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(net.minecraft.text.Text.literal(
                            "Check this message before it sends")))
                    .build());
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void translateeverything$pollControllerText(DrawContext context, int mouseX, int mouseY,
                                                          float delta, CallbackInfo ci) {
        if (chatField != null) {
            if (this.getFocused() != chatField) {
                this.setFocused(chatField);
            }
            InputTranslator.noteDraft(chatField.getText());
            InputTranslator.updatePreview(chatField.getText());
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void translateeverything$renderPreview(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (chatField != null) {
            InputTranslator.renderPreviewBar(context, chatField, this.width);
        }
    }

    @Inject(method = "sendMessage(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true)
    private void translateeverything$onSend(String chatText, boolean addToHistory, CallbackInfo ci) {
        if (InputTranslator.interceptSend(MinecraftClient.getInstance(), chatText, addToHistory)) {
            ci.cancel();
        }
    }
}
