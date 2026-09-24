package dev.adamfr06.translateeverything.capture;

import dev.adamfr06.translateeverything.TranslateEverythingClient;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.hud.BoxManager;
import dev.adamfr06.translateeverything.translate.ChatContext;
import dev.adamfr06.translateeverything.translate.LanguageUtil;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** The chat translator. */
public final class ChatTranslator {
    private record Pending(Text original, MessageSignatureData signature, MessageIndicator indicator,
                           String plainText, long deadline, int gen, CompletableFuture<TranslationService.Result> future) {
    }

    /** Hard cap so a message flood can never grow the hold queue without bound. */
    private static final int MAX_QUEUE = 40;
    /** How long an emitted line stays recognisable as the mod's own echo. */
    private static final long ECHO_TTL_MS = 30_000;
    private static final int MAX_ECHO_ENTRIES = 400;
    /** Cap on outstanding translation requests. */
    private static final int MAX_INFLIGHT = 48;
    private static final java.util.concurrent.atomic.AtomicInteger activeTranslations = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Bumped on every reset (disconnect); stale in-flight translations from a previous server are
     * dropped instead of bleeding into the new one.
     */
    private static volatile int generation = 0;

    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private static final Map<String, Long> recentlyEmitted = new ConcurrentHashMap<>();
    /** Messages sent by the player, so their server echo is not translated again or logged as context. */
    private static final Map<String, Long> selfSent = new ConcurrentHashMap<>();
    private static boolean internalAdd = false;

    /** Non-null when a conflicting chat-translator mod is installed (e.g. "GoogleChat"). */
    private static volatile String conflictMod = null;
    private static boolean conflictWarningPending = false;
    private static boolean conflictWarned = false;

    private ChatTranslator() {
    }

    public static void setConflictMod(String name) {
        conflictMod = name;
    }

    /** Clears per-session state on disconnect so nothing leaks between servers. */
    public static void reset() {
        InputTranslator.resetSession();
        queue.clear();
        recentlyEmitted.clear();
        selfSent.clear();
        generation++;
        dev.adamfr06.translateeverything.translate.ChatContext.clear();
        conflictWarned = false;
        conflictWarningPending = false;
    }

    /** Called from the ChatHud mixin. */
    public static boolean onChatMessage(Text message, MessageSignatureData signature, MessageIndicator indicator) {
        try {
            if (internalAdd || message == null) {
                return false;
            }
            TEConfig cfg = TEConfig.get();
            if (!cfg.enabled || !cfg.isSourceEnabled(SourceType.CHAT)) {
                return false;
            }
            String text = CaptureManager.sanitize(message.getString());
            if (isOwnEcho(text)) {
                return false;
            }
            boolean self = isSelfSent(text);
            if (!self) {
                var entry = dev.adamfr06.translateeverything.translate.ChatContext.add(text,
                        dev.adamfr06.translateeverything.translate.ChatContext.Origin.INCOMING);
                if (entry.autoIncluded) {
                    var body = dev.adamfr06.translateeverything.translate.MessageShape.split(text).body();
                    var guess = dev.adamfr06.translateeverything.translate.LanguageGuess.of(body);
                    if (guess.confident()) InputTranslator.noteIncomingLanguage(guess.code());
                }
            }
            if (self && !cfg.translateOwnMessages) {
                return false;
            }
            if (!CaptureManager.worthTranslating(cfg, text)) {
                return false;
            }
            if (cfg.chatMode == TEConfig.ChatMode.TILE) {
                BoxManager.pushEvent(SourceType.CHAT, "Chat", text);
                return false;
            }
            if (conflictMod != null && !conflictWarned) {
                conflictWarningPending = true;
            }
            // Overload protection: a flooded queue releases its oldest untranslated.
            if (queue.size() >= MAX_QUEUE) {
                Pending oldest = queue.pollFirst();
                addLine(MinecraftClient.getInstance(),
                        withCopy(oldest.original(), oldest.plainText(), "Click to copy the original"),
                        oldest.signature(), oldest.indicator());
            }
            if (activeTranslations.get() >= MAX_INFLIGHT) {
                return false;
            }
            final int gen = generation;
            final dev.adamfr06.translateeverything.translate.MessageShape.Split shape =
                    dev.adamfr06.translateeverything.translate.MessageShape.split(text);
            if (!dev.adamfr06.translateeverything.translate.MessageShape.worthTranslating(shape.body())) {
                return false; // emotes, links or numbers only: nothing a translator can add
            }
            if (cfg.hideSameLanguage
                    && TranslationService.looksAlreadyIn(shape.body(), cfg.targetLanguage)) {
                int id = ChatContext.registerTranslated(shape.body(), shape.body(), false);
                addLine(MinecraftClient.getInstance(), message.copy().append(forceSuffix(id)), signature, indicator);
                return true;
            }
            final dev.adamfr06.translateeverything.translate.MessageShape.Masked masked =
                    dev.adamfr06.translateeverything.translate.MessageShape.mask(shape.body(), onlinePlayerNames());
            final String toTranslate = masked.text();
            String mine = self ? knownOriginal(text) : null;
            if (mine != null && cfg.ownMessageShowRoundTrip) {
                String back = knownReadBack(text);
                mine = back != null ? back : null;
            }
            if (mine != null) {
                queue.addLast(new Pending(message, signature, indicator, text,
                        System.currentTimeMillis() + cfg.chatHoldMaxMs, generation,
                        java.util.concurrent.CompletableFuture.completedFuture(
                                TranslationService.Result.ok(shape.rebuild(mine), ""))));
                return true;
            }
            activeTranslations.incrementAndGet();
            java.util.concurrent.CompletableFuture<TranslationService.Result> fut = (self
                    ? TranslationService.translate(toTranslate)
                    : TranslationService.translateChat(toTranslate, dev.adamfr06.translateeverything.translate.ChatContext.window(text)))
                    .thenApply(r -> (r == null || r.error() || r.translatedText().isBlank()) ? r
                            : TranslationService.Result.ok(
                                    shape.rebuild(masked.restore(r.translatedText())), r.detectedLanguage()))
                    .whenComplete((r, t) -> activeTranslations.decrementAndGet());
            queue.addLast(new Pending(message, signature, indicator, text,
                    System.currentTimeMillis() + cfg.chatHoldMaxMs, gen, fut));
            if (cfg.debugLogging) {
                TranslateEverythingClient.LOGGER.info("[Chat] Holding message ({} queued): {}",
                        queue.size(), preview(text));
            }
            return true;
        } catch (Throwable t) {
            TranslateEverythingClient.LOGGER.error("[Chat] Intake failed: letting the message through untouched", t);
            return false;
        }
    }

