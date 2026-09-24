package dev.adamfr06.translateeverything.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.adamfr06.translateeverything.TranslateEverythingClient;
import dev.adamfr06.translateeverything.capture.SourceType;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** All user-tunable settings. */
public class TEConfig {
    public enum HudAnchor {
        TOP_LEFT("Top Left", 0, 0),
        TOP_CENTER("Top Center", 1, 0),
        TOP_RIGHT("Top Right", 2, 0),
        MIDDLE_LEFT("Middle Left", 0, 1),
        MIDDLE_RIGHT("Middle Right", 2, 1),
        BOTTOM_LEFT("Bottom Left", 0, 2),
        BOTTOM_CENTER("Bottom Center", 1, 2),
        BOTTOM_RIGHT("Bottom Right", 2, 2);

        public final String label;
        /** 0 = left/top, 1 = center/middle, 2 = right/bottom */
        public final int hx, vy;

        HudAnchor(String label, int hx, int vy) {
            this.label = label;
            this.hx = hx;
            this.vy = vy;
        }
    }

    public enum ChatMode {
        REPLACE("In place"),
        BELOW("Line below"),
        TILE("Card");

        public final String label;

        ChatMode(String label) {
            this.label = label;
        }
    }

    public enum InputMode {
        SELECTION("Hotkey only"),
        PREVIEW("Preview first"),
        RAW("On send");

        public final String label;

        InputMode(String label) {
            this.label = label;
        }
    }

    public enum SignOverflowMode {
        WRAP("Overflow"),
        SHRINK("Shrink to fit"),
        CLAMP("Truncate");

        public final String label;

        SignOverflowMode(String label) {
            this.label = label;
        }
    }

    public enum Engine {
        GOOGLE_FREE("Google (free)"),
        GOOGLE_CLOUD("Google Cloud"),
        LIBRE_TRANSLATE("LibreTranslate"),
        AZURE("Azure"),
        AI_LOCAL("AI");

        public final String label;

        Engine(String label) {
            this.label = label;
        }
    }

    // ------------------------------------------------------------------ general
    public boolean enabled = true;
    /** ISO code the text is translated into, e.g. "en", "es", "de", "pt-BR". */
    public String targetLanguage = "en";
    /** ISO code of the source text, or "auto" for detection (recommended). */
    public String sourceLanguage = "auto";
    public Engine engine = Engine.GOOGLE_FREE;
    /** Only used by the GOOGLE_CLOUD engine. */
    public String googleApiKey = "";
    /** Only used by the LIBRE_TRANSLATE engine. */
    public String libreTranslateUrl = "https://libretranslate.com/translate";
    public String libreTranslateApiKey = "";
    /** Azure Translator. */
    public String azureKey = "";
    public String azureRegion = "global";
    public String azureEndpoint = "https://api.cognitive.microsofttranslator.com";

    /**
     * OpenRouter picks a provider by price by default, which routes to the cheapest host rather
     * than the quickest one.
     */
    public String aiProviderSort = "throughput";
    /** CEILING on generated tokens, not the value used. */
    public int aiMaxTokens = 2048;
    /** Let a reasoning model think before it answers. */
    public boolean aiAllowReasoning = false;
    /** Experimental: send the AI's instructions in Chinese. */
    public boolean aiPromptChinese = false;
    /** Cached custom instruction translation; invalidated by exact prompt, model and endpoint identity. */
    public String aiChinesePromptSource = "";
    public String aiChinesePromptProfile = "";
    public String aiChinesePromptText = "";

    // ------------------------------------------------------------------ AI engine
    /** OpenAI-compatible chat-completions endpoint (Ollama, LM Studio, llama.cpp…). */
    public String aiEndpointUrl = "http://localhost:11434/v1/chat/completions";
    /** Model name the endpoint should use. */
    public String aiModel = "qwen2.5:3b";
    /** Optional bearer token (LM Studio / remote setups; Ollama ignores it). */
    public String aiApiKey = "";
    /** Speaker style/tone injected into the AI prompt. */
    public String speakerTone = "";
    /** Custom AI system prompt. */
    public String aiSystemPrompt = "";
    /** AI request timeout (ms). */
    public int aiTimeoutMs = 30000;

