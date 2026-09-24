package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.hud.BoxManager;
import dev.adamfr06.translateeverything.gui.widget.SettingsList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Searchable session history with complete, wrapped originals and translations. */
public class HistoryScreen extends Screen {
    private final Screen parent;
    private SettingsList list;
    private String query = "";
    private String status = "";
    public HistoryScreen(Screen parent) { super(Component.literal("Translation history")); this.parent = parent; }
    @Override protected void init() {
        EditBox search = new EditBox(font,width/2-155,30,310,20,Component.literal("Search history"));
        search.setMaxLength(256); search.setValue(query);
        search.setResponder(value -> { query=value; rebuildList(); });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Clear history"),b->{ BoxManager.clearHistory(); rebuildList(); }).bounds(width/2-155,height-28,150,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(width/2+5,height-28,150,20).build());
        rebuildList();
    }
    private void rebuildList() {
        if(list!=null) removeWidget(list);
        list=addRenderableWidget(new SettingsList(minecraft,width,height,56,height-44));
        String needle=query.toLowerCase(Locale.ROOT);
        int count=0;
        for (BoxManager.HistoryEntry entry:BoxManager.history()) {
            if (!(entry.original()+" "+entry.translated()+" "+entry.title()).toLowerCase(Locale.ROOT).contains(needle)) continue;
            count++;
            list.section(new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(entry.time()))+" · "+entry.type().label);
            list.note(entry.translated());
            list.pair(Button.builder(Component.literal("Copy translation"),b->{ minecraft.keyboardHandler.setClipboard(entry.translated()); status="Translation copied"; }).bounds(0,0,150,20).build(),
                    Button.builder(Component.literal("Copy original"),b->{ minecraft.keyboardHandler.setClipboard(entry.original()); status="Original copied"; }).bounds(0,0,150,20).build());
            list.note(entry.original());
        }
        if(count==0) list.note(needle.isBlank()?"No translations yet":"No matching translations");
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mx,int my,float delta) {
        super.extractRenderState(graphics,mx,my,delta);
        graphics.centeredText(font,title,width/2,10,0xFFFFFFFF);
        if(!status.isBlank()) graphics.centeredText(font,status,width/2,height-40,0xFF80D080);
    }
    @Override public void onClose(){ minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen(){ return false; }
}
