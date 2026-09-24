package dev.adamfr06.translateeverything.capture;

import dev.adamfr06.translateeverything.TranslateEverythingClient;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.translate.AiConversation;
import dev.adamfr06.translateeverything.translate.TranslationService;
import dev.adamfr06.translateeverything.mixin.ChatScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/** Translates text the player types / sends (outgoing), the mirror of the incoming translators. */
public final class InputTranslator {
    /** Updated by the incoming chat translator so "auto" outgoing target can follow the room. */
    private static volatile String lastIncomingLang = "";

    // Live preview state (single-threaded: touched only on the client thread).
    private static String pendingInput = "";
    private static long pendingSince = 0;
    private static long previewRevision = 0;
    private static String shownInput = "";
    private static String forwardText = "";
    private static String forwardLang = "";
    private static String roundTripText = "";
    private static boolean computing = false;
    // Beta faithfulness check (verdict OK / MINOR / OFF / "…" pending / "" off).
    private static String faithVerdict = "";
    private static String faithNote = "";

    /** Set while the mod sends a message itself, so the send hook ignores it. */
    public static boolean internalSend = false;

    /** The current chat draft, tracked from the ChatScreen so the confirm keybind can read it. */
    public static volatile String lastDraft = "";

    public static void noteDraft(String s) {
        lastDraft = s == null ? "" : s;
    }

    public static String currentDraft() {
        return lastDraft;
    }

    /** Translation staged into the chat box by the review screen, pending the player's send. */
    public static volatile String pendingChatInsert = null;
    /** A translation already reviewed by the user; sending this exact text skips re-translation. */
    public static volatile String sendVerbatim = null;

    /** Draft and its reviewed translation after "Edit in chat"; sending the draft sends the pinned text. */
    public static volatile String pinnedOriginal = null;
    public static volatile String pinnedTranslation = null;

    /** The review conversation the current draft was refined in. */
    public static volatile AiConversation refineSession = null;
    private static volatile long refineTouched = 0;
    private static final long REFINE_TTL_MS = 5 * 60_000;
    /** True when the current preview came from the review session. */
    private static boolean stickyApplied = false;
    /** The current forward text is an error message, never something to send. */
    private static boolean forwardIsError = false;
    /** Tag for the typing preview, so superseded keystrokes stop costing anything. */
    private static final String PREVIEW_TAG = "input-preview";

    /** Called by the confirm box's "Use this translation", stage the text into the chat box, don't send yet. */
    public static void applyToChat(String text) {
        if (text != null && !text.isBlank()) {
            pendingChatInsert = text.strip();
            sendVerbatim = text.strip();
        }
    }

    /** "Edit in chat": restores the draft, pins the reviewed translation and keeps the review conversation. */
    public static void editInChatPinned(String english, String fixedTranslation, AiConversation session) {
        if (english == null || english.isBlank()) {
            applyToChat(fixedTranslation);
            return;
        }
        pendingChatInsert = english.strip();
        pinnedOriginal = english.strip();
        pinnedTranslation = fixedTranslation == null ? null : fixedTranslation.strip();
        sendVerbatim = null;
        refineSession = session;
        refineTouched = System.currentTimeMillis();
        resetPreview();
    }

