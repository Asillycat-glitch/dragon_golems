package a_silly_cat.dragom_golems.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import a_silly_cat.dragom_golems.dragon.DragonGolemFireball;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 龙弹的渲染：照抄原版 {@code DragonFireballRenderer}（同一个贴图、同一个方块光 15），
 * 只是把类型换成我们自己的实体（原版那个写死了 {@code EntityRenderer<DragonFireball>}，泛型对不上，
 * 没法直接拿来注册）。
 *
 * <p>贴图直接复用原版末影龙火球那张，不占我们自己的资源。
 */
public class DragonGolemFireballRenderer extends EntityRenderer<DragonGolemFireball> {

    private static final ResourceLocation TEXTURE_LOCATION =
            new ResourceLocation("textures/entity/enderdragon/dragon_fireball.png");
    private static final RenderType RENDER_TYPE = RenderType.entityCutoutNoCull(TEXTURE_LOCATION);
    /** 显示大小倍率（原版是 2.0）。 */
    private static final float SCALE = 2.0F;

    public DragonGolemFireballRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    protected int getBlockLightLevel(DragonGolemFireball entity, BlockPos pos) {
        return 15;
    }

    @Override
    public void render(DragonGolemFireball entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        pose.pushPose();
        pose.scale(SCALE, SCALE, SCALE);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        PoseStack.Pose last = pose.last();
        Matrix4f matrix4f = last.pose();
        Matrix3f matrix3f = last.normal();
        VertexConsumer vc = buffer.getBuffer(RENDER_TYPE);
        vertex(vc, matrix4f, matrix3f, packedLight, 0.0F, 0, 0, 1);
        vertex(vc, matrix4f, matrix3f, packedLight, 1.0F, 0, 1, 1);
        vertex(vc, matrix4f, matrix3f, packedLight, 1.0F, 1, 1, 0);
        vertex(vc, matrix4f, matrix3f, packedLight, 0.0F, 1, 0, 0);
        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);
    }

    private static void vertex(VertexConsumer vc, Matrix4f matrix4f, Matrix3f matrix3f, int light,
                               float x, int y, int u, int v) {
        vc.vertex(matrix4f, x - 0.5F, (float) y - 0.25F, 0.0F)
                .color(255, 255, 255, 255)
                .uv((float) u, (float) v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(matrix3f, 0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(DragonGolemFireball entity) {
        return TEXTURE_LOCATION;
    }
}
