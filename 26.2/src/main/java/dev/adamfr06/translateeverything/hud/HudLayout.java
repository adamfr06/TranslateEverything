package dev.adamfr06.translateeverything.hud;

import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.config.TEConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared geometry + painting for translation cards. */
public final class HudLayout {
    public static final int GAP = 4;
    public static final int PADDING = 4;
    public static final int ACCENT_WIDTH = 2;
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_ORIGINAL_LINES = 6;

    public record Placed(TranslationBox box, int x, int y, int width, int height, int fullHeight) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        public boolean clipped() {
            return fullHeight > height;
        }

        public int maxScroll() {
            return Math.max(0, fullHeight - height);
        }
    }

    public record Layout(List<Placed> placed, int hiddenCount, int overflowX, int overflowY) {
    }

    private HudLayout() {
    }

    // ------------------------------------------------------------------ geometry

    /**
     * @param screenWidth/height already divided by {@code textScale}.
     */
    public static Layout compute(Minecraft client, int screenWidth, int screenHeight) {
        return compute(client, screenWidth, screenHeight, BoxManager.activeBoxes(), (int) (screenHeight * 0.45));
    }

    public static Layout compute(Minecraft client, int screenWidth, int screenHeight,
                                 List<TranslationBox> active, int maxBoxHeight) {
        return compute(client, screenWidth, screenHeight, active, maxBoxHeight, TEConfig.get().maxVisibleBoxes);
    }

    public static Layout compute(Minecraft client, int screenWidth, int screenHeight,
                                 List<TranslationBox> active, int maxBoxHeight, int maxVisible) {
        TEConfig cfg = TEConfig.get();
        List<Placed> placed = new ArrayList<>();
        int[] overflow = layoutStack(client, placed, active, screenWidth, screenHeight,
                cfg.hudAnchor, cfg.hudOffsetX, cfg.hudOffsetY, maxBoxHeight, maxVisible);
        return new Layout(placed, overflow[2], overflow[0], overflow[1]);
    }

    /** Lays out one stack; returns {overflowX, overflowY, hiddenCount}. */
    private static int[] layoutStack(Minecraft client, List<Placed> out, List<TranslationBox> stack,
                                     int screenWidth, int screenHeight,
                                     TEConfig.HudAnchor anchor, int offsetX, int offsetY,
                                     int maxBoxHeight, int maxVisible) {
        TEConfig cfg = TEConfig.get();
        Font tr = client.font;
        int visibleCount = Math.min(stack.size(), maxVisible);
        int hidden = stack.size() - visibleCount;

        int width = cfg.boxWidth;
        boolean bottom = anchor.vy == 2;
        boolean middle = anchor.vy == 1;

        int[] fullHeights = new int[visibleCount];
        int[] heights = new int[visibleCount];
        int total = 0;
        for (int i = 0; i < visibleCount; i++) {
            fullHeights[i] = boxHeight(tr, stack.get(i), width);
            heights[i] = Math.min(fullHeights[i], Math.max(40, maxBoxHeight));
            total += heights[i] + (i > 0 ? GAP : 0);
        }

        int x = switch (anchor.hx) {
            case 0 -> offsetX;
            case 1 -> (screenWidth - width) / 2 + offsetX;
            default -> screenWidth - width - offsetX;
        };
        int y;
        if (middle) {
            y = (screenHeight - total) / 2 + offsetY;
        } else if (bottom) {
            y = screenHeight - offsetY;
        } else {
            y = offsetY;
        }

        // Newest box hugs the anchor edge; the stack grows away from it.
        for (int i = 0; i < visibleCount; i++) {
            int h = heights[i];
            if (bottom) {
                y -= h;
                out.add(new Placed(stack.get(i), x, y, width, h, fullHeights[i]));
                y -= GAP;
            } else {
                out.add(new Placed(stack.get(i), x, y, width, h, fullHeights[i]));
                y += h + GAP;
            }
        }

        int overflowY = bottom ? y - LINE_HEIGHT + GAP : y;
        return new int[]{x + ACCENT_WIDTH + PADDING, overflowY, hidden};
    }

    public static int boxHeight(Font tr, TranslationBox box, int width) {
        TEConfig cfg = TEConfig.get();
        int inner = innerWidth(width);
        int h = PADDING + (headerVisible(cfg) ? LINE_HEIGHT + 1 : 0);
        h += switch (box.state) {
            case PENDING, ERROR -> LINE_HEIGHT;
            case READY -> Math.max(1, tr.split(FormattedText.of(box.translated), inner).size()) * LINE_HEIGHT;
        };
        if (box.state == TranslationBox.State.ERROR) {
            h += LINE_HEIGHT; // retry hint line
        }
        if (cfg.showOriginalText && box.state == TranslationBox.State.READY) {
            h += 4 + Math.min(MAX_ORIGINAL_LINES, originalLines(tr, box, inner).size()) * LINE_HEIGHT;
        }
        return h + PADDING;
    }

    private static int innerWidth(int width) {
        return width - ACCENT_WIDTH - PADDING * 2 - 2;
    }

    private static boolean headerVisible(TEConfig cfg) {
        return cfg.showSourceLabel || cfg.showLanguageTag;
    }

    private static List<FormattedCharSequence> originalLines(Font tr, TranslationBox box, int inner) {
        return tr.split(Component.literal(box.original.replace('\n', ' ')).withStyle(ChatFormatting.ITALIC), inner);
    }

    // ------------------------------------------------------------------ painting

    /**
     * @param scroll content scroll offset for clipped cards (0 on the HUD)
     * @param clipHint true on the HUD: clipped cards get a "open [B]" hint instead of the overlay's
     * scrollbar
     */
    public static void drawBox(GuiGraphicsExtractor ctx, Font tr, Placed placed, float alpha,
                               boolean highlight, int scroll, boolean clipHint) {
        if (alpha <= 0.02f) {
            return;
        }
        TEConfig cfg = TEConfig.get();
        TranslationBox box = placed.box();
        int x = placed.x(), y = placed.y(), w = placed.width(), h = placed.height();
        int inner = innerWidth(w);
        int accent = box.type.color;
        boolean clipped = placed.clipped();
        int clampedScroll = clipped ? Math.min(scroll, placed.maxScroll()) : 0;

        float bgAlpha = (float) (alpha * cfg.backgroundOpacity);
        ctx.fill(x, y, x + w, y + h, argb(highlight ? Math.min(1f, bgAlpha + 0.2f) : bgAlpha, 0x101014));
        ctx.fill(x, y, x + ACCENT_WIDTH, y + h, argb(alpha, accent & 0xFFFFFF));
        ctx.outline(x, y, w, h, argb(alpha * (highlight ? 0.8f : 0.25f), 0xFFFFFF));

        int tx = x + ACCENT_WIDTH + PADDING;
        int cy = y + PADDING;
        int minY = y + 2;
        int maxY = y + h - 2;

        if (headerVisible(cfg)) {
            int rowY = cy - clampedScroll;
            if (rowY >= minY && rowY + LINE_HEIGHT <= maxY + 1) {
                int rightEdge = x + w - PADDING - (clipped ? 3 : 0);
                if (cfg.showLanguageTag) {
                    String tag = languageTag(cfg, box);
                    int tagWidth = tr.width(tag);
                    ctx.text(tr, tag, rightEdge - tagWidth, rowY, argb(alpha * 0.7f, 0xFFFFFF), true);
                    rightEdge -= tagWidth + 4;
                }
                if (box.pinned) {
                    ctx.fill(rightEdge - 4, rowY + 2, rightEdge, rowY + 6, argb(alpha, accent & 0xFFFFFF));
                    rightEdge -= 8;
                }
                int titleX = tx;
                if (cfg.showSourceLabel) {
                    String badge = box.type.label.toUpperCase(Locale.ROOT);
                    ctx.text(tr, badge, tx, rowY, argb(alpha, accent & 0xFFFFFF), true);
                    titleX += tr.width(badge) + 5;
                }
                String title = box.title;
                if (!title.isBlank() && titleX < rightEdge) {
                    title = tr.plainSubstrByWidth(title, rightEdge - titleX);
                    ctx.text(tr, title, titleX, rowY, argb(alpha * 0.65f, 0xFFFFFF), true);
                }
            }
            cy += LINE_HEIGHT + 1;
        }

        switch (box.state) {
            case PENDING -> {
                int dots = (int) ((System.currentTimeMillis() / 300) % 4);
                drawLine(ctx, tr, "Translating" + ".".repeat(dots), tx, cy, clampedScroll, minY, maxY, argb(alpha * 0.8f, 0xFFFFFF));
            }
            case ERROR -> {
                drawLine(ctx, tr, tr.plainSubstrByWidth("Couldn't translate: " + box.errorMessage, inner), tx, cy, clampedScroll, minY, maxY, argb(alpha, 0xFF6666));
                drawLine(ctx, tr, "Press the translate key to retry", tx, cy + LINE_HEIGHT, clampedScroll, minY, maxY, argb(alpha * 0.6f, 0xFFFFFF));
            }
            case READY -> {
                for (FormattedCharSequence line : tr.split(FormattedText.of(box.translated), inner)) {
                    drawLine(ctx, tr, line, tx, cy, clampedScroll, minY, maxY, argb(alpha, 0xFFFFFF));
                    cy += LINE_HEIGHT;
                }
                if (cfg.showOriginalText) {
                    int sepY = cy + 1 - clampedScroll;
                    if (sepY >= minY && sepY + 1 <= maxY) {
                        ctx.fill(tx, sepY, x + w - PADDING, sepY + 1, argb(alpha * 0.15f, 0xFFFFFF));
                    }
                    cy += 4;
                    List<FormattedCharSequence> lines = originalLines(tr, box, inner);
                    int shown = Math.min(MAX_ORIGINAL_LINES, lines.size());
                    for (int i = 0; i < shown; i++) {
                        drawLine(ctx, tr, lines.get(i), tx, cy, clampedScroll, minY, maxY, argb(alpha * 0.55f, 0xFFFFFF));
                        cy += LINE_HEIGHT;
                    }
                }
            }
        }

        if (clipped) {
            if (clipHint) {
                String hint = "… more in the card manager";
                int hw = tr.width(hint);
                ctx.fill(x + w - hw - 8, y + h - LINE_HEIGHT - 1, x + w - 1, y + h - 1, argb(alpha * 0.85f, 0x101014));
                ctx.text(tr, hint, x + w - hw - 5, y + h - LINE_HEIGHT + 1, argb(alpha * 0.6f, 0xFFFFFF), true);
            } else {
                // Scrollbar for the overlay screen.
                int track = h - 4;
                int thumb = Math.max(8, track * h / placed.fullHeight());
                int thumbY = y + 2 + (track - thumb) * clampedScroll / Math.max(1, placed.maxScroll());
                ctx.fill(x + w - 3, y + 2, x + w - 1, y + h - 2, argb(alpha * 0.2f, 0xFFFFFF));
                ctx.fill(x + w - 3, thumbY, x + w - 1, thumbY + thumb, argb(alpha * 0.7f, accent & 0xFFFFFF));
            }
        }
    }

    private static void drawLine(GuiGraphicsExtractor ctx, Font tr, String text,
                                 int x, int cy, int scroll, int minY, int maxY, int color) {
        int rowY = cy - scroll;
        if (rowY >= minY && rowY + LINE_HEIGHT <= maxY + 1 && (color >>> 24) > 3) {
            ctx.text(tr, text, x, rowY, color, true);
        }
    }

    private static void drawLine(GuiGraphicsExtractor ctx, Font tr, FormattedCharSequence text,
                                 int x, int cy, int scroll, int minY, int maxY, int color) {
        int rowY = cy - scroll;
        if (rowY >= minY && rowY + LINE_HEIGHT <= maxY + 1 && (color >>> 24) > 3) {
            ctx.text(tr, text, x, rowY, color, true);
        }
    }

    public static void drawOverflow(GuiGraphicsExtractor ctx, Font tr, Layout layout, float alpha) {
        if (layout.hiddenCount() > 0) {
            int color = argb(alpha * 0.55f, 0xFFFFFF);
            if ((color >>> 24) > 3) {
                ctx.text(tr, "+" + layout.hiddenCount() + " more…", layout.overflowX(), layout.overflowY(), color, true);
            }
        }
    }

    private static String languageTag(TEConfig cfg, TranslationBox box) {
        String target = (box.targetLanguage.isBlank() ? cfg.targetLanguage : box.targetLanguage).toUpperCase(Locale.ROOT);
        if (box.state != TranslationBox.State.READY || box.detectedLanguage.isBlank()) {
            return "→ " + target;
        }
        return box.detectedLanguage.toUpperCase(Locale.ROOT) + " → " + target;
    }

    public static int argb(float alpha, int rgb) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
