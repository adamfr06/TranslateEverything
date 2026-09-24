package dev.adamfr06.translateeverything.mixin;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.translate.ImmersionService;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Full immersion: name tags render translated (the render state is rebuilt every frame, purely visual). */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void translateeverything$immersiveLabel(Entity entity, EntityRenderState state, float tickDelta,
                                                    CallbackInfo ci) {
        if (state.displayName == null || !ImmersionService.active(SourceType.ENTITY)) {
            return;
        }
        if (entity.getCustomName() == null) {
            return;
        }
        String ready = ImmersionService.ready(CaptureManager.sanitize(state.displayName.getString()));
        if (ready != null) {
            state.displayName = ImmersionService.restyled(state.displayName, ready);
        }
    }
}
