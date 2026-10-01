package a_silly_cat.dragom_golems.dragon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import a_silly_cat.dragom_golems.client.DragonGolemModel;
import dev.xkmc.modulargolems.content.core.GolemSlot;
import dev.xkmc.modulargolems.content.core.IGolemPart;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemDisplayContext;

import java.util.Locale;

/**
 * 龙傀儡的部件。
 *
 * <p><b>枚举顺序不能随意调整。</b> Modular Golems 在渲染时按
 * {@code entity.getMaterials().get(part.ordinal())} 取该部位的材料（进而决定贴图），
 * 而材料列表的顺序就是装配网格从左到右、从上到下的扫描顺序（装配时按格子索引逐个
 * {@code GolemHolder.addMaterial}，存进去的顺序就是扫描顺序）。我们的装配表是
 * <pre>
 * " W "
 * "HBT"
 * " L "
 * </pre>
 * 扫描顺序为 <b>双翼 → 头 → 身体 → 尾巴 → 四肢</b>（第一行先被扫到），所以枚举必须按这个顺序声明，
 * 否则混装不同材料时每个部位会显示到别的材料贴图上（属性总和不受影响）。
 */
public enum DragonGolemPartType implements IGolemPart<DragonGolemPartType> {

    /** 双翼（左右翼合成一件），装配表第一行。 */
    WINGS(GolemSlot.UP),
    /** 头部（头 + 脖颈），装配表第二行左侧。 */
    HEAD(GolemSlot.LEFT),
    /** 身体（只剩重心那一块），装配表正中。 */
    BODY(GolemSlot.MIDDLE),
    /** 尾巴，装配表第二行右侧。 */
    TAIL(GolemSlot.RIGHT),
    /** 四肢（四条腿算一件），装配表第三行。 */
    LEG(GolemSlot.DOWN);

    private final GolemSlot slot;

    // ---- 物品预览（背包 / 手持 / 展示框）----
    // 每个部件"居中到物品格 + 缩放"的参数由 DragonGolemModel 实测自动算出（measurePreview），
    // 因为那些数字必须和模型几何放在同一个坐标系里（之前手填在这里、又叠在模型自身的 2 倍缩放外面，
    // 结果就是整体偏个两三格、身体只剩一个切面）。这里只留"显示场景倍率"和成品的兜底值。
    /**
     * 成品（holder）预览的<b>兜底</b>缩放：正常情况下倍率由 {@code DragonGolemModel} 实测整条龙的
     * 包围盒算出（见那里的 holderScale），只有模型还没量出来时才用这个数。
     * 注意这条路 MG 会传 <b>null</b> 部件进来，见 {@link #setupItemRender}。
     */
    private static final float SCALE_HOLDER = 0.2F;
    /** 第一人称手持 / 头盔位再缩小一点（实测手里会更大）。 */
    private static final float HAND_FACTOR = 0.6F;
    private static final float HEAD_FACTOR = 0.8F;

    // ---- 成品（holder）在 GUI 里的整体姿态 ----
    // 成品预览走的是 MG 的 GolemBEWLR：空成品和"带数据的成品"都会先用本方法（part == null）
    // 铺一层变换，然后才交给模型/实体渲染器。所以下面这两个角度就是"成品图标怎么摆"的旋钮，
    // 和部件预览的 PREVIEW_PITCH / PREVIEW_YAW 是同一套写法、但互不影响。
    /** 成品图标上下倾斜（度）。成品这条链路外面有 MG 的 scale(1,-1,-1) 和原版渲染器的 scale(-1,-1,1)，
     *  绕 Z 的倾斜符号会被翻两次；如果看着反了，把这个值取负即可。 */
    private static final float HOLDER_PITCH = 35.0F;
    /** 成品图标左右旋转（度）。绕 Y 的旋转不受那两次镜像影响。 */
    private static final float HOLDER_YAW = -60.0F;
    /**
     * 成品图标的轴翻转（符号）。我们的龙借的是原版末影龙网格，而原版渲染它时靠 {@code scale(-1,-1,1)}
     * 把它摆正；部件预览那条路自己写了 {@code scale(-s,-s,-s)}，成品这条以前是 {@code (s,-s,s)}（少一个 X 翻转），
     * 所以图标看起来是左右镜像的。这里补上 X、Y 两次翻转 = 和原版一致的 {@code (-1,-1,1)}。
     * <p>如果发现"创造栏里那个正了、但合成出来拿在手里的反了"：带数据的成品外面还多叠了
     * MG 的 {@code scale(1,-1,-1)} + 原版渲染器的 {@code scale(-1,-1,1)}，那就要把 Z 也一起翻
     * （{@code HOLDER_FLIP_Z = -1.0F}），或者告诉我去给"物品预览"单独补一次 Z 镜像。
     */
    private static final float HOLDER_FLIP_X = 1.0F;
    private static final float HOLDER_FLIP_Y = -1.0F;
    private static final float HOLDER_FLIP_Z = -1.0F;

