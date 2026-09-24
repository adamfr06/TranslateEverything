package dev.adamfr06.translateeverything.translate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.adamfr06.translateeverything.TranslateEverythingClient;
import dev.adamfr06.translateeverything.config.TEConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Asynchronous translation pipeline: caching, request de-duplication, rate limiting and engine dispatch. */
public final class TranslationService {
    /** Outcome of one translation. */
    public record Result(String translatedText, String detectedLanguage, boolean error, String errorMessage) {
        public static Result ok(String text, String lang) {
            return new Result(text, lang == null ? "" : lang.toLowerCase(Locale.ROOT), false, "");
        }

        public static Result fail(String message) {
            return new Result("", "", true, message);
        }
    }

    private record CacheEntry(String text, String lang) {
    }

    private static final Gson GSON = new Gson();
    private static final int MAX_PERSISTED = 5000;

    /** Part of every cache key; bump it when translation behaviour changes so stale results are not reused. */
    private static final int BEHAVIOUR_VERSION = 7;

    /** Slow lane: local-AI inference. */
    private static ExecutorService executor;
    /** Fast lane: plain HTTP translation endpoints, which answer in well under a second. */
    private static ExecutorService netExecutor;
    private static java.util.concurrent.ScheduledExecutorService heartbeat;
    private static HttpClient httpClient;

    private static final Object CACHE_LOCK = new Object();
    private static final Object PERSIST_LOCK = new Object();
    private static long cacheEpoch;
    private static LinkedHashMap<String, CacheEntry> cache;
    private static final Map<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

    private static final Object RATE_LOCK = new Object();
    private static long lastRequestAt = 0;

    /** Adaptive throttle for the free Google endpoint, which rate-limits per IP and answers 429 when pushed. */
    private static final Object GOOGLE_LOCK = new Object();
    private static long googleNextSlot = 0;
    private static long googlePenaltyMs = 0;
    private static final long GOOGLE_PENALTY_MAX_MS = 4000;
    private static long googleLastOk = 0;

    /** Called on every 429 from the free endpoint: widen the spacing. */
    private static void googlePenalise() {
        synchronized (GOOGLE_LOCK) {
            googlePenaltyMs = googlePenaltyMs == 0 ? 500 : Math.min(GOOGLE_PENALTY_MAX_MS, googlePenaltyMs * 2);
            TranslateEverythingClient.LOGGER.warn(
                    "Google free endpoint rate-limited us; spacing requests by {}ms", googlePenaltyMs);
        }
    }

    /** Called on every success: let the penalty decay so normal speed returns. */
    private static void googleRecovered() {
        synchronized (GOOGLE_LOCK) {
            googleLastOk = System.currentTimeMillis();
            if (googlePenaltyMs > 0) {
                googlePenaltyMs = googlePenaltyMs <= 500 ? 0 : googlePenaltyMs / 2;
            }
        }
    }

    /** Claims this request's slot on the shared Google budget and returns how long to wait for it. */
    private static long googleClaimSlot(TEConfig cfg) {
        synchronized (GOOGLE_LOCK) {
            long now = System.currentTimeMillis();
            long spacing = Math.max(cfg.minRequestIntervalMs, 120) + googlePenaltyMs;
            long slot = Math.max(now, googleNextSlot);
            googleNextSlot = slot + spacing;
            return slot - now;
        }
    }

    private static volatile int dirtyEntries = 0;
    private static final AtomicLong lastPersistAt = new AtomicLong(System.currentTimeMillis());

    // Session statistics, surfaced in the config screen.
    public static final AtomicInteger apiCalls = new AtomicInteger();
    public static final AtomicInteger cacheHits = new AtomicInteger();
    public static final AtomicInteger errors = new AtomicInteger();
    public static final AtomicLong charsSent = new AtomicLong();

    private TranslationService() {
    }