    // ------------------------------------------------------------------ AI conversation context
    public enum ContextMode {
        COUNT("By count"),
        TIME("By time");

        public final String label;

        ContextMode(String label) {
            this.label = label;
        }
    }

    /** Feed recent chat to the AI so it translates with conversational context. */
    public boolean aiUseContext = true;
    public ContextMode contextMode = ContextMode.COUNT;
    /** COUNT mode: how many recent included messages to feed. */
    public int contextCount = 6;
    /** TIME mode: how many minutes back to include. */
    public int contextMinutes = 5;
    /** Sender names treated as system/automated (excluded from context), matched at line start. */
    public List<String> contextSystemSenders = new ArrayList<>(List.of(
            "server", "info", "system", "broadcast", "console", "announcement", "alert", "notice"));
    /** Prefix on an outgoing message that translates it without conversation context. */
    public String contextDisregardPrefix = "'";
    /** Also translate the player's own sent messages when they echo back in chat. */
    public boolean translateOwnMessages = true;
    /** Show the player's own messages as their read-back instead of the typed text. */
    public boolean ownMessageShowRoundTrip = false;
    /** Question pre-filled (not sent) in the review screen's ask box. */
    public String aiCheckerDefaultQuestion = "";

    // ------------------------------------------------------------------ sources
    public Map<SourceType, Boolean> sources = defaultSources();
    /**
     * When true, inventory items are only translated if they carry custom text (renamed, custom
     * lore, written book title).
     */
    public boolean itemsRequireCustomText = true;
    /** How far away signs / entities are picked up (blocks). */
    public double scanRange = 8.0;
    /** Typing pause before sign or book text being edited is translated (ms). */
    public int editDebounceMs = 600;

    // ------------------------------------------------------------------ display
    public HudAnchor hudAnchor = HudAnchor.TOP_RIGHT;
    public int hudOffsetX = 8;
    public int hudOffsetY = 8;
    public int boxWidth = 220;
    public double textScale = 1.0;
    public int maxVisibleBoxes = 5;
    public double backgroundOpacity = 0.62;
    public boolean showOriginalText = true;
    public boolean showLanguageTag = true;
    public boolean showSourceLabel = true;
    /** Seconds an event box (title, action bar, boss bar…) stays. 0 = until dismissed. */
    public int autoHideSeconds = 12;
    /** Seconds a contextual box lingers after its source is no longer in view. */
    public int contextLingerSeconds = 3;
    public boolean fadeAnimations = true;
    public boolean hideInDebugHud = true;

    // ------------------------------------------------------------------ behavior
    /** Don't show a box when the detected language already equals the target. */
    public boolean hideSameLanguage = true;
    /**
     * Skip pure-ASCII text before it is ever sent (saves API calls; only makes sense for non-Latin
     * source languages).
     */
    public boolean onlyTranslateNonAscii = false;
    public int minTextLength = 2;
    public boolean playSound = false;
    /** Regexes; any match on the original text skips translation entirely. */
    public List<String> ignorePatterns = new ArrayList<>();

    // ------------------------------------------------------------------ chat
    /** How chat translations are presented (the CHAT source toggle turns chat translation on/off). */
    public ChatMode chatMode = ChatMode.BELOW;
    /** Chat cards (TILE mode) can live in their own corner; same anchor+offsets as the main stack merges them. */
    public HudAnchor chatAnchor = HudAnchor.TOP_LEFT;
    public int chatOffsetX = 8;
    public int chatOffsetY = 8;
    /** Max time chat (and immersion swaps) wait for a translation before showing the original (ms). */
    public int chatHoldMaxMs = 4000;

    // ------------------------------------------------------------------ immersion
    /**
     * Full immersion: translations replace the original text in place, chat lines, sign text in
     * the world, item tooltips, book pages, titles, boss bars, menu titles, name tags, instead of
     * showing cards.
     */
    public boolean immersionMode = false;
    /** Engine used for full-immersion in-world text. */
    public Engine immersionEngine = Engine.GOOGLE_FREE;
    /**
     * Immersive signs: translations are usually longer than the original, when true, lines may
     * overhang the sign board (floating text) so nothing is cut off; when false, text is clamped to
     * the board with an ellipsis.
     */
    public SignOverflowMode signOverflowMode = SignOverflowMode.SHRINK;

