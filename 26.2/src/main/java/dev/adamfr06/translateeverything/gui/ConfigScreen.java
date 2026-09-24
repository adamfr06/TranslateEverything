package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.gui.widget.SettingsList;
import dev.adamfr06.translateeverything.translate.LanguageUtil;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** The mod's settings. */
public class ConfigScreen extends Screen {
    private static final int FOOTER_H = 36;

    private final Screen parent;
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private final List<SettingsList> lists = new ArrayList<>();
    private TabNavigationBar tabBar;
    private int selectedTab = 0;

    public ConfigScreen(Screen parent) {
        super(Component.literal("TranslateEverything"));
        this.parent = parent;
    }

    /** Opens the screen on the tab at {@code index}. */
    public void openTab(int index) {
        this.selectedTab = index;
    }

    // ------------------------------------------------------------------ widget factory

    private Button toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        return Button.builder(onOff(label, get.get()), b -> {
            boolean now = !get.get();
            set.accept(now);
            b.setMessage(onOff(label, now));
        }).bounds(0, 0, SettingsList.CONTROL_WIDTH, 20).build();
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": ").append(Component.literal(on ? "ON" : "OFF")
                .withStyle(s -> s.withColor(on ? 0x7FE08A : 0xE0827F)));
    }

    /** A button that steps through a fixed set of choices, vanilla-options style. */
    private <T> Button cycle(String label, T[] values, Supplier<T> get, Consumer<T> set,
                             java.util.function.Function<T, String> name) {
        Button[] self = new Button[1];
        self[0] = Button.builder(Component.literal(label + ": " + name.apply(get.get())), b -> {
            int i = 0;
            for (int k = 0; k < values.length; k++) {
                if (java.util.Objects.equals(values[k], get.get())) {
                    i = k;
                    break;
                }
            }
            T next = values[(i + 1) % values.length];
            set.accept(next);
            b.setMessage(Component.literal(label + ": " + name.apply(next)));
        }).bounds(0, 0, SettingsList.CONTROL_WIDTH, 20).build();
        return self[0];
    }

    private Button engines(String label, Supplier<TEConfig.Engine> get, Consumer<TEConfig.Engine> set) {
        return cycle(label, TEConfig.Engine.values(), get, set, e -> e.label);
    }

    private AbstractSliderButton slider(String label, double min, double max, double step, String unit,
                                        DoubleSupplier get, Consumer<Double> set) {
        return new AbstractSliderButton(0, 0, SettingsList.CONTROL_WIDTH, 20,
                Component.literal(sliderText(label, get.getAsDouble(), step, unit)),
                (get.getAsDouble() - min) / (max - min)) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(sliderText(label, snapped(), step, unit)));
            }

            @Override
            protected void applyValue() {
                set.accept(snapped());
            }

            private double snapped() {
                return Math.round((min + value * (max - min)) / step) * step;
            }
        };
    }

    private static String sliderText(String label, double value, double step, String unit) {
        String v = step >= 1 ? String.valueOf((long) Math.round(value))
                : String.format(Locale.ROOT, "%.2f", value);
        return label + ": " + v + unit;
    }

    private EditBox field(String initial, int maxLength, Consumer<String> onChange) {
        EditBox box = new EditBox(font, 0, 0, SettingsList.CONTROL_WIDTH, 18, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(initial);
        box.setCursorPosition(0);
        box.setHighlightPos(0);
        box.setResponder(onChange);
        return box;
    }

    private static AbstractWidget tip(AbstractWidget w, String text) {
        w.setTooltip(Tooltip.create(Component.literal(text)));
        return w;
    }

    // ------------------------------------------------------------------ assembly

    @Override
    protected void init() {
        lists.clear();
        TEConfig cfg = TEConfig.get();

        MenuTabBar.Builder bar = MenuTabBar.builder(tabManager, width);
        bar.addTabs(
                page("General", list -> translateTab(cfg, list)),
                page("Chat", list -> chatTab(cfg, list)),
                page("Writing", list -> writingTab(cfg, list)),
                page("AI", list -> aiTab(cfg, list)),
                page("Sources", list -> sourcesTab(cfg, list)),
                page("Display", list -> displayTab(cfg, list)),
                page("System", list -> advancedTab(cfg, list)));
        tabBar = addRenderableWidget(bar.build());

        LinearLayout footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(Component.literal("Reset to defaults"), b -> resetDefaults())
                .width(150).build());
        footer.addChild(Button.builder(Component.literal("Done"), b -> onClose()).width(150).build());
        footer.visitWidgets(this::addRenderableWidget);
        footer.arrangeElements();
        net.minecraft.client.gui.layouts.FrameLayout.centerInRectangle(
                footer, 0, height - FOOTER_H, width, FOOTER_H);

        tabBar.selectTab(selectedTab, false);
        repositionElements();
    }

    /** Builds one tab whose whole body is a scrolling settings list. */
    private Tab page(String title, Consumer<SettingsList> build) {
        SettingsList list = new SettingsList(minecraft, width, height, 0, 0);
        build.accept(list);
        lists.add(list);
        return new ListTab(Component.literal(title), list);
    }

    /** A tab that is nothing but its list; the list is sized to whatever area it is given. */
    private final class ListTab implements Tab {
        private final Component title;
        private final SettingsList list;

        ListTab(Component title, SettingsList list) {
            this.title = title;
            this.list = list;
        }

        @Override
        public Component getTabTitle() {
            return title;
        }

        @Override
        public Component getTabExtraNarration() {
            return title;
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> visitor) {
            visitor.accept(list);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            list.updateSizeAndPosition(area.width(), area.height(), area.top());
        }

        @Override
        public Layout getLayout() {
            return null;
        }
    }

    @Override
    protected void repositionElements() {
        if (tabBar == null) {
            return;
        }
        tabBar.arrangeElements(width);
        int top = tabBar.getRectangle().bottom();
        tabManager.setTabArea(new ScreenRectangle(0, top, width, height - top - FOOTER_H));
    }

    // ------------------------------------------------------------------ tabs

    private void translateTab(TEConfig cfg, SettingsList l) {
        l.section("Reading");
        l.pair(toggle("Translation", () -> cfg.enabled, v -> cfg.enabled = v),
                engines("Engine", () -> cfg.engine, e -> cfg.engine = e));
        l.row("Target language", field(cfg.targetLanguage, 8, v -> cfg.targetLanguage = v.trim()));
        l.row("Source language", tip(field(cfg.sourceLanguage, 8, v -> cfg.sourceLanguage = v.trim()),
                "auto detects it per message. A code here forces every message to be read as that language."));

        l.section("In-world text");
        l.pair(toggle("Full immersion", () -> cfg.immersionMode, v -> cfg.immersionMode = v),
                engines("Engine", () -> cfg.immersionEngine, e -> cfg.immersionEngine = e));

        l.section("Engine keys");
        l.row("Google Cloud key", field(cfg.googleApiKey, 128, v -> cfg.googleApiKey = v.trim()));
        l.row("LibreTranslate URL", field(cfg.libreTranslateUrl, 256, v -> cfg.libreTranslateUrl = v.trim()));
        l.row("LibreTranslate key", field(cfg.libreTranslateApiKey, 128, v -> cfg.libreTranslateApiKey = v.trim()));
        l.row("Azure key", field(cfg.azureKey, 128, v -> cfg.azureKey = v.trim()));
        l.row("Azure region", tip(field(cfg.azureRegion, 40,
                        v -> cfg.azureRegion = v.trim().toLowerCase(Locale.ROOT)),
                "The Location/Region field on the resource's Keys and Endpoint page, lowercase and "
                        + "without spaces. A Global resource uses: global"));

        SettingsList.StatusRow status = l.status();
        l.wide(Button.builder(Component.literal("Test connection"), b -> runTest(status))
                .bounds(0, 0, 150, 20).build());
    }

    private void chatTab(TEConfig cfg, SettingsList l) {
        l.section("Incoming chat");
        l.pair(toggle("Chat translation",
                        () -> cfg.isSourceEnabled(SourceType.CHAT),
                        v -> cfg.setSourceEnabled(SourceType.CHAT, v)),
                cycle("Display", TEConfig.ChatMode.values(), () -> cfg.chatMode,
                        v -> cfg.chatMode = v, m -> m.label));
        l.wide(tip(slider("Timeout", 500, 15000, 250, " ms",
                        () -> cfg.chatHoldMaxMs, v -> cfg.chatHoldMaxMs = v.intValue()),
                "How long a message is held back waiting for its translation. Holding it keeps "
                        + "chat from reshuffling; after this it appears untranslated."));

        l.section("Sent messages");
        l.pair(toggle("Own messages",
                        () -> cfg.translateOwnMessages, v -> cfg.translateOwnMessages = v),
                cycle("Show", new Boolean[]{Boolean.FALSE, Boolean.TRUE},
                        () -> cfg.ownMessageShowRoundTrip, v -> cfg.ownMessageShowRoundTrip = v,
                        v -> v ? "Back-translation" : "Original"));
    }

    private void writingTab(TEConfig cfg, SettingsList l) {
        l.section("Sending");
        l.pair(toggle("Outgoing chat",
                        () -> cfg.inputTranslatorEnabled, v -> cfg.inputTranslatorEnabled = v),
                cycle("Mode", TEConfig.InputMode.values(), () -> cfg.inputMode,
                        v -> cfg.inputMode = v, m -> m.label));
        l.row("Target language", tip(field(cfg.inputTargetLanguage, 8, v -> cfg.inputTargetLanguage = v.trim()),
                "auto uses whatever language the chat around you is in."));
        l.pair(engines("Send", () -> cfg.inputEngine, e -> cfg.inputEngine = e),
                engines("Read-back", () -> cfg.inputRoundTripEngine, e -> cfg.inputRoundTripEngine = e));

        l.section("Review");
        l.pair(toggle("Back-translation", () -> cfg.inputRoundTrip, v -> cfg.inputRoundTrip = v),
                toggle("Accuracy check", () -> cfg.inputFaithfulnessCheck,
                        v -> cfg.inputFaithfulnessCheck = v));
        l.pair(toggle("Other commands", () -> cfg.inputTranslateCommands,
                        v -> cfg.inputTranslateCommands = v),
                toggle("Editor buttons", () -> cfg.writeTranslateButtons,
                        v -> cfg.writeTranslateButtons = v));
        l.wide(slider("Typing pause", 100, 3000, 50, " ms",
                () -> cfg.inputDebounceMs, v -> cfg.inputDebounceMs = v.intValue()));
        l.wide(tip(engines("Reference", () -> cfg.referenceEngine, e -> cfg.referenceEngine = e),
                "Used for comparison translations and independent back-translation."));
    }

    private void aiTab(TEConfig cfg, SettingsList l) {
        l.section("Connection");
        l.row("Endpoint", field(cfg.aiEndpointUrl, 256, v -> cfg.aiEndpointUrl = v.trim()));
        l.row("Model", field(cfg.aiModel, 64, v -> cfg.aiModel = v.trim()));
        l.row("API key", field(cfg.aiApiKey, 128, v -> cfg.aiApiKey = v.trim()));
        SettingsList.StatusRow[] status = {l.status()};
        l.pair(Button.builder(Component.literal("Test connection"), b -> runAiTest(status[0]))
                        .bounds(0, 0, 150, 20).build(),
                tip(cycle("Route by", new String[]{"throughput", "latency", "price", ""},
                                () -> cfg.aiProviderSort, v -> cfg.aiProviderSort = v,
                                v -> v == null || v.isBlank() ? "Default" : v),
                        "OpenRouter only: which providers to prefer. Other endpoints ignore this."));

        l.section("Speed and cost");
        l.pair(tip(toggle("Reasoning", () -> cfg.aiAllowReasoning,
                        v -> cfg.aiAllowReasoning = v),
                "Enables model reasoning. May increase latency and output-token use."),
                tip(toggle("Chinese prompt", () -> cfg.aiPromptChinese,
                        v -> cfg.aiPromptChinese = v),
                "Experimental Chinese system instructions. Token savings depend on the model."));
        l.wide(slider("Timeout", 8000, 60000, 1000, " ms",
                () -> cfg.aiTimeoutMs, v -> cfg.aiTimeoutMs = v.intValue()));

        l.section("Conversation context");
        l.pair(toggle("Chat context", () -> cfg.aiUseContext, v -> cfg.aiUseContext = v),
                cycle("Window", TEConfig.ContextMode.values(), () -> cfg.contextMode,
                        v -> cfg.contextMode = v, m -> m.label));
        l.pair(slider("Messages", 0, 30, 1, "", () -> cfg.contextCount,
                        v -> cfg.contextCount = v.intValue()),
                slider("Minutes", 1, 120, 1, "", () -> cfg.contextMinutes,
                        v -> cfg.contextMinutes = v.intValue()));

        l.section("Writing style");
        l.row("Tone", tip(field(cfg.speakerTone, 128, v -> cfg.speakerTone = v.trim()),
                "How you want to sound, e.g. casual and friendly. Blank is neutral."));
        l.row("Preset question", tip(field(cfg.aiCheckerDefaultQuestion, 200,
                        v -> cfg.aiCheckerDefaultQuestion = v),
                "Pre-fills the review screen's ask box. Not sent on its own."));

        l.section("Instructions");
        l.wide(Button.builder(Component.literal(cfg.aiSystemPrompt.isBlank()
                                ? "Edit instructions" : "Edit instructions (custom)"),
                        b -> minecraft.setScreenAndShow(new PromptScreen(this)))
                .bounds(0, 0, 150, 20).build());
    }

    private void sourcesTab(TEConfig cfg, SettingsList l) {
        l.section("Translate text from");
        SourceType[] types = SourceType.values();
        for (int i = 0; i < types.length; i += 2) {
            SourceType a = types[i];
            SourceType b = i + 1 < types.length ? types[i + 1] : null;
            l.pair(tip(toggle(a.label, () -> cfg.isSourceEnabled(a), v -> cfg.setSourceEnabled(a, v)),
                            a.description),
                    b == null ? null
                            : tip(toggle(b.label, () -> cfg.isSourceEnabled(b),
                                    v -> cfg.setSourceEnabled(b, v)), b.description));
        }

        l.section("Capture");
        l.pair(tip(toggle("Renamed items", () -> cfg.itemsRequireCustomText,
                        v -> cfg.itemsRequireCustomText = v),
                "Only translate items with a custom name or lore."),
                slider("Reach", 2, 32, 1, " blocks", () -> cfg.scanRange, v -> cfg.scanRange = v));
        l.pair(slider("Typing pause", 100, 3000, 100, " ms",
                        () -> cfg.editDebounceMs, v -> cfg.editDebounceMs = v.intValue()),
                cycle("Long signs", TEConfig.SignOverflowMode.values(),
                        () -> cfg.signOverflowMode, v -> cfg.signOverflowMode = v, m -> m.label));
    }

    private void displayTab(TEConfig cfg, SettingsList l) {
        l.section("Card position");
        l.pair(cycle("Corner", TEConfig.HudAnchor.values(), () -> cfg.hudAnchor,
                        v -> cfg.hudAnchor = v, a -> a.label),
                slider("Width", 120, 400, 10, " px", () -> cfg.boxWidth, v -> cfg.boxWidth = v.intValue()));
        l.pair(slider("Offset X", 0, 300, 2, " px", () -> cfg.hudOffsetX, v -> cfg.hudOffsetX = v.intValue()),
                slider("Offset Y", 0, 300, 2, " px", () -> cfg.hudOffsetY, v -> cfg.hudOffsetY = v.intValue()));
        l.pair(slider("Scale", 0.5, 2.0, 0.05, "x", () -> cfg.textScale, v -> cfg.textScale = v),
                slider("Maximum", 1, 10, 1, " cards", () -> cfg.maxVisibleBoxes,
                        v -> cfg.maxVisibleBoxes = v.intValue()));
        l.pair(slider("Background", 0, 1, 0.05, "", () -> cfg.backgroundOpacity,
                        v -> cfg.backgroundOpacity = v),
                toggle("Fade in and out", () -> cfg.fadeAnimations, v -> cfg.fadeAnimations = v));

        l.section("Duration");
        l.pair(tip(slider("Events", 0, 60, 1, " s", () -> cfg.autoHideSeconds,
                        v -> cfg.autoHideSeconds = v.intValue()), "Titles and boss bars. 0 keeps them."),
                tip(slider("After looking away", 0, 30, 1, " s", () -> cfg.contextLingerSeconds,
                        v -> cfg.contextLingerSeconds = v.intValue()), "Signs and items."));

        l.section("Content");
        l.pair(toggle("Original text", () -> cfg.showOriginalText, v -> cfg.showOriginalText = v),
                toggle("Language", () -> cfg.showLanguageTag, v -> cfg.showLanguageTag = v));
        l.pair(toggle("Source badge", () -> cfg.showSourceLabel, v -> cfg.showSourceLabel = v),
                toggle("Hide under F3", () -> cfg.hideInDebugHud, v -> cfg.hideInDebugHud = v));
    }

    private void advancedTab(TEConfig cfg, SettingsList l) {
        l.section("Filters");
        l.pair(tip(toggle("Skip target language", () -> cfg.hideSameLanguage,
                        v -> cfg.hideSameLanguage = v),
                "Leave text that is already in your language untranslated. Skipped chat lines "
                        + "can still be translated by clicking the ⇄ next to them."),
                tip(toggle("Non-ASCII only", () -> cfg.onlyTranslateNonAscii,
                        v -> cfg.onlyTranslateNonAscii = v),
                "Only translate text containing non-Latin characters. Latin-script languages "
                        + "such as Spanish or German are skipped."));
        l.pair(slider("Minimum length", 1, 20, 1, " chars", () -> cfg.minTextLength,
                        v -> cfg.minTextLength = v.intValue()),
                toggle("Notification sound", () -> cfg.playSound, v -> cfg.playSound = v));

        l.section("Network");
        l.pair(slider("Timeout", 1000, 30000, 500, " ms", () -> cfg.requestTimeoutMs,
                        v -> cfg.requestTimeoutMs = v.intValue()),
                slider("Spacing", 0, 2000, 50, " ms", () -> cfg.minRequestIntervalMs,
                        v -> cfg.minRequestIntervalMs = v.intValue()));
        l.pair(slider("Request limit", 100, 5000, 100, " chars", () -> cfg.maxCharsPerRequest,
                        v -> cfg.maxCharsPerRequest = v.intValue()),
                slider("Cache size", 100, 20000, 100, "", () -> cfg.cacheSize,
                        v -> cfg.cacheSize = v.intValue()));
        l.pair(toggle("Save cache", () -> cfg.persistentCache,
                        v -> cfg.persistentCache = v),
                toggle("Debug logging", () -> cfg.debugLogging, v -> cfg.debugLogging = v));
        l.wide(Button.builder(Component.literal("Manage cache…"),
                b -> minecraft.setScreenAndShow(new CacheScreen(this))).bounds(0, 0, 150, 20).build());

        l.section("Screen reading");
        l.wide(tip(toggle("OCR scan", () -> cfg.ocrEnabled, v -> cfg.ocrEnabled = v),
                "Reads text from the screen image, such as text on maps or textures. "
                        + "Bind its key under Options > Controls."));
        l.row("OCR key", tip(field(cfg.ocrApiKey, 64, v -> cfg.ocrApiKey = v.trim()),
                "An ocr.space API key. The default demo key is heavily rate-limited."));
    }

    private static String sessionStats() {
        return String.format(Locale.ROOT, "%d stored  ·  %d requests, %d reused, %d failed this session",
                TranslationService.cacheSize(), TranslationService.apiCalls.get(),
                TranslationService.cacheHits.get(), TranslationService.errors.get());
    }

    // ------------------------------------------------------------------ actions

    private void runTest(SettingsList.StatusRow status) {
        status.set("Testing…", 0xFF8B939C);
        Minecraft mc = minecraft;
        TranslationService.translate("¿Puedes leer este cartel?").thenAccept(r -> mc.execute(() -> {
            if (r.error()) {
                status.set(r.errorMessage(), 0xFFE0827F);
            } else {
                status.set("“" + r.translatedText() + "”  from "
                        + LanguageUtil.name(r.detectedLanguage()), 0xFF7FE08A);
            }
        }));
    }

    private void runAiTest(SettingsList.StatusRow status) {
        status.set("Testing…", 0xFF8B939C);
        Minecraft mc = minecraft;
        long t0 = System.currentTimeMillis();
        TranslationService.translate("Hey, do you want to team up?", "auto",
                        TEConfig.get().targetLanguage, TEConfig.Engine.AI_LOCAL)
                .thenAccept(r -> mc.execute(() -> {
                    long ms = System.currentTimeMillis() - t0;
                    if (r.error()) {
                        status.set(r.errorMessage() + "  (" + ms + " ms)", 0xFFE0827F);
                    } else {
                        status.set("“" + r.translatedText() + "”  (" + ms + " ms)", 0xFF7FE08A);
                    }
                }));
    }

    private void resetDefaults() {
        TEConfig fresh = new TEConfig();
        TEConfig live = TEConfig.get();
        copyInto(fresh, live);
        live.clamp();
        selectedTab = tabBar == null ? 0 : indexOfCurrentTab();
        rebuildWidgets();
    }

    private int indexOfCurrentTab() {
        List<Tab> tabs = tabBar.getTabs();
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i) == tabManager.getCurrentTab()) {
                return i;
            }
        }
        return 0;
    }

    /** Copies every user-facing field. */
    private static void copyInto(TEConfig from, TEConfig to) {
        to.enabled = from.enabled;
        to.targetLanguage = from.targetLanguage;
        to.sourceLanguage = from.sourceLanguage;
        to.engine = from.engine;
        to.aiProviderSort = from.aiProviderSort;
        to.aiMaxTokens = from.aiMaxTokens;
        to.aiAllowReasoning = from.aiAllowReasoning;
        to.aiPromptChinese = from.aiPromptChinese;
        to.speakerTone = from.speakerTone;
        to.aiSystemPrompt = from.aiSystemPrompt;
        to.aiTimeoutMs = from.aiTimeoutMs;
        to.aiUseContext = from.aiUseContext;
        to.contextMode = from.contextMode;
        to.contextCount = from.contextCount;
        to.contextMinutes = from.contextMinutes;
        to.aiCheckerDefaultQuestion = from.aiCheckerDefaultQuestion;
        to.translateOwnMessages = from.translateOwnMessages;
        to.ownMessageShowRoundTrip = from.ownMessageShowRoundTrip;
        to.sources.clear();
        to.sources.putAll(from.sources);
        to.itemsRequireCustomText = from.itemsRequireCustomText;
        to.scanRange = from.scanRange;
        to.editDebounceMs = from.editDebounceMs;
        to.signOverflowMode = from.signOverflowMode;
        to.hudAnchor = from.hudAnchor;
        to.hudOffsetX = from.hudOffsetX;
        to.hudOffsetY = from.hudOffsetY;
        to.boxWidth = from.boxWidth;
        to.textScale = from.textScale;
        to.maxVisibleBoxes = from.maxVisibleBoxes;
        to.backgroundOpacity = from.backgroundOpacity;
        to.showOriginalText = from.showOriginalText;
        to.showLanguageTag = from.showLanguageTag;
        to.showSourceLabel = from.showSourceLabel;
        to.autoHideSeconds = from.autoHideSeconds;
        to.contextLingerSeconds = from.contextLingerSeconds;
        to.fadeAnimations = from.fadeAnimations;
        to.hideInDebugHud = from.hideInDebugHud;
        to.hideSameLanguage = from.hideSameLanguage;
        to.onlyTranslateNonAscii = from.onlyTranslateNonAscii;
        to.minTextLength = from.minTextLength;
        to.playSound = from.playSound;
        to.ignorePatterns = new ArrayList<>(from.ignorePatterns);
        to.chatMode = from.chatMode;
        to.chatAnchor = from.chatAnchor;
        to.chatOffsetX = from.chatOffsetX;
        to.chatOffsetY = from.chatOffsetY;
        to.chatHoldMaxMs = from.chatHoldMaxMs;
        to.immersionMode = from.immersionMode;
        to.immersionEngine = from.immersionEngine;
        to.inputTranslatorEnabled = from.inputTranslatorEnabled;
        to.inputMode = from.inputMode;
        to.inputEngine = from.inputEngine;
        to.inputTargetLanguage = from.inputTargetLanguage;
        to.inputRoundTrip = from.inputRoundTrip;
        to.inputRoundTripEngine = from.inputRoundTripEngine;
        to.referenceEngine = from.referenceEngine;
        to.inputFaithfulnessCheck = from.inputFaithfulnessCheck;
        to.inputTranslateCommands = from.inputTranslateCommands;
        to.inputDebounceMs = from.inputDebounceMs;
        to.writeTranslateButtons = from.writeTranslateButtons;
        to.ocrEnabled = from.ocrEnabled;
        to.requestTimeoutMs = from.requestTimeoutMs;
        to.minRequestIntervalMs = from.minRequestIntervalMs;
        to.maxCharsPerRequest = from.maxCharsPerRequest;
        to.cacheSize = from.cacheSize;
        to.persistentCache = from.persistentCache;
        to.debugLogging = from.debugLogging;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return (tabBar != null && tabBar.keyPressed(event)) || super.keyPressed(event);
    }

    @Override
    public void removed() {
        TEConfig.get().clamp();
        TEConfig.save();
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
