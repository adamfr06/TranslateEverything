package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.hud.BoxManager;
import dev.adamfr06.translateeverything.hud.TranslationBox;
import dev.adamfr06.translateeverything.gui.widget.SettingsList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Card management uses the same native list and actions as history and settings. */
public class OverlayScreen extends Screen {
    private SettingsList list;
    private String signature="", status="";
    public OverlayScreen(){ super(Text.literal("Translation cards")); }
    @Override protected void init(){
        BoxManager.setFreezeExpiry(true);
        int x=width/2-155;
        addDrawableChild(ButtonWidget.builder(Text.literal("Restore hidden"),b->{BoxManager.restoreHidden();rebuildList();}).dimensions(x,height-54,150,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Dismiss all"),b->{BoxManager.clearAll();rebuildList();}).dimensions(x+160,height-54,150,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("History"),b->client.setScreen(new HistoryScreen(this))).dimensions(x,height-28,100,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Layout"),b->{ConfigScreen screen=new ConfigScreen(this);screen.openTab(5);client.setScreen(screen);}).dimensions(x+105,height-28,100,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"),b->close()).dimensions(x+210,height-28,100,20).build());
        rebuildList();
    }
    private void rebuildList(){
        if(list!=null) remove(list);
        list=addDrawableChild(new SettingsList(client,width,height,32,height-64));
        var boxes=BoxManager.activeBoxes();
        if(boxes.isEmpty()) list.note("No active translations");
        for(TranslationBox box:boxes){
            list.section(box.type.label);
            list.note(switch(box.state){case READY -> box.translated;case PENDING -> "Translating…";case ERROR -> "Translation unavailable";});
            list.pair(ButtonWidget.builder(Text.literal(box.pinned?"Unpin":"Pin"),b->{BoxManager.togglePin(box);rebuildList();}).dimensions(0,0,150,20).build(),
                    ButtonWidget.builder(Text.literal("Dismiss"),b->{BoxManager.dismiss(box);rebuildList();}).dimensions(0,0,150,20).build());
            list.pair(ButtonWidget.builder(Text.literal("Copy translation"),b->{if(box.state==TranslationBox.State.READY){client.keyboard.setClipboard(box.translated);status="Translation copied";}}).dimensions(0,0,150,20).build(),
                    ButtonWidget.builder(Text.literal("Retry"),b->{BoxManager.retranslate(box);rebuildList();}).dimensions(0,0,150,20).build());
            list.note(box.original);
        }
    }
    @Override public void tick(){
        String next=BoxManager.activeBoxes().stream().map(b->b.id+":"+b.state+":"+b.translated).collect(java.util.stream.Collectors.joining("|"));
        if(!next.equals(signature)){signature=next;rebuildList();}
    }
    @Override public void render(DrawContext graphics,int mx,int my,float delta){
        super.render(graphics,mx,my,delta);
        graphics.drawCenteredTextWithShadow(textRenderer,status.isBlank()?title:Text.literal(status),width/2,10,0xFFFFFFFF);
    }
    @Override public void removed(){BoxManager.setFreezeExpiry(false);super.removed();}
    @Override public boolean shouldPause(){return false;}
}
