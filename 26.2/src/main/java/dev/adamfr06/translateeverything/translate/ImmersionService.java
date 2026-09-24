package dev.adamfr06.translateeverything.translate;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.hud.BoxManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Backbone of full-immersion mode: hands out ready-made replacements for text that is being rendered right now. */
public final class ImmersionService {
    private static final Object SAME = new Object();
    private record RetryAfter(long at) {} // marker: already in target language / error
    private static final Map<String, Object> STATE = lru(2048);       // original -> SAME | String translated
    private static final Map<String, FormattedCharSequence[]> SIGN_LINES = lru(512);
    private static final Map<String, List<FormattedCharSequence>> BOOK_PAGES = lru(64);

    /** Title/subtitle/action-bar swaps waiting for their translation. */
    private record Held(long deadline, Component original, Consumer<Component> setter,
                        java.util.concurrent.CompletableFuture<TranslationService.Result> future) {
    }

    private static final List<Held> held = new ArrayList<>();

    private ImmersionService() {
    }

    private static <V> Map<String, V> lru(int cap) {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > cap;
            }
        });
    }

    public static boolean active(SourceType type) {
        TEConfig cfg = TEConfig.get();
        return cfg.immersionMode && cfg.enabled && cfg.isSourceEnabled(type);
    }

    /**
     * Translated form of {@code original} if it's ready and actually foreign; null otherwise
     * (caller keeps the original).
     */
    public static String ready(String original) {
        TEConfig cfg = TEConfig.get();
        if (!CaptureManager.worthTranslating(cfg, original)) {
            return null;
        }
        if (cfg.hideSameLanguage && TranslationService.looksAlreadyIn(original, cfg.targetLanguage)) return null;
        String key = TranslationService.cacheProfile() + "\u0000" + original;
        Object state = STATE.get(key);
        if (state instanceof RetryAfter retry && System.currentTimeMillis() >= retry.at()) { STATE.remove(key); state = null; }
        if (state == null) {
            STATE.put(key, SAME);
            TranslationService.translate(original, cfg.sourceLanguage, cfg.targetLanguage, cfg.immersionEngine).thenAccept(result -> {
                if (result.error()) {
                    STATE.put(key, new RetryAfter(System.currentTimeMillis() + 10_000));
                } else if (LanguageUtil.sameLanguage(result.detectedLanguage(), TEConfig.get().targetLanguage)
                        || result.translatedText().isBlank()) {
                    STATE.put(key, SAME);
                } else {
                    STATE.put(key, result.translatedText());
                    BoxManager.recordExternal(SourceType.SIGN, "Immersion", original,
                            result.translatedText(), result.detectedLanguage());
                }
            });
            return null;
        }
        return state instanceof String s ? s : null;
    }

    /** Sign translations retain their complete text in Shrink and Overflow modes. */
    /** Standard sign board width in font pixels; hanging signs report their own. */
    private static final int DEFAULT_BOARD = 90;

    public static int currentSignBoard = 90;

    public static FormattedCharSequence[] signLines(String joined) { return signLines(joined, currentSignBoard); }

    public static FormattedCharSequence[] signLines(String joined, int board) {
        String translated = ready(joined);
        if (translated == null) {
            return null;
        }
        return SIGN_LINES.computeIfAbsent(TEConfig.get().signOverflowMode + ":" + board + "\u0000" + translated, key -> {
            String t = translated;
            var tr = Minecraft.getInstance().font;
            String flat = t.replace('\n', ' ');
            List<FormattedCharSequence> wrapped = tr.split(FormattedText.of(flat), board);
            TEConfig.SignOverflowMode mode = TEConfig.get().signOverflowMode;
            if (wrapped.size() > 4 && mode != TEConfig.SignOverflowMode.CLAMP) {
                // Widen until all text fits four rows; Shrink scales these rows at submission.
                int width = board;
                while (wrapped.size() > 4) {
                    width = Math.max(width + 1, width * 2);
                    wrapped = tr.split(FormattedText.of(flat), width);
                }
                int low = board, high = width;
                while (low < high) {
                    int mid = low + (high - low) / 2;
                    if (tr.split(FormattedText.of(flat), mid).size() <= 4) high = mid;
                    else low = mid + 1;
                }
                wrapped = tr.split(FormattedText.of(flat), low);
            }
            if (wrapped.size() > 4) {
                wrapped = new ArrayList<>(wrapped.subList(0, 3));
                wrapped.add(Component.literal("…").getVisualOrderText());
            }
            FormattedCharSequence[] lines = new FormattedCharSequence[4];
            for (int i = 0; i < 4; i++) {
                lines[i] = i < wrapped.size() ? wrapped.get(i) : FormattedCharSequence.EMPTY;
            }
            return lines;
        });
    }

    /**
     * Shrink factor (<=1) for a sign whose translated text is wider than the board, or 1 when not
     * in SHRINK mode / no translation ready.
     */
    /** Per-face shrink factor for 26.x (checks SHRINK mode; 1 otherwise). */
    public static float signFaceScale(String joinedOriginal, int board) {
        TEConfig cfg = TEConfig.get();
        if (!active(SourceType.SIGN) || cfg.signOverflowMode != TEConfig.SignOverflowMode.SHRINK) {
            return 1f;
        }
        return scaleForFace(joinedOriginal, board);
    }

    public static float signScaleFor(String frontJoined, String backJoined, int board) {
        TEConfig cfg = TEConfig.get();
        if (!active(SourceType.SIGN) || cfg.signOverflowMode != TEConfig.SignOverflowMode.SHRINK) {
            return 1f;
        }
        return Math.min(scaleForFace(frontJoined, board), scaleForFace(backJoined, board));
    }

    private static float scaleForFace(String joined, int board) {
        if (joined == null || joined.isBlank()) {
            return 1f;
        }
        FormattedCharSequence[] lines = signLines(joined, board);
        if (lines == null) {
            return 1f;
        }
        var tr = Minecraft.getInstance().font;
        int widest = 0;
        for (FormattedCharSequence line : lines) {
            if (line != null) {
                widest = Math.max(widest, tr.width(line));
            }
        }
        return widest > board ? (float) board / widest : 1f;
    }

    /**
     * Rebuilds {@code newText} carrying over the original's color, the explicit style if there is
     * one, otherwise the first legacy §-code.
     */
    public static Component restyled(Component original, String newText) {
        Style style = colorStyleOf(original);
        return style == null ? Component.literal(newText) : Component.literal(newText).setStyle(style);
    }

    /** First colored style of a text, explicit style, or the first legacy §-code; null when uncolored. */
    public static Style colorStyleOf(Component original) {
        Style[] found = {null};
        original.visit((style, str) -> {
            if (found[0] == null && style.getColor() != null && !str.isBlank()) {
                found[0] = style;
                return java.util.Optional.of(true);
            }
            return java.util.Optional.<Boolean>empty();
        }, Style.EMPTY);
        if (found[0] != null) {
            return found[0];
        }
        String raw = original.getString();
        for (int i = 0; i < raw.length() - 1; i++) {
            if (raw.charAt(i) == '§') {
                net.minecraft.ChatFormatting formatting = net.minecraft.ChatFormatting.getByCode(raw.charAt(i + 1));
                if (formatting != null) {
                    net.minecraft.network.chat.TextColor color =
                            net.minecraft.network.chat.TextColor.fromLegacyFormat(formatting);
                    if (color != null) {
                        return Style.EMPTY.withColor(color);
                    }
                }
            }
        }
        return null;
    }

    /** Book page replacement wrapped to the page width, or null. */
    public static List<FormattedCharSequence> bookPage(String pageText) {
        return bookPage(pageText, null);
    }

    /** Book page replacement wrapped to the page width, keeping the page's own colour. */
    public static List<FormattedCharSequence> bookPage(String pageText, Component original) {
        String translated = ready(pageText);
        if (translated == null) {
            return null;
        }
        Style originalStyle = original == null ? null : colorStyleOf(original);
        Style style = (originalStyle == null ? Style.EMPTY.withColor(0x000000) : originalStyle).withoutShadow();
        String key = style == null ? translated : translated + "\u0000" + style.hashCode();
        return BOOK_PAGES.computeIfAbsent(key, k -> {
            FormattedText text = style == null
                    ? FormattedText.of(translated)
                    : FormattedText.of(translated, style);
            return Minecraft.getInstance().font.split(text, 114);
        });
    }

    // ------------------------------------------------------------------ hold-and-swap (titles etc.)

    /**
     * Holds a transient message (title/subtitle/action bar) until translated, then re-delivers it
     * through {@code setter}.
     */
    public static void holdAndSwap(String plainText, Component original, Consumer<Component> setter) {
        if (TEConfig.get().hideSameLanguage && TranslationService.looksAlreadyIn(plainText, TEConfig.get().targetLanguage)) {
            setter.accept(original); return;
        }
        held.add(new Held(System.currentTimeMillis() + TEConfig.get().chatHoldMaxMs,
                original, setter, TranslationService.translate(plainText, TEConfig.get().sourceLanguage, TEConfig.get().targetLanguage, TEConfig.get().immersionEngine)));
    }

    public static void tick(Minecraft client) {
        if (held.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (var it = held.iterator(); it.hasNext(); ) {
            Held entry = it.next();
            if (entry.future().isDone()) {
                it.remove();
                TranslationService.Result result = entry.future().getNow(null);
                if (result == null || result.error() || result.translatedText().isBlank()
                        || LanguageUtil.sameLanguage(result.detectedLanguage(), TEConfig.get().targetLanguage)) {
                    entry.setter().accept(entry.original());
                } else {
                    // Keep the original's color (server-styled titles/action bars).
                    entry.setter().accept(restyled(entry.original(), result.translatedText()));
                }
            } else if (now > entry.deadline()) {
                it.remove();
                entry.setter().accept(entry.original());
            }
        }
    }
}
