package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.translate.ImmersionService;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Full immersion: name tags render translated (the render state is rebuilt every frame, purely visual). */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void translateeverything$immersiveLabel(Entity entity, EntityRenderState state, float tickDelta,
                                                    CallbackInfo ci) {
        if (state.nameTag == null || !ImmersionService.active(SourceType.ENTITY)) {
            return;
        }
        if (entity.getCustomName() == null) {
            return;
        }
        String ready = ImmersionService.ready(CaptureManager.sanitize(state.nameTag.getString()));
        if (ready != null) {
            state.nameTag = ImmersionService.restyled(state.nameTag, ready);
        }
    }
}