    // ------------------------------------------------------------------ input translator
    /** Master switch for translating outgoing messages. */
    public boolean inputTranslatorEnabled = false;
    /**
     * SELECTION: press the input-translate key to translate the selection (or whole field) in any
     * text box, in place.
     */
    public InputMode inputMode = InputMode.PREVIEW;
    /** Engine for outgoing messages. */
    public Engine inputEngine = Engine.GOOGLE_FREE;
    /** Language outgoing messages are translated into. */
    public String inputTargetLanguage = "auto";
    /** Show the round-trip (translated-back) line in the preview bar. */
    public boolean inputRoundTrip = true;
    /** Engine for the round-trip line. */
    public Engine inputRoundTripEngine = Engine.GOOGLE_FREE;
    /** Non-AI engine used for read-backs, comparison candidates and status notes. */
    public Engine referenceEngine = Engine.GOOGLE_FREE;
    /** Beta: have the AI grade how faithfully the translation conveys the original message. */
    public boolean inputFaithfulnessCheck = false;
    /** Also translate messages that start with "/" (commands). */
    public boolean inputTranslateCommands = false;
    /** Typing pause before the preview bar refreshes (ms). */
    public int inputDebounceMs = 350;
    /** Show the "Translate" button on sign / book editing screens. */
    public boolean writeTranslateButtons = true;

    // ------------------------------------------------------------------ OCR beta
    /** Master switch for the experimental screen-OCR feature. */
    public boolean ocrEnabled = false;
    /** OCR.space API key. */
    public String ocrApiKey = "helloworld";

    // ------------------------------------------------------------------ advanced
    public int requestTimeoutMs = 8000;
    /** Minimum spacing between web requests (ms) so servers full of signs don't hammer the API. */
    public int minRequestIntervalMs = 200;
    /** Longest text ever sent in one request; anything longer is truncated. */
    public int maxCharsPerRequest = 1500;
    public int cacheSize = 3000;
    /** Keep the translation cache across game restarts. */
    public boolean persistentCache = true;
    public boolean debugLogging = false;

    // ------------------------------------------------------------------ plumbing

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().enableComplexMapKeySerialization().create();
    private static TEConfig instance;

