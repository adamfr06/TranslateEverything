package dev.adamfr06.translateeverything.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The scrolling body of a settings tab. */
public class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {
    /** Matches the vanilla options screens: two 150-wide controls with a gap. */
    public static final int CONTENT_WIDTH = 310;
    public static final int CONTROL_WIDTH = 150;
    private static final int GAP = 10;
    private static final int ROW_H = 25;
    private final int rowHeight;

    private static final int LABEL = 0xFFBFC5CC;
    private static final int SECTION = 0xFFFFFFFF;
    private static final int NOTE = 0xFFE0E0E0;
    private static final int RULE = 0x40FFFFFF;

    public SettingsList(Minecraft mc, int width, int height, int top, int bottom) {
        this(mc, width, height, top, bottom, false);
    }

    public SettingsList(Minecraft mc, int width, int height, int top, int bottom, boolean compact) {
        super(mc, width, bottom - top, top, compact ? 22 : ROW_H);
        rowHeight = compact ? 22 : ROW_H;
    }

    @Override
    public int getRowWidth() {
        return CONTENT_WIDTH;
    }

    /** Section heading with a rule under it. */
    public void section(String title) {
        addEntry(new SectionRow(minecraft, title), 22);
    }

    /** A named control: label on the left, widget on the right. */
    public void row(String label, AbstractWidget control) {
        addEntry(new LabelledRow(minecraft, label, control), rowHeight);
    }

    /** Two self-describing controls side by side, as the vanilla options screens do. */
    public void pair(AbstractWidget left, AbstractWidget right) {
        addEntry(new PairRow(left, right), rowHeight);
    }

    /** One control spanning the full content width. */
    public void wide(AbstractWidget control) {
        addEntry(new PairRow(control, null), rowHeight);
    }

    /** A short explanation, wrapped to the content width. */
    public void note(String text) { note(Component.literal(text)); }

    public void note(Component text) {
        List<net.minecraft.util.FormattedCharSequence> lines =
                minecraft.font.split(text, CONTENT_WIDTH);
        for (int i = 0; i < lines.size(); i += 2) {
            addEntry(new NoteRow(minecraft, lines.subList(i, Math.min(lines.size(), i + 2))), rowHeight);
        }
    }

    /**
     * A small control on the left with a line of text filling the rest, for lists of things rather
     * than lists of settings, the chat lines the translator may use.
     */
    public void entry(AbstractWidget control, String text, java.util.function.BooleanSupplier live) {
        var lines = minecraft.font.split(Component.literal(text), CONTENT_WIDTH - 62);
        for (int i = 0; i < lines.size(); i += 2) {
            addEntry(new EntryRow(minecraft, i == 0 ? control : null, lines.subList(i, Math.min(i + 2, lines.size())), live), rowHeight);
        }
    }

    /** A line of status text the screen updates, e.g. a test result. */
    public StatusRow status() {
        StatusRow row = new StatusRow(minecraft);
        addEntry(row, 20);
        return row;
    }

    // ------------------------------------------------------------------ rows

    public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private static final class SectionRow extends Row {
        private final Minecraft mc;
        private final String title;

        SectionRow(Minecraft mc, String title) {
            this.mc = mc;
            this.title = title;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            int y = getContentY() + getContentHeight() - 11;
            g.text(mc.font, title, getContentX(), y, SECTION, false);
            int textEnd = getContentX() + mc.font.width(title) + 6;
            g.fill(textEnd, y + 4, getContentX() + CONTENT_WIDTH, y + 5, RULE);
        }
    }

    private static final class LabelledRow extends Row {
        private final Minecraft mc;
        private final String label;
        private final AbstractWidget control;

