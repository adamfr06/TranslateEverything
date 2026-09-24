package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit amount and live counts for both cache eviction policies. */
public class CacheScreen extends Screen {
    private final Screen parent;
    private EditBox amount;
    private Button recent, oldest, all;
    private String result = "";
    public CacheScreen(Screen parent) { super(Component.literal("Translation cache")); this.parent = parent; }
    @Override protected void init() {
        int x = width / 2 - 155, y = height / 2 - 24;
        amount = new EditBox(font, x + 160, y, 150, 20, Component.literal("Translations to remove"));
        amount.setMaxLength(6);
        amount.setValue(String.valueOf(Math.min(20, TranslationService.cacheSize())));
        addRenderableWidget(amount);
        recent = addRenderableWidget(Button.builder(Component.literal("Remove recent"), b -> remove(true)).bounds(x,y+28,150,20).build());
        oldest = addRenderableWidget(Button.builder(Component.literal("Remove least used"), b -> remove(false)).bounds(x+160,y+28,150,20).build());
        all = addRenderableWidget(Button.builder(Component.literal("Clear cache"), b -> {
            int count = TranslationService.cacheSize(); TranslationService.clearCache(); result = "Removed " + count + " translations";
        }).bounds(x,height-28,150,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x+160,height-28,150,20).build());
        tick();
    }
    private int count() { try { return Math.min(TranslationService.cacheSize(), Math.max(0,Integer.parseInt(amount.getValue()))); } catch (NumberFormatException e) { return 0; } }
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
        all.setMessage(Component.literal("Clear " + total + " translations"));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mx,int my,float delta) {
        super.extractRenderState(graphics,mx,my,delta);
        graphics.centeredText(font,title,width/2,20,0xFFFFFFFF);
        int y = height/2-24;
        graphics.centeredText(font,TranslationService.cacheSize() + " translations stored",width/2,y-30,0xFFFFFFFF);
        graphics.text(font,"Amount to remove",width/2-155,y+6,0xFFFFFFFF,false);
        if (!result.isBlank()) graphics.centeredText(font,result,width/2,y+58,0xFF80D080);
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
}
