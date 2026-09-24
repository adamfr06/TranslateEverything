package dev.adamfr06.translateeverything.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** The scrolling body of a settings tab. */
public class SettingsList extends ElementListWidget<SettingsList.Row> {
    /** Matches the vanilla options screens: two 150-wide controls with a gap. */
    public static final int CONTENT_WIDTH = 310;
    public static final int CONTROL_WIDTH = 150;
    private static final int GAP = 10;
    private static final int ROW_H = 25;

    private static final int LABEL = 0xFFBFC5CC;
    private static final int SECTION = 0xFFFFFFFF;
    private static final int NOTE = 0xFFE0E0E0;
    private static final int RULE = 0x40FFFFFF;

    public SettingsList(MinecraftClient mc, int width, int height, int top, int bottom) {
        this(mc, width, height, top, bottom, false);
    }

    public SettingsList(MinecraftClient mc, int width, int height, int top, int bottom, boolean compact) {
        super(mc, width, bottom - top, top, compact ? 22 : ROW_H);
    }

    @Override
    public int getRowWidth() {
        return CONTENT_WIDTH;
    }

    @Override
    protected int getScrollbarX() {
        return width / 2 + CONTENT_WIDTH / 2 + 10;
    }

    /** Section heading with a rule under it. */
    public void section(String title) {
        addEntry(new SectionRow(client, title));
    }

    /** A named control: label on the left, widget on the right. */
    public void row(String label, ClickableWidget control) {
        addEntry(new LabelledRow(client, label, control));
    }

    /** Two self-describing controls side by side, as the vanilla options screens do. */
    public void pair(ClickableWidget left, ClickableWidget right) {
        addEntry(new PairRow(left, right));
    }

    /** One control spanning the full content width. */
    public void wide(ClickableWidget control) {
        addEntry(new PairRow(control, null));
    }

    /**
     * A small control on the left with a line of text filling the rest, for lists of things rather
     * than lists of settings, the chat lines the translator may use.
     */
    public void entry(ClickableWidget control, String text, java.util.function.BooleanSupplier live) {
        var lines = client.textRenderer.wrapLines(Text.literal(text), CONTENT_WIDTH - 62);
        for (int i = 0; i < lines.size(); i += 2) {
            addEntry(new EntryRow(client, i == 0 ? control : null, lines.subList(i, Math.min(i + 2, lines.size())), live));
        }
    }

    /** A short explanation, wrapped to the content width. */
    public void note(String text) { note(Text.literal(text)); }

    public void note(Text text) {
        // Chunked two lines to a row, because this list widget cannot vary row heights.
        List<OrderedText> lines = client.textRenderer.wrapLines(text, CONTENT_WIDTH);
        for (int i = 0; i < lines.size(); i += 2) {
            addEntry(new NoteRow(client, lines.subList(i, Math.min(lines.size(), i + 2))));
        }
    }

    /** A line of status text the screen updates, e.g. a test result. */
    public StatusRow status() {
        StatusRow row = new StatusRow(client);
        addEntry(row);
        return row;
    }

    // ------------------------------------------------------------------ rows

    public abstract static class Row extends ElementListWidget.Entry<Row> {
        @Override
        public List<? extends Element> children() {
            return List.of();
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of();
        }
    }

    private static final class SectionRow extends Row {
        private final MinecraftClient mc;
        private final String title;

        SectionRow(MinecraftClient mc, String title) {
            this.mc = mc;
            this.title = title;
        }

        @Override
        public void render(DrawContext c, int index, int y, int x, int w, int h,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            int ty = y + h - 11;
            c.drawText(mc.textRenderer, title, x, ty, SECTION, false);
            c.fill(x + mc.textRenderer.getWidth(title) + 6, ty + 4, x + CONTENT_WIDTH, ty + 5, RULE);
        }
    }

    private static final class LabelledRow extends Row {
        private final MinecraftClient mc;
        private final String label;
        private final ClickableWidget control;

        LabelledRow(MinecraftClient mc, String label, ClickableWidget control) {
            this.mc = mc;
            this.label = label;
            this.control = control;
        }

