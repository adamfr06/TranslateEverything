package dev.adamfr06.translateeverything.translate;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.adamfr06.translateeverything.TranslateEverythingClient;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minecraft's own official term translations (English term to target term), so a small AI model
 * cannot invent its own word for pillager, warden or netherite.
 */
public final class MinecraftGlossary {
    /** Supplies raw Minecraft language maps (translation key to text). */
    public interface LangSource {
        /** The English base table, or null if unavailable. */
        Map<String, String> english();

        /** The table for an ISO code such as "es" or "pt", or null when the game has no such language. */
        Map<String, String> forIso(String isoCode);
    }

    private static volatile LangSource source;

    /** Installed once at client start by the version-specific loader. */
    public static void setSource(LangSource s) {
        source = s;
        synchronized (MinecraftGlossary.class) {
            CACHE.clear(); // a resource reload can change the available languages
        }
    }

    private static final Gson GSON = new Gson();
    private static final int MAX_TERMS = 12;

    /** Translation-key families worth learning: the things players actually name in chat. */
    private static final String[] KEY_PREFIXES = {
            "block.minecraft.", "item.minecraft.", "entity.minecraft.",
            "biome.minecraft.", "effect.minecraft.", "enchantment.minecraft."
    };

    /** One loaded language map + a first-word index for cheap detection. */
    private static final class Table {
        final Map<String, String> map;               // en (lower) -> target term
        final Map<String, List<String>> byFirstWord; // first token -> keys, longest first
        Table(Map<String, String> map) {
            this.map = map;
            this.byFirstWord = new HashMap<>();
            for (String key : map.keySet()) {
                String first = firstWord(key);
                byFirstWord.computeIfAbsent(first, k -> new ArrayList<>()).add(key);
            }
            for (List<String> keys : byFirstWord.values()) {
                keys.sort((a, b) -> b.length() - a.length()); // longest match wins
            }
        }
    }

    private static final Map<String, Table> CACHE = new HashMap<>();

    private MinecraftGlossary() {
    }

    /**
     * Returns {@code "term=translation; term=translation"} for every glossary term found in {@code text}, or {@code ""}
     * when there is no glossary for {@code targetCode} or nothing matched.
     */
    public static String hints(String text, String targetCode) {
        if (text == null || text.isBlank() || targetCode == null) {
            return "";
        }
        Table t = table(targetCode);
        if (t == null) {
            return "";
        }
        try {
            String lower = text.toLowerCase(Locale.ROOT);
            List<String> matched = new ArrayList<>();
            for (String token : lower.split("[^a-z0-9]+")) {
                List<String> cands = t.byFirstWord.get(token);
                if (cands == null) {
                    continue;
                }
                for (String key : cands) {
                    if (!matched.contains(key) && containsWord(lower, key)) {
                        matched.add(key);
                    }
                }
            }
            if (matched.isEmpty()) {
                return "";
            }
            matched.sort((a, b) -> b.length() - a.length());
            List<String> kept = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (String key : matched) {
                boolean covered = false;
                for (String longer : kept) {
                    if (containsWord(longer, key)) { // e.g. skip "brick" when "resin brick" is present
                        covered = true;
                        break;
                    }
                }
                if (covered) {
                    continue;
                }
                kept.add(key);
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(key).append('=').append(t.map.get(key));
                if (kept.size() >= MAX_TERMS) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** True when the player's game has official terms for this target language. */
    public static boolean has(String targetCode) {
        return table(targetCode) != null;
    }

    private static synchronized Table table(String targetCode) {
        String code = targetCode.toLowerCase(Locale.ROOT);
        if (code.contains("-")) {
            code = code.substring(0, code.indexOf('-')); // pt-BR -> pt
        }
        if (code.contains("_")) {
            code = code.substring(0, code.indexOf('_'));
        }
        if (CACHE.containsKey(code)) {
            return CACHE.get(code);
        }
        Table built = null;
        try {
            built = build(code);
        } catch (Exception e) {
            TranslateEverythingClient.LOGGER.warn("Could not build Minecraft glossary for '{}'", code, e);
        }
        CACHE.put(code, built);
        return built;
    }

    /** Pair the English table against the target one, keeping only genuinely translated game terms. */
    private static Table build(String code) {
        LangSource src = source;
        if (src == null || code.equals("en")) {
            return null; // no source installed yet, or English needs no glossary
        }
        Map<String, String> en = src.english();
        Map<String, String> target = src.forIso(code);
        if (en == null || en.isEmpty() || target == null || target.isEmpty()) {
            return null;
        }
        Map<String, String> pairs = new HashMap<>();
        for (Map.Entry<String, String> e : en.entrySet()) {
            String key = e.getKey();
            if (!isTermKey(key)) {
                continue;
            }
            String english = e.getValue();
            if (english == null || english.isBlank() || english.length() > 40) {
                continue;
            }
            String localized = target.get(key);
            if (localized == null || localized.isBlank() || localized.equals(english)) {
                continue;
            }
            pairs.putIfAbsent(english.strip().toLowerCase(Locale.ROOT), localized.strip());
        }
        if (pairs.isEmpty()) {
            return null;
        }
        TranslateEverythingClient.LOGGER.info("Built {} Minecraft glossary terms for '{}' from the game's own language files",
                pairs.size(), code);
        return new Table(pairs);
    }

    private static boolean isTermKey(String key) {
        for (String p : KEY_PREFIXES) {
            if (key.startsWith(p)) {
                return !key.contains(".desc") && !key.contains(".subtitle") && !key.contains(".effect.");
            }
        }
        return false;
    }

    private static String firstWord(String key) {
        int sp = key.indexOf(' ');
        return sp < 0 ? key : key.substring(0, sp);
    }

    /** Whole-word (ASCII-boundary) containment: {@code needle} inside {@code hay}. */
    private static boolean containsWord(String hay, String needle) {
        int from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) {
                return false;
            }
            char before = i == 0 ? ' ' : hay.charAt(i - 1);
            int end = i + needle.length();
            char after = end >= hay.length() ? ' ' : hay.charAt(end);
            if (!isWordChar(before) && !isWordChar(after)) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
