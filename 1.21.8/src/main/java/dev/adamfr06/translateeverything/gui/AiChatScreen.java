package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.capture.InputTranslator;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.gui.widget.SettingsList;
import dev.adamfr06.translateeverything.translate.AiConversation;
import dev.adamfr06.translateeverything.translate.ChatContext;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import java.util.List;

/** Native controls and a scrolling document keep the entire message available at any GUI scale. */
public class AiChatScreen extends Screen {
    private final Screen parent;
    private final AiConversation convo;
    private final boolean confirm;
    private SettingsList body;
    private TextFieldWidget original, ask;
    private ButtonWidget send, edit, compare, askButton;
    private boolean discussion, started, closed;
    private int revision;
    private long changedAt;
    private String draft, question = "", translatedDraft = "";
    private String aiText = "", referenceText = "", aiBack = "", referenceBack = "";
    private String aiError = "", referenceError = "", status = "";
    private boolean translating, asking;

    public AiChatScreen(Screen parent, AiConversation convo) {
        super(Text.literal(convo.mode == AiConversation.Mode.CONFIRM ? "Review translation" : "Message details"));
        this.parent = parent;
        this.convo = convo;
        confirm = convo.mode == AiConversation.Mode.CONFIRM;
        draft = convo.currentOriginal == null ? "" : convo.currentOriginal;
        discussion = !confirm;
        if (confirm) question = TEConfig.get().aiCheckerDefaultQuestion;
        if (convo.targetCode.isBlank()) convo.targetCode = InputTranslator.resolveTarget();
    }