    /** Whether {@code draft} is still an edit of the message the review session was about. */
    private static boolean sameThought(String draft, String sessionOriginal) {
        if (draft == null || sessionOriginal == null) {
            return false;
        }
        String a = draft.toLowerCase(Locale.ROOT).strip();
        String b = sessionOriginal.toLowerCase(Locale.ROOT).strip();
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) < 12) return false;
        return a.startsWith(b + " ") || b.startsWith(a + " ");
    }

    /** The refine session, if it is still fresh and still about this draft; retires it otherwise. */
    private static AiConversation liveSession(String draft) {
        AiConversation s = refineSession;
        if (s == null) {
            return null;
        }
        if (System.currentTimeMillis() - refineTouched > REFINE_TTL_MS || !sameThought(draft, s.currentOriginal)) {
            refineSession = null;
            return null;
        }
        return s;
    }

    private InputTranslator() {
    }

    public static boolean active() {
        TEConfig cfg = TEConfig.get();
        return cfg.enabled && cfg.inputTranslatorEnabled;
    }

    public static void noteIncomingLanguage(String lang) {
        if (lang != null && !lang.isBlank()) {
            lastIncomingLang = lang.toLowerCase(Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------ what was typed

    /**
     * One line of input, split into the part that is sent unchanged and the part that is language.
     *
     * @param prefix kept verbatim: a whisper's verb and recipient, or nothing
     * @param core the text to translate
     * @param recipient who a whisper is addressed to, for the preview line; "" otherwise
     * @param translatable false when this line must be sent exactly as typed
     */
    public record Typed(String prefix, String core, String recipient, boolean translatable) {
    }

    /** Works out which part of {@code input} is a message. */
    public static Typed typed(String input) {
        TEConfig cfg = TEConfig.get();
        if (input == null || input.isBlank()) {
            return new Typed("", "", "", false);
        }
        if (input.startsWith("/")) {
            dev.adamfr06.translateeverything.translate.CommandShape.Whisper w =
                    dev.adamfr06.translateeverything.translate.CommandShape.parse(input);
            if (w != null) {
                return new Typed(w.prefix(), w.message(), w.recipient(), true);
            }
            return cfg.inputTranslateCommands ? new Typed("", input, "", true) : new Typed(input, "", "", false);
        }
        String disregard = cfg.contextDisregardPrefix;
        if (disregard != null && !disregard.isBlank() && input.startsWith(disregard)) {
            return new Typed("", input.substring(disregard.length()), "", true);
        }
        return new Typed("", input, "", true);
    }

    /** True when this line should carry no conversational context (the ' prefix). */
    private static boolean noContext(String input) {
        String disregard = TEConfig.get().contextDisregardPrefix;
        return disregard != null && !disregard.isBlank() && !input.startsWith("/") && input.startsWith(disregard);
    }

    /** The language outgoing text is translated into (resolves "auto"). */
    public static String resolveTarget() {
        String configured = TEConfig.get().inputTargetLanguage;
        if (configured != null && !configured.equalsIgnoreCase("auto") && !configured.isBlank()) {
            return configured;
        }
        return lastIncomingLang.isBlank() ? "en" : lastIncomingLang;
    }

    // ------------------------------------------------------------------ selection (mode 1)

    /** Translates the focused text field's selection (or all of it) in place. */
    public static void translateFocusedField(MinecraftClient client, TextFieldWidget field) {
        String selected = field.getSelectedText();
        boolean whole = selected == null || selected.isEmpty();
        String text = whole ? field.getText() : selected;
        if (text == null || text.isBlank()) {
            CaptureManager.feedback(client, "Nothing to translate in this field");
            return;
        }
        String fieldSnapshot = field.getText();
        int cursorSnapshot = field.getCursor();
        Object screenSnapshot = client.currentScreen;
        String target = resolveTarget();
        CaptureManager.feedback(client, "Translating field -> " + target.toUpperCase(Locale.ROOT) + "…");
        TranslationService.translate(text, TranslationService.homeLanguage(), target, TEConfig.get().inputEngine, null)
                .thenAccept(result -> client.execute(() -> {
                    if (client.currentScreen != screenSnapshot || !field.getText().equals(fieldSnapshot) || field.getCursor() != cursorSnapshot || !java.util.Objects.equals(field.getSelectedText(), selected)) return;
                    if (result.error()) {
                        CaptureManager.feedback(client, "Input translate failed: " + result.errorMessage());
                        return;
                    }
                    String out = result.translatedText();
                    if (out.isBlank()) {
                        return;
                    }
                    // Copy to the clipboard as well, then paste in place.
                    client.keyboard.setClipboard(out);
                    if (whole) {
                        field.setText(out);
                    } else {
                        field.write(out); // replaces the current selection
                    }
                }));
    }

    // ------------------------------------------------------------------ preview (mode 2)

    /** Refreshes the debounced preview for the current chat input. */
    public static void updatePreview(String currentInput) {
        TEConfig cfg = TEConfig.get();
        if (!active() || cfg.inputMode != TEConfig.InputMode.PREVIEW) {
            return;
        }
        if (currentInput == null) {
            currentInput = "";
        }
        if (currentInput.equals(shownInput)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!currentInput.equals(pendingInput)) {
            pendingInput = currentInput;
            previewRevision++;
            TranslationService.supersede(PREVIEW_TAG);
            forwardText = "";
            roundTripText = "";
            shownInput = "\u0000";
            pendingSince = now;
            return;
        }
        if (now - pendingSince < TEConfig.get().inputDebounceMs) {
            return;
        }
        // Stable and new -> (re)compute.
        final String input = currentInput;
        final long request = previewRevision;
        shownInput = input;
        forwardText = "";
        roundTripText = "";
        forwardLang = "";
        faithVerdict = "";
        faithNote = "";
        stickyApplied = false;
        forwardIsError = false;
        Typed typedInput = typed(input);
        if (!typedInput.translatable() || typedInput.core().isBlank()) {
            computing = false;
            return;
        }
        // Pinned (AI-checked) translation: keep showing it while the English is unchanged.
        if (pinnedOriginal != null) {
            if (input.equals(pinnedOriginal) && pinnedTranslation != null && !pinnedTranslation.isBlank()) {
                computing = false;
                forwardText = typed(pinnedTranslation).core();
                forwardLang = "";
                faithVerdict = "PINNED";
                if (TEConfig.get().inputRoundTrip) {
                    TranslationService.backTranslate(forwardText, resolveTarget(), TranslationService.homeLanguage(),
                            TEConfig.get().inputRoundTripEngine, PREVIEW_TAG)
                            .thenAccept(rt -> MinecraftClient.getInstance().execute(() -> {
                                if (request == previewRevision && input.equals(shownInput) && !rt.error()) {
                                    roundTripText = rt.translatedText();
                                }
                            }));
                }
                return;
            }
            pinnedOriginal = null; // edited away from the pinned text, translate fresh
            pinnedTranslation = null;
        }
        computing = true;
        String target = resolveTarget();
        boolean noCtx = noContext(input);
        final String core = typedInput.core();
        final java.util.List<String> ctx = noCtx ? java.util.List.of() : dev.adamfr06.translateeverything.translate.ChatContext.window(null);
        AiConversation sess = liveSession(core);
        if (sess != null) {
            refineTouched = System.currentTimeMillis();
            sess.reseedOriginalOnly(core);
            sess.askHidden("I edited my message to: \"" + core + "\". Re-translate it into " + sess.tgtName
                            + ", keeping every correction and tone choice we already agreed on. "
                            + "Reply with ONLY one line 'TRANSLATION: <text>'.")
                    .thenAccept(reply -> MinecraftClient.getInstance().execute(() -> {
                        if (request != previewRevision || !input.equals(shownInput)) {
                            return; // superseded by newer input
                        }
                        String t = AiConversation.suggestionIn(reply);
                        if (t == null || t.isBlank()) {
                            coldPreview(input, core, target, ctx); // model rambled, plain translation instead
                            return;
                        }
                        computing = false;
                        stickyApplied = true;
                        sess.currentTranslation = t;
                        forwardText = t;
                        forwardLang = "";
                        afterForward(input, core, target, ctx);
                    }));
            return;
        }
        coldPreview(input, core, target, ctx);
    }

    /** A plain forward translation on the configured engine (no session context). */
    private static void coldPreview(String input, String core, String target, java.util.List<String> ctx) {
        final long request = previewRevision;
        computing = true;
        TranslationService.supersede(PREVIEW_TAG);
        TranslationService.translate(core, TranslationService.homeLanguage(), target, TEConfig.get().inputEngine, ctx, PREVIEW_TAG)
                .thenAccept(fwd -> MinecraftClient.getInstance().execute(() -> {
                    if (request != previewRevision || !input.equals(shownInput)) {
                        return; // superseded by newer input
                    }
                    computing = false;
                    if (fwd.error()) {
                        forwardText = "(" + fwd.errorMessage() + ")";
                        forwardIsError = true;
                        return;
                    }
                    forwardText = fwd.translatedText();
                    forwardLang = fwd.detectedLanguage();
                    afterForward(input, core, target, ctx);
                }));
    }

    /**
     * Conversation context with the player's own original message removed, so an AI round-trip
     * cannot simply read back the wording it is supposed to be checking.
     */
    private static java.util.List<String> blindContext(java.util.List<String> ctx, String core) {
        if (ctx == null || ctx.isEmpty() || core == null || core.isBlank()) {
            return java.util.List.of();
        }
        String needle = core.strip().toLowerCase(Locale.ROOT);
        java.util.List<String> out = new java.util.ArrayList<>(ctx.size());
        for (String line : ctx) {
            if (line == null) {
                continue;
            }
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains(needle) || needle.contains(l.strip())) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /** Round-trip + faithfulness for whatever forward translation just landed. */
    private static void afterForward(String input, String core, String target, java.util.List<String> ctx) {
        final long request = previewRevision;
        if (forwardText.isBlank()) {
            return;
        }
        if (TEConfig.get().inputRoundTrip) {
            String back = forwardLang.isBlank() ? TranslationService.homeLanguage() : forwardLang;
            TEConfig.Engine rtEngine = TEConfig.get().inputRoundTripEngine;
            TranslationService.backTranslate(forwardText, target, back, rtEngine, PREVIEW_TAG)
                    .thenAccept(rt -> MinecraftClient.getInstance().execute(() -> {
                        if (request == previewRevision && input.equals(shownInput) && !rt.error()) {
                            roundTripText = rt.translatedText();
                        }
                    }));
        }
        if (TEConfig.get().inputFaithfulnessCheck) {
            faithVerdict = "…";
            TranslationService.checkFaithfulness(core, forwardText, target, ctx)
                    .thenAccept(f -> MinecraftClient.getInstance().execute(() -> {
                        if (request == previewRevision && input.equals(shownInput)) {
                            faithVerdict = f.valid() ? f.verdict() : "";
                            faithNote = f.note();
                        }
                    }));
        }
    }

    /** The round-trip the preview bar already computed for {@code original}, or null. */
    public static String cachedRoundTrip(String original) {
        if (original == null || roundTripText.isBlank()) {
            return null;
        }
        return original.strip().equals(shownInput.strip()) ? roundTripText : null;
    }

    public static String cachedRoundTrip(String original, String translated) {
        return translated != null && translated.equals(forwardText) ? cachedRoundTrip(original) : null;
    }

    /** Draws the preview bar just above the chat input field. */
    public static void renderPreviewBar(DrawContext context, TextFieldWidget chatField, int screenWidth) {
        var client = MinecraftClient.getInstance();
        var wrapped = previewLines(chatField == null ? "" : chatField.getText(), screenWidth);
        if (wrapped.isEmpty()) return;
        int lineH = 11;
        int barBottom = client.getWindow().getScaledHeight() - 22;
        int visible = Math.min(wrapped.size(), Math.max(2, (client.getWindow().getScaledHeight() / 2 - 22) / lineH));
        int y = barBottom - visible * lineH - 2;
        context.fill(2, y - 2, screenWidth - 2, barBottom - 2, 0xD0000000);
        for (int i = 0; i < visible; i++) {
            context.drawText(client.textRenderer, wrapped.get(i), 6, y + i * lineH, 0xFFFFFFFF, true);
        }
    }

    private static java.util.List<net.minecraft.text.OrderedText> previewLines(String currentInput, int screenWidth) {
        TEConfig cfg = TEConfig.get();
        if (!active() || cfg.inputMode != TEConfig.InputMode.PREVIEW) {
            return java.util.List.of();
        }
        updatePreview(currentInput);
        Typed typedInput = typed(currentInput);
        if (!typedInput.translatable() || typedInput.core().isBlank()) {
            return java.util.List.of();
        }
        String addressed = typedInput.recipient().isBlank() ? "" : "to " + typedInput.recipient() + ": ";
        MinecraftClient client = MinecraftClient.getInstance();
        String target = resolveTarget().toUpperCase(Locale.ROOT);
        java.util.List<Text> lines = new java.util.ArrayList<>();
        if (forwardText.isBlank()) {
            lines.add(Text.literal("-> " + target + "  ").styled(s -> s.withColor(0x66CCFF))
                    .append(Text.literal(computing ? "translating…" : "…").styled(s -> s.withColor(0x888888))));
        } else {
            lines.add(Text.literal("-> " + target + "  ").styled(s -> s.withColor(0x66CCFF))
                    .append(Text.literal(addressed).styled(s -> s.withColor(0xE6B15E)))
                    .append(Text.literal(forwardText).styled(s -> s.withColor(0xFFFFFF))));
            if (cfg.inputRoundTrip && !roundTripText.isBlank()) {
                String back = (forwardLang.isBlank() ? TranslationService.homeLanguage() : forwardLang).toUpperCase(Locale.ROOT);
                lines.add(Text.literal("-> " + back + "  ").styled(s -> s.withColor(0x77AA77))
                        .append(Text.literal(roundTripText).styled(s -> s.withColor(0xBBBBBB))));
            }
            if (stickyApplied) {
                lines.add(Text.literal("Reviewed").styled(s -> s.withColor(0x4FC1A6)));
            }
            if (!faithVerdict.isEmpty()) {
                lines.addAll(faithLines(client, Math.min(screenWidth - 24, 440)));
            }
        }
        java.util.List<net.minecraft.text.OrderedText> wrapped = new java.util.ArrayList<>();
        for (Text line : lines) wrapped.addAll(client.textRenderer.wrapLines(line, Math.max(80, screenWidth - 20)));
        return wrapped;
    }

    public static int chatPreviewSpace() {
        var client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof ChatDraftAccess access)) return 0;
        int height = client.getWindow().getScaledHeight();
        int lines = previewLines(access.translateeverything$draft(), client.getWindow().getScaledWidth()).size();
        return lines == 0 ? 0 : Math.max(0, Math.min(lines, Math.max(2, (height / 2 - 22) / 11)) * 11 - 10);
    }

    /** The verify verdict as styled preview lines, the full note, WRAPPED, never cut off mid-sentence. */
    private static java.util.List<Text> faithLines(MinecraftClient client, int maxW) {
        int col;
        String text;
        switch (faithVerdict) {
            case "OK" -> { col = 0x77DD88; text = "Accuracy: passed"; }
            case "PINNED" -> { col = 0x77DD88; text = "Reviewed"; }
            case "MINOR" -> { col = 0xE0B15E; text = "Difference: " + cleanNote(faithNote, "minor wording change"); }
            case "OFF" -> { col = 0xFF6B6B; text = "Meaning differs: " + cleanNote(faithNote, "Review translation"); }
            default -> { col = 0x888888; text = "Checking" + dots(); }
        }
        final int fc = col;
        java.util.List<String> wrapped = new java.util.ArrayList<>(3);
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String probe = line.isEmpty() ? word : line + " " + word;
            if (client.textRenderer.getWidth(probe) > maxW && !line.isEmpty()) {
                wrapped.add(line.toString());
                line = new StringBuilder("   " + word); // hanging indent under the verdict mark
            } else {
                line = new StringBuilder(probe);
            }
        }
        if (!line.isEmpty()) {
            wrapped.add(line.toString());
        }
        if (wrapped.size() > 3) { // three lines is plenty for a chat overlay
            wrapped = new java.util.ArrayList<>(wrapped.subList(0, 3));
            wrapped.set(2, wrapped.get(2) + "…");
        }
        java.util.List<Text> out = new java.util.ArrayList<>(wrapped.size());
        for (String l : wrapped) {
            out.add(Text.literal(l).styled(s -> s.withColor(fc)));
        }
        return out;
    }

    private static String dots() {
        return ".".repeat((int) ((System.currentTimeMillis() / 300) % 4));
    }

    private static String cleanNote(String note, String fallback) {
        if (note == null || note.isBlank()) {
            return fallback;
        }
        String n = note.replaceAll("\\s+", " ").trim();
        return n.length() > 200 ? n.substring(0, 199) + "…" : n; // sanity cap only, display wraps
    }

    // ------------------------------------------------------------------ send interception (modes 2 & 3)

    /** Called from the ChatScreen send hook. */
    public static boolean interceptSend(MinecraftClient client, String content, boolean addToHistory) {
        TEConfig cfg = TEConfig.get();
        if (internalSend || !active() || cfg.inputMode == TEConfig.InputMode.SELECTION) {
            return false;
        }
        if (content == null || content.isBlank()) {
            return false;
        }
        if (sendVerbatim != null && content.equals(sendVerbatim)) {
            sendVerbatim = null;
            ChatTranslator.noteSelfSent(content);
            return false;
        }
        if (pinnedOriginal != null && content.equals(pinnedOriginal)
                && pinnedTranslation != null && !pinnedTranslation.isBlank()) {
            String pinned = pinnedTranslation;
            String originalBody = typed(content).core();
            String approvedBack = roundTripText;
            pinnedOriginal = null;
            pinnedTranslation = null;
            dev.adamfr06.translateeverything.translate.ChatContext.add(originalBody,
                    dev.adamfr06.translateeverything.translate.ChatContext.Origin.OUTGOING);
            resetPreview();
            doSend(client, pinned, originalBody, approvedBack);
            return true;
        }
        Typed typedSend = typed(content);
        if (!typedSend.translatable() || typedSend.core().isBlank()) {
            return false; // sent exactly as typed
        }
        boolean noCtx = noContext(content);
        final String core = typedSend.core();
        final String envelope = typedSend.prefix();
        if (cfg.inputMode == TEConfig.InputMode.PREVIEW && content.equals(shownInput)
                && !computing && !forwardText.isBlank() && !forwardIsError) {
            String approved = envelope + forwardText;
            String approvedBack = roundTripText;
            dev.adamfr06.translateeverything.translate.ChatContext.add(core, dev.adamfr06.translateeverything.translate.ChatContext.Origin.OUTGOING);
            resetPreview();
            if (envelope.isEmpty() && client.currentScreen instanceof ChatScreen chatScreen) {
                ChatTranslator.noteSelfSent(approved, core, approvedBack);
                refineSession = null;
                internalSend = true;
                try {
                    ((ChatScreenAccessor) (Object) chatScreen).translateeverything$sendMessage(approved, addToHistory);
                } finally {
                    internalSend = false;
                }
            } else {
                doSend(client, approved, core, approvedBack);
            }
            return true;
        }
        java.util.List<String> ctx = noCtx ? java.util.List.of() : dev.adamfr06.translateeverything.translate.ChatContext.window(null);
        dev.adamfr06.translateeverything.translate.ChatContext.add(core, dev.adamfr06.translateeverything.translate.ChatContext.Origin.OUTGOING);
        String target = resolveTarget();
        Object connection = client.getNetworkHandler();
        resetPreview();
        TranslationService.translate(core, TranslationService.homeLanguage(), target, cfg.inputEngine, ctx)
                .completeOnTimeout(TranslationService.Result.fail("send timeout"),
                        cfg.inputEngine == TEConfig.Engine.AI_LOCAL
                                ? Math.max(cfg.chatHoldMaxMs, cfg.aiTimeoutMs)
                                : Math.max(1500, cfg.chatHoldMaxMs),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenAccept(result -> client.execute(() -> {
                    if (connection != client.getNetworkHandler()) return;
                    boolean failed = result == null || result.error() || result.translatedText().isBlank();
                    if (failed) {
                        pendingChatInsert = content;
                        CaptureManager.feedback(client, "Translation failed. Your draft was restored.");
                        return;
                    }
                    String toSend = failed ? core : result.translatedText();
                    if (client.currentScreen instanceof ChatScreen chatScreen) {
                        internalSend = true;
                        try {
                            ((ChatScreenAccessor) (Object) chatScreen)
                                    .translateeverything$sendMessage(toSend, addToHistory);
                        } finally {
                            internalSend = false;
                        }
                    } else {
                        doSend(client, toSend);
                    }
                }));
        return true;
    }

    private static void doSend(MinecraftClient client, String message) {
        doSend(client, message, null, null);
    }

    private static void doSend(MinecraftClient client, String message, String original) {
        doSend(client, message, original, null);
    }

    /** Sends a message, recording the original text and preview read-back so its echo needs no translation. */
    private static void doSend(MinecraftClient client, String message, String original, String readBack) {
        if (message.length() > 256) {
            var envelope = dev.adamfr06.translateeverything.translate.CommandShape.parse(message);
            pendingChatInsert = original == null ? message : original;
            if (envelope != null && !pendingChatInsert.startsWith(envelope.prefix())) pendingChatInsert = envelope.prefix() + pendingChatInsert;
            CaptureManager.feedback(client, "Translation exceeds the 256-character chat limit. Draft preserved.");
            return;
        }
        if (client.getNetworkHandler() == null) {
            return;
        }
        refineSession = null;
        ChatTranslator.noteSelfSent(message, original, readBack);
        internalSend = true;
        try {
            if (message.startsWith("/")) {
                client.getNetworkHandler().sendChatCommand(message.substring(1));
            } else {
                client.getNetworkHandler().sendChatMessage(message);
            }
        } catch (Throwable t) {
            TranslateEverythingClient.LOGGER.error("[Input] Failed to send translated message", t);
        } finally {
            internalSend = false;
        }
    }

    public static void resetSession() {
        resetPreview(); lastIncomingLang = ""; lastDraft = "";
        pendingChatInsert = sendVerbatim = pinnedOriginal = pinnedTranslation = null;
        refineSession = null;
    }

    public static void resetPreview() {
        previewRevision++;
        TranslationService.supersede(PREVIEW_TAG);
        forwardIsError = false;
        stickyApplied = false;
        pendingInput = "";
        shownInput = "";
        forwardText = "";
        roundTripText = "";
        forwardLang = "";
        computing = false;
        faithVerdict = "";
        faithNote = "";
    }

    // ------------------------------------------------------------------ AI conversation boxes

    /** Sends already-translated text as-is (used when applying the AI's suggested translation). */
    public static void sendTranslated(MinecraftClient client, String text) {
        if (text != null && !text.isBlank()) {
            doSend(client, text.strip());
        }
    }

    public static void sendReviewed(MinecraftClient client, String text, String original, String back) {
        dev.adamfr06.translateeverything.translate.ChatContext.add(original,
                dev.adamfr06.translateeverything.translate.ChatContext.Origin.OUTGOING);
        resetPreview();
        doSend(client, text, original, back);
    }

    /** Opens the confirmation box for the current outgoing draft. */
    public static void openConfirm(MinecraftClient client, net.minecraft.client.gui.screen.Screen parent, String draft) {
        if (draft == null || draft.isBlank()) {
            CaptureManager.feedback(client, "Type a message first");
            return;
        }
        TEConfig cfg = TEConfig.get();
        boolean noCtx = !cfg.contextDisregardPrefix.isBlank() && draft.startsWith(cfg.contextDisregardPrefix);
        Typed envelope = typed(draft);
        if (!envelope.translatable() || envelope.core().isBlank()) {
            CaptureManager.feedback(client, "No message to review");
            return;
        }
        final String orig = envelope.core().strip();
        AiConversation resumed = liveSession(orig);
        if (resumed != null) {
            refineTouched = System.currentTimeMillis();
            resumed.reseedOriginalOnly(orig);
            resumed.commandPrefix = envelope.prefix();
            resumed.targetCode = resolveTarget();
            resumed.useContext = !noCtx;
            client.setScreen(new dev.adamfr06.translateeverything.gui.AiChatScreen(parent, resumed));
            return;
        }
        java.util.List<String> ctx = noCtx ? java.util.List.of()
                : dev.adamfr06.translateeverything.translate.ChatContext.window(null);
        String target = resolveTarget();
        String tgtName = dev.adamfr06.translateeverything.translate.AiConversation.langName(target);
        String gloss = dev.adamfr06.translateeverything.translate.MinecraftGlossary.hints(orig, target);
        dev.adamfr06.translateeverything.translate.AiConversation convo =
                dev.adamfr06.translateeverything.translate.AiConversation.confirm(orig, "", TranslationService.homeLanguage(), tgtName,
                        AiConversation.langName(TranslationService.homeLanguage()), ctx, gloss);
        convo.commandPrefix = envelope.prefix();
        convo.targetCode = target;
        convo.useContext = !noCtx;
        client.setScreen(new dev.adamfr06.translateeverything.gui.AiChatScreen(parent, convo));
    }

    /** Opens the follow-up box for the most recently translated incoming message. */
    public static void openFollowup(MinecraftClient client) {
        dev.adamfr06.translateeverything.translate.ChatContext.Translated last =
                dev.adamfr06.translateeverything.translate.ChatContext.last();
        if (last == null) {
            CaptureManager.feedback(client, "No recent message to ask about");
            return;
        }
        TEConfig cfg = TEConfig.get();
        String tgtName = dev.adamfr06.translateeverything.translate.AiConversation.langName(cfg.targetLanguage);
        dev.adamfr06.translateeverything.translate.AiConversation convo =
                dev.adamfr06.translateeverything.translate.AiConversation.followup(
                        last.original(), last.translation(), "the original language", tgtName, tgtName, last.context());
        client.setScreen(new dev.adamfr06.translateeverything.gui.AiChatScreen(null, convo));
    }

    /** Opens the follow-up box for a specific chat message (clicked in chat). */
    public static void openFollowupFor(MinecraftClient client, int id) {
        dev.adamfr06.translateeverything.translate.ChatContext.Translated t =
                dev.adamfr06.translateeverything.translate.ChatContext.byId(id);
        if (t == null) {
            CaptureManager.feedback(client, "That message is no longer available");
            return;
        }
        TEConfig cfg = TEConfig.get();
        String tgtName = dev.adamfr06.translateeverything.translate.AiConversation.langName(cfg.targetLanguage);
        dev.adamfr06.translateeverything.translate.AiConversation convo =
                dev.adamfr06.translateeverything.translate.AiConversation.followup(
                        t.original(), t.translation(), "the original language", tgtName, tgtName, t.context());
        client.setScreen(new dev.adamfr06.translateeverything.gui.AiChatScreen(null, convo));
    }
}
