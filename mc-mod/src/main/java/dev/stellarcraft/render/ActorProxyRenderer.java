package dev.stellarcraft.render;

import dev.stellarcraft.world.ActorProxy;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** SB renders the real enemy; its MC combat proxy still needs a registered renderer. */
public final class ActorProxyRenderer extends EntityRenderer<ActorProxy> {
    private static final ResourceLocation UNUSED_TEXTURE = new ResourceLocation("minecraft", "textures/misc/white.png");

    public ActorProxyRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(ActorProxy entity, Frustum frustum, double x, double y, double z) {
        return false;
    }

    @Override
    public ResourceLocation getTextureLocation(ActorProxy entity) {
        return UNUSED_TEXTURE;
    }
}