        LabelledRow(Minecraft mc, String label, AbstractWidget control) {
            this.mc = mc;
            this.label = label;
            this.control = control;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            int right = getContentX() + CONTENT_WIDTH;
            control.setWidth(CONTROL_WIDTH);
            control.setX(right - CONTROL_WIDTH);
            control.setY(getContentY() + (getContentHeight() - control.getHeight()) / 2);
            g.text(mc.font, mc.font.plainSubstrByWidth(label, CONTENT_WIDTH - CONTROL_WIDTH - GAP),
                    getContentX(), getContentY() + (getContentHeight() - 8) / 2, LABEL, false);
            control.extractRenderState(g, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(control);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(control);
        }
    }

    private static final class PairRow extends Row {
        private final AbstractWidget left;
        private final AbstractWidget right;

        PairRow(AbstractWidget left, AbstractWidget right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX();
            int y = getContentY();
            if (right == null) {
                left.setWidth(CONTENT_WIDTH);
                left.setX(x);
                left.setY(y + (getContentHeight() - left.getHeight()) / 2);
            } else {
                int half = (CONTENT_WIDTH - GAP) / 2;
                left.setWidth(half);
                left.setX(x);
                left.setY(y + (getContentHeight() - left.getHeight()) / 2);
                right.setWidth(half);
                right.setX(x + half + GAP);
                right.setY(y + (getContentHeight() - right.getHeight()) / 2);
            }
            left.extractRenderState(g, mouseX, mouseY, delta);
            if (right != null) {
                right.extractRenderState(g, mouseX, mouseY, delta);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return right == null ? List.of(left) : List.of(left, right);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return right == null ? List.of(left) : List.of(left, right);
        }
    }

    private static final class EntryRow extends Row {
        private static final int CONTROL = 52;
        private final Minecraft mc;
        private final AbstractWidget control;
        private final List<net.minecraft.util.FormattedCharSequence> text;
        private final java.util.function.BooleanSupplier live;

        EntryRow(Minecraft mc, AbstractWidget control, List<net.minecraft.util.FormattedCharSequence> text, java.util.function.BooleanSupplier live) {
            this.mc = mc;
            this.control = control;
            this.text = text;
            this.live = live;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            int left = getContentX(), top = getContentY();
            if (control != null) {
                control.setWidth(CONTROL); control.setX(left); control.setY(top + 2);
                control.extractRenderState(g, mouseX, mouseY, delta);
            }
            // Continuation rows carry on the 10px rhythm of the lines above them.
            int rowY = control != null ? top + 2 + (20 - (text.size() * 10 - 2)) / 2 : top + 3 - (getHeight() - 20);
            for (var line : text) {
                g.text(mc.font, line, left + CONTROL + GAP, rowY, live.getAsBoolean() ? 0xFFE7EDF2 : 0xFF79818A, false);
                rowY += 10;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return control == null ? List.of() : List.of(control);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return control == null ? List.of() : List.of(control);
        }
    }

    private static final class NoteRow extends Row {
        private final Minecraft mc;
        private final List<net.minecraft.util.FormattedCharSequence> lines;

        NoteRow(Minecraft mc, List<net.minecraft.util.FormattedCharSequence> lines) {
            this.mc = mc;
            this.lines = new ArrayList<>(lines);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            int y = getContentY() + 2; // top-aligned so consecutive note rows read as one paragraph
            for (net.minecraft.util.FormattedCharSequence line : lines) {
                g.text(mc.font, line, getContentX(), y, NOTE, false);
                y += 10;
            }
        }
    }

    /** One line of text the screen writes into, e.g. the result of a connection test. */
    public static final class StatusRow extends Row {
        private final Minecraft mc;
        private String text = "";
        private int color = NOTE;

        StatusRow(Minecraft mc) {
            this.mc = mc;
        }

        public void set(String text, int color) {
            this.text = text == null ? "" : text;
            this.color = color;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
            if (text.isEmpty()) {
                return;
            }
            g.text(mc.font, mc.font.plainSubstrByWidth(text, CONTENT_WIDTH),
                    getContentX(), getContentY() + 5, color, false);
        }
    }
}
