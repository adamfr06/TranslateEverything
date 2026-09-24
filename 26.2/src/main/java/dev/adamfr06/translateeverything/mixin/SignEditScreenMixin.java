package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.InputTranslator;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.translate.SignTextWrap;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Locale;

/** Adds a "Translate" button to the sign / hanging-sign editing screen (26.x). */
@Mixin(AbstractSignEditScreen.class)
public abstract class SignEditScreenMixin extends Screen {
    @Shadow @Final private String[] messages;
    @Shadow private int line;
    @Shadow @Final protected SignBlockEntity sign;
    @Shadow private void setMessage(String message) {}

    @Unique private String translateeverything$fitSource = "";
    @Unique private long translateeverything$fitDueAt = 0;
    @Unique private String translateeverything$fitChecked = "";
    @Unique private int translateeverything$fitLines = -1;
    @Unique private int[] translateeverything$fitWidths = new int[0];

    @Unique private String translateeverything$notice = "";
    @Unique private long translateeverything$noticeUntil = 0;
    @Unique private int translateeverything$noticeColor = 0xFFFFAA00;

    protected SignEditScreenMixin() { super(null); }

    @Inject(method = "init", at = @At("TAIL"))
    private void translateeverything$addButton(CallbackInfo ci) {
        if (!TEConfig.get().enabled || !TEConfig.get().writeTranslateButtons) return;
        for (var child : children()) {
            if (child instanceof Button done) { done.setWidth(98); done.setX(width / 2 - 100); }
        }
        addRenderableWidget(Button.builder(Component.literal("Translate"), b -> { setFocused(null); translateeverything$translate(); })
                .bounds(this.width / 2 + 2, this.height / 4 + 144, 98, 20).build());
    }

    @Inject(method = "keyPressed", at = @At("HEAD"))
    private void translateeverything$typingFocus(net.minecraft.client.input.KeyEvent event, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (event.key() == 32) setFocused(null);
    }

    @Unique
    private void translateeverything$translate() {
        StringBuilder joined = new StringBuilder();
        for (String message : messages) {
            String s = message == null ? "" : message.strip();
            if (!s.isEmpty()) { if (joined.length() > 0) joined.append(' '); joined.append(s); }
        }
        if (joined.toString().isBlank()) { translateeverything$notify("Nothing to translate", 0xFFAAAAAA); return; }
        int maxWidth = sign.getMaxTextLineWidth();
        final String snapshot = String.join("\n", messages);
        String target = InputTranslator.resolveTarget();
        Minecraft client = Minecraft.getInstance();
        translateeverything$notify("Translating \u2192 " + target.toUpperCase(Locale.ROOT) + "\u2026", 0xFFFFAA00);
        TranslationService.translate(joined.toString(), TranslationService.homeLanguage(), target, TEConfig.get().inputEngine)
                .thenAccept(result -> client.execute(() -> {
                    if (!snapshot.equals(String.join("\n", messages))) return;
                    if (result.error()) { translateeverything$notify("Failed: " + result.errorMessage(), 0xFFFF5555); return; }
                    List<String> lines = SignTextWrap.wrap(result.translatedText(), maxWidth, messages.length, this.font::width);
                    if (lines == null) { translateeverything$notify("Translation is too long to fit on this sign", 0xFFFF5555); return; }
                    for (int i = 0; i < messages.length; i++) {
                        line = i;
                        setMessage(i < lines.size() ? lines.get(i) : "");
                    }
                    line = 0;
                    translateeverything$notify("Translated to " + target.toUpperCase(Locale.ROOT), 0xFF55FF55);
                }));
    }

    /** Joins the four lines the way the translator will see them. */
    @Unique
    private String translateeverything$joined() {
        StringBuilder joined = new StringBuilder();
        for (String message : messages) {
            String s = message == null ? "" : message.strip();
            if (!s.isEmpty()) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(s);
            }
        }
        return joined.toString();
    }

    /** Re-measures after the player pauses. */
    @Unique
    private void translateeverything$updateFit() {
        TEConfig cfg = TEConfig.get();
        if (!cfg.enabled || !cfg.writeTranslateButtons) {
            return;
        }
        String now = translateeverything$joined();
        if (!now.equals(translateeverything$fitSource)) {
            translateeverything$fitSource = now;
            translateeverything$fitLines = -1;
            translateeverything$fitDueAt = System.currentTimeMillis() + cfg.editDebounceMs;
            return;
        }
        if (translateeverything$fitDueAt == 0 || System.currentTimeMillis() < translateeverything$fitDueAt) {
            return;
        }
        translateeverything$fitDueAt = 0;
        if (now.isBlank()) {
            translateeverything$fitLines = -1;
            translateeverything$fitChecked = "";
            return;
        }
        if (now.equals(translateeverything$fitChecked)) {
            return;
        }
        translateeverything$fitChecked = now;
        int maxWidth = sign.getMaxTextLineWidth();
        Minecraft client = Minecraft.getInstance();
        TranslationService.translate(now, TranslationService.homeLanguage(), InputTranslator.resolveTarget(), cfg.inputEngine)
                .thenAccept(result -> client.execute(() -> {
                    if (result.error() || !now.equals(translateeverything$joined())) {
                        return; // failed, or the player has typed on since
                    }
                    List<String> lines = SignTextWrap.wrapAll(result.translatedText(), maxWidth, this.font::width);
                    translateeverything$fitLines = lines.size();
                    int[] widths = new int[lines.size()];
                    for (int i = 0; i < lines.size(); i++) {
                        widths[i] = this.font.width(lines.get(i));
                    }
                    translateeverything$fitWidths = widths;
                }));
    }

    /** Four bars, one per sign line, filled by how much of the board the line uses. */
    @Unique
    private void translateeverything$drawFit(GuiGraphicsExtractor context) {
        if (translateeverything$fitLines < 0) {
            return;
        }
        int slots = messages.length;
        int board = sign.getMaxTextLineWidth();
        int barW = 96;
        int x = this.width / 2 - barW / 2;
        int y = Math.min(this.height / 4 + 76, this.height - 98);
        boolean fits = translateeverything$fitLines <= slots;
        for (int i = 0; i < slots; i++) {
            int top = y + i * 4;
            context.fill(x, top, x + barW, top + 3, 0xFF2A2F36);
            int used = i < translateeverything$fitWidths.length ? translateeverything$fitWidths[i] : 0;
            if (used > 0) {
                int filled = Math.min(barW, Math.round(barW * Math.min(1f, used / (float) board)));
                context.fill(x, top, x + filled, top + 3, used > board ? 0xFFE0736B : 0xFF7FD88A);
            }
        }
        int over = translateeverything$fitLines - slots;
        String verdict = fits
                ? "Fits: " + translateeverything$fitLines + " of " + slots + " lines"
                : over + (over == 1 ? " line" : " lines") + " over capacity";
        context.centeredText(this.font, Component.literal(verdict), this.width / 2,
                y + slots * 4 + 3, fits ? 0xFF7FD88A : 0xFFE0736B);
    }

    @Unique
    private void translateeverything$notify(String message, int color) {
        translateeverything$notice = message;
        translateeverything$noticeColor = color;
        translateeverything$noticeUntil = System.currentTimeMillis() + 3500;
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void translateeverything$renderNotice(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        translateeverything$updateFit();
        translateeverything$drawFit(context);
        if (System.currentTimeMillis() < translateeverything$noticeUntil) {
            context.centeredText(this.font, Component.literal(translateeverything$notice),
                    this.width / 2, this.height / 4 + 168, translateeverything$noticeColor);
        }
    }
}
