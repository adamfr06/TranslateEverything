package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.hud.BoxManager;
import dev.adamfr06.translateeverything.hud.TranslationBox;
import dev.adamfr06.translateeverything.gui.widget.SettingsList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Card management uses the same native list and actions as history and settings. */
public class OverlayScreen extends Screen {
    private SettingsList list;
    private String signature="", status="";
    public OverlayScreen(){ super(Component.literal("Translation cards")); }
    @Override protected void init(){
        BoxManager.setFreezeExpiry(true);
        int x=width/2-155;
        addRenderableWidget(Button.builder(Component.literal("Restore hidden"),b->{BoxManager.restoreHidden();rebuildList();}).bounds(x,height-54,150,20).build());
        addRenderableWidget(Button.builder(Component.literal("Dismiss all"),b->{BoxManager.clearAll();rebuildList();}).bounds(x+160,height-54,150,20).build());
        addRenderableWidget(Button.builder(Component.literal("History"),b->minecraft.setScreenAndShow(new HistoryScreen(this))).bounds(x,height-28,100,20).build());
        addRenderableWidget(Button.builder(Component.literal("Layout"),b->{ConfigScreen screen=new ConfigScreen(this);screen.openTab(5);minecraft.setScreenAndShow(screen);}).bounds(x+105,height-28,100,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x+210,height-28,100,20).build());
        rebuildList();
    }
    private void rebuildList(){
        if(list!=null) removeWidget(list);
        list=addRenderableWidget(new SettingsList(minecraft,width,height,32,height-64));
        var boxes=BoxManager.activeBoxes();
        if(boxes.isEmpty()) list.note("No active translations");
        for(TranslationBox box:boxes){
            list.section(box.type.label);
            list.note(switch(box.state){case READY -> box.translated;case PENDING -> "Translating…";case ERROR -> "Translation unavailable";});
            list.pair(Button.builder(Component.literal(box.pinned?"Unpin":"Pin"),b->{BoxManager.togglePin(box);rebuildList();}).bounds(0,0,150,20).build(),
                    Button.builder(Component.literal("Dismiss"),b->{BoxManager.dismiss(box);rebuildList();}).bounds(0,0,150,20).build());
            list.pair(Button.builder(Component.literal("Copy translation"),b->{if(box.state==TranslationBox.State.READY){minecraft.keyboardHandler.setClipboard(box.translated);status="Translation copied";}}).bounds(0,0,150,20).build(),
                    Button.builder(Component.literal("Retry"),b->{BoxManager.retranslate(box);rebuildList();}).bounds(0,0,150,20).build());
            list.note(box.original);
        }
    }
    @Override public void tick(){
        String next=BoxManager.activeBoxes().stream().map(b->b.id+":"+b.state+":"+b.translated).collect(java.util.stream.Collectors.joining("|"));
        if(!next.equals(signature)){signature=next;rebuildList();}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mx,int my,float delta){
        super.extractRenderState(graphics,mx,my,delta);
        graphics.centeredText(font,status.isBlank()?title:Component.literal(status),width/2,10,0xFFFFFFFF);
    }
    @Override public void removed(){BoxManager.setFreezeExpiry(false);super.removed();}
    @Override public boolean isPauseScreen(){return false;}
}