    private ButtonWidget button(String text, int x, int y, int w, Runnable action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(text), b -> action.run()).dimensions(x,y,w,20).build());
    }

    @Override protected void init() {
        int x = width / 2 - 155;
        button("Translation", x, 28, 150, () -> switchView(false)).active = discussion;
        button("Discussion", x + 160, 28, 150, () -> switchView(true)).active = !discussion;
        if (confirm && !discussion) {
            original = new TextFieldWidget(textRenderer, x, 55, 310, 20, Text.literal("Original message"));
            original.setMaxLength(256);
            original.setText(draft);
            original.setCursor(0, false);
            original.setSelectionEnd(0);
            original.setChangedListener(value -> {
                draft = value;
                changedAt = System.currentTimeMillis();
                revision++;
                translatedDraft = "";
                status = "Draft changed";
                refreshActions();
            });
            addDrawableChild(original);
        } else original = null;
        if (discussion) {
            ask = new TextFieldWidget(textRenderer, x, height - 54, 224, 20, Text.literal("Question"));
            ask.setMaxLength(1024);
            ask.setText(question);
            ask.setChangedListener(value -> question = value);
            addDrawableChild(ask);
            askButton = button("Ask", x + 230, height - 54, 80, () -> askQuestion(question));
        } else {
            ask = null;
            askButton = null;
            if (confirm) {
                compare = button("Compare", x, height - 54, 74, () -> askQuestion("Compare both candidates. Explain any differences in meaning and recommend the more accurate one."));
                button("Casual", x + 78, height - 54, 74, () -> askQuestion("Rewrite the selected translation in a casual tone."));
                button("Formal", x + 156, height - 54, 74, () -> askQuestion("Rewrite the selected translation in a formal tone."));
                button("Shorter", x + 234, height - 54, 76, () -> askQuestion("Shorten the selected translation while preserving its meaning."));
            }
        }
        if (confirm) {
            send = button("Send", x, height - 28, 100, this::sendNow);
            edit = button("Edit in chat", x + 105, height - 28, 100, this::editInChat);
            button("Cancel", x + 210, height - 28, 100, this::close);
        } else {
            button("Copy translation", x, height - 28, 150, () -> MinecraftClient.getInstance().keyboard.setClipboard(convo.currentTranslation));
            button("Done", x + 160, height - 28, 150, this::close);
        }
        rebuildBody();
        if (!started) {
            started = true;
            if (confirm) {
                if (convo.currentTranslation != null && !convo.currentTranslation.isBlank() && !convo.currentTranslation.equals(draft)) {
                    aiText = convo.currentTranslation;
                    translatedDraft = draft;
                    fetchReference(revision, draft);
                    readBack(revision, aiText, true);
                } else translateDraft();
            }
        }
        refreshActions();
    }

    private void switchView(boolean value) {
        discussion = value;
        clearAndInit();
    }

    private void rebuildBody() {
        if (body != null) remove(body);
        body = addDrawableChild(new SettingsList(client, width, height,
                confirm && !discussion ? 82 : 54, height - 62, true));
        if (discussion) {
            if (convo.transcript.isEmpty()) body.note("No discussion yet");
            for (AiConversation.Turn turn : convo.transcript) {
                String text = turn.role().equals("user") ? turn.text() : AiConversation.displayText(turn.text());
                if (!text.isBlank()) {
                    body.section(turn.role().equals("user") ? "You" : "AI");
                    body.note(text);
                }
            }
            String suggestion = convo.latestSuggestion();
            if (suggestion != null && !suggestion.isBlank() && !suggestion.equals(convo.currentTranslation)) {
                body.section("Suggested translation");
                body.note(suggestion);
                body.wide(ButtonWidget.builder(Text.literal("Use suggestion"), b -> {
                    if (confirm && !ready()) return;
                    selectedAI = true;
                    convo.currentTranslation = suggestion;
                    aiText = suggestion;
                    aiBack = "";
                    readBack(revision, suggestion, true);
                    switchView(false);
                }).dimensions(0,0,310,20).build());
            }
            if (asking) body.note("Waiting for response…");
        } else if (confirm) {
            candidate(true, "AI", aiText, aiBack, aiError);
            candidate(false, TranslationService.referenceEngine().label, referenceText, referenceBack, referenceError);
            if (!status.isBlank()) body.note(status);
            if ((convo.commandPrefix + convo.currentTranslation).length() > 256) body.note("Message exceeds the 256-character chat limit");
        } else {
            body.section("Original"); body.note(convo.currentOriginal);
            body.section("Translation"); body.note(convo.currentTranslation);
        }
        refreshActions();
    }

    private boolean selectedAI = true;

    private void candidate(boolean isAI, String name, String text, String back, String error) {
        boolean selected = !text.isBlank() && selectedAI == isAI;
        if (!error.isBlank()) {
            body.section(name);
            body.note(error);
            body.wide(ButtonWidget.builder(Text.literal("Retry"), b -> { if (!asking && !translating) { revision++; translateDraft(); } }).dimensions(0,0,310,20).build());
            return;
        }
        if (text.isBlank()) { body.note("Translating…"); return; }
        ButtonWidget select = ButtonWidget.builder(Text.literal(name + (selected ? " · Selected" : " · Select")), b -> {
            if (!ready()) return;
            selectedAI = isAI;
            convo.currentTranslation = text;
            syncCandidates();
            rebuildBody();
        }).dimensions(0,0,310,20).build();
        select.active = !selected && ready();
        body.wide(select);
        body.note(Text.literal(text).styled(style -> style.withColor(0xFFFFFF))
                .append(Text.literal("\n-> " + (back.isBlank() ? "Translating…" : back)).styled(style -> style.withColor(0xA8B0B8))));
    }

    private boolean ready() {
        return !closed && !translating && !asking && draft.equals(translatedDraft) && !draft.isBlank();
    }

    private boolean current(int request, String source) {
        return !closed && revision == request && draft.equals(source);
    }

    private void refreshActions() {
        boolean ready = ready() && convo.currentTranslation != null && !convo.currentTranslation.isBlank();
        if (send != null) send.active = ready && (convo.commandPrefix + convo.currentTranslation).length() <= 256;
        if (edit != null) edit.active = ready;
        if (compare != null) compare.active = ready && !aiText.isBlank() && !referenceText.isBlank();
        if (askButton != null) askButton.active = !asking && (!confirm || ready);
    }

    private List<String> context() { return convo.useContext ? ChatContext.window(draft) : List.of(); }

    private void translateDraft() {
        if (draft.isBlank()) { translating = false; return; }
        final String source = draft;
        final int request = revision;
        translating = true;
        aiText = referenceText = aiBack = referenceBack = aiError = referenceError = "";
        convo.currentTranslation = "";
        status = "";
        rebuildBody();
        fetchReference(request, source);
        if (!convo.transcript.isEmpty()) {
            convo.reseedOriginalOnly(source);
            convo.askHidden("Translate this edited message into " + convo.tgtName + ", preserving our agreed corrections: " + source
                    + "\nReturn only TRANSLATION: <text>.").thenAccept(reply -> MinecraftClient.getInstance().execute(() -> {
                if (!current(request, source)) { translating = false; return; }
                String suggestion = AiConversation.suggestionIn(reply);
                acceptAI(request, source, suggestion == null ? TranslationService.Result.fail("No translation returned") : TranslationService.Result.ok(suggestion, ""));
            }));
        } else {
            TranslationService.translate(source, TranslationService.homeLanguage(), convo.targetCode, TEConfig.Engine.AI_LOCAL, context())
                    .thenAccept(result -> MinecraftClient.getInstance().execute(() -> {
                        if (!current(request, source)) { translating = false; return; }
                        acceptAI(request, source, result);
                    }));
        }
    }

    private void acceptAI(int request, String source, TranslationService.Result result) {
        translating = false;
        translatedDraft = source;
        if (result.error() || result.translatedText().isBlank()) {
            aiError = result.errorMessage().isBlank() ? "No translation returned" : result.errorMessage();
            if (!referenceText.isBlank()) { selectedAI = false; convo.reseedConfirm(source, referenceText); }
        } else {
            selectedAI = true;
            aiText = result.translatedText();
            convo.reseedConfirm(source, aiText);
            readBack(request, aiText, true);
        }
        rebuildBody();
    }

    private void fetchReference(int request, String source) {
        TranslationService.referenceTranslate(source, TranslationService.homeLanguage(), convo.targetCode, context())
                .thenAccept(result -> MinecraftClient.getInstance().execute(() -> {
                    if (!current(request, source)) return;
                    if (result.error() || result.translatedText().isBlank()) referenceError = result.errorMessage().isBlank() ? "No translation returned" : result.errorMessage();
                    else {
                        referenceText = result.translatedText();
                        if (!translating && convo.currentTranslation.isBlank()) { selectedAI = false; convo.reseedConfirm(source, referenceText); }
                        readBack(request, referenceText, false);
                    }
                    rebuildBody();
                }));
    }

    private void readBack(int request, String candidate, boolean ai) {
        String cached = InputTranslator.cachedRoundTrip(convo.commandPrefix + draft, candidate);
        if (cached != null) { if (ai) aiBack = cached; else referenceBack = cached; rebuildBody(); return; }
        TranslationService.backTranslate(candidate, convo.targetCode, TranslationService.homeLanguage(), TEConfig.get().inputRoundTripEngine, null)
                .thenAccept(result -> MinecraftClient.getInstance().execute(() -> {
                    if (closed || request != revision || !candidate.equals(ai ? aiText : referenceText)) return;
                    String value = result.error() ? "Unavailable: " + result.errorMessage() : result.translatedText();
                    if (ai) aiBack = value; else referenceBack = value;
                    rebuildBody();
                }));
    }

    private void syncCandidates() {
        if (!confirm) return;
        boolean ai = selectedAI;
        convo.setCandidates(ai ? "AI" : TranslationService.referenceEngine().label, ai ? aiBack : referenceBack,
                ai ? TranslationService.referenceEngine().label : "AI", ai ? referenceText : aiText, ai ? referenceBack : aiBack);
    }

    private void askQuestion(String text) {
        if (text == null || text.isBlank() || asking || (confirm && !ready())) return;
        syncCandidates();
        asking = true;
        int request = revision;
        question = "";
        discussion = true;
        convo.ask(text.strip()).thenAccept(reply -> MinecraftClient.getInstance().execute(() -> {
            asking = false;
            if (closed || request != revision) return;
            rebuildBody();
        }));
        clearAndInit();
    }

    @Override public void tick() {
        super.tick();
        if (confirm && !draft.equals(translatedDraft) && !translating && !asking && System.currentTimeMillis() - changedAt >= TEConfig.get().inputDebounceMs) translateDraft();
        refreshActions();
    }

    private void sendNow() {
        if (!ready() || !send.active) return;
        String back = selectedAI ? aiBack : referenceBack;
        TEConfig cfg = TEConfig.get();
        dev.adamfr06.translateeverything.translate.TranslationService.rememberCorrection(
                draft, cfg.sourceLanguage, InputTranslator.resolveTarget(), cfg.inputEngine,
                dev.adamfr06.translateeverything.translate.ChatContext.window(null),
                convo.currentTranslation);
        InputTranslator.sendReviewed(MinecraftClient.getInstance(), convo.commandPrefix + convo.currentTranslation,
                draft, back.startsWith("Unavailable:") ? "" : back);
        closed = true;
        MinecraftClient.getInstance().setScreen(null);
    }

    private void editInChat() {
        if (!ready()) return;
        InputTranslator.editInChatPinned(convo.commandPrefix + draft, convo.commandPrefix + convo.currentTranslation, convo);
        close();
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if ((key == 257 || key == 335) && ask != null && ask.isFocused()) { askQuestion(question); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredTextWithShadow(textRenderer, confirm ? Text.literal(convo.commandPrefix.isBlank() ? "Review · " + convo.tgtName : "Review · " + convo.commandPrefix.strip()) : title, width / 2, 10, 0xFFFFFFFF);
    }
    @Override public void close() { closed = true; revision++; MinecraftClient.getInstance().setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
