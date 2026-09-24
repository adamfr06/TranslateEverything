package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.hud.BoxManager;
import dev.adamfr06.translateeverything.gui.widget.SettingsList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Searchable session history with complete, wrapped originals and translations. */
public class HistoryScreen extends Screen {
    private final Screen parent;
    private SettingsList list;
    private String query = "";
    private String status = "";
    public HistoryScreen(Screen parent) { super(Text.literal("Translation history")); this.parent = parent; }
    @Override protected void init() {
        TextFieldWidget search = new TextFieldWidget(textRenderer,width/2-155,30,310,20,Text.literal("Search history"));
        search.setMaxLength(256); search.setText(query);
        search.setChangedListener(value -> { query=value; rebuildList(); });
        addDrawableChild(search);
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear history"),b->{ BoxManager.clearHistory(); rebuildList(); }).dimensions(width/2-155,height-28,150,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"),b->close()).dimensions(width/2+5,height-28,150,20).build());
        rebuildList();
    }
    private void rebuildList() {
        if(list!=null) remove(list);
        list=addDrawableChild(new SettingsList(client,width,height,56,height-44));
        String needle=query.toLowerCase(Locale.ROOT);
        int count=0;
        for (BoxManager.HistoryEntry entry:BoxManager.history()) {
            if (!(entry.original()+" "+entry.translated()+" "+entry.title()).toLowerCase(Locale.ROOT).contains(needle)) continue;
            count++;
            list.section(new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(entry.time()))+" · "+entry.type().label);
            list.note(entry.translated());
            list.pair(ButtonWidget.builder(Text.literal("Copy translation"),b->{ client.keyboard.setClipboard(entry.translated()); status="Translation copied"; }).dimensions(0,0,150,20).build(),
                    ButtonWidget.builder(Text.literal("Copy original"),b->{ client.keyboard.setClipboard(entry.original()); status="Original copied"; }).dimensions(0,0,150,20).build());
            list.note(entry.original());
        }
        if(count==0) list.note(needle.isBlank()?"No translations yet":"No matching translations");
    }
    @Override public void render(DrawContext graphics,int mx,int my,float delta) {
        super.render(graphics,mx,my,delta);
        graphics.drawCenteredTextWithShadow(textRenderer,title,width/2,10,0xFFFFFFFF);
        if(!status.isBlank()) graphics.drawCenteredTextWithShadow(textRenderer,status,width/2,height-40,0xFF80D080);
    }
    @Override public void close(){ client.setScreen(parent); }
    @Override public boolean shouldPause(){ return false; }
}
