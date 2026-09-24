package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.translate.ImmersionService;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/** Full immersion: signs render their translated text directly on the board. */
@Mixin(SignText.class)
public class SignTextMixin {
    @Inject(method = "getOrderedMessages", at = @At("HEAD"), cancellable = true)
    private void translateeverything$immersiveSign(boolean filtered, Function<Text, OrderedText> converter,
                                                   CallbackInfoReturnable<OrderedText[]> cir) {
        if (!ImmersionService.active(SourceType.SIGN)) {
            return;
        }
        SignText self = (SignText) (Object) this;
        StringBuilder sb = new StringBuilder();
        for (Text message : self.getMessages(filtered)) {
            String line = CaptureManager.sanitize(message.getString());
            if (!line.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        if (sb.length() == 0) {
            return;
        }
        OrderedText[] lines = ImmersionService.signLines(sb.toString());
        if (lines != null) {
            cir.setReturnValue(lines);
        }
    }
}
