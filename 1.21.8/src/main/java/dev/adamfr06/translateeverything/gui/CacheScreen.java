package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Explicit amount and live counts for both cache eviction policies. */
public class CacheScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget amount;
    private ButtonWidget recent, oldest, all;
    private String result = "";
    public CacheScreen(Screen parent) { super(Text.literal("Translation cache")); this.parent = parent; }
    @Override protected void init() {
        int x = width / 2 - 155, y = height / 2 - 24;
        amount = new TextFieldWidget(textRenderer, x + 160, y, 150, 20, Text.literal("Translations to remove"));
        amount.setMaxLength(6);
        amount.setText(String.valueOf(Math.min(20, TranslationService.cacheSize())));
        addDrawableChild(amount);
        recent = addDrawableChild(ButtonWidget.builder(Text.literal("Remove recent"), b -> remove(true)).dimensions(x,y+28,150,20).build());
        oldest = addDrawableChild(ButtonWidget.builder(Text.literal("Remove least used"), b -> remove(false)).dimensions(x+160,y+28,150,20).build());
        all = addDrawableChild(ButtonWidget.builder(Text.literal("Clear cache"), b -> {
            int count = TranslationService.cacheSize(); TranslationService.clearCache(); result = "Removed " + count + " translations";
        }).dimensions(x,height-28,150,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x+160,height-28,150,20).build());
        tick();
    }
    private int count() { try { return Math.min(TranslationService.cacheSize(), Math.max(0,Integer.parseInt(amount.getText()))); } catch (NumberFormatException e) { return 0; } }
    private void remove(boolean newest) {
        int removed = TranslationService.evictCache(count(), newest);
        result = "Removed " + removed + " translations";
        tick();
    }
    @Override public void tick() {
        if (recent == null) return;
        int n = count(), total = TranslationService.cacheSize();
        recent.active = oldest.active = n > 0;
        all.active = total > 0;
        all.setMessage(Text.literal("Clear " + total + " translations"));
    }
    @Override public void render(DrawContext graphics,int mx,int my,float delta) {
        super.render(graphics,mx,my,delta);
        graphics.drawCenteredTextWithShadow(textRenderer,title,width/2,20,0xFFFFFFFF);
        int y = height/2-24;
        graphics.drawCenteredTextWithShadow(textRenderer,TranslationService.cacheSize() + " translations stored",width/2,y-30,0xFFFFFFFF);
        graphics.drawText(textRenderer,"Amount to remove",width/2-155,y+6,0xFFFFFFFF,false);
        if (!result.isBlank()) graphics.drawCenteredTextWithShadow(textRenderer,result,width/2,y+58,0xFF80D080);
    }
    @Override public void close() { client.setScreen(parent); }
}
