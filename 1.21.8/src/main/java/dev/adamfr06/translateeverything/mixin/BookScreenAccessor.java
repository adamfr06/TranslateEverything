package dev.adamfr06.translateeverything.mixin;

import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(BookScreen.class)
public interface BookScreenAccessor {
    @Accessor("contents")
    BookScreen.Contents translateeverything$getContents();

    @Accessor("pageIndex")
    int translateeverything$getPageIndex();

    @Accessor("cachedPage")
    void translateeverything$setCachedPage(List<OrderedText> lines);

    @Accessor("cachedPageIndex")
    void translateeverything$setCachedPageIndex(int index);
}
