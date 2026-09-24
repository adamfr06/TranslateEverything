package dev.adamfr06.translateeverything.mixin;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(BookViewScreen.class)
public interface BookScreenAccessor {
    @Accessor("bookAccess")
    BookViewScreen.BookAccess translateeverything$getContents();

    @Accessor("currentPage")
    int translateeverything$getPageIndex();

    @Accessor("cachedPageComponents")
    void translateeverything$setCachedPage(List<FormattedCharSequence> lines);

    @Accessor("cachedPage")
    void translateeverything$setCachedPageIndex(int index);
}
