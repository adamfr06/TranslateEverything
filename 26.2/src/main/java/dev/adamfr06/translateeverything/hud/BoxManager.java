package dev.adamfr06.translateeverything.hud;

import dev.adamfr06.translateeverything.TranslateEverythingClient;
import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.translate.LanguageUtil;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns every {@link TranslationBox}: creation, dedupe, expiry, dismissal memory and the
 * pin/clear/restore actions bound to keys and the overlay screen.
 */
public final class BoxManager {
    private static final int MAX_TRACKED_BOXES = 40;
    private static final int DISMISS_MEMORY = 256;
    /** Contextual boxes whose producer went quiet for this long start their linger countdown. */
    private static final long CONTEXT_TIMEOUT_MS = 250;

    private static final List<TranslationBox> boxes = new ArrayList<>();
    /** The single "active context" box per contextual source. */
    private static final Map<SourceType, TranslationBox> contextBoxes = new EnumMap<>(SourceType.class);
    /** Keys the user dismissed, don't resurrect them while they keep being looked at. */
    private static final Set<String> dismissedKeys = lruSet();
    /** Keys suppressed because the text already was in the target language. */
    private static final Set<String> suppressedKeys = lruSet();

    /** A finished translation, kept for the history screen. */
    public record HistoryEntry(long time, SourceType type, String title, String original,
                               String translated, String lang) {
    }

    private static final int HISTORY_LIMIT = 200;
    private static final java.util.ArrayDeque<HistoryEntry> history = new java.util.ArrayDeque<>();

    private BoxManager() {
    }

    /** Newest first. */
    public static List<HistoryEntry> history() {
        return List.copyOf(history);
    }

    public static void clearHistory() {
        history.clear();
    }

    private static void recordHistory(TranslationBox box) {
        // Contextual sources re-push constantly; don't fill the history with copies.
        int i = 0;
        for (HistoryEntry entry : history) {
            if (i++ >= 10) {
                break;
            }
            if (entry.original().equals(box.original)) {
                return;
            }
        }
        history.addFirst(new HistoryEntry(System.currentTimeMillis(), box.type, box.title,
                box.original, box.translated, box.detectedLanguage));
        while (history.size() > HISTORY_LIMIT) {
            history.removeLast();
        }
    }

    /** History entry for translations that never had a card (chat replace/below, immersion). */
    public static void recordExternal(SourceType type, String title, String original, String translated, String lang) {
        int i = 0;
        for (HistoryEntry entry : history) {
            if (i++ >= 10) {
                break;
            }
            if (entry.original().equals(original)) {
                return;
            }
        }
        history.addFirst(new HistoryEntry(System.currentTimeMillis(), type, title, original, translated, lang));
        while (history.size() > HISTORY_LIMIT) {
            history.removeLast();
        }
    }

