package dev.adamfr06.translateeverything.translate;

import dev.adamfr06.translateeverything.config.TEConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/** Rolling record of recent chat, used to give the AI conversational context. */
public final class ChatContext {
    public enum Origin { INCOMING, OUTGOING }

    /** One remembered line. */
    public static final class Entry {
        public final long time;
        public final String text;
        public final Origin origin;
        public final boolean autoIncluded;
        public Boolean manual; // null = follow autoIncluded

        Entry(String text, Origin origin, boolean autoIncluded) {
            this.time = System.currentTimeMillis();
            this.text = text;
            this.origin = origin;
            this.autoIncluded = autoIncluded;
        }

        public boolean included() {
            return manual != null ? manual : autoIncluded;
        }
    }

    private static final int MAX_LOG = 300;
    private static final Object LOCK = new Object();
    private static final Deque<Entry> LOG = new ArrayDeque<>();

    // <Name> …  OR  optional [rank]/(rank)/{rank} tags then a Name followed by : » ›
    private static final Pattern SPEECH = Pattern.compile(
            "^\\s*(?:\\[[^\\]]*\\]\\s*|\\([^)]*\\)\\s*|\\{[^}]*\\}\\s*)*"
                    + "(?:<[^>]{1,32}>|[A-Za-z0-9_][A-Za-z0-9_.]{0,23}\\s*[:»›])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEECH_WORDS =
            Pattern.compile("\\b(whisper|whispers|whispered|shouts?)\\b|\\s->\\s", Pattern.CASE_INSENSITIVE);

    private ChatContext() {
    }

    /** Records a line and returns its entry (so callers can surface its state). */
    public static Entry add(String text, Origin origin) {
        if (text == null) {
            text = "";
        }
        String t = text.strip();
        Entry e = new Entry(t, origin, classify(t, origin));
        synchronized (LOCK) {
            LOG.addLast(e);
            while (LOG.size() > MAX_LOG) {
                LOG.removeFirst();
            }
        }
        return e;
    }

    /** Outgoing lines are always kept; incoming lines must look like player speech. */
    public static boolean isPlayerMessage(String text) {
        return classify(text, Origin.INCOMING);
    }

    private static boolean classify(String text, Origin origin) {
        if (origin == Origin.OUTGOING) {
            return true;
        }
        boolean speech = SPEECH.matcher(text).find() || SPEECH_WORDS.matcher(text).find();
        return speech && !isSystemSender(text);
    }

    private static boolean isSystemSender(String text) {
        List<String> names = TEConfig.get().contextSystemSenders;
        if (names == null || names.isEmpty()) {
            return false;
        }
        String head = text.replaceFirst("^\\s*(?:\\[[^\\]]*\\]\\s*|\\([^)]*\\)\\s*|\\{[^}]*\\}\\s*|<)*", "").stripLeading();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            // sender name at the very start, followed by a speech separator or '>'
            if (head.regionMatches(true, 0, n, 0, n.length())) {
                String after = head.substring(n.length()).stripLeading();
                if (after.isEmpty() || after.startsWith(":") || after.startsWith(">")
                        || after.startsWith("»") || after.startsWith("›")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The context window to feed the AI, oldest→newest, honoring the configured count/time limit. */
    public static List<String> window(String excludingText) {
        TEConfig cfg = TEConfig.get();
        if (!cfg.aiUseContext) {
            return List.of();
        }
        boolean byCount = cfg.contextMode != TEConfig.ContextMode.TIME;
        long cutoff = System.currentTimeMillis() - (long) cfg.contextMinutes * 60_000L;
        List<Entry> picked = new ArrayList<>();
        synchronized (LOCK) {
            Iterator<Entry> it = LOG.descendingIterator();
            boolean skippedSelf = false;
            while (it.hasNext()) {
                Entry e = it.next();
                if (!skippedSelf && excludingText != null && e.text.equals(excludingText.strip())) {
                    skippedSelf = true; // drop only the most-recent identical line (this message)
                    continue;
                }
                if (!e.included()) {
                    continue;
                }
                if (byCount) {
                    if (picked.size() >= cfg.contextCount) {
                        break;
                    }
                } else if (e.time < cutoff) {
                    break;
                }
                picked.add(e);
            }
        }
        Collections.reverse(picked);
        List<String> out = new ArrayList<>(picked.size());
        for (Entry e : picked) {
            out.add((e.origin == Origin.OUTGOING ? "Me: " : "") + e.text);
        }
        return out;
    }

    /** Convenience: record {@code text} then return the context window before it (single expression for callers). */
    public static List<String> recordAndWindow(String text, Origin origin) {
        add(text, origin);
        return window(text);
    }

    /** Recent entries, newest first, for the context screen. */
    public static List<Entry> recent(int max) {
        List<Entry> out = new ArrayList<>();
        synchronized (LOCK) {
            Iterator<Entry> it = LOG.descendingIterator();
            while (it.hasNext() && out.size() < max) {
                out.add(it.next());
            }
        }
        return out;
    }

    public static void clear() {
        synchronized (LOCK) {
            LOG.clear();
            BY_ID.clear();
            last = null;
        }
    }

    /** Drops the {@code n} most recent remembered messages. */
    public static void clearRecent(int n) {
        synchronized (LOCK) {
            for (int i = 0; i < n && !LOG.isEmpty(); i++) {
                LOG.removeLast();
            }
        }
    }

    // ------------------------------------------------------------------ translated-message registry (for follow-up)

    /** A completed translation, remembered so any chat line (by id) or the follow-up key can target it. */
    public record Translated(int id, String original, String translation, List<String> context, boolean outgoing) {
    }

    private static final java.util.Map<Integer, Translated> BY_ID = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_IDS = 256;
    private static volatile Translated last;

    /** Registers a translated line and returns a stable id used by the in-chat "ask AI" click. */
    public static int registerTranslated(String original, String translation, boolean outgoing) {
        int id = SEQ.incrementAndGet();
        Translated t = new Translated(id, original == null ? "" : original.strip(),
                translation == null ? "" : translation.strip(), window(original), outgoing);
        BY_ID.put(id, t);
        last = t;
        if (BY_ID.size() > MAX_IDS) {
            BY_ID.keySet().stream().sorted().limit(BY_ID.size() - MAX_IDS).forEach(BY_ID::remove);
        }
        return id;
    }

    public static Translated last() {
        return last;
    }

    public static Translated byId(int id) {
        return BY_ID.get(id);
    }
}
