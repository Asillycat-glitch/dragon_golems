package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import a_silly_cat.dragon_golems.dragon.DragonGolemPartType;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 龙的渲染器。继承 {@link AbstractGolemRenderer} 就自动获得本家的"按部件渲染 + 按材料贴图"流程
 * （{@code GolemDefaultLayer} 是在 {@link AbstractGolemRenderer} 构造函数里挂上的），
 * 所以这里只需要把模型和部件表接上。
 *
 * <p>这里额外管一件事：<b>本家 GUI 里那条悬浮 3D 预览的缩放</b>（置换/分解、装备界面）。
 * 那两个界面用的是原版 {@code InventoryScreen.renderEntityInInventoryFollowsAngle}，
 * 本家给的"每格多少像素"（金属 18 / 类人 24 / 犬 32）是照那三种傀儡定的，
 * 而这条龙有 26 格长 —— 不缩的话预览会铺满整个界面（见 {@link DragonGolemModel#guiPreviewScale()}）。
 *
 * <p>缩在这一层而不是缩模型，是因为渲染顺序是
 * {@code scale(getScale()) → translate(0,-1.501,0)}：把倍率插在 {@code scale} 里，
 * 那一步 -1.501 会跟着一起缩，龙的"脚底"仍然钉在面板坐标上，不会上下跑偏。
 */
@OnlyIn(Dist.CLIENT)
public class DragonGolemRenderer
        extends AbstractGolemRenderer<DragonGolemEntity, DragonGolemPartType, DragonGolemModel> {

    /**
     * 判定"是不是 GUI 悬浮预览"的阈值。
     *
     * <p><b>不能</b>用"实体有没有 {@code ClientOnly} 标签"来判：
     * 本家 {@code ClientHolderManager.getEntityForDisplayInternal} 只在<b>没有</b> {@code KEY_ENTITY}
     * 数据的那条分支里加标签，而带实体数据的成品（也就是正常的龙）走的是 {@code createForDisplay}，
     * <b>没有标签</b>；装备界面用的更是世界上那个真实体（`EquipmentsMenu.golem`）。
     *
     * <p>所以改判"这一趟的姿势栈被放大了多少"：只有 GUI 预览才会经过原版的
     * {@code renderEntityInInventory}，它给整个姿势栈乘一个"每格多少像素"的缩放
     * （本家 {@code getPreviewScale()}：默认 18 / 金属 20 / 类人 24 / 犬 32）。
     *
     * <p><b>阈值取 10 而不是 2</b>：世界那条路也不是 1 —— 本家 {@code AbstractGolemRenderer.scale}
     * 就是 {@code pose.scale(getScale())}，而第三方体型升级（例如傀儡地牢的"泰坦" +300%，
     * {@code getScale()} 直接到 4，再叠本家 size_up 也就 5~6）能把它顶到个位数。
     * 取 2 的话世界里的泰坦龙会被误判成 GUI 预览、平白多缩一次；而 GUI 那条路最低也有 18，
     * 所以 10 把这两群彻底分开。
     */
    private static final float GUI_PREVIEW_POSE_SCALE = 10.0F;

    public DragonGolemRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new DragonGolemModel(ctx.getModelSet()), 1.2F, DragonGolemPartType::values);
    }

    @Override
    protected void scale(DragonGolemEntity entity, PoseStack pose, float partialTick) {
        super.scale(entity, pose, partialTick);
        if (isGuiPreviewPose(pose)) {
            float s = DragonGolemModel.guiPreviewScale();
            pose.scale(s, s, s);
        }
    }

    /** 当前姿势矩阵的线性缩放是否大到"只可能是 GUI 预览"（见 {@link #GUI_PREVIEW_POSE_SCALE}）。 */
    private static boolean isGuiPreviewPose(PoseStack pose) {
        Matrix4f m = pose.last().pose();
        // 取 X 基向量的长度当缩放估计：平移不影响它，旋转也不改变它的模长。
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        return sx > GUI_PREVIEW_POSE_SCALE;
    }
}
