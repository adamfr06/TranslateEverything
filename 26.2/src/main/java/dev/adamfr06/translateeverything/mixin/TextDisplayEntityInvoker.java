package dev.adamfr06.translateeverything.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayEntityInvoker {
    @Invoker("getText")
    Component translateeverything$getText();
}
