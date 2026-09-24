package dev.adamfr06.translateeverything.translate;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.adamfr06.translateeverything.TranslateEverythingClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Minecraft's own language files straight out of the running game, which is where the
 * glossary's official terms come from.
 */
public final class GameLanguages implements MinecraftGlossary.LangSource {
    private static final Gson GSON = new Gson();

    private GameLanguages() {
    }

    /** Installs this reader as the glossary's source. */
    public static void install() {
        MinecraftGlossary.setSource(new GameLanguages());
    }

    @Override
    public Map<String, String> english() {
        return read("en_us");
    }

    @Override
    public Map<String, String> forIso(String isoCode) {
        String file = resolve(isoCode);
        return file == null ? null : read(file);
    }

    /** Finds the game's language file for an ISO code, e.g. "es" to "es_es" and "pt" to "pt_br". */
    private static String resolve(String isoCode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getLanguageManager() == null) {
            return null;
        }
        String iso = isoCode.toLowerCase(Locale.ROOT);
        String prefix = iso + "_";
        String firstMatch = null;
        for (String code : client.getLanguageManager().getAllLanguages().keySet()) {
            String lower = code.toLowerCase(Locale.ROOT);
            if (lower.equals(iso)) {
                return code;
            }
            if (lower.startsWith(prefix) && firstMatch == null) {
                firstMatch = code;
            }
        }
        return firstMatch;
    }

    /** Merges every pack's copy of {@code lang/<file>.json}, later packs overriding earlier ones. */
    private static Map<String, String> read(String file) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) {
            return null;
        }
        Identifier id = Identifier.of("minecraft", "lang/" + file + ".json");
        List<Resource> stack = client.getResourceManager().getAllResources(id);
        if (stack.isEmpty()) {
            return null;
        }
        Map<String, String> merged = new HashMap<>();
        for (Resource resource : stack) {
            try (BufferedReader reader = resource.getReader()) {
                Map<String, String> part = GSON.fromJson(reader,
                        new TypeToken<LinkedHashMap<String, String>>() {}.getType());
                if (part != null) {
                    merged.putAll(part);
                }
            } catch (Exception e) {
                TranslateEverythingClient.LOGGER.warn("Could not read language file {}", file, e);
            }
        }
        return merged.isEmpty() ? null : merged;
    }
}
