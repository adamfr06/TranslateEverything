package dev.adamfr06.translateeverything.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a rendered chat line into the parts a translator should touch and the parts it must not. */
public final class MessageShape {
    /** A chat line divided into an untouched prefix and the body worth translating. */
    public record Split(String prefix, String body) {
        public String rebuild(String translatedBody) {
            return prefix.isEmpty() ? translatedBody : prefix + translatedBody;
        }
    }

    private static final Pattern LEADING_TAGS = Pattern.compile("^(?:\\[[^\\]]{1,32}\\]\\s*)+");
    private static final Pattern SPEAKER_ARROW = Pattern.compile("^[^»\\n]{1,64}»\\s*");
    private static final Pattern SPEAKER_COLON = Pattern.compile("^[A-Za-z0-9_]{3,16}:\\s+");
    private static final Pattern ANGLE_NAME = Pattern.compile("^<[A-Za-z0-9_]{3,16}>\\s*");

    /** Things inside the body that must survive translation exactly as written. */
    private static final Pattern PROTECTED = Pattern.compile(
            "(https?://[^\\s)\\]},]+)"   // links, without trailing bracket or comma
            + "|(:[a-z0-9_+-]{1,32}:)" // :emote:, including short ones like :x:
            + "|(@[A-Za-z0-9_]{2,32})" // @mention
            + "|(§.)");                // colour codes

    /** A body with nothing a translator can act on: emotes, links, numbers, punctuation. */
    private static final Pattern NO_WORDS = Pattern.compile(
            "^(?:\\s|\\p{Punct}|\\d|:[a-z0-9_+-]{1,32}:|https?://\\S+|§.)*$");

    private MessageShape() {
    }

    /** Peels chat machinery off the front of a line. */
    public static Split split(String line) {
        if (line == null || line.isBlank()) {
            return new Split("", line == null ? "" : line);
        }
        String rest = line;
        StringBuilder prefix = new StringBuilder();
        Matcher tags = LEADING_TAGS.matcher(rest);
        if (tags.find()) {
            prefix.append(tags.group());
            rest = rest.substring(tags.end());
        }
        for (Pattern speaker : List.of(SPEAKER_ARROW, ANGLE_NAME, SPEAKER_COLON)) {
            Matcher m = speaker.matcher(rest);
            if (m.find()) {
                prefix.append(m.group());
                rest = rest.substring(m.end());
                break;
            }
        }
        // Never strip the whole line: a message that is only a speaker tag has no body.
        if (rest.isBlank()) {
            return new Split("", line);
        }
        return new Split(prefix.toString(), rest);
    }

    /** False when translating would be pointless: no letters to work with. */
    public static boolean worthTranslating(String body) {
        return body != null && !body.isBlank() && !NO_WORDS.matcher(body).matches();
    }

    /** A body with its protected tokens swapped for stable placeholders. */
    public record Masked(String text, Map<String, String> tokens) {
        /** Puts the original tokens back into a translated string. */
        public String restore(String translated) {
            String out = translated;
            for (Map.Entry<String, String> e : tokens.entrySet()) {
                out = out.replace(e.getKey(), e.getValue());
                // Models sometimes lower-case or space out the placeholder.
                out = out.replace(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
            return out;
        }
    }

    /**
     * Replaces links, emotes and mentions with short placeholders so the model cannot translate,
     * explain or mangle them.
     */
    public static Masked mask(String body) {
        return mask(body, java.util.List.of());
    }

    /** As above, and also protects the names of players currently online. */
    public static Masked mask(String body, java.util.Collection<String> playerNames) {
        Map<String, String> tokens = new LinkedHashMap<>();
        String working = body;
        if (playerNames != null && !playerNames.isEmpty()) {
            // Longest first, so "Player12345" is matched before a shorter name inside it.
            List<String> names = new ArrayList<>(playerNames);
            names.removeIf(n -> n == null || n.length() < 3);
            names.sort((x, y) -> y.length() - x.length());
            for (String name : names) {
                int at = working.indexOf(name);
                if (at < 0) {
                    continue;
                }
                String token = "\u2402n" + tokens.size() + "\u2403";
                tokens.put(token, name);
                working = working.replace(name, token);
            }
        }
        Map<String, String> all = new LinkedHashMap<>(tokens);
        Masked inner = maskTokens(working, all);
        return new Masked(inner.text(), all);
    }

    private static Masked maskTokens(String body, Map<String, String> tokens) {
        Matcher m = PROTECTED.matcher(body);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (m.find()) {
            String token = "␂" + tokens.size() + "␃"; // unlikely in real chat
            tokens.put(token, m.group());
            out.append(body, i, m.start()).append(token);
            i = m.end();
        }
        out.append(body.substring(i));
        return new Masked(out.toString(), tokens);
    }

    /** Player names seen in chat, so a name is never mistaken for a word. */
    public static List<String> namesIn(String line, java.util.Collection<String> known) {
        List<String> found = new ArrayList<>();
        if (known == null) {
            return found;
        }
        for (String name : known) {
            if (name != null && name.length() >= 3 && line.contains(name)) {
                found.add(name);
            }
        }
        return found;
    }
}