    public static void init() {
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "TranslateEverything-Worker");
            t.setDaemon(true);
            return t;
        });
        netExecutor = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "TranslateEverything-Net");
            t.setDaemon(true);
            return t;
        });
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        synchronized (CACHE_LOCK) {
            cache = new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > TEConfig.get().cacheSize;
                }
            };
        }
        if (TEConfig.get().persistentCache) {
            loadPersistedCache();
        }
        warmUpAi();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TranslateEverything-Warm");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleWithFixedDelay(TranslationService::warmUpAi, 4, 4, TimeUnit.MINUTES);
    }

    /**
     * If the AI engine is selected, fire one throwaway request at startup so the model loads into
     * memory in the background.
     */
    private static void warmUpAi() {
        TEConfig cfg = TEConfig.get();
        if (cfg.engine != TEConfig.Engine.AI_LOCAL) {
            return;
        }
        if (!isLocalEndpoint(cfg.aiEndpointUrl)) {
            return;
        }
        executor.execute(() -> {
            try {
                fetch(TEConfig.get(), TEConfig.Engine.AI_LOCAL, "hello", "auto", "en", java.util.List.of());
                TranslateEverythingClient.LOGGER.info("AI model warm-up sent to {}", TEConfig.get().aiEndpointUrl);
            } catch (Exception ignored) {
                // Warm-up is best-effort; the load keeps going server-side regardless.
            }
        });
    }

    public static void shutdown() {
        persistCache();
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        if (netExecutor != null) {
            netExecutor.shutdownNow();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Translates {@code text} into the configured target language. */
    public static CompletableFuture<Result> translate(String text) {
        TEConfig cfg = TEConfig.get();
        return translate(text, cfg.sourceLanguage, cfg.targetLanguage);
    }

    /**
     * Translates {@code text} with explicit source/target language codes (used by the input
     * translator for outgoing text and its round-trip).
     */
    public static CompletableFuture<Result> translate(String text, String source, String target) {
        return translate(text, source, target, null);
    }

    /** As above, but forces a specific engine (e.g. the retry-with-AI key) regardless of the configured default. */
    public static CompletableFuture<Result> translate(String text, String source, String target, TEConfig.Engine engineOverride) {
        return translate(text, source, target, engineOverride, null);
    }

    /** Translate a chat line with conversational context (AI engine uses it; other engines ignore it). */
    public static CompletableFuture<Result> translateChat(String text, java.util.List<String> context) {
        TEConfig cfg = TEConfig.get();
        return translate(text, cfg.sourceLanguage, cfg.targetLanguage, null, context);
    }

    public static CompletableFuture<Result> translate(String text, String source, String target,
                                                      TEConfig.Engine engineOverride, java.util.List<String> context) {
        return translate(text, source, target, engineOverride, context, null);
    }

    /** As above, tagged so the work can be superseded. */
    public static CompletableFuture<Result> translate(String text, String source, String target,
                                                      TEConfig.Engine engineOverride, java.util.List<String> context,
                                                      String tag) {
        final int submittedAt = generationOf(tag);
        TEConfig cfg = TEConfig.get();
        TEConfig.Engine engine = engineOverride != null ? engineOverride : cfg.engine;
        java.util.List<String> ctx = (engine == TEConfig.Engine.AI_LOCAL && context != null) ? context : java.util.List.of();
        if (text == null || text.isBlank()) return CompletableFuture.completedFuture(Result.ok("", ""));
        if (text.length() > cfg.maxCharsPerRequest) return CompletableFuture.completedFuture(Result.fail("Text exceeds the request limit (" + cfg.maxCharsPerRequest + " characters)"));
        String trimmed = text;
        String key = cacheKey(cfg, engine, trimmed, source, target, ctx);

        final long submittedEpoch;
        synchronized (CACHE_LOCK) {
            submittedEpoch = cacheEpoch;
            CacheEntry hit = cache.get(key);
            if (hit != null) {
                cacheHits.incrementAndGet();
                return CompletableFuture.completedFuture(Result.ok(hit.text(), hit.lang()));
            }
        }

        CompletableFuture<Result> shared = new CompletableFuture<>();
        CompletableFuture<Result> existing = inFlight.putIfAbsent(key, shared);
        if (existing != null) return existing.copy();
        CompletableFuture.supplyAsync(() -> {
            if (generationOf(tag) != submittedAt) return Result.fail("superseded");
            return fetch(cfg, engine, trimmed, source, target, ctx);
        }, engine == TEConfig.Engine.AI_LOCAL && isLocalEndpoint(cfg.aiEndpointUrl) ? executor : netExecutor)
                .whenComplete((result, throwable) -> {
                    if (throwable == null && result != null && !result.error()) {
                        synchronized (CACHE_LOCK) {
                            if (submittedEpoch == cacheEpoch) {
                                cache.put(key, new CacheEntry(result.translatedText(), result.detectedLanguage()));
                                dirtyEntries++;
                            }
                        }
                    }
                    inFlight.remove(key, shared);
                    shared.complete(throwable == null && result != null ? result : Result.fail("Translation request failed"));
                });
        return shared.copy();
    }

    /** Generation counter per request "tag". */
    private static final Map<String, Integer> tagGeneration = new ConcurrentHashMap<>();

    /** Invalidates everything already submitted under {@code tag}. */
    public static void supersede(String tag) {
        if (tag != null && !tag.isBlank()) {
            tagGeneration.merge(tag, 1, Integer::sum);
        }
    }

    private static int generationOf(String tag) {
        return tag == null || tag.isBlank() ? 0 : tagGeneration.getOrDefault(tag, 0);
    }

    /** Persists the cache if enough new entries piled up or enough time passed. */
    public static void maybePersist() {
        if (!TEConfig.get().persistentCache || dirtyEntries == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (dirtyEntries >= 50 || now - lastPersistAt.get() > 5 * 60_000) {
            lastPersistAt.set(now);
            int snapshot = dirtyEntries;
            executor.execute(() -> {
                persistCache();
                dirtyEntries = Math.max(0, dirtyEntries - snapshot);
            });
        }
    }

    // ------------------------------------------------------------------ engines

    /** Strips context markers, applies rate limiting and dispatches to the selected engine. */
    private static Result fetch(TEConfig cfg, TEConfig.Engine engine, String rawText, String source, String target,
                               java.util.List<String> context) {
        Parsed parsed = parseContext(rawText);
        java.util.List<String> notes = parsed.notes();
        String text = parsed.text();
        if (text.isBlank()) {
            text = notes.isEmpty() ? rawText : String.join(" ", notes);
            notes = java.util.List.of();
        }
        if (engine != TEConfig.Engine.AI_LOCAL) {
            long sleepFor;
            if (engine == TEConfig.Engine.GOOGLE_FREE) {
                sleepFor = googleClaimSlot(cfg); // its own budget, widened whenever it 429s
            } else {
                synchronized (RATE_LOCK) {
                    long now = System.currentTimeMillis();
                    long slot = Math.max(now, lastRequestAt + cfg.minRequestIntervalMs);
                    sleepFor = slot - now;
                    lastRequestAt = slot;
                }
            }
            if (sleepFor > 0) {
                try {
                    Thread.sleep(sleepFor);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.fail("interrupted");
                }
            }
        }
        long startedAt = System.currentTimeMillis();
        try {
            apiCalls.incrementAndGet();
            charsSent.addAndGet(text.length());
            Result result = switch (engine) {
                case GOOGLE_FREE -> googleFree(cfg, text, source, target);
                case GOOGLE_CLOUD -> googleCloud(cfg, text, source, target);
                case LIBRE_TRANSLATE -> libreTranslate(cfg, text, source, target);
                case AZURE -> azureTranslate(cfg, text, source, target);
                case AI_LOCAL -> aiLocal(cfg, text, notes, source, target, context);
            };
            if (result.error()) {
                errors.incrementAndGet();
            }
            long tookMs = System.currentTimeMillis() - startedAt;
            if (tookMs > 1500 || cfg.debugLogging) {
                TranslateEverythingClient.LOGGER.info("[timing] {} {}->{} {} chars in, {} chars out: {} ms",
                        engine, source, target, text.length(),
                        result.error() ? 0 : result.translatedText().length(), tookMs);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            errors.incrementAndGet();
            TranslateEverythingClient.LOGGER.warn("Translation failed [engine={}, {} chars, target={}]: {}",
                    engine, text.length(), target, e.toString());
            if (cfg.debugLogging) {
                TranslateEverythingClient.LOGGER.warn("Translation failure stack trace", e);
            }
            return Result.fail(shortError(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("interrupted");
        }
    }

    /** Undocumented but very reliable endpoint used by the Google Translate web widget. */
    private static Result googleFree(TEConfig cfg, String text, String source, String target) throws IOException, InterruptedException {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&dj=1"
                + "&sl=" + URLEncoder.encode(source, StandardCharsets.UTF_8)
                + "&tl=" + URLEncoder.encode(target, StandardCharsets.UTF_8)
                + "&q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 200) {
                googleRecovered();
                break;
            }
            if (code != 429 && code < 500) {
                return Result.fail("HTTP " + code);
            }
            if (attempt == 2) {
                break;
            }
            long wait;
            if (code == 429) {
                googlePenalise();
                wait = response.headers().firstValue("Retry-After")
                        .map(v -> {
                            try {
                                return Long.parseLong(v.trim()) * 1000L;
                            } catch (NumberFormatException ignored) {
                                return 0L;
                            }
                        })
                        .filter(v -> v > 0)
                        .orElse(1000L << attempt);
                wait = Math.min(wait, 8000L);
            } else {
                wait = 300L * (attempt + 1);
            }
            Thread.sleep(wait + (long) (Math.random() * 200)); // jitter so parallel callers don't sync up
        }
        if (response == null || response.statusCode() != 200) {
            int code = response == null ? -1 : response.statusCode();
            return Result.fail(code == 429 ? "rate limited, easing off" : "HTTP " + code);
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        StringBuilder out = new StringBuilder();
        if (root.has("sentences")) {
            for (JsonElement el : root.getAsJsonArray("sentences")) {
                JsonObject sentence = el.getAsJsonObject();
                if (sentence.has("trans")) {
                    out.append(sentence.get("trans").getAsString());
                }
            }
        }
        String detected = root.has("src") ? root.get("src").getAsString() : "";
        return Result.ok(out.toString(), detected);
    }

    /** Azure Translator (Cognitive Services). */
    private static Result azureTranslate(TEConfig cfg, String text, String source, String target)
            throws IOException, InterruptedException {
        if (cfg.azureKey.isBlank()) {
            return Result.fail("set your Azure key in Settings");
        }
        StringBuilder url = new StringBuilder(cfg.azureEndpoint.replaceAll("/+$", ""))
                .append("/translate?api-version=3.0&to=")
                .append(URLEncoder.encode(target, StandardCharsets.UTF_8));
        if (!"auto".equalsIgnoreCase(source)) {
            url.append("&from=").append(URLEncoder.encode(source, StandardCharsets.UTF_8));
        }
        JsonArray body = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("Text", text);
        body.add(item);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Ocp-Apim-Subscription-Key", cfg.azureKey);
        if (!cfg.azureRegion.isBlank()) {
            // Required for anything but a global resource; harmless when it is global.
            b.header("Ocp-Apim-Subscription-Region", cfg.azureRegion);
        }
        HttpResponse<String> r = httpClient.send(
                b.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            return Result.fail("Azure rejected the key or region");
        }
        if (r.statusCode() != 200) {
            return Result.fail("HTTP " + r.statusCode());
        }
        JsonArray root = JsonParser.parseString(r.body()).getAsJsonArray();
        if (root.isEmpty()) {
            return Result.fail("Azure: empty response");
        }
        JsonObject first = root.get(0).getAsJsonObject();
        JsonArray translations = first.getAsJsonArray("translations");
        if (translations == null || translations.isEmpty()) {
            return Result.fail("Azure: no translation");
        }
        String out = translations.get(0).getAsJsonObject().get("text").getAsString();
        String detected = first.has("detectedLanguage")
                ? first.getAsJsonObject("detectedLanguage").get("language").getAsString() : "";
        return Result.ok(out, detected);
    }

    /** Official Google Cloud Translation v2 REST API. */
    private static Result googleCloud(TEConfig cfg, String text, String source, String target) throws IOException, InterruptedException {
        if (cfg.googleApiKey.isBlank()) {
            return Result.fail("No API key set (Config > General)");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("q", text);
        payload.addProperty("target", target);
        payload.addProperty("format", "text");
        if (!"auto".equalsIgnoreCase(source)) {
            payload.addProperty("source", source);
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://translation.googleapis.com/language/translate/v2?key="
                                + URLEncoder.encode(cfg.googleApiKey, StandardCharsets.UTF_8)))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return Result.fail("HTTP " + response.statusCode() + (response.statusCode() == 403 ? " (bad API key?)" : ""));
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray translations = root.getAsJsonObject("data").getAsJsonArray("translations");
        JsonObject first = translations.get(0).getAsJsonObject();
        String detected = first.has("detectedSourceLanguage") ? first.get("detectedSourceLanguage").getAsString() : source;
        return Result.ok(first.get("translatedText").getAsString(), detected);
    }

    /** Self-hosted or public LibreTranslate instance. */
    /** True when LibreTranslate is actually usable. */
    private static boolean libreUsable(TEConfig cfg) {
        String url = cfg.libreTranslateUrl;
        if (url == null || url.isBlank()) {
            return false;
        }
        return !url.contains("libretranslate.com") || !cfg.libreTranslateApiKey.isBlank();
    }

    private static Result libreTranslate(TEConfig cfg, String text, String source, String target) throws IOException, InterruptedException {
        if (!libreUsable(cfg)) {
            return Result.fail("set a LibreTranslate URL or API key");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("q", text);
        payload.addProperty("source", "auto".equalsIgnoreCase(source) ? "auto" : source);
        payload.addProperty("target", target);
        payload.addProperty("format", "text");
        if (!cfg.libreTranslateApiKey.isBlank()) {
            payload.addProperty("api_key", cfg.libreTranslateApiKey);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.libreTranslateUrl))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return Result.fail("HTTP " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String detected = "";
        if (root.has("detectedLanguage") && root.get("detectedLanguage").isJsonObject()) {
            detected = root.getAsJsonObject("detectedLanguage").get("language").getAsString();
        }
        return Result.ok(root.get("translatedText").getAsString(), detected);
    }

    // ------------------------------------------------------------------ cache persistence

    private static Path cachePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("translateeverything.cache.json");
    }

    private static void loadPersistedCache() {
        Path p = cachePath();
        if (!Files.exists(p)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version != BEHAVIOUR_VERSION) {
                TranslateEverythingClient.LOGGER.info(
                        "Discarding cached translations from an older version of the translator");
                Files.deleteIfExists(p);
                return;
            }
            JsonObject entries = root.getAsJsonObject("entries");
            synchronized (CACHE_LOCK) {
                for (String key : entries.keySet()) {
                    JsonObject e = entries.getAsJsonObject(key);
                    cache.put(key, new CacheEntry(e.get("t").getAsString(), e.get("l").getAsString()));
                }
            }
            TranslateEverythingClient.LOGGER.info("Loaded {} cached translations", entries.size());
        } catch (Exception e) {
            TranslateEverythingClient.LOGGER.warn("Could not load translation cache, starting fresh", e);
        }
    }

    private static void persistCache() {
        synchronized (PERSIST_LOCK) {
        if (!TEConfig.get().persistentCache || cacheSize() == 0) {
            try { Files.deleteIfExists(cachePath()); } catch (IOException e) {
                TranslateEverythingClient.LOGGER.warn("Could not remove persisted cache", e);
            }
            return;
        }
        try {
            JsonObject entries = new JsonObject();
            synchronized (CACHE_LOCK) {
                int skip = Math.max(0, cache.size() - MAX_PERSISTED);
                int i = 0;
                for (Map.Entry<String, CacheEntry> e : cache.entrySet()) {
                    if (i++ < skip) {
                        continue; // drop least-recently-used overflow
                    }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("t", e.getValue().text());
                    obj.addProperty("l", e.getValue().lang());
                    entries.add(e.getKey(), obj);
                }
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", BEHAVIOUR_VERSION);
            root.add("entries", entries);
            Files.createDirectories(cachePath().getParent());
            Files.writeString(cachePath(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TranslateEverythingClient.LOGGER.warn("Could not persist translation cache", e);
        }
    }
    }

    /**
     * Replaces whatever is cached for {@code text} with a translation the player has actually
     * approved on the review screen.
     */
    public static void rememberCorrection(String text, String source, String target,
                                          TEConfig.Engine engine, java.util.List<String> context,
                                          String corrected) {
        if (text == null || text.isBlank() || corrected == null || corrected.isBlank()) {
            return;
        }
        TEConfig cfg = TEConfig.get();
        String suffix = "|" + text;
        String key = cacheKey(cfg, engine, text, source, target,
                context == null ? java.util.List.of() : context);
        synchronized (CACHE_LOCK) {
            cache.keySet().removeIf(k -> k.endsWith(suffix));
            cache.put(key, new CacheEntry(corrected, ""));
            dirtyEntries++;
        }
        TranslateEverythingClient.LOGGER.info("Cached the reviewed translation for \"{}\"", text);
    }

    public static void clearCache() { evictCache(Integer.MAX_VALUE, true); }

    public static int cacheSize() {
        synchronized (CACHE_LOCK) { return cache.size(); }
    }

    public static void clearRecent(int n) { evictCache(n, true); }

    /** Removes exactly the requested available entries, ordered by last use. */
    public static int evictCache(int n, boolean recent) {
        int removed = 0;
        synchronized (CACHE_LOCK) {
            cacheEpoch++;
            java.util.List<String> keys = new java.util.ArrayList<>(cache.keySet());
            if (recent) java.util.Collections.reverse(keys);
            for (String key : keys) {
                if (removed >= Math.max(0, n)) break;
                cache.remove(key);
                removed++;
            }
            dirtyEntries++;
        }
        // Serialize disk writes so an older snapshot cannot resurrect cleared entries.
        if (netExecutor != null) netExecutor.execute(TranslationService::persistCache);
        return removed;
    }

    public static void invalidateText(String text) {
        synchronized (CACHE_LOCK) {
            cacheEpoch++;
            cache.keySet().removeIf(key -> key.endsWith("|" + text));
            dirtyEntries++;
        }
    }

    public static String cacheProfile() {
        TEConfig cfg = TEConfig.get();
        synchronized (CACHE_LOCK) {
            return cacheEpoch + ":" + cacheKey(cfg, cfg.immersionEngine, "", cfg.sourceLanguage, cfg.targetLanguage, java.util.List.of());
        }
    }

    /** Identity of the effective AI system prompt, so a changed prompt never reuses old output. */
    private static int promptFingerprint(TEConfig cfg) {
        return (styleTemplate(cfg) + coreRules(cfg)).hashCode();
    }

    private static String cacheKey(TEConfig cfg, TEConfig.Engine engine, String text, String source, String target,
                                   java.util.List<String> context) {
        String profile = engine == TEConfig.Engine.AI_LOCAL
                ? "AI:" + cfg.aiEndpointUrl + ":" + cfg.aiModel + ":" + cfg.speakerTone.hashCode()
                + ":p" + promptFingerprint(cfg)
                + (context != null && !context.isEmpty() ? ":c" + context.hashCode() : "")
                : engine.name();
        return "v" + BEHAVIOUR_VERSION + "|" + profile + "|" + source + "|" + target + "|" + text;
    }

    // ------------------------------------------------------------------ context markers

    private record Parsed(String text, java.util.List<String> notes) {
    }

    private static final java.util.regex.Pattern CONTEXT_MARKER =
            java.util.regex.Pattern.compile("(?:\\{<|<<)(.*?)(?:>\\}|>>)");

    /** Pulls {@code {<note>}} hints out of the text, returning the clean text + the notes. */
    private static Parsed parseContext(String raw) {
        java.util.regex.Matcher m = CONTEXT_MARKER.matcher(raw);
        java.util.List<String> notes = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String note = m.group(1).trim();
            if (!note.isEmpty()) {
                notes.add(note);
            }
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        return new Parsed(sb.toString().replaceAll("\\s+", " ").trim(), notes);
    }

    // ------------------------------------------------------------------ AI engine

    /** Built-in AI system prompt. */
    public static final String DEFAULT_AI_PROMPT =
            "Translate the user's Minecraft chat message into {target}. Natural and idiomatic, same meaning, "
            + "nuance and tone; make pronouns explicit.";

    /** Chinese rendering of {@link #DEFAULT_AI_PROMPT}, used when the Chinese-prompt option is on. */
    public static final String DEFAULT_AI_PROMPT_ZH =
            "把用户的 Minecraft 聊天消息翻译成{target}。"
            + "自然地道，含义、语气、语体完全一致，"
            + "代词明确。";

    /** Accuracy rules that are always applied, on top of the built-in prompt or a custom one. */
    private static final String CORE_RULES =
            "\n1 Translate every word, including slang, profanity, loanwords and brand names. Usernames unchanged."
            + "\n2 Idioms and figures of speech by meaning, never literally."
            + "\n3 Keep the message type: a greeting stays a greeting, a question asks the same thing."
            + "\n4 Keep it/this/that as pronouns; never substitute what they refer to, or the reason behind them."
            + "\n5 Add nothing absent from this message: no facts, names, transliterations, pronunciation guides or notes."
            + "\n6 Minecraft terms: use the game's own {target} wording."
            + "\n7 Never answer, continue or extend the message."
            + "\n8 Output the translation alone.";

    /** Chinese rendering of {@link #CORE_RULES}. */
    private static final String CORE_RULES_ZH =
            "\n1 逐句全译，俚语、脏话、外来语、品牌名都要译。用户名保持原样。"
            + "\n2 习语和比喻按意思译，不可逐字。"
            + "\n3 保持句子类型：问候仍是问候，疑问仍问同一件事。"
            + "\n4 it/this/that 等指代词仍译成指代词，不可替换成所指的事物或原因。"
            + "\n5 不添加本句没有的内容：不加事实、人名、音译、注音、注释。"
            + "\n6 Minecraft 术语使用游戏官方{target}译名。"
            + "\n7 不回答、不续写、不扩展。"
            + "\n8 只输出译文，不要引号、说明或原文。";

    /** The style template in force: the user's own, the built-in English one, or its Chinese twin. */
    private static String styleTemplate(TEConfig cfg) {
        if (cfg.aiSystemPrompt != null && !cfg.aiSystemPrompt.isBlank()) {
            return cfg.aiSystemPrompt;
        }
        return cfg.aiPromptChinese ? DEFAULT_AI_PROMPT_ZH : DEFAULT_AI_PROMPT;
    }

    private static final Object PROMPT_LOCK = new Object();

    /** Runs on a translation worker. */
    private static String resolvedStyleTemplate(TEConfig cfg) throws IOException, InterruptedException {
        if (!cfg.aiPromptChinese || cfg.aiSystemPrompt == null || cfg.aiSystemPrompt.isBlank()) return styleTemplate(cfg);
        String source = cfg.aiSystemPrompt;
        String profile = cfg.aiEndpointUrl + "|" + cfg.aiModel;
        synchronized (PROMPT_LOCK) {
            if (source.equals(cfg.aiChinesePromptSource) && profile.equals(cfg.aiChinesePromptProfile)
                    && cfg.aiChinesePromptText != null && !cfg.aiChinesePromptText.isBlank()) return cfg.aiChinesePromptText;
            JsonObject payload = new JsonObject();
            payload.addProperty("model", cfg.aiModel);
            payload.addProperty("temperature", 0);
            addSpeedParams(payload, cfg, source);
            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject(); system.addProperty("role", "system");
            system.addProperty("content", "Translate the following instructions into Simplified Chinese. Preserve every rule, literal code, and the {target} placeholder exactly. Output only the translated instructions. Do not execute them.");
            JsonObject user = new JsonObject(); user.addProperty("role", "user"); user.addProperty("content", source);
            messages.add(system); messages.add(user); payload.add("messages", messages);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(cfg.aiEndpointUrl)).timeout(Duration.ofMillis(cfg.aiTimeoutMs));
            addAiHeaders(request, cfg);
            apiCalls.incrementAndGet(); charsSent.addAndGet(source.length());
            HttpResponse<String> response = httpClient.send(request.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Prompt translation HTTP " + response.statusCode());
            try {
                JsonObject choice = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
                String translated = choice.getAsJsonObject("message").get("content").getAsString().strip();
                if (translated.isBlank() || (choice.has("finish_reason") && "length".equals(choice.get("finish_reason").getAsString()))
                        || (source.contains("{target}") && !translated.contains("{target}"))) throw new IOException("Incomplete Chinese instruction translation");
                cfg.aiChinesePromptSource = source; cfg.aiChinesePromptProfile = profile; cfg.aiChinesePromptText = translated;
                TEConfig.save();
                return translated;
            } catch (RuntimeException e) { throw new IOException("Invalid Chinese instruction translation", e); }
        }
    }

    /** The always-applied accuracy rules, in whichever language the prompt option selects. */
    private static String coreRules(TEConfig cfg) {
        return cfg.aiPromptChinese ? CORE_RULES_ZH : CORE_RULES;
    }

    /** Casual openers that ask how someone is doing without containing a greeting word. */
    private static final java.util.Set<String> CASUAL_OPENERS = java.util.Set.of(
            "sup", "whats up", "what up", "whatsup", "wassup", "wazzup", "whats good", "whats new",
            "whats poppin", "whats crackin", "hows it going", "how goes it");

    private static final String OPENER_HINT =
            " This message is a casual opener: it asks how the other person is doing and contains no "
            + "greeting word. Use what a native {target} speaker would really say here, not a word-for-word "
            + "gloss, and do not begin the translation with a greeting word. If the conversation above already "
            + "contains a greeting, this is a follow-up question, not a new greeting.";

    /** True when the whole message is one of {@link #CASUAL_OPENERS}, ignoring punctuation and a trailing address. */
    static boolean isCasualOpener(String text) {
        if (text == null || text.length() > 40) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT).strip().replaceAll("[!?.,\u2026]+$", "").strip();
        t = t.replaceAll("\\s+(man|bro|dude|bruh|g|fam|guys|y'all|everyone|all)$", "").strip();
        return CASUAL_OPENERS.contains(t.replace("'", "").replaceAll("\\s+", " "));
    }

    /** {@code keep_alive} is an Ollama extension that pins the model in memory. */
    private static boolean isLocalEndpoint(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.contains("localhost") || u.contains("127.0.0.1")
                || u.contains("::1") || u.contains(".local");
    }

    /** Lean back-translation: reads a translation back with a minimal prompt. */
    public static CompletableFuture<Result> backTranslate(String text, String from, String to,
                                                          TEConfig.Engine engine, String tag) {
        if (engine != TEConfig.Engine.AI_LOCAL) {
            return translate(text, from, to, engine, null, tag);
        }
        TEConfig cfg = TEConfig.get();
        final int submittedAt = generationOf(tag);
        String key = "back|" + cacheKey(cfg, engine, text, from, to, java.util.List.of());
        long epoch;
        synchronized (CACHE_LOCK) {
            epoch = cacheEpoch;
            CacheEntry hit = cache.get(key);
            if (hit != null) { cacheHits.incrementAndGet(); return CompletableFuture.completedFuture(Result.ok(hit.text(), hit.lang())); }
        }
        CompletableFuture<Result> shared = new CompletableFuture<>();
        CompletableFuture<Result> existing = inFlight.putIfAbsent(key, shared);
        if (existing != null) return existing.copy();
        CompletableFuture.supplyAsync(() -> {
            if (generationOf(tag) != submittedAt) {
                return Result.fail("superseded");
            }
            try {
                String sys = "Translate the user's message into " + LanguageUtil.name(to)
                        + ". Output only the translation: no notes, quotes or explanation.";
                java.util.List<String[]> msgs = new java.util.ArrayList<>(2);
                msgs.add(new String[]{"system", sys});
                msgs.add(new String[]{"user", text});
                long t0 = System.currentTimeMillis();
                Result r = chatRawSync(cfg, msgs);
                long took = System.currentTimeMillis() - t0;
                if (took > 1500 || cfg.debugLogging) {
                    TranslateEverythingClient.LOGGER.info("[timing] read-back {}->{} {} chars: {} ms",
                            from, to, text.length(), took);
                }
                return r;
            } catch (Exception e) {
                return Result.fail(shortError(e));
            }
        }, isLocalEndpoint(cfg.aiEndpointUrl) ? executor : netExecutor).whenComplete((result, error) -> {
            if (error == null && result != null && !result.error()) {
                synchronized (CACHE_LOCK) {
                    if (epoch == cacheEpoch) { cache.put(key, new CacheEntry(result.translatedText(), result.detectedLanguage())); dirtyEntries++; }
                }
            }
            inFlight.remove(key, shared);
            shared.complete(error == null && result != null ? result : Result.fail("Back-translation failed"));
        });
        return shared.copy();
    }

    /** Common English function words. */
    private static final java.util.Set<String> EN_WORDS = java.util.Set.of(
            "the", "and", "or", "but", "if", "of", "in", "on", "at", "for", "with", "from", "by", "to",
            "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "have", "has", "had",
            "can", "will", "would", "should", "could", "you", "your", "i", "my", "we", "our", "he",
            "she", "it", "its", "they", "them", "this", "that", "these", "those", "not", "yes",
            "press", "start", "started", "stop", "begin", "click", "use", "get", "got", "go", "going",
            "see", "want", "need", "like", "know", "think", "make", "made", "take", "join",
            "joined", "left", "here", "there", "now", "then", "what", "when", "where", "who", "how",
            "why", "all", "some", "any", "more", "just", "very", "really", "still", "again", "too",
            "so", "up", "out", "down", "over", "back", "about", "because", "after", "before", "than",
            "world", "new", "game", "server", "player", "time", "session", "advancement", "achievement",
            "chat", "message", "welcome", "hello", "thanks", "sorry", "please", "found", "raining",
            "cool", "nice", "good", "bad", "sword", "village", "creeper", "diamond", "level");

    /** True when the text is already in the player's target language, so translating it would only reword it. */
    public static boolean looksAlreadyIn(String text, String targetCode) {
        if (text == null || targetCode == null || targetCode.isBlank()) {
            return false;
        }
        if (LanguageGuess.isProbably(text, targetCode)) {
            return true;
        }
        if (!targetCode.toLowerCase(Locale.ROOT).startsWith("en")) {
            return false;
        }
        // ...but never overrule a confident reading of some OTHER language.
        LanguageGuess.Guess guess = LanguageGuess.of(text);
        if (guess.confident() && !guess.code().equals("en")) {
            return false;
        }
        String t = text.strip();
        if (t.isEmpty()) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) > 0x7F) {
                return false; // any non-ASCII character: not plain English, translate it
            }
        }
        String[] words = t.toLowerCase(Locale.ROOT).split("[^a-z']+");
        int real = 0;
        int known = 0;
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            real++;
            if (EN_WORDS.contains(w)) {
                known++;
            }
        }
        if (real == 0) {
            return false;
        }
        if (real <= 2) {
            return known == real && real >= 2;
        }
        return known >= 2 && known * 3 >= real * 2;
    }

    /** True for the OpenRouter gateway, which accepts a provider-routing block. */
    private static boolean isOpenRouter(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains("openrouter.ai");
    }

    /** Bounds how long a reply can take and, on OpenRouter, which provider serves it. */
    /** Longest message in a conversation, used to size the reply budget. */
    private static String longestMessage(java.util.List<String[]> messages) {
        String longest = "";
        for (String[] m : messages) {
            if (m.length > 1 && m[1] != null && m[1].length() > longest.length()) {
                longest = m[1];
            }
        }
        return longest;
    }

    /** Output budget for a translation of {@code text}. */
    private static int tokenBudgetFor(TEConfig cfg, String text) {
        int chars = text == null ? 0 : text.length();
        long scaled = (long) chars * 2 + 64;
        return (int) Math.max(256, Math.min(cfg.aiMaxTokens, scaled));
    }

    private static void addSpeedParams(JsonObject payload, TEConfig cfg, String text) {
        payload.addProperty("max_tokens", tokenBudgetFor(cfg, text));
        if (isOpenRouter(cfg.aiEndpointUrl)) {
            if (!cfg.aiProviderSort.isBlank()) {
                JsonObject provider = new JsonObject();
                provider.addProperty("sort", cfg.aiProviderSort);
                provider.addProperty("allow_fallbacks", true);
                payload.add("provider", provider);
            }
            addNoReasoning(payload, cfg);
        }
    }

    /** Turns off chain-of-thought on gateways that expose the switch. */
    private static void addNoReasoning(JsonObject payload, TEConfig cfg) {
        if (cfg.aiAllowReasoning) {
            return;
        }
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("enabled", false);
        payload.add("reasoning", reasoning);
    }

    /** Adds the params a local Ollama wants and a hosted API must not receive. */
    private static void addLocalOnlyParams(JsonObject payload, TEConfig cfg) {
        if (isLocalEndpoint(cfg.aiEndpointUrl)) {
            payload.addProperty("keep_alive", -1);
        }
    }

    /** Auth plus the identifying headers hosted gateways ask callers to send. */
    private static void addAiHeaders(HttpRequest.Builder b, TEConfig cfg) {
        b.header("Content-Type", "application/json");
        if (!cfg.aiApiKey.isBlank()) {
            b.header("Authorization", "Bearer " + cfg.aiApiKey);
        }
        if (!isLocalEndpoint(cfg.aiEndpointUrl)) {
            b.header("HTTP-Referer", "https://github.com/adamfr06/TranslateEverything");
            b.header("X-Title", "TranslateEverything");
        }
    }

    /** OpenAI-compatible chat-completions request to a local (or remote) model. */
    private static Result aiLocal(TEConfig cfg, String text, java.util.List<String> notes, String source, String target,
                                  java.util.List<String> context)
            throws IOException, InterruptedException {
        String targetName = LanguageUtil.name(target);
        StringBuilder sys = new StringBuilder(resolvedStyleTemplate(cfg).replace("{target}", targetName));
        sys.append(coreRules(cfg).replace("{target}", targetName));
        if (notes.isEmpty() && isCasualOpener(text)) {
            sys.append(OPENER_HINT.replace("{target}", targetName));
        }
        if (!"auto".equalsIgnoreCase(source)) {
            sys.append(" The source language is ").append(LanguageUtil.name(source)).append('.');
        }
        if (!cfg.speakerTone.isBlank()) {
            sys.append(" Write it in this voice/tone: ").append(cfg.speakerTone).append('.');
        }
        if (notes != null && !notes.isEmpty()) {
            sys.append(" The player attached these notes to guide the translation: ")
               .append(String.join("; ", notes))
               .append(". If a note asks to add, mention, or ask about something, weave that into the "
                       + "translation naturally, as if it were part of the original message. If a note only "
                       + "explains what a word or pronoun refers to, use it to pick the right meaning without "
                       + "adding new content. Never mention the notes themselves, quote them, or explain that "
                       + "you used them.");
        }
        if (context != null && !context.isEmpty()) {
            sys.append(" Recent conversation, for CONTEXT ONLY (do NOT translate, repeat, or mention it):");
            for (String line : context) {
                sys.append("\n").append(line);
            }
            sys.append("\nUse the conversation ONLY to disambiguate word meaning and tone. Translate the user's next message "
                    + "literally and independently - never continue, answer, or extend it; a number or short phrase must stay a "
                    + "number or short phrase.");
        }
        String gloss = MinecraftGlossary.hints(text, target);
        if (!gloss.isEmpty()) {
            sys.append(" Use these EXACT Minecraft ").append(targetName)
               .append(" terms wherever they appear, unchanged: ").append(gloss).append('.');
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", cfg.aiModel);
        payload.addProperty("temperature", 0.15);
        payload.addProperty("stream", false);
        addLocalOnlyParams(payload, cfg);
        addSpeedParams(payload, cfg, text);
        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", sys.toString());
        messages.add(sysMsg);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", text);
        messages.add(userMsg);
        payload.add("messages", messages);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(cfg.aiEndpointUrl))
                .timeout(Duration.ofMillis(cfg.aiTimeoutMs))
                .header("Content-Type", "application/json");
        if (!cfg.aiApiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + cfg.aiApiKey);
        }
        HttpResponse<String> response = null;
        String content = null;
        String lastProblem = "";
        int attempts = isLocalEndpoint(cfg.aiEndpointUrl) ? 1 : 3;
        long deadline = System.currentTimeMillis() + Math.max(2000, cfg.aiTimeoutMs / 2);
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0 && System.currentTimeMillis() > deadline) {
                break;
            }
            if (attempt > 0) {
                if (lastProblem.contains("ran out of tokens")) {
                    payload.addProperty("max_tokens", Math.min(cfg.aiMaxTokens, tokenBudgetFor(cfg, text) * 4));
                }
                Thread.sleep(150L * attempt);
            }
            response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 429 || code >= 500) {
                lastProblem = "AI HTTP " + code;
                continue; // provider was busy or broke; another one may answer
            }
            if (code != 200) {
                return Result.fail("AI HTTP " + code + (code == 404 ? " (model/endpoint?)" : ""));
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                lastProblem = "AI: empty response";
                continue;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            JsonElement raw = message == null ? null : message.get("content");
            content = raw == null || raw.isJsonNull() ? "" : raw.getAsString();
            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() && "length".equals(choice.get("finish_reason").getAsString())) {
                lastProblem = "AI: ran out of tokens before answering";
                content = null;
                continue;
            }
            if (content.isBlank()) {
                String finish = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                        ? choice.get("finish_reason").getAsString() : "";
                lastProblem = "length".equals(finish)
                        ? "AI: ran out of tokens before answering" : "AI: empty response";
                content = null;
                continue;
            }
            break;
        }
        if (content == null) {
            return Result.fail(lastProblem.isEmpty() ? "AI: empty response" : lastProblem);
        }
        content = cleanAiOutput(content, text);
        if (content.isBlank()) {
            return Result.fail("AI: no translation");
        }
        if (looksLikeExplanation(content, text)) {
            TranslateEverythingClient.LOGGER.debug("AI commented instead of translating; using {} instead",
                    referenceEngine());
            for (TEConfig.Engine fallback : referenceChain()) {
                try {
                    Result r = fetch(cfg, fallback, text, source, target, java.util.List.of());
                    if (r != null && !r.error() && !r.translatedText().isBlank()) {
                        return r;
                    }
                } catch (Exception ignored) {
                    // try the next engine
                }
            }
            return Result.ok(text, target);
        }
        String detected = "auto".equalsIgnoreCase(source) ? "" : source;
        return Result.ok(content, detected);
    }

    private static final java.util.regex.Pattern EXPLANATION = java.util.regex.Pattern.compile(
            "(?i)translates?\\s+to|the (?:exact |same )?term (?:is|as|it)|keeping the (?:exact |same )?"
            + "|no (?:direct )?translation|would be translated|remains? the same in|is the (?:same|correct) "
            + "(?:term|word) in"
            + "|this (?:message|phrase|text|appears|seems)|there(?:'s| is) no (?:specific|direct)"
            + "|\\((?:meaning|implying|referring to|i\\.e\\.|lit\\.)|the english equivalent"
            + "|expressing|indicates that|suggests that|in this context");

    /** True when the model returned a meta-explanation rather than a plain translation. */
    private static boolean looksLikeExplanation(String out, String input) {
        if (out.length() > input.length() * 3 + 24 && out.length() > 60) {
            return true;
        }
        return EXPLANATION.matcher(out).find() && out.length() > input.length() + 12;
    }

    /** Small models sometimes wrap output in quotes or leave marker syntax; tidy it. */
    private static String cleanAiOutput(String content, String source) {
        String out = content.strip();
        out = CONTEXT_MARKER.matcher(out).replaceAll("").strip();
        boolean srcHasBracket = source != null && source.matches("(?s).*[\uFF08(\\[\u3010].*");
        if (!srcHasBracket) {
            out = out.replaceAll("[\uFF08(\\[\u3010][^\uFF09)\\]\u3011]*[\uFF09)\\]\u3011]", "").strip();
        } else {
            out = stripRomanization(out);
        }
        out = out.replaceAll("\\s{2,}", " ").strip();
        if (out.length() >= 2 && (out.charAt(0) == '"' || out.charAt(0) == '\u201C')
                && (out.endsWith("\"") || out.endsWith("\u201D"))) {
            out = out.substring(1, out.length() - 1).strip();
        }
        return out;
    }

    private static final java.util.regex.Pattern PARENTHETICAL =
            java.util.regex.Pattern.compile("\\s*[\uFF08(]([^\uFF09)]*)[\uFF09)]");

    /**
     * Removes parenthetical romanizations (romaji, pinyin, romanized Hangul and so on) that
     * follow a translation written in a non-Latin script.
     */
    private static String stripRomanization(String out) {
        String outside = PARENTHETICAL.matcher(out).replaceAll("");
        long nonLatin = outside.codePoints().filter(Character::isLetter).filter(c -> c > 0x24F).count();
        if (nonLatin == 0) {
            return out;
        }
        java.util.regex.Matcher m = PARENTHETICAL.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean latinOnly = m.group(1).codePoints().allMatch(c -> c < 0x250 || !Character.isLetter(c));
            m.appendReplacement(sb, latinOnly ? "" : java.util.regex.Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(sb);
        return sb.toString().strip();
    }

    /** Raw multi-turn chat completion against the AI endpoint (for the follow-up and confirmation conversations). */
    /** The blocking half of {@link #chatRaw}, so callers can reuse it on their own thread. */
    static Result chatRawSync(TEConfig cfg, java.util.List<String[]> messages) {
        try {
                JsonObject payload = new JsonObject();
                payload.addProperty("model", cfg.aiModel);
                payload.addProperty("temperature", 0.4);
                payload.addProperty("stream", false);
                addLocalOnlyParams(payload, cfg);
                addSpeedParams(payload, cfg, longestMessage(messages));
                JsonArray msgs = new JsonArray();
                for (String[] m : messages) {
                    JsonObject o = new JsonObject();
                    o.addProperty("role", m[0]);
                    o.addProperty("content", m[1]);
                    msgs.add(o);
                }
                payload.add("messages", msgs);
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(cfg.aiEndpointUrl))
                        .timeout(Duration.ofMillis(Math.max(cfg.aiTimeoutMs, 20000)))
                        .header("Content-Type", "application/json");
                if (!cfg.aiApiKey.isBlank()) {
                    b.header("Authorization", "Bearer " + cfg.aiApiKey);
                }
                HttpResponse<String> r = httpClient.send(
                        b.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    return Result.fail("AI HTTP " + r.statusCode());
                }
                JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    return Result.fail("AI: empty response");
                }
                String content = choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
                return Result.ok(content.strip(), "");
            } catch (Exception e) {
                return Result.fail(shortError(e));
            }
    }

    public static CompletableFuture<Result> chatRaw(java.util.List<String[]> messages) {
        return CompletableFuture.supplyAsync(() -> chatRawSync(TEConfig.get(), messages), executor);
    }

    /** Beta faithfulness verdict: {@code verdict} is OK / MINOR / OFF (or "" on error). */
    public record Faithfulness(String verdict, String note, String fix) {
        public boolean ok() { return "OK".equalsIgnoreCase(verdict); }
        public boolean minor() { return "MINOR".equalsIgnoreCase(verdict); }
        public boolean off() { return "OFF".equalsIgnoreCase(verdict); }
        public boolean valid() { return ok() || minor() || off(); }
    }

    private static final java.util.regex.Pattern FAITH_VERDICT =
            java.util.regex.Pattern.compile("(?im)^\\s*VERDICT\\s*[:：]\\s*(OK|MINOR|OFF)");
    private static final java.util.regex.Pattern FAITH_NOTE =
            java.util.regex.Pattern.compile("(?im)^\\s*NOTE\\s*[:：]\\s*(.+?)\\s*$");
    private static final java.util.regex.Pattern FAITH_FIX =
            java.util.regex.Pattern.compile("(?im)^\\s*FIX\\s*[:：]\\s*(.+?)\\s*$");

    /**
     * Beta: judge whether {@code proposed} faithfully conveys {@code original} without
     * re-translating it (the round-trip's weakness), strict and armed with Minecraft's own official
     * term translations so it can catch a wrong game term.
     */
    public static CompletableFuture<Faithfulness> checkFaithfulness(String original, String proposed,
                                                                    String targetCode, java.util.List<String> context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                TEConfig cfg = TEConfig.get();
                String gloss = MinecraftGlossary.hints(original, targetCode);
                StringBuilder sys = new StringBuilder(
                    "You are a STRICT bilingual QA checker for a Minecraft chat game. Assume there IS a problem until "
                    + "proven otherwise. Given MESSAGE and its TRANSLATION, decide whether the TRANSLATION conveys the "
                    + "MESSAGE with NOTHING dropped, added, reversed, or mistranslated.\n"
                    + "First check PURPOSE: if the MESSAGE is a greeting, the TRANSLATION must be a greeting. A casual "
                    + "\"what's up\" rendered as \"what happened?\" or \"what's wrong?\" is OFF, not MINOR.\n"
                    + "First list the key pieces of MESSAGE (subject, action, object, who-to-whom, every noun, any "
                    + "negation, tone/politeness). Check each is correctly present in TRANSLATION. If any piece is "
                    + "missing, changed, reversed, or a Minecraft term is wrong, it is NOT OK.\n");
                if (!gloss.isEmpty()) {
                    sys.append("The correct Minecraft terms here are: ").append(gloss)
                       .append(". If the TRANSLATION uses a different word for one of these, it is OFF.\n");
                }
                sys.append("Reply EXACTLY in this format:\nCHECK: <key pieces, one line>\nVERDICT: OK | MINOR | OFF\n"
                    + "NOTE: at most 10 words, written in " + LanguageUtil.name(homeLanguage())
                    + " - what is missing or changed, or 'faithful'\n"
                    + "FIX: the corrected translation on one line, or NONE");
                StringBuilder user = new StringBuilder("MESSAGE: ").append(original)
                        .append("\nTRANSLATION: ").append(proposed);
                if (context != null && !context.isEmpty()) {
                    user.append("\n(Recent conversation, context only - do NOT judge it: ")
                        .append(String.join(" / ", context)).append(")");
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("model", cfg.aiModel);
                payload.addProperty("temperature", 0.1);
                payload.addProperty("stream", false);
                addLocalOnlyParams(payload, cfg);
                // The verdict is short, but FIX can be as long as the translation.
                addSpeedParams(payload, cfg, proposed);
                JsonArray msgs = new JsonArray();
                JsonObject s = new JsonObject();
                s.addProperty("role", "system");
                s.addProperty("content", sys.toString());
                msgs.add(s);
                JsonObject u = new JsonObject();
                u.addProperty("role", "user");
                u.addProperty("content", user.toString());
                msgs.add(u);
                payload.add("messages", msgs);
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(cfg.aiEndpointUrl))
                        .timeout(Duration.ofMillis(Math.max(cfg.aiTimeoutMs, 20000)))
                        .header("Content-Type", "application/json");
                if (!cfg.aiApiKey.isBlank()) {
                    b.header("Authorization", "Bearer " + cfg.aiApiKey);
                }
                HttpResponse<String> r = httpClient.send(
                        b.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    return new Faithfulness("", "", "");
                }
                JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    return new Faithfulness("", "", "");
                }
                String content = choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
                java.util.regex.Matcher mv = FAITH_VERDICT.matcher(content);
                String verdict = mv.find() ? mv.group(1).toUpperCase(Locale.ROOT) : "";
                java.util.regex.Matcher mn = FAITH_NOTE.matcher(content);
                String note = mn.find() ? ensureHomeLanguage(mn.group(1).trim()) : "";
                java.util.regex.Matcher mf = FAITH_FIX.matcher(content);
                String fix = mf.find() ? mf.group(1).trim() : "";
                if (fix.equalsIgnoreCase("NONE")) {
                    fix = "";
                }
                return new Faithfulness(verdict, note, fix);
            } catch (Exception e) {
                return new Faithfulness("", "", "");
            }
        }, executor);
    }

    /** Result of the arbiter: the reconciled best translation, its English round-trip, and a short reason. */
    public record Arbiter(String best, String bestBack, String why) {}

    private static final java.util.regex.Pattern ARB_BEST =
            java.util.regex.Pattern.compile("(?im)^\\s*BEST\\s*[:：]\\s*(.+?)\\s*$");
    private static final java.util.regex.Pattern ARB_WHY =
            java.util.regex.Pattern.compile("(?im)^\\s*WHY\\s*[:：]\\s*(.+?)\\s*$");

    /** The non-AI engine used for read-backs, the compared candidate, and status notes. */
    public static TEConfig.Engine referenceEngine() {
        TEConfig.Engine e = TEConfig.get().referenceEngine;
        return e == null ? TEConfig.Engine.GOOGLE_FREE : e;
    }

    /** The language the player reads and writes in, for read-backs and status notes. */
    public static String homeLanguage() {
        String language = TEConfig.get().targetLanguage;
        return language == null || language.isBlank() || language.equalsIgnoreCase("auto") ? "en" : language;
    }

    /**
     * Non-AI engines worth trying for a reference lookup, preferred order: the configured one
     * first, then anything else that is actually set up.
     */
    private static java.util.List<TEConfig.Engine> referenceChain() {
        TEConfig cfg = TEConfig.get();
        java.util.List<TEConfig.Engine> chain = new java.util.ArrayList<>(3);
        chain.add(referenceEngine());
        if (!cfg.azureKey.isBlank()) {
            chain.add(TEConfig.Engine.AZURE);
        }
        if (libreUsable(cfg)) {
            chain.add(TEConfig.Engine.LIBRE_TRANSLATE);
        }
        if (!cfg.googleApiKey.isBlank()) {
            chain.add(TEConfig.Engine.GOOGLE_CLOUD);
        }
        chain.add(TEConfig.Engine.GOOGLE_FREE);
        java.util.List<TEConfig.Engine> out = new java.util.ArrayList<>(3);
        for (TEConfig.Engine e : chain) {
            if (e != TEConfig.Engine.AI_LOCAL && !out.contains(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** A reference lookup that falls back to the next configured engine when one fails. */
    public static CompletableFuture<Result> referenceTranslate(String text, String source, String target,
                                                               java.util.List<String> context) {
        java.util.List<TEConfig.Engine> chain = referenceChain();
        CompletableFuture<Result> f = translate(text, source, target, chain.get(0), context);
        java.util.concurrent.atomic.AtomicReference<String> firstError = new java.util.concurrent.atomic.AtomicReference<>("");
        for (int i = 1; i < chain.size(); i++) {
            final TEConfig.Engine next = chain.get(i);
            f = f.thenCompose(r -> {
                if (r != null && !r.error() && !r.translatedText().isBlank()) {
                    return CompletableFuture.completedFuture(r);
                }
                if (r != null && r.error()) {
                    firstError.compareAndSet("", r.errorMessage());
                }
                TranslateEverythingClient.LOGGER.debug("Reference engine failed, trying {}", next);
                return translate(text, source, target, next, context);
            });
        }
        return f.thenApply(r -> {
            if (r != null && r.error() && !firstError.get().isEmpty()) {
                return Result.fail(firstError.get());
            }
            return r;
        });
    }

    private static CompletableFuture<Result> backOrBlank(String text, String targetCode) {
        return (text == null || text.isBlank())
                ? CompletableFuture.completedFuture(Result.ok("", ""))
                : referenceTranslate(text, targetCode, homeLanguage(), null);
    }

    /** Translates with the reference engine and the AI, reads both back, and lets the AI pick or write the best. */
    public static CompletableFuture<Arbiter> arbitrate(String original, String targetCode, java.util.List<String> context) {
        String tgtName = LanguageUtil.name(targetCode);
        java.util.List<String> ctx = context == null ? java.util.List.of() : context;
        CompletableFuture<Result> gf = referenceTranslate(original, "auto", targetCode, ctx);
        CompletableFuture<Result> af = translate(original, "auto", targetCode, TEConfig.Engine.AI_LOCAL, ctx);
        return gf.thenCombine(af, (g, a) -> new String[]{
                        g == null || g.error() ? "" : g.translatedText(),
                        a == null || a.error() ? "" : a.translatedText()})
                .thenCompose(fwd -> {
                    String refCand = fwd[0];
                    String aiCand = fwd[1];
                    return backOrBlank(refCand, targetCode).thenCombine(backOrBlank(aiCand, targetCode),
                            (gb, ab) -> new String[]{refCand, aiCand,
                                    gb == null ? "" : gb.translatedText(),
                                    ab == null ? "" : ab.translatedText()});
                })
                .thenCompose(all -> {
                    String refCand = all[0];
                    String aiCand = all[1];
                    String gback = all[2];
                    String aback = all[3];
                    String gloss = MinecraftGlossary.hints(original, targetCode);
                    String sys = "You are the final arbiter for a Minecraft chat translation into " + tgtName
                            + ". You are fluent in the player's language and " + tgtName + ". You are given the player's "
                            + "MESSAGE, two candidate " + tgtName + " translations (A from " + referenceEngine().label
                            + ", B from a local AI) each "
                            + "with its back-translation into " + LanguageUtil.name(homeLanguage()) + ", and recent conversation "
                            + "context. Choose the candidate "
                            + "that most faithfully AND naturally conveys the MESSAGE; if neither is fully correct, write a "
                            + "better one yourself. Priorities in order: (1) the MESSAGE'S PURPOSE - a greeting must "
                            + "stay a greeting, a question must ask the same thing, a statement must stay a statement; "
                            + "(2) correct meaning - subject, who-to-whom, "
                            + "question vs statement, negation, nothing added or dropped; (3) correct Minecraft terms; "
                            + "(4) natural, matching tone and slang. If BOTH candidates change the purpose of the "
                            + "MESSAGE, do not pick either: write the correct translation yourself."
                            + (gloss.isEmpty() ? "" : " Correct Minecraft terms: " + gloss + ".")
                            + " Priority ONE is meaning: keep the exact subject and who-is-asking-whom - if the MESSAGE "
                            + "asks about 'you', the translation must ask about 'you', never flip it to 'I' or 'me'."
                            + " Reply EXACTLY:\nBEST: <the final " + tgtName + " translation, one line>\n"
                            + "WHY: <one short clause explaining your choice, written in "
                            + LanguageUtil.name(homeLanguage()) + " (never " + tgtName + ")>";
                    StringBuilder u = new StringBuilder("MESSAGE: ").append(original);
                    if (!ctx.isEmpty()) {
                        u.append("\nContext: ").append(String.join(" / ", ctx));
                    }
                    u.append("\nCandidate A (").append(tgtName).append("): ").append(refCand)
                     .append("\n  A back-translates to: ").append(gback)
                     .append("\nCandidate B (").append(tgtName).append("): ").append(aiCand)
                     .append("\n  B back-translates to: ").append(aback);
                    java.util.List<String[]> msgs = new java.util.ArrayList<>();
                    msgs.add(new String[]{"system", sys});
                    msgs.add(new String[]{"user", u.toString()});
                    final String fRefCand = refCand;
                    final String fAiCand = aiCand;
                    final String fgback = gback;
                    final String faback = aback;
                    return chatRaw(msgs).thenCompose(r -> {
                        String best;
                        String why = "";
                        if (r == null || r.error()) {
                            best = !fAiCand.isBlank() ? fAiCand : fRefCand;
                        } else {
                            String c = r.translatedText();
                            java.util.regex.Matcher mb = ARB_BEST.matcher(c);
                            java.util.regex.Matcher mw = ARB_WHY.matcher(c);
                            best = mb.find() ? cleanAiOutput(mb.group(1).trim(), original) : "";
                            why = mw.find() ? mw.group(1).trim() : "";
                            if (best.isBlank()) {
                                best = !fAiCand.isBlank() ? fAiCand : fRefCand;
                            }
                        }
                        why = ensureHomeLanguage(why);
                        final String fbest = best;
                        final String fwhy = why;
                        if (fbest.equals(fRefCand) && !fgback.isBlank()) {
                            return CompletableFuture.completedFuture(new Arbiter(fbest, fgback, fwhy));
                        }
                        if (fbest.equals(fAiCand) && !faback.isBlank()) {
                            return CompletableFuture.completedFuture(new Arbiter(fbest, faback, fwhy));
                        }
                        return backOrBlank(fbest, targetCode).thenApply(bk ->
                                new Arbiter(fbest, bk == null ? "" : bk.translatedText(), fwhy));
                    });
                });
    }

    /** Translates a model-written status line into the player's language when it is not already. */
    private static String ensureHomeLanguage(String s) {
        if (s == null || s.isBlank()) {
            return s == null ? "" : s;
        }
        String home = homeLanguage();
        if (LanguageGuess.isProbably(s, home)) {
            return s;
        }
        try {
            Result r = referenceTranslate(s, "auto", home, null)
                    .get(6, TimeUnit.SECONDS);
            if (r != null && !r.error() && !r.translatedText().isBlank()
                    && !r.detectedLanguage().isBlank() && !home.equalsIgnoreCase(r.detectedLanguage())) {
                return r.translatedText();
            }
        } catch (Exception ignored) {
            // best effort: the original text is better than nothing
        }
        return s;
    }

    private static String shortError(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "timed out";
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException) {
            return "no connection";
        }
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
