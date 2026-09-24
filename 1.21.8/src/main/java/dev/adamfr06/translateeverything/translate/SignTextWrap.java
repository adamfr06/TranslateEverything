package dev.adamfr06.translateeverything.translate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Greedy pixel-aware word wrap for fixed-width text surfaces (sign faces). */
public final class SignTextWrap {
    private SignTextWrap() {
    }

    /**
     * @return up to {@code maxLines} lines each within {@code maxWidthPx}, or {@code null} if the
     * text cannot be made to fit.
     */
    /**
     * Wraps without a line limit, so the caller can see how many lines the text really needs rather
     * than only that it did not fit.
     */
    public static List<String> wrapAll(String text, int maxWidthPx, ToIntFunction<String> widthOf) {
        List<String> all = wrap(text, maxWidthPx, Integer.MAX_VALUE, widthOf);
        return all == null ? List.of() : all;
    }

    public static List<String> wrap(String text, int maxWidthPx, int maxLines, ToIntFunction<String> widthOf) {
        if (maxWidthPx <= 0 || maxLines <= 0) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (widthOf.applyAsInt(word) > maxWidthPx) {
                // Word doesn't fit a whole line, flush, then hard-split it.
                if (cur.length() > 0) {
                    lines.add(cur.toString());
                    cur.setLength(0);
                }
                StringBuilder chunk = new StringBuilder();
                for (int i = 0; i < word.length();) {
                    int cp = word.codePointAt(i);
                    String c = new String(Character.toChars(cp));
                    i += Character.charCount(cp);
                    if (chunk.length() > 0 && widthOf.applyAsInt(chunk.toString() + c) > maxWidthPx) {
                        lines.add(chunk.toString());
                        chunk.setLength(0);
                        if (lines.size() > maxLines) {
                            return null;
                        }
                    }
                    chunk.append(c);
                }
                cur.append(chunk); // last chunk continues the current line
                continue;
            }
            String candidate = cur.length() == 0 ? word : cur + " " + word;
            if (widthOf.applyAsInt(candidate) <= maxWidthPx) {
                cur.setLength(0);
                cur.append(candidate);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
                if (lines.size() > maxLines) {
                    return null;
                }
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        return lines.size() <= maxLines ? lines : null;
    }
}
