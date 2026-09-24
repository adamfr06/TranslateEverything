package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.InputTranslator;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/** Adds a "Translate" button to the book &amp; quill editing screen. */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen {
    @Shadow
    private EditBoxWidget editBox;

    @Unique
    private String translateeverything$notice = "";
    @Unique
    private long translateeverything$noticeUntil = 0;
    @Unique
    private int translateeverything$noticeColor = 0xFFFFAA00;

    protected BookEditScreenMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void translateeverything$addButton(CallbackInfo ci) {
        if (!TEConfig.get().enabled || !TEConfig.get().writeTranslateButtons) {
            return;
        }
        int rowBottom = this.height / 2 - 10 + 20; // sane fallback if no button is found below
        for (net.minecraft.client.gui.Element child : this.children()) {
            if (child instanceof ButtonWidget b) {
                rowBottom = Math.max(rowBottom, b.getY() + b.getHeight());
            }
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Translate"), b -> translateeverything$translate())
                .dimensions(this.width / 2 - 100, rowBottom + 4, 200, 20)
                .build());
    }

    @Unique
    private void translateeverything$translate() {
        if (editBox == null) {
            return;
        }
        String source = editBox.getText();
        if (source == null || source.isBlank()) {
            translateeverything$notify("This page is empty", 0xFFAAAAAA);
            return;
        }
        int requestedPage = ((BookEditScreenAccessor)(Object)this).translateeverything$getCurrentPage();
        String target = InputTranslator.resolveTarget();
        MinecraftClient client = MinecraftClient.getInstance();
        translateeverything$notify("Translating page → " + target.toUpperCase(Locale.ROOT) + "…", 0xFFFFAA00);
        TranslationService.translate(source, TranslationService.homeLanguage(), target, TEConfig.get().inputEngine)
                .thenAccept(result -> client.execute(() -> {
                    if (editBox == null || !source.equals(editBox.getText()) || requestedPage != ((BookEditScreenAccessor)(Object)this).translateeverything$getCurrentPage()) {
                        translateeverything$notify("Page changed; translation discarded", 0xFFAAAAAA);
                        return;
                    }
                    if (result.error()) {
                        translateeverything$notify("Failed: " + result.errorMessage(), 0xFFFF5555);
                        return;
                    }
                    if (editBox != null && !result.translatedText().isBlank()) {
                        editBox.setText(result.translatedText());
                        translateeverything$notify("Page translated to " + target.toUpperCase(Locale.ROOT), 0xFF55FF55);
                    }
                }));
    }

    @Unique
    private void translateeverything$notify(String message, int color) {
        translateeverything$notice = message;
        translateeverything$noticeColor = color;
        translateeverything$noticeUntil = System.currentTimeMillis() + 3500;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void translateeverything$renderNotice(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (System.currentTimeMillis() < translateeverything$noticeUntil) {
            context.drawCenteredTextWithShadow(this.textRenderer, translateeverything$notice,
                    this.width / 2, this.height - 16, translateeverything$noticeColor);
        }
    }
}
