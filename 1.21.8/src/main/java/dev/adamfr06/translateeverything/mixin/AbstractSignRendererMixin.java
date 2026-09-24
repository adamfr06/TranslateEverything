package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import dev.adamfr06.translateeverything.translate.ImmersionService;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.AbstractSignBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Full-immersion "shrink" sign mode (part 1 of 2): compute the shrink factor for the sign currently
 * being drawn and stash it for {@link SignTextScaleMixin} to apply on the concrete renderer's
 * {@code getTextScale()} (which is abstract here, so it can only be intercepted on the subclasses).
 */
@Mixin(AbstractSignBlockEntityRenderer.class)
public class AbstractSignRendererMixin {
    @Inject(method = "render(Lnet/minecraft/block/entity/SignBlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IILnet/minecraft/util/math/Vec3d;)V",
            at = @At("HEAD"))
    private void translateeverything$scaleHead(SignBlockEntity sign, float tickDelta, MatrixStack matrices,
                                               VertexConsumerProvider vertexConsumers, int light, int overlay,
                                               Vec3d cameraPos, CallbackInfo ci) {
        ImmersionService.currentSignBoard = sign.getMaxTextWidth();
        ImmersionService.currentSignScale = ImmersionService.signScaleFor(
                translateeverything$join(sign.getFrontText()), translateeverything$join(sign.getBackText()),
                sign.getMaxTextWidth());
    }

    @Inject(method = "render(Lnet/minecraft/block/entity/SignBlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IILnet/minecraft/util/math/Vec3d;)V",
            at = @At("RETURN"))
    private void translateeverything$scaleReturn(SignBlockEntity sign, float tickDelta, MatrixStack matrices,
                                                 VertexConsumerProvider vertexConsumers, int light, int overlay,
                                                 Vec3d cameraPos, CallbackInfo ci) {
        ImmersionService.currentSignScale = 1f;
        ImmersionService.currentSignBoard = 90;
    }

    @Unique
    private static String translateeverything$join(SignText text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Text message : text.getMessages(false)) {
            String line = CaptureManager.sanitize(message.getString());
            if (!line.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
