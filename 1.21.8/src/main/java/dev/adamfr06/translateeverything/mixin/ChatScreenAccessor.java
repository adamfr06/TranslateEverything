package dev.adamfr06.translateeverything.mixin;

import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Re-enters vanilla ChatScreen.sendMessage after async translation completes. */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
    @Invoker("sendMessage")
    void translateeverything$sendMessage(String chatText, boolean addToHistory);
}
