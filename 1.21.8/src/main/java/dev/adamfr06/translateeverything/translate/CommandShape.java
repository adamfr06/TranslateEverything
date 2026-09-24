package dev.adamfr06.translateeverything.translate;

import java.util.Locale;
import java.util.Set;

/** Splits a chat command into the part that must not change and the part that is actually a message to a person. */
public final class CommandShape {
    /**
     * @param prefix everything up to and including the recipient, kept verbatim
     * @param message the human part, the only text that is translated
     * @param recipient who it is going to, for the preview line; "" if the verb has none
     */
    public record Whisper(String prefix, String message, String recipient) {
        public String rebuild(String translated) {
            return prefix + translated;
        }
    }

    /** Whisper verbs that take a recipient before the message. */
    private static final Set<String> TO_SOMEONE = Set.of(
            "msg", "w", "whisper", "tell", "t", "pm", "dm", "m", "message", "emsg", "epm", "etell",
            "pmsg", "whisperto");

    /** Whisper verbs with no recipient argument: the server already knows who. */
    private static final Set<String> TO_LAST = Set.of("r", "reply", "respond", "reply2");

    /** Verbs whose whole tail is a public message rather than a command. */
    private static final Set<String> BROADCAST = Set.of("me", "say", "shout", "global", "g", "local", "l", "yell");

    private CommandShape() {
    }

    /**
     * @return the split, or {@code null} when the line is not a command carrying a human message , 
     * including every unrecognised command, which is left alone.
     */
    public static Whisper parse(String line) {
        if (line == null || !line.startsWith("/") || line.length() < 2) {
            return null;
        }
        int verbEnd = line.indexOf(' ');
        if (verbEnd < 0) {
            return null; // a bare command, nothing to say
        }
        String verb = line.substring(1, verbEnd).toLowerCase(Locale.ROOT);
        int cursor = skipSpaces(line, verbEnd);
        if (cursor >= line.length()) {
            return null;
        }
        if (TO_LAST.contains(verb) || BROADCAST.contains(verb)) {
            return new Whisper(line.substring(0, cursor), line.substring(cursor), "");
        }
        if (!TO_SOMEONE.contains(verb)) {
            return null;
        }
        int nameEnd = line.indexOf(' ', cursor);
        if (nameEnd < 0) {
            return null; // a recipient but nothing said to them yet
        }
        String recipient = line.substring(cursor, nameEnd);
        if (!looksLikeName(recipient)) {
            return null;
        }
        int messageStart = skipSpaces(line, nameEnd);
        if (messageStart >= line.length()) {
            return null;
        }
        return new Whisper(line.substring(0, messageStart), line.substring(messageStart), recipient);
    }

    private static int skipSpaces(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** Minecraft account names, and the bracketed or coloured forms some proxies use. */
    private static boolean looksLikeName(String token) {
        if (token.isEmpty() || token.length() > 20) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.' && c != '-') {
                return false;
            }
        }
        return true;
    }
}
