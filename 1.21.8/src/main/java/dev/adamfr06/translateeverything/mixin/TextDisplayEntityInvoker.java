package dev.adamfr06.translateeverything.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.TextDisplayEntity.class)
public interface TextDisplayEntityInvoker {
    @Invoker("getText")
    Text translateeverything$getText();
}