        @Override
        public void render(DrawContext c, int index, int y, int x, int w, int h,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            control.setWidth(CONTROL_WIDTH);
            control.setX(x + CONTENT_WIDTH - CONTROL_WIDTH);
            control.setY(y + (h - control.getHeight()) / 2);
            c.drawText(mc.textRenderer,
                    mc.textRenderer.trimToWidth(label, CONTENT_WIDTH - CONTROL_WIDTH - GAP),
                    x, y + (h - 8) / 2, LABEL, false);
            control.render(c, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(control);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(control);
        }
    }

    private static final class PairRow extends Row {
        private final ClickableWidget left;
        private final ClickableWidget right;

        PairRow(ClickableWidget left, ClickableWidget right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void render(DrawContext c, int index, int y, int x, int w, int h,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            if (right == null) {
                left.setWidth(CONTENT_WIDTH);
                left.setX(x);
                left.setY(y + (h - left.getHeight()) / 2);
            } else {
                int half = (CONTENT_WIDTH - GAP) / 2;
                left.setWidth(half);
                left.setX(x);
                left.setY(y + (h - left.getHeight()) / 2);
                right.setWidth(half);
                right.setX(x + half + GAP);
                right.setY(y + (h - right.getHeight()) / 2);
            }
            left.render(c, mouseX, mouseY, delta);
            if (right != null) {
                right.render(c, mouseX, mouseY, delta);
            }
        }

        @Override
        public List<? extends Element> children() {
            return right == null ? List.of(left) : List.of(left, right);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return right == null ? List.of(left) : List.of(left, right);
        }
    }

    private static final class EntryRow extends Row {
        private static final int CONTROL = 52;
        private final MinecraftClient mc;
        private final ClickableWidget control;
        private final List<OrderedText> text;
        private final java.util.function.BooleanSupplier live;

        EntryRow(MinecraftClient mc, ClickableWidget control, List<OrderedText> text,
                 java.util.function.BooleanSupplier live) {
            this.mc = mc;
            this.control = control;
            this.text = text;
            this.live = live;
        }

        @Override
        public void render(DrawContext c, int index, int y, int x, int w, int h,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            int left = x, top = y;
            if (control != null) {
                control.setWidth(CONTROL); control.setX(left); control.setY(top + 2);
                control.render(c, mouseX, mouseY, delta);
            }
            // Continuation rows carry on the 10px rhythm of the lines above them.
            int rowY = control != null ? top + 2 + (20 - (text.size() * 10 - 2)) / 2 : top + 3 - (h + 4 - 20);
            for (var line : text) {
                c.drawText(mc.textRenderer, line, left + CONTROL + GAP, rowY, live.getAsBoolean() ? 0xFFE7EDF2 : 0xFF79818A, false);
                rowY += 10;
            }
        }

        @Override
        public List<? extends Element> children() {
            return control == null ? List.of() : List.of(control);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return control == null ? List.of() : List.of(control);
        }
    }

    private static final class NoteRow extends Row {
        private final MinecraftClient mc;
        private final List<OrderedText> lines;

        NoteRow(MinecraftClient mc, List<OrderedText> lines) {
            this.mc = mc;
            this.lines = new ArrayList<>(lines);
        }

        @Override
        public void render(DrawContext c, int index, int y, int x, int w, int h,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            int ty = y + 2; // top-aligned so consecutive note rows read as one paragraph
            for (OrderedText line : lines) {
                c.drawText(mc.textRenderer, line, x, ty, NOTE, false);
                ty += 10;
            }
        }
    }

    /** One line of text the screen writes into, e.g. the result of a connection test. */
    public static final class StatusRow extends Row {
        private final MinecraftClient mc;
        private String text = "";
        private int color = NOTE;

        StatusRow(MinecraftClient mc) {
            this.mc = mc;
        }

        public void set(String text, int color) {
            this.text = text == null ? "" : text;
            this.color = color;
        }

        @Override
        public void render(DrawContext c, int index, int y, int x, int w, int h,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            if (text.isEmpty()) {
                return;
            }
            c.drawText(mc.textRenderer, mc.textRenderer.trimToWidth(text, CONTENT_WIDTH),
                    x, y + (h - 8) / 2, color, false);
        }
    }
}