    DragonGolemPartType(GolemSlot slot) {
        this.slot = slot;
    }

    @Override
    public GolemSlot getSlot() {
        return slot;
    }

    @Override
    public GolemPart<?, DragonGolemPartType> toItem() {
        // 在枚举里逐个列举，避免 switch 生成额外内部类（部件数量固定，改动时这里也要跟着改）。
        if (this == WINGS) {
            return DragonGolemItems.WINGS.get();
        } else if (this == HEAD) {
            return DragonGolemItems.HEAD.get();
        } else if (this == BODY) {
            return DragonGolemItems.BODY.get();
        } else if (this == TAIL) {
            return DragonGolemItems.TAIL.get();
        }
        return DragonGolemItems.LEG.get();
    }

    @Override
    public void setupItemRender(PoseStack pose, ItemDisplayContext ctx, DragonGolemPartType part) {
        if (part == null) {
            // 本家 GolemBEWLR 渲染成品时，会故意用 null 调一次这个方法来铺"整条龙"的预览变换
            // （它自己的实现压根不读这个参数，所以不判空就会直接 NPE 崩游戏——创造栏一点就崩就是这么来的）。
            // 倍率与中心由模型实测给出：整条龙 ~250 像素，手填的 0.2 会让图标占掉好几个格子、还偏在一边。
            float auto = DragonGolemModel.holderScale();
            float base = auto > 0.0F ? auto : SCALE_HOLDER;
            float holderScale = ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                    || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                    ? base * HAND_FACTOR
                    : base;
            // 把整条龙的中心挪到物品格中心。单位是"格"，而且必须包含模型自身的
            // translate(-MODEL_LIFT) + scale(MODEL_SCALE) —— 外层平移在这两步之外，见 holderCenterOffset()。
            float[] center = DragonGolemModel.holderCenterOffset();
            // 和部件预览一样，先补 +0.5 把模型中心放进 [0,1]³ 那个方块里 ——
            // 原版 ItemRenderer 在调我们之前已经 translate(-0.5,-0.5,-0.5) 了（见 DragonGolemModel 里的长注释）。
            pose.translate(0.5F, 0.5F, 0.5F);
            // 和部件预览一样：先转再缩放（顺序不能反，缩放里 Y 是负的 = 镜像，镜像和旋转不交换）
            pose.mulPose(Axis.ZP.rotationDegrees(HOLDER_PITCH));
            pose.mulPose(Axis.YP.rotationDegrees(HOLDER_YAW));
            pose.scale(holderScale * HOLDER_FLIP_X, holderScale * HOLDER_FLIP_Y, holderScale * HOLDER_FLIP_Z);
            // 平移写在最后 = 最先作用到顶点，也就是在"模型自身的 translate/scale 之外"的那一层，
            // 所以 center 已经是含了 MODEL_LIFT / MODEL_SCALE 的成品偏移（见 holderCenterOffset()）。
            pose.translate(center[0], center[1], center[2]);
            DragonGolemModel.setPreviewFactor(0.0F);
            // 告诉模型"这是成品图标"：别把世界里的俯仰/侧倾姿态残留到图标上
            DragonGolemModel.setHolderPreview(true);
            return;
        }
        // 真正的部件：把"这是个预览"告诉模型，剩下居中/缩放/翻 Y 都在模型内部完成
        // （那里和几何同一坐标系，数字才可按直观调）。这里只按显示场景再给一个倍率。
        float factor = switch (ctx) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> HAND_FACTOR;
            case HEAD -> HEAD_FACTOR;
            default -> 1.0F;
        };
        DragonGolemModel.setPreviewFactor(factor);
    }

    @Override
    public MutableComponent getDesc(MutableComponent comp) {
        // 本家的规则：golem_part.<傀儡种类的注册名>.<部件名小写>，种类名不含命名空间。
        return Component.translatable("golem_part.dragon_golem." + name().toLowerCase(Locale.ROOT), comp)
                .withStyle(ChatFormatting.GREEN);
    }
}