    public static TEConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("translateeverything.json");
    }

    public static void load() {
        TEConfig cfg = null;
        Path p = path();
        if (Files.exists(p)) {
            try {
                cfg = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), TEConfig.class);
            } catch (Exception e) {
                TranslateEverythingClient.LOGGER.error("Could not read config, falling back to defaults", e);
            }
        }
        if (cfg == null) {
            cfg = new TEConfig();
        }
        cfg.clamp();
        instance = cfg;
        save();
    }

    public static void save() {
        if (instance == null) {
            return;
        }
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TranslateEverythingClient.LOGGER.error("Could not save config", e);
        }
    }

    public boolean isSourceEnabled(SourceType type) {
        return sources.getOrDefault(type, Boolean.TRUE);
    }

    public void setSourceEnabled(SourceType type, boolean value) {
        sources.put(type, value);
    }

    private static Map<SourceType, Boolean> defaultSources() {
        Map<SourceType, Boolean> map = new EnumMap<>(SourceType.class);
        for (SourceType t : SourceType.values()) {
            // Own-writing translation and the (potentially spammy) scoreboard are opt-in.
            map.put(t, t != SourceType.SCOREBOARD);
        }
        return map;
    }

    /** Repairs nulls (from hand-edited files) and clamps numeric ranges. */
    public void clamp() {
        if (targetLanguage == null || targetLanguage.isBlank()) targetLanguage = "en";
        if (sourceLanguage == null || sourceLanguage.isBlank()) sourceLanguage = "auto";
        if (engine == null) engine = Engine.GOOGLE_FREE;
        if (immersionEngine == null) immersionEngine = Engine.GOOGLE_FREE;
        if (inputEngine == null) inputEngine = Engine.GOOGLE_FREE;
        if (inputRoundTripEngine == null) inputRoundTripEngine = Engine.GOOGLE_FREE;
        if (referenceEngine == null) referenceEngine = Engine.GOOGLE_FREE;
        if (googleApiKey == null) googleApiKey = "";
        if (libreTranslateUrl == null || libreTranslateUrl.isBlank()) libreTranslateUrl = "https://libretranslate.com/translate";
        if (libreTranslateApiKey == null) libreTranslateApiKey = "";
        if (azureKey == null) azureKey = "";
        if (aiProviderSort == null) aiProviderSort = "";
        aiMaxTokens = (int) clamp(aiMaxTokens, 256, 8192);
        if (azureRegion == null || azureRegion.isBlank()) azureRegion = "global";
        if (azureEndpoint == null || azureEndpoint.isBlank()) azureEndpoint = "https://api.cognitive.microsofttranslator.com";
        if (aiEndpointUrl == null || aiEndpointUrl.isBlank()) aiEndpointUrl = "http://localhost:11434/v1/chat/completions";
        if (aiModel == null || aiModel.isBlank()) aiModel = "qwen2.5:3b";
        if (aiApiKey == null) aiApiKey = "";
        if (speakerTone == null) speakerTone = "";
        if (aiSystemPrompt == null) aiSystemPrompt = "";
        if (contextMode == null) contextMode = ContextMode.COUNT;
        if (contextSystemSenders == null) contextSystemSenders = new ArrayList<>();
        if (contextDisregardPrefix == null) contextDisregardPrefix = "'";
        if (aiCheckerDefaultQuestion == null) aiCheckerDefaultQuestion = "";
        contextCount = (int) clamp(contextCount, 0, 30); // hard ceiling: more context reliably degrades small models
        contextMinutes = (int) clamp(contextMinutes, 1, 120);
        if (ocrApiKey == null || ocrApiKey.isBlank()) ocrApiKey = "helloworld";
        if (hudAnchor == null) hudAnchor = HudAnchor.TOP_RIGHT;
        if (chatMode == null) chatMode = ChatMode.BELOW;
        if (signOverflowMode == null) signOverflowMode = SignOverflowMode.SHRINK;
        if (inputMode == null) inputMode = InputMode.PREVIEW;
        if (inputTargetLanguage == null || inputTargetLanguage.isBlank()) inputTargetLanguage = "auto";
        if (chatAnchor == null) chatAnchor = HudAnchor.TOP_LEFT;
        if (ignorePatterns == null) ignorePatterns = new ArrayList<>();

        Map<SourceType, Boolean> merged = defaultSources();
        if (sources != null) {
            merged.putAll(sources);
        }
        sources = merged;

        scanRange = clamp(scanRange, 2.0, 32.0);
        editDebounceMs = (int) clamp(editDebounceMs, 100, 3000);
        boxWidth = (int) clamp(boxWidth, 120, 400);
        textScale = clamp(textScale, 0.5, 2.0);
        maxVisibleBoxes = (int) clamp(maxVisibleBoxes, 1, 10);
        backgroundOpacity = clamp(backgroundOpacity, 0.0, 1.0);
        autoHideSeconds = (int) clamp(autoHideSeconds, 0, 120);
        contextLingerSeconds = (int) clamp(contextLingerSeconds, 0, 60);
        minTextLength = (int) clamp(minTextLength, 1, 20);
        requestTimeoutMs = (int) clamp(requestTimeoutMs, 1000, 30000);
        minRequestIntervalMs = (int) clamp(minRequestIntervalMs, 0, 5000);
        maxCharsPerRequest = (int) clamp(maxCharsPerRequest, 100, 5000);
        cacheSize = (int) clamp(cacheSize, 100, 50000);
        aiTimeoutMs = (int) clamp(aiTimeoutMs, 8000, 60000);
        inputDebounceMs = (int) clamp(inputDebounceMs, 100, 3000);
        hudOffsetX = (int) clamp(hudOffsetX, -2000, 2000);
        hudOffsetY = (int) clamp(hudOffsetY, -2000, 2000);
        chatOffsetX = (int) clamp(chatOffsetX, -2000, 2000);
        chatOffsetY = (int) clamp(chatOffsetY, -2000, 2000);
        chatHoldMaxMs = (int) clamp(chatHoldMaxMs, 500, 15000);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
