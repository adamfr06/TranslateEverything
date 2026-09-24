package dev.adamfr06.translateeverything.translate;

import java.util.Locale;
import java.util.Map;

/** Small helpers for language codes and text heuristics. */
public final class LanguageUtil {
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("af", "Afrikaans"), Map.entry("ar", "Arabic"), Map.entry("bg", "Bulgarian"),
            Map.entry("bn", "Bengali"), Map.entry("cs", "Czech"), Map.entry("da", "Danish"),
            Map.entry("de", "German"), Map.entry("el", "Greek"), Map.entry("en", "English"),
            Map.entry("es", "Spanish"), Map.entry("et", "Estonian"), Map.entry("fa", "Persian"),
            Map.entry("fi", "Finnish"), Map.entry("fr", "French"), Map.entry("he", "Hebrew"),
            Map.entry("hi", "Hindi"), Map.entry("hr", "Croatian"), Map.entry("hu", "Hungarian"),
            Map.entry("id", "Indonesian"), Map.entry("it", "Italian"), Map.entry("ja", "Japanese"),
            Map.entry("ko", "Korean"), Map.entry("lt", "Lithuanian"), Map.entry("lv", "Latvian"),
            Map.entry("ms", "Malay"), Map.entry("nl", "Dutch"), Map.entry("no", "Norwegian"),
            Map.entry("pl", "Polish"), Map.entry("pt", "Portuguese"), Map.entry("ro", "Romanian"),
            Map.entry("ru", "Russian"), Map.entry("sk", "Slovak"), Map.entry("sl", "Slovenian"),
            Map.entry("sr", "Serbian"), Map.entry("sv", "Swedish"), Map.entry("th", "Thai"),
            Map.entry("tl", "Filipino"), Map.entry("tr", "Turkish"), Map.entry("uk", "Ukrainian"),
            Map.entry("vi", "Vietnamese"), Map.entry("zh", "Chinese"),
            Map.entry("zh-cn", "Chinese"), Map.entry("zh-tw", "Chinese (Trad.)"));

    private LanguageUtil() {
    }

    /** "es" -> "Spanish"; unknown codes are returned as-is. */
    public static String name(String code) {
        if (code == null || code.isBlank()) {
            return "?";
        }
        return NAMES.getOrDefault(code.toLowerCase(Locale.ROOT), code);
    }

    /** Compares codes loosely: "pt-BR" matches "pt", "zh-CN" matches "zh". */
    public static boolean sameLanguage(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = a.toLowerCase(Locale.ROOT);
        String nb = b.toLowerCase(Locale.ROOT);
        if (na.equals(nb)) {
            return true;
        }
        String baseA = na.contains("-") ? na.substring(0, na.indexOf('-')) : na;
        String baseB = nb.contains("-") ? nb.substring(0, nb.indexOf('-')) : nb;
        return baseA.equals(baseB);
    }

    public static boolean isAsciiOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /** True when there is nothing worth translating (digits, punctuation, arrows…). */
    public static boolean hasNoLetters(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
