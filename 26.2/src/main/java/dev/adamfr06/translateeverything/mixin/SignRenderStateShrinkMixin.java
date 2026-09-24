package dev.adamfr06.translateeverything.mixin;

import com.mojang.math.Transformation;
import dev.adamfr06.translateeverything.capture.CaptureManager;
import dev.adamfr06.translateeverything.translate.ImmersionService;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Full-immersion "shrink" sign mode on 26.x. */
@Mixin(AbstractSignRenderer.class)
public class SignRenderStateShrinkMixin {
    @Inject(method = "submitSignText", at = @At("HEAD"))
    private void translateeverything$shrink(SignRenderState state, com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.SubmitNodeCollector collector, SignText text, CallbackInfo ci) {
        pose.pushPose();
        ImmersionService.currentSignBoard = state.maxTextLineWidth;
        float factor = ImmersionService.signFaceScale(translateeverything$join(text), state.maxTextLineWidth);
        pose.scale(factor, factor, factor);
    }

    @Inject(method = "submitSignText", at = @At("RETURN"))
    private void translateeverything$restore(SignRenderState state, com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.SubmitNodeCollector collector, SignText text, CallbackInfo ci) {
        pose.popPose();
        ImmersionService.currentSignBoard = 90;
    }

    @Unique
    private static String translateeverything$join(SignText text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Component message : text.getMessages(false)) {
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