    private static Set<String> lruSet() {
        return Collections.newSetFromMap(new java.util.LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > DISMISS_MEMORY;
            }
        });
    }

    // ------------------------------------------------------------------ intake

    /**
     * Called every tick by watchers while a contextual source (sign under the crosshair, hovered
     * item, open book page…) keeps existing.
     */
    public static void pushContext(SourceType type, String title, String text, String key, boolean forced) {
        pushContext(type, title, text, key, forced, null);
    }

    public static void pushContext(SourceType type, String title, String text, String key, boolean forced,
                                   TEConfig.Engine engineOverride) {
        if (!forced && (dismissedKeys.contains(key) || suppressedKeys.contains(key))) {
            return;
        }
        long now = System.currentTimeMillis();
        TranslationBox current = contextBoxes.get(type);
        if (current != null && current.key.equals(key) && engineOverride == null) {
            current.lastSeenAt = now;
            if (current.contextGoneAt != 0) {
                current.contextGoneAt = 0;
                if (!current.pinned) {
                    current.expiresAt = 0;
                }
            }
            return;
        }
        if (current != null && !current.pinned) {
            beginLinger(current, now);
        }
        TranslationBox box = new TranslationBox(type, title, text, key, forced);
        box.engineOverride = engineOverride;
        contextBoxes.put(type, box);
        addBox(box);
        requestTranslation(box);
    }

    /** Fire-and-forget sources: titles, subtitles, action bar, boss bars. */
    public static void pushEvent(SourceType type, String title, String text) {
        pushEvent(type, title, text, false);
    }

    /**
     * @param forced explicit user actions (OCR scans) must always show their card, even when the
     * text is already in the target language (which would normally suppress it, silently and
     * instantly when the translation is already cached) or was dismissed.
     */
    public static void pushEvent(SourceType type, String title, String text, boolean forced) {
        String key = type.name() + ":" + text.hashCode();
        if (forced) {
            forget(key);
        } else if (dismissedKeys.contains(key) || suppressedKeys.contains(key)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (TranslationBox box : boxes) {
            if (box.key.equals(key)) {
                // Same message again (action bars often repeat every tick): keep it alive quietly.
                if (!box.pinned && box.expiresAt > 0) {
                    box.expiresAt = expiry(now);
                }
                return;
            }
        }
        TranslationBox box = new TranslationBox(type, title, text, key, forced);
        box.expiresAt = expiry(now);
        addBox(box);
        requestTranslation(box);
    }

    /** The context for this source is gone (looked away, closed the screen). */
    public static void clearContext(SourceType type) {
        TranslationBox box = contextBoxes.get(type);
        if (box != null && box.contextGoneAt == 0) {
            beginLinger(box, System.currentTimeMillis());
        }
    }

    private static void beginLinger(TranslationBox box, long now) {
        box.contextGoneAt = now;
        if (!box.pinned) {
            box.expiresAt = now + TEConfig.get().contextLingerSeconds * 1000L;
        }
    }

    private static long expiry(long now) {
        int seconds = TEConfig.get().autoHideSeconds;
        return seconds <= 0 ? 0 : now + seconds * 1000L;
    }

    private static void addBox(TranslationBox box) {
        boxes.add(box);
        while (boxes.size() > MAX_TRACKED_BOXES) {
            TranslationBox victim = null;
            for (TranslationBox b : boxes) {
                if (!b.pinned) {
                    victim = b;
                    break;
                }
            }
            if (victim == null) {
                victim = boxes.get(0);
            }
            removeBox(victim);
        }
    }

    private static void requestTranslation(TranslationBox box) {
        int request = ++box.requestRevision;
        box.targetLanguage = TEConfig.get().targetLanguage;
        if (!box.forced && TEConfig.get().hideSameLanguage && TranslationService.looksAlreadyIn(box.original, box.targetLanguage)) {
            suppressedKeys.add(box.key);
            removeBox(box);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        TEConfig cfg = TEConfig.get();
        TranslationService.translate(box.original, cfg.sourceLanguage, box.targetLanguage, box.engineOverride != null ? box.engineOverride : cfg.engine)
                .thenAccept(result -> client.execute(() -> { if (request == box.requestRevision && boxes.contains(box)) applyResult(box, result); }));
    }

    private static void applyResult(TranslationBox box, TranslationService.Result result) {
        TEConfig cfg = TEConfig.get();
        if (result.error()) {
            box.state = TranslationBox.State.ERROR;
            box.errorMessage = result.errorMessage();
            return;
        }
        box.translated = result.translatedText();
        box.detectedLanguage = result.detectedLanguage();
        if (box.type == SourceType.CHAT && dev.adamfr06.translateeverything.translate.ChatContext.isPlayerMessage(box.original)) {
            dev.adamfr06.translateeverything.capture.InputTranslator.noteIncomingLanguage(result.detectedLanguage());
        }
        recordHistory(box);
        boolean sameLanguage = LanguageUtil.sameLanguage(result.detectedLanguage(), box.targetLanguage);
        if (sameLanguage && cfg.hideSameLanguage && !box.forced) {
            suppressedKeys.add(box.key);
            removeBox(box);
            if (cfg.debugLogging) {
                TranslateEverythingClient.LOGGER.info("[{}] Hidden: already in target language ({}): {}",
                        box.type, result.detectedLanguage(), box.original);
            }
            return;
        }
        box.state = TranslationBox.State.READY;
        if (cfg.playSound && System.currentTimeMillis() - box.createdAt < 5000) {
            Minecraft client = Minecraft.getInstance();
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1.4f, 0.35f));
        }
        if (cfg.debugLogging) {
            TranslateEverythingClient.LOGGER.info("[{}] {} -> {}", box.type, box.original, box.translated);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** While the overlay screen is open, boxes are frozen so they can be inspected. */
    private static boolean freezeExpiry = false;

    public static void setFreezeExpiry(boolean freeze) {
        freezeExpiry = freeze;
    }

    public static void tick() {
        if (freezeExpiry) {
            return;
        }
        long now = System.currentTimeMillis();
        for (TranslationBox box : contextBoxes.values()) {
            if (box.contextGoneAt == 0 && now - box.lastSeenAt > CONTEXT_TIMEOUT_MS) {
                beginLinger(box, now);
            }
        }
        Iterator<TranslationBox> it = boxes.iterator();
        while (it.hasNext()) {
            TranslationBox box = it.next();
            if (box.expired(now) && box.alpha(now) <= 0f) {
                it.remove();
                contextBoxes.remove(box.type, box);
            }
        }
    }

    // ------------------------------------------------------------------ queries & actions

    /** Newest first. */
    public static List<TranslationBox> activeBoxes() {
        List<TranslationBox> list = new ArrayList<>(boxes);
        Collections.reverse(list);
        return list;
    }

    public static boolean isEmpty() {
        return boxes.isEmpty();
    }

    public static void dismiss(TranslationBox box) {
        dismissedKeys.add(box.key);
        removeBox(box);
    }

    public static void dismissNewest() {
        List<TranslationBox> active = activeBoxes();
        if (!active.isEmpty()) {
            dismiss(active.get(0));
        }
    }

    public static void togglePinNewest() {
        List<TranslationBox> active = activeBoxes();
        if (!active.isEmpty()) {
            togglePin(active.get(0));
        }
    }

    public static void togglePin(TranslationBox box) {
        box.pinned = !box.pinned;
        if (box.pinned) {
            box.expiresAt = 0;
        } else if (box.contextGoneAt != 0 || !box.type.contextual) {
            box.expiresAt = System.currentTimeMillis() + Math.max(1, TEConfig.get().contextLingerSeconds) * 1000L;
        }
    }

    public static void clearAll() {
        for (TranslationBox box : new ArrayList<>(boxes)) {
            dismissedKeys.add(box.key);
        }
        boxes.clear();
        contextBoxes.clear();
    }

    /** Forget every dismissal/suppression so hidden translations can reappear. */
    public static void restoreHidden() {
        dismissedKeys.clear();
        suppressedKeys.clear();
    }

    public static void forget(String key) {
        dismissedKeys.remove(key);
        suppressedKeys.remove(key);
    }

    public static void retranslate(TranslationBox box) {
        TranslationService.invalidateText(box.original);
        box.state = TranslationBox.State.PENDING;
        box.errorMessage = "";
        requestTranslation(box);
    }

    private static void removeBox(TranslationBox box) {
        boxes.remove(box);
        contextBoxes.remove(box.type, box);
    }
}
