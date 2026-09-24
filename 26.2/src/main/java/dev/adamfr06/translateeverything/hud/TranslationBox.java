package dev.adamfr06.translateeverything.hud;

import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.config.TEConfig;

/** One on-screen translation card. */
public class TranslationBox {
    public enum State {
        PENDING, READY, ERROR
    }

    private static long nextId = 1;

    public final long id;
    public final SourceType type;
    /** Header line, e.g. "Sign · 120 64 -33" or "Book · Page 2/7". */
    public final String title;
    public final String original;
    /** Identity used for dedupe / dismiss memory. */
    public final String key;
    /** Manual translations bypass same-language suppression. */
    public final boolean forced;

    /** Force a specific engine for this box (e.g. retry-with-AI); null = configured default. */
    public String targetLanguage = "";
    public dev.adamfr06.translateeverything.config.TEConfig.Engine engineOverride = null;

    public State state = State.PENDING;
    public int requestRevision;
    public String translated = "";
    public String detectedLanguage = "";
    public String errorMessage = "";

    public final long createdAt;
    /** Wall time when this box disappears; 0 = sticky. */
    public long expiresAt;
    /** For contextual boxes: last tick the producer confirmed the context still exists. */
    public long lastSeenAt;
    /** Set once the context vanished and the linger countdown started; 0 while alive. */
    public long contextGoneAt;
    public boolean pinned;

    public TranslationBox(SourceType type, String title, String original, String key, boolean forced) {
        this.id = nextId++;
        this.type = type;
        this.title = title;
        this.original = original;
        this.key = key;
        this.forced = forced;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    /** 0..1 opacity from fade-in/fade-out animation. */
    public float alpha(long now) {
        TEConfig cfg = TEConfig.get();
        if (!cfg.fadeAnimations) {
            return expired(now) ? 0f : 1f;
        }
        float in = Math.min(1f, (now - createdAt) / 150f);
        float out = 1f;
        if (!pinned && expiresAt > 0) {
            out = Math.max(0f, Math.min(1f, (expiresAt - now) / 250f));
        }
        return Math.min(in, out);
    }

    public boolean expired(long now) {
        return !pinned && expiresAt > 0 && now >= expiresAt;
    }
}
