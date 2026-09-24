package dev.adamfr06.translateeverything.mixin;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
    @Accessor("messages")
    String[] translateeverything$getMessages();

    @Accessor("sign")
    SignBlockEntity translateeverything$getBlockEntity();
}
