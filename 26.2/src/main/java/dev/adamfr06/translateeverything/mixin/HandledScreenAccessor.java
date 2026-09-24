package dev.adamfr06.translateeverything.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
    @Accessor("hoveredSlot")
    Slot translateeverything$getFocusedSlot();

    @Accessor("leftPos")
    int translateeverything$getX();

    @Accessor("topPos")
    int translateeverything$getY();
}
