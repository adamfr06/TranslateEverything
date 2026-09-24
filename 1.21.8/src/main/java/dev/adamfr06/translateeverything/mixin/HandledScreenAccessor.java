package dev.adamfr06.translateeverything.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("focusedSlot")
    Slot translateeverything$getFocusedSlot();

    @Accessor("x")
    int translateeverything$getX();

    @Accessor("y")
    int translateeverything$getY();
}