    /** Releases every held message as-is (used when translation is toggled off / on disconnect). */
    public static void flushAll(MinecraftClient client) {
        Pending pending;
        while ((pending = queue.pollFirst()) != null) {
            addLine(client, pending.original(), pending.signature(), pending.indicator());
        }
    }

    /** Releases held messages in order once translated (or timed out). */
    public static void tick(MinecraftClient client) {
        if (conflictWarningPending) {
            conflictWarningPending = false;
            conflictWarned = true;
            showConflictWarning(client);
        }
        pruneEchoes();
        if (queue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int released = 0;
        while (!queue.isEmpty() && released < 60) { // bounded per tick so a flood can't stall the frame
            Pending head = queue.peekFirst();
            if (head.future().isDone()) {
                queue.pollFirst();
                released++;
                try {
                    release(client, head, head.future().getNow(null));
                } catch (Throwable t) {
                    TranslateEverythingClient.LOGGER.error("[Chat] Release failed: showing the original", t);
                    addLine(client, head.original(), head.signature(), head.indicator());
                }
            } else if (now > head.deadline()) {
                queue.pollFirst();
                released++;
                addLine(client, withCopy(head.original(), head.plainText(), "Click to copy the original"),
                        head.signature(), head.indicator());
                head.future().thenAccept(result -> client.execute(() -> {
                    if (head.gen() != generation) {
                        return; // server changed, drop stale translation
                    }
                    if (result != null && !result.error() && !sameLanguage(result)) {
                        addLine(client, translationLine(result, "↳ ", head.plainText()), null, null);
                        record(head, result);
                    }
                }));
            } else {
                break;
            }
        }
    }

    private static void release(MinecraftClient client, Pending pending, TranslationService.Result result) {
        TEConfig cfg = TEConfig.get();
        boolean untranslatable = result == null || result.error() || sameLanguage(result);
        TEConfig.ChatMode mode = cfg.chatMode;
        if (untranslatable) {
            addLine(client, withCopy(pending.original(), pending.plainText(), "Click to copy this message"),
                    pending.signature(), pending.indicator());
            return;
        }
        if (!isSelfSent(pending.plainText()) && dev.adamfr06.translateeverything.translate.ChatContext.isPlayerMessage(pending.plainText())) {
            InputTranslator.noteIncomingLanguage(result.detectedLanguage());
        }
        if (cfg.debugLogging) {
            TranslateEverythingClient.LOGGER.info("[Chat] Released ({}, {}): {}",
                    mode, result.detectedLanguage(), preview(result.translatedText()));
        }
        if (mode == TEConfig.ChatMode.REPLACE) {
            Style colored = dev.adamfr06.translateeverything.translate.ImmersionService.colorStyleOf(pending.original());
            MutableText line = Text.literal(result.translatedText()).setStyle((colored == null ? Style.EMPTY : colored)
                    .withClickEvent(new ClickEvent.CopyToClipboard(pending.plainText()))
                    .withHoverEvent(new HoverEvent.ShowText(Text.empty()
                            .append(pending.original())
                            .append(Text.literal("\n[" + languageTag(result) + " · click to copy the original]")
                                    .styled(s -> s.withColor(0x777777))))));
            line.append(Text.literal(" ⧉").setStyle(Style.EMPTY
                    .withColor(0x555555)
                    .withClickEvent(new ClickEvent.CopyToClipboard(result.translatedText()))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Copy the translation")))));
            line.append(askAiSuffix(dev.adamfr06.translateeverything.translate.ChatContext.registerTranslated(pending.plainText(), result.translatedText(), false)));
            markEmitted(result.translatedText());
            addLine(client, line, null, pending.indicator());
        } else { // BELOW
            addLine(client, withCopy(pending.original(), pending.plainText(), "Click to copy the original"),
                    pending.signature(), pending.indicator());
            addLine(client, translationLine(result, " ↳ ", pending.plainText()), null, null);
        }
        record(pending, result);
    }

    private static MutableText translationLine(TranslationService.Result result, String prefix, String original) {
        int id = dev.adamfr06.translateeverything.translate.ChatContext.registerTranslated(
                original, result.translatedText(), false);
        return Text.literal(prefix).styled(s -> s.withColor(0x557799))
                .append(Text.literal(result.translatedText()).setStyle(Style.EMPTY
                        .withColor(0xB8C8D8)
                        .withClickEvent(new ClickEvent.CopyToClipboard(result.translatedText()))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal(languageTag(result) + " · click to copy the translation")
                                        .styled(s -> s.withColor(0x777777))))))
                .append(askAiSuffix(id));
    }

    /** A small clickable icon that opens the AI follow-up box for this exact message. */
    private static MutableText askAiSuffix(int id) {
        return Text.literal(" ⟳").setStyle(Style.EMPTY
                .withColor(0x5AC8E0)
                .withClickEvent(new ClickEvent.RunCommand("/teai " + id))
                .withHoverEvent(new HoverEvent.ShowText(
                        Text.literal("Ask the AI about this message").styled(s -> s.withColor(0x9AD8E8)))));
    }

    /** Clickable icon on a line that was skipped as already readable; translates it on demand. */
    private static MutableText forceSuffix(int id) {
        return Text.literal(" ⇄").setStyle(Style.EMPTY
                .withColor(0xE6B15E)
                .withClickEvent(new ClickEvent.RunCommand("/teforce " + id))
                .withHoverEvent(new HoverEvent.ShowText(
                        Text.literal("Translate this line").styled(s -> s.withColor(0xE6B15E)))));
    }

    /** Translates a previously skipped chat line and prints the result beneath it. */
    public static void forceTranslate(MinecraftClient client, int id) {
        ChatContext.Translated line = ChatContext.byId(id);
        if (line == null) {
            CaptureManager.feedback(client, "That message is no longer available");
            return;
        }
        TEConfig cfg = TEConfig.get();
        TranslationService.translate(line.original(), cfg.sourceLanguage, cfg.targetLanguage, null, java.util.List.of())
                .thenAccept(result -> client.execute(() -> {
                    if (result == null || result.error() || result.translatedText().isBlank()) {
                        CaptureManager.feedback(client, "Translation failed");
                        return;
                    }
                    addLine(client, translationLine(result, " ↳ ", line.original()), null, null);
                }));
    }

    /** Wraps a text so clicking anywhere on it copies {@code toCopy} (keeps original look). */
    private static Text withCopy(Text original, String toCopy, String hint) {
        return Text.empty().setStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent.CopyToClipboard(toCopy))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal(hint).styled(s -> s.withColor(0x777777)))))
                .append(original);
    }

    private static void addLine(MinecraftClient client, Text text,
                                MessageSignatureData signature, MessageIndicator indicator) {
        markEmitted(text);
        internalAdd = true;
        try {
            client.inGameHud.getChatHud().addMessage(text, signature, indicator);
        } catch (Throwable t) {
            TranslateEverythingClient.LOGGER.error("[Chat] addMessage failed, retrying plain", t);
            try {
                client.inGameHud.getChatHud().addMessage(text);
            } catch (Throwable t2) {
                TranslateEverythingClient.LOGGER.error("[Chat] Plain addMessage failed too", t2);
            }
        } finally {
            internalAdd = false;
        }
    }

    // ------------------------------------------------------------------ echo guard

    private static void markEmitted(Text text) {
        markEmitted(text.getString());
    }

    private static void markEmitted(String raw) {
        String key = CaptureManager.sanitize(raw);
        if (!key.isEmpty()) {
            recentlyEmitted.put(key, System.currentTimeMillis() + ECHO_TTL_MS);
        }
    }

    /** The player's original text, keyed by the translated text that was sent. */
    private static final Map<String, String> selfOriginal = new ConcurrentHashMap<>();
    /** The preview's read-back for each sent message, keyed by the translated text. */
    private static final Map<String, String> selfReadBack = new ConcurrentHashMap<>();

    /** Records a sent message so its server echo is recognised. */
    public static void noteSelfSent(String text) {
        noteSelfSent(text, null);
    }

    /** Records a sent message together with the player's original text. */
    public static void noteSelfSent(String text, String original) {
        noteSelfSent(text, original, null);
    }

    /** As above, plus the read-back the preview already computed for this message. */
    public static void noteSelfSent(String text, String original, String readBack) {
        String key = CaptureManager.sanitize(text);
        if (key != null && !key.isBlank()) {
            selfSent.put(key, System.currentTimeMillis() + 12_000);
            if (original != null && !original.isBlank()) {
                selfOriginal.put(key, original.strip());
                if (selfOriginal.size() > 64) {
                    selfOriginal.clear();
                }
            }
            if (readBack != null && !readBack.isBlank()) {
                selfReadBack.put(key, readBack.strip());
                if (selfReadBack.size() > 64) {
                    selfReadBack.clear();
                }
            }
        }
    }

    /** The preview's read-back for an echoed sent message, or null. */
    private static String knownReadBack(String incomingSanitized) {
        if (incomingSanitized == null) {
            return null;
        }
        for (Map.Entry<String, String> e : selfReadBack.entrySet()) {
            if (incomingSanitized.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** The player's original text for an echoed sent message, or null. */
    private static String knownOriginal(String incomingSanitized) {
        if (incomingSanitized == null) {
            return null;
        }
        for (Map.Entry<String, String> e : selfOriginal.entrySet()) {
            if (incomingSanitized.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean isSelfSent(String incomingSanitized) {
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<String, Long>> it = selfSent.entrySet().iterator();
        boolean hit = false;
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < now) {
                it.remove();
            } else if (incomingSanitized != null && incomingSanitized.contains(e.getKey())) {
                hit = true;
            }
        }
        return hit;
    }

    private static boolean isOwnEcho(String sanitized) {
        Long expiry = recentlyEmitted.get(sanitized);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry) {
            recentlyEmitted.remove(sanitized);
            return false;
        }
        return true;
    }

    private static void pruneEchoes() {
        if (recentlyEmitted.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = recentlyEmitted.entrySet().iterator();
        while (it.hasNext()) {
            if (now > it.next().getValue()) {
                it.remove();
            }
        }
        if (recentlyEmitted.size() > MAX_ECHO_ENTRIES) {
            recentlyEmitted.clear(); // pathological flood; a brief guard gap is fine
        }
    }

    // ------------------------------------------------------------------ conflict warning

    private static void showConflictWarning(MinecraftClient client) {
        Text warning = Text.empty()
                .append(Text.literal("⚠ TranslateEverything: ").styled(s -> s.withColor(0xFFAA00)))
                .append(Text.literal(conflictMod + " also translates chat. Running both duplicates work and can loop "
                        + "messages. Disable " + conflictMod + " or turn off chat translation here.").styled(s -> s.withColor(0xFFDD88)));
        addLine(client, warning, null, null);
        TranslateEverythingClient.LOGGER.warn("[Chat] Conflict: {} is also handling chat. Disable one chat "
                + "translator to avoid duplicated/looping messages.", conflictMod);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean sameLanguage(TranslationService.Result result) {
        return LanguageUtil.sameLanguage(result.detectedLanguage(), TEConfig.get().targetLanguage);
    }

    private static String languageTag(TranslationService.Result result) {
        return result.detectedLanguage().toUpperCase(Locale.ROOT) + " → "
                + TEConfig.get().targetLanguage.toUpperCase(Locale.ROOT);
    }

    /** Names of players currently online, so a username is never translated as a word. */
    private static java.util.List<String> onlinePlayerNames() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() == null) {
                return java.util.List.of();
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            for (net.minecraft.client.network.PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                names.add(entry.getProfile().getName());
            }
            return names;
        } catch (Throwable t) {
            return java.util.List.of(); // never let name lookup break translation
        }
    }

    private static String preview(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static void record(Pending pending, TranslationService.Result result) {
        BoxManager.recordExternal(SourceType.CHAT, "Chat", pending.plainText(),
                result.translatedText(), result.detectedLanguage());
    }
}
