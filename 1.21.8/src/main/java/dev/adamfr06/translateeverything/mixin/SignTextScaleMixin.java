package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.translate.ImmersionService;
import net.minecraft.client.render.block.entity.HangingSignBlockEntityRenderer;
import net.minecraft.client.render.block.entity.SignBlockEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Full-immersion "shrink" sign mode (part 2 of 2): multiply the concrete sign renderer's text scale
 * by the factor {@link AbstractSignRendererMixin} computed for the sign being drawn.
 */
@Mixin({SignBlockEntityRenderer.class, HangingSignBlockEntityRenderer.class})
public class SignTextScaleMixin {
    @Inject(method = "getTextScale()F", at = @At("RETURN"), cancellable = true)
    private void translateeverything$applyScale(CallbackInfoReturnable<Float> cir) {
        if (ImmersionService.currentSignScale != 1f) {
            cir.setReturnValue(cir.getReturnValueF() * ImmersionService.currentSignScale);
        }
    }
}
