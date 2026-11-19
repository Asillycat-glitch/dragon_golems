package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 披风层：把 {@code getBannerSlot()} 里的旗帜<b>当布料</b>披在龙背上。
 *
 * <p><b>为什么不用 banner 物品</b>：物品模型带旗杆和横杆，画出来是"插在背上的旗"
 * （实机截图就是一根杆 + 一块板），不是披风。这里改用原版横幅自己的
 * {@code flag} 模型（20×40 的布面，没有杆），再用
 * {@link BannerRenderer#renderPatterns} 把底色 + 花纹逐层画上去——
 * 和方块横幅/盾牌是同一套贴图和图案逻辑，所以玩家染的颜色、加的图案都会原样出现。
 *
 * <p><b>姿态</b>：布料绕 X 转 90° 铺在背上，从肩膀往尾巴方向盖过去；尾端略微下垂，
 * 再叠一个随时间的轻微摆动（{@code CAPE_SWAY}）。挂点仍然走
 * {@link DragonGolemModel#applyRootTransform} + {@link DragonGolemModel#translateToBackAnchor}，
 * 所以龙俯仰/侧倾/拍翅时披风会跟着身体动。
 *
 * <p><b>骑乘适配</b>：有人控制时把披风整体往后挪（从骑手身后开始盖），并稍微收小；
 * 本地玩家是乘客时<b>一律不画</b>（`render` 里的 `hasPassenger` 检查，不区分第一/第三人称），
 * 避免披风挡住骑手视野。`hiddenFromLocalCamera` 只是额外的一层第一人称判断，
 * 实际相机实体是玩家，`cameraEntity == dragon` 基本不成立。
 *
 * <p><b>调参</b>：下面 {@code CAPE_*} / {@code RIDDEN_*} 全部是旋钮。长度按模型像素算
 * （布面本身 40 像素长），{@link #CAPE_SCALE} 是整体倍率。
 */
@OnlyIn(Dist.CLIENT)
public class DragonCapeLayer extends RenderLayer<DragonGolemEntity, DragonGolemModel> {

    // ---- 常态 ----
    /** 挂点在 body 枢轴上方多少像素（12.5 ≈ 背脊顶面）。 */
    private static final float CAPE_UP_PX = 12.5F;
    /** 挂点往尾巴方向偏多少像素；负值往头那边挪（肩膀位置）。 */
    private static final float CAPE_BACK_PX = -4.0F;
    /** 整体缩放。布面本身 20×40 像素（1.25×2.5 格），乘模型 1.7 倍后再乘这个数。 */
    private static final float CAPE_SCALE = 0.85F;
    /** 铺下去的倾角：90 = 完全贴平在背上，>90 尾端往下垂。 */
    private static final float CAPE_LAY_DEG = 94.0F;
    /** 摆动幅度（弧度），0 = 完全不动。 */
    private static final float CAPE_SWAY = 0.06F;

    // ---- 骑乘：整体往后挪、稍微收小，从骑手身后开始盖 ----
    private static final float RIDDEN_UP_PX = 13.0F;
    private static final float RIDDEN_BACK_PX = 14.0F;
    private static final float RIDDEN_SCALE = 0.75F;
    private static final float RIDDEN_LAY_DEG = 97.0F;

    private final ModelPart flag;
    private ItemStack cachedStack = ItemStack.EMPTY;
    private List<Pair<Holder<BannerPattern>, DyeColor>> cachedPatterns = List.of();

    public DragonCapeLayer(RenderLayerParent<DragonGolemEntity, DragonGolemModel> parent,
                           EntityModelSet modelSet) {
        super(parent);
        // 原版横幅的"布面"模型：20×40 像素、没有旗杆/横杆（见 BannerRenderer.createBodyLayer）。
        this.flag = modelSet.bakeLayer(ModelLayers.BANNER).getChild("flag");
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, DragonGolemEntity dragon,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack banner = dragon.getBannerSlot().getItem();
        if (banner.isEmpty() || !(banner.getItem() instanceof BannerItem)) {
            return;
        }
        if (hiddenFromLocalCamera(dragon)) {
            return;
        }
        // 本地玩家骑着它时整条龙会按 0.15 透明（本家骑乘防遮挡），披风没有半透明通道，
        // 干脆不画——否则会是一块不透明的布挂在半透明的龙背上。
        if (Minecraft.getInstance().player != null && dragon.hasPassenger(Minecraft.getInstance().player)) {
            return;
        }
        boolean ridden = dragon.getControllingPassenger() != null;
        List<Pair<Holder<BannerPattern>, DyeColor>> patterns = patterns(banner);
        if (patterns.isEmpty()) {
            return;
        }

        DragonGolemModel model = this.getParentModel();
        pose.pushPose();
        model.applyRootTransform(pose);
        model.translateToBackAnchor(pose,
                ridden ? RIDDEN_UP_PX : CAPE_UP_PX,
                ridden ? RIDDEN_BACK_PX : CAPE_BACK_PX);
        // 把"竖直的布"放倒到背上：绕 X 转 90° 后，布面从"往下"变成"往尾巴方向"。
        pose.mulPose(Axis.XP.rotationDegrees(ridden ? RIDDEN_LAY_DEG : CAPE_LAY_DEG));
        float scale = ridden ? RIDDEN_SCALE : CAPE_SCALE;
        pose.scale(scale, scale, scale);

        // 布面自身的摆动：绕挂点（布的上沿）小幅摆，尾端上下飘。
        this.flag.y = 0.0F;
        this.flag.xRot = Mth.sin(ageInTicks * 0.12F) * CAPE_SWAY;

        BannerRenderer.renderPatterns(pose, buffer, light, OverlayTexture.NO_OVERLAY,
                this.flag, ModelBakery.BANNER_BASE, true, patterns);
        pose.popPose();
    }

    /**
     * 底色 + 图案层。底色用 {@link BannerItem#getColor()}，图案层从物品的 BlockEntityTag 读，
     * 和原版横幅完全同源；物品换了才重建。
     */
    private List<Pair<Holder<BannerPattern>, DyeColor>> patterns(ItemStack stack) {
        if (!ItemStack.matches(stack, this.cachedStack)) {
            this.cachedStack = stack.copy();
            BannerItem item = (BannerItem) stack.getItem();
            this.cachedPatterns = BannerBlockEntity.createPatterns(
                    item.getColor(), BannerBlockEntity.getItemPatterns(stack));
        }
        return this.cachedPatterns;
    }

    /**
     * 本地玩家正骑着这条龙、而且是第一人称：不画。
     *
     * <p>第一人称下骑手相机就在龙背上，披风离近裁剪面太近，少量偏移就会变成一堵糊在屏幕上的布。
     * 注意：实际相机实体是玩家，`mc.getCameraEntity() == dragon` 基本不会成立；
     * 真正保证本地骑手看不到披风的是 `render` 开头的 `hasPassenger` 检查。
     */
    private static boolean hiddenFromLocalCamera(DragonGolemEntity dragon) {
        Minecraft mc = Minecraft.getInstance();
        return mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == dragon;
    }
}
