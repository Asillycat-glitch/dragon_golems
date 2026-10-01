package a_silly_cat.dragom_golems.client;

import a_silly_cat.dragom_golems.Dragom_golems;
import a_silly_cat.dragom_golems.dragon.DragonGolemEntity;
import a_silly_cat.dragom_golems.dragon.DragonGolemPartType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.xkmc.modulargolems.content.entity.common.IGolemModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;

/**
 * 傀儡龙的模型：直接复用原版末影龙的网格（{@link ModelLayers#ENDER_DRAGON}），
 * 动画按末影龙那套拍翅/摆尾公式写了一版简化实现。
 *
 * <p>为什么能直接白嫖网格：末影龙模型注册在 {@code ModelLayers.ENDER_DRAGON} 上，
 * 而 {@code ModelProvider.generateModel(EntityModelSet)} 正好拿得到 model set。
 * 代价是<b>贴图必须按原版 {@code dragon.png} 的 UV 布局画</b>（网格里的 UV 是写死的）。
 *
 * <p>按部件渲染由 {@code AbstractGolemRenderer} + {@code GolemDefaultLayer} 完成：
 * 每个部件会带着自己材料的贴图单独调一次 {@link #renderToBufferInternal}，
 * 所以这里要把原版龙的各个 ModelPart 分派到 5 个部件上。
 */
@OnlyIn(Dist.CLIENT)
public class DragonGolemModel extends HierarchicalModel<DragonGolemEntity>
        implements IGolemModel<DragonGolemEntity, DragonGolemPartType, DragonGolemModel> {

    // ---- 实机微调用常量 ----
    /**
     * 整体缩放。原版龙模型是像素单位，直接放会偏小，这里放大到傀儡体型。
     *
     * <p>它只影响"模型"这一层：判定箱由 {@code DragonGolemItems} 的
     * {@code .sized(2.4F, 2.6F)} 决定，再乘本家 {@code getScale()}
     * （= GOLEM_SIZE / 默认 GOLEM_SIZE，见 {@code DragonGolemEntity.createAttributes} 的注释）。
     * 所以想让龙看起来小一点就调这里，想让它的碰撞箱一起变小就调 {@code sized(...)}。
     */
    public static final float MODEL_SCALE = 1.7F;
    /**
     * 抬高量（格）。当前姿态里 +Y 是向下（渲染器翻了 Y 轴），所以负数是往上抬。
     *
     * <p>它和 {@link #MODEL_SCALE} 是绑在一起的：抬高相当于"把模型原点顶到脚底之上"，
     * 两者比值就是"原点到脚底有多少模型单位"。保持 {@code MODEL_LIFT / MODEL_SCALE = 0.8}
     * 时改 MODEL_SCALE 只是"整体等比缩小"，龙站的位置和姿态都不动；
     * 想让龙在判定箱里坐得更低/更高，再单独动这个数。
     */
    private static final float MODEL_LIFT = 1.36F;
    /**
     * 模型朝向补偿。原版生物模型一律"头朝 -Z"（末影龙网格也是：head 的盒子在 z=-24..-10），
     * 而 {@code LivingEntityRenderer} 自己会按 yaw 旋转，所以这里必须是 0；给成 180 会让它倒着走。
     */
    private static final float MODEL_YAW = 0.0F;
    /** 前颈段数（末影龙用 5 段把身体连到头）。 */
    public static final int FRONT_NECK = 5;
    /** 尾巴段数（末影龙用 12 段）。 */
    public static final int TAIL = 12;
    /** 每段脖子/尾巴在模型里的步长（像素）。末影龙用的是 10。 */
    public static final float NECK_STEP = 10.0F;
    /** 物品预览用的假时间：固定相位，预览里就不会自己扇翅膀了。 */
    private static final float DISPLAY_TIME = 6.0F;
    /** 本家 {@code GolemType.createForDisplay} 给展示用傀儡加的标记。 */
    private static final String DISPLAY_TAG = "ClientOnly";
    /** 预览时整体左右旋转（度）。负值头向右，正值头向左。 */
    private static final float PREVIEW_YAW = -60.0F;
    /** 预览时整体上下倾斜（度）。负值头向下，正值头向上。 */
    private static final float PREVIEW_PITCH = 35.0F;
    // ---- 物品预览参数（背包 / 手持 / 展示框）----
    /**
     * 每个部件的预览目标跨度（模型像素，1 格 = 16）。
     * 索引 = {@link DragonGolemPartType#ordinal()}：WINGS, HEAD, BODY, TAIL, LEG。
     *
     * <p><b>为什么明显大于 16：</b>图标渲染时还会绕 Z 倾斜 {@link #PREVIEW_PITCH}、绕 Y 转
     * {@link #PREVIEW_YAW}，而包围盒是<b>转之前</b>量的 —— 那一圈旋转会把屏幕上的实际大小压掉一截
     * （经验上只剩 0.6~0.7）。所以按 13 量出来屏幕上只有半格多一点，看着就是"图标过小"。
     *
     * <p><b>为什么按部件分开给：</b>双翼是一整片薄板、头含 5 段前颈是一条长条，
     * 这两个的"3D 最大跨度"远大于它们在屏幕上的投影，统一给 22 会明显偏小。
     * 龙傀儡允许（也应该）超出格子，所以这两个给 30；身体/尾巴/四肢形状接近方块，22 就够。
     *
     * <p>偏小就往上加、偏大就往下减，<b>一格 = 16 像素</b>。
     */
    private static final float[] PREVIEW_TARGET_PX = {30.0F, 30.0F, 22.0F, 22.0F, 22.0F};
    /** 成品（整条龙）预览的目标跨度：整条龙最长、投影压得最狠，所以给得最大。 */
    private static final float HOLDER_TARGET_PX = 30.0F;
    /** 表里取不到时的兜底跨度（模型像素）。 */
    private static final float PREVIEW_TARGET_FALLBACK_PX = 22.0F;
    /**
     * 自动量出来的预览摆位：{@code [部件序号]} = 该部件包围盒中心（模型像素）。
     *
     * <p>以前这里是手填的 pivot/scale 表，一旦某个部件没填对、或者模型形状改了，
     * 那个部件就会整体偏出物品格（表现就是"图标跑到旁边格子上、只剩一个切面"）。
     * 现在改为<b>实测</b>：按预览姿势渲染一遍并收集顶点算包围盒 ——
     * 中心做 pivot、跨度换算成 scale，五个部件一次性都对，模型改了也自动跟着走。
     * <p>量的是"渲染时用到的同一批 ModelPart"，所以头+前颈、12 节尾巴
     * （同一个 neck 部件被逐段摆多次）也都会算进去。
     */
    private static final float[][] AUTO_PIVOT = new float[DragonGolemPartType.values().length][];
    private static final float[] AUTO_SCALE = new float[DragonGolemPartType.values().length];
    /** 整条龙的包围盒中心（模型像素）与成品预览倍率，供 {@code DragonGolemPartType.setupItemRender} 用。 */
    private static float[] holderCenterPx;
    private static float holderScale = 0.2F;
    /**
     * 本家 GUI（置换/分解、装备界面、装配台）里那条悬浮 3D 预览的目标长度（<b>格</b>）。
     *
     * <p>那些界面用的是原版 {@code InventoryScreen.renderEntityInInventoryFollowsAngle}，
     * 本家给的比例是"每格多少像素"（金属 18 / 类人 24 / 犬 32）——金属傀儡高 2.7 格 ≈ 49 px，
     * 正好塞进面板。而这条龙有 <b>26.6 格长</b> → 26.6×18 ≈ 479 px，直接糊满整个界面。
     *
     * <p>所以按"缩到和本家傀儡一个量级"算一个额外倍率（见 {@link #guiPreviewScale()}）。
     * <b>注意基准是"长度"</b>：龙又长又扁（26.6 格长、只有 4 格高），按长度缩完高度只剩
     * 目标格数的 15% 左右 —— 所以这个数要比"看起来合适的高度"大不少。
     * 3.0 的时候就是一根细签子（约 54×8 px），现在是 5.0（约 90×14 px）。
     * 嫌大就往回收（到 6 以上会开始压到面板边缘），嫌小就往上加。
     */
    private static final float GUI_PREVIEW_TARGET_BLOCKS = 5.0F;
    /** 上面那条算出来的倍率（1 = 不缩）。 */
    private static float guiPreviewScale = 1.0F;

    /**
     * 预览缩放倍率，由 {@code DragonGolemPartType.setupItemRender} 设置、{@link #setupAnim} 复位。
     * 0 = 不是预览（按实体方式渲染）。
     */
    private static float previewFactor = 0.0F;

    public static void setPreviewFactor(float factor) {
        previewFactor = factor;
    }

    /** 俯仰角正负号修正（本家实体渲染在模型之前做过 scale(-1,-1,1)，必要时用它纠号）。 */
    private static final float BODY_PITCH_SIGN = 1.0F;
    private static final float BODY_ROLL_SIGN = -1.0F;
    /**
     * 喷息 / 龙弹前摇时的张嘴角度（弧度）。
     *
     * <p>本家的 {@code jaw} 平时只有 0~0.4 弧度（0~23°，跟着扇翅膀），这里锁到 0.55（约 31°），
     * 看得出来是"张着嘴在喷东西"。
     */
    private static final float JAW_OPEN_BREATH = 0.55F;
    /** 张嘴/闭嘴的平滑系数（每帧朝目标角度靠这么多），免得开合是一帧硬切。 */
    private static final float JAW_LERP = 0.25F;
    /**
     * 成品图标（holder 预览）标记。那条路不会调 {@code setupAnim}，如果不打标记，
     * 世界里的俯仰/侧倾会残留在图标上（图标会莫名其妙歪着或压着弯）。
     */
    private static boolean holderPreview = false;

    public static void setHolderPreview(boolean preview) {
        holderPreview = preview;
    }
    /**
     * 一份动画状态（拍翅相位 / 俯仰 / 侧倾 / 平滑速度）。
     *
     * <p><b>为什么必须按 key 分开存：</b>一个 {@code EntityRenderer} 只有一个模型实例，世界里所有龙共用；
     * 更麻烦的是物品栏里的成品/部件图标也走同一个模型（本家 BEWLR 会拿同一份模型渲染）。
     * 状态混在一起时，图标那条路每次摆姿势都会把 {@code lastPoseTime} 改成它自己的时间
     * （物品是固定值 6），世界里的龙下一帧算出来的"帧间隔"就是几千 tick、被当成异常值丢掉 ——
     * 表现就是<b>翅膀扇两下突然停住 / 时快时停</b>，只要物品栏里有一个龙傀儡就一直这样。
     */
    private static final class AnimState {
        /** 拍翅相位（0..1），按帧增量累加（不能用 time * rate 取模，见旧注释：rate 一变相位会瞬移）。 */
        float flapPhase;
        /** 上一次摆姿势用的时间（tick），用来算帧间隔。 */
        float lastPoseTime = Float.NaN;
        /** 平滑过的俯仰（度），正值 = 低头。 */
        float bodyPitch;
        /** 平滑过的侧倾（度）：转弯压弯，方向反了就把 {@link #BODY_ROLL_SIGN} 取负。 */
        float bodyRoll;
        /** 平滑过的移动速度（格/tick）：拍翅频率只吃这个值。 */
        float speed;
        /** 速度是否已经初始化（第一帧直接取值，不要从 0 慢慢爬）。 */
        boolean speedInit;
        /** 张嘴程度（0 = 只有本家的拍翅开合，1 = 喷息时锁到最大张开）。 */
        float jawOpen;
        /** 这条龙"身体部件"的材料 id：贴图没画对应皮肤时退回去用它，见 {@link #getTextureLocationInternal}。 */
        ResourceLocation bodyMaterial;
    }

    /** 物品预览 / "展示用傀儡"共用的那一份状态（用负 key，不会和实体 id 撞）。 */
    private static final int PREVIEW_KEY = -1;
    private final Map<Integer, AnimState> animStates = new HashMap<>();
    /** 最近一次摆姿势用的状态：{@code renderToBufferInternal} 读它拿俯仰/侧倾。 */
    private AnimState current = new AnimState();

    private AnimState state(int key) {
        if (this.animStates.size() > 64) {
            // 龙死了/卸载了就让表回收一次（最多让拍翅跳一帧）
            this.animStates.clear();
        }
        return this.animStates.computeIfAbsent(key, k -> new AnimState());
    }

    /** 世界实体用实体 id；物品预览和"展示用傀儡"共用预览那一份状态。 */
    private static int keyOf(DragonGolemEntity entity) {
        return entity.getTags().contains(DISPLAY_TAG) ? PREVIEW_KEY : entity.getId();
    }
    /**
     * 拍翅频率的基线（相位/tick）：0.04 → 25 tick 一轮 ≈ 0.8 次/秒（悬停/待机）。
     */
    private static final float FLAP_BASE_RATE = 0.04F;
    /**
     * 移动速度换算成"额外拍翅频率"的增益与上限：每 1 格/tick 加 {@link #FLAP_SPEED_GAIN}，
     * 最多只加到 {@link #FLAP_SPEED_CAP}。
     *
     * <p>这两个数就是"高速时翅膀扑腾多快"的旋钮：当前配置最快 0.09 相位/tick
     * （≈ 11 tick 一轮 ≈ 1.8 次/秒，待机的 2.2 倍）。原来上限是 0.10（≈ 7 tick 一轮、2.9 次/秒），
     * 幽匿龙那种 2~3 格/tick 的速度会直接顶到上限，看起来就是"鬼畜"。
     */
    private static final float FLAP_SPEED_GAIN = 0.03F;
    private static final float FLAP_SPEED_CAP = 0.05F;
    /**
     * 速度的平滑系数（每帧朝当前速度靠这么多）。俯冲掠过时速度会瞬间冲到 3 格/tick，
     * 不做平滑的话拍翅频率会跟着一起抽。0.15 ≈ 7 帧跟上。
     */
    private static final float FLAP_SPEED_LERP = 0.15F;
    /** 帧间平滑系数：目标俯仰由服务端同步过来（见 DragonGolemEntity.pitchForPhase）。 */
    private static final float PITCH_LERP = 0.5F;
    /**
     * 前颈起点的高度（模型像素）——<b>这是动画用的基准，不是"判定箱高度"</b>。
     *
     * <p>模型空间里 +y 是向下（本家 {@code LivingEntityRenderer} 会先做 {@code scale(-1,-1,1)}），
     * 而且我们还额外 {@code translate(0, -MODEL_LIFT, 0)}、本家又 {@code translate(0, -1.501, 0)}，
     * 所以模型像素 → 世界高度是 {@code MODEL_LIFT + 1.501 - px * MODEL_SCALE / 16}。
     * 判定箱要按那个公式算，别直接照抄这里的像素值。
     */
    private static final float NECK_BASE_Y_PX = 20.0F;

    private final ModelPart root;
    private final ModelPart head;
    /**
     * 下颌。本家末影龙网格里 {@code jaw} 是 {@code head} 的<b>子部件</b>
     * （盒子 12×4×16，枢轴在 head 空间 (0, 4, -8)），而且原版每帧都在动它：
     * {@code jaw.xRot = (sin(拍翅相位 × 2π) + 1) × 0.2}，也就是 0~23° 跟着扇翅膀开合，
     * 跟"吼叫"没关系。
     *
     * <p>不取它的话它就永远停在默认角度 = <b>嘴闭死</b>（虽然照样会被 head 带着画出来）——
     * 这就是"龙息是从一个闭着的嘴里喷出来"的原因。用 {@code hasChild} 判一下：
     * 万一哪天换了个没有 jaw 的网格，也只会"不张嘴"，而不是在构造里抛异常崩启动。
     */
    private final ModelPart jaw;
    private final ModelPart neck;
    private final ModelPart body;
    private final ModelPart leftWing;
    private final ModelPart rightWing;
    private final ModelPart leftFrontLeg;
    private final ModelPart leftFrontTip;
    private final ModelPart leftFrontFoot;
    private final ModelPart leftRearLeg;
    private final ModelPart leftRearTip;
    private final ModelPart leftRearFoot;
    private final ModelPart rightFrontLeg;
    private final ModelPart rightFrontTip;
    private final ModelPart rightFrontFoot;
    private final ModelPart rightRearLeg;
    private final ModelPart rightRearTip;
    private final ModelPart rightRearFoot;

    /** 逐段算出来的脖子/尾巴姿态：每行 {x, y, z, yRot, xRot}（模型空间像素）。 */
    private final float[][] frontSeg = new float[FRONT_NECK][5];
    private final float[][] tailSeg = new float[TAIL][5];
    private float headX;
    private float headY;
    private float headZ;
    private float bob;

    public DragonGolemModel(EntityModelSet set) {
        this(set.bakeLayer(ModelLayers.ENDER_DRAGON));
    }

    public DragonGolemModel(ModelPart root) {
        this.root = root;
        this.head = root.getChild("head");
        this.jaw = this.head.hasChild("jaw") ? this.head.getChild("jaw") : null;
        this.neck = root.getChild("neck");
        this.body = root.getChild("body");
        this.leftWing = root.getChild("left_wing");
        this.rightWing = root.getChild("right_wing");
        this.leftFrontLeg = root.getChild("left_front_leg");
        this.leftFrontTip = this.leftFrontLeg.getChild("left_front_leg_tip");
        this.leftFrontFoot = this.leftFrontTip.getChild("left_front_foot");
        this.leftRearLeg = root.getChild("left_hind_leg");
        this.leftRearTip = this.leftRearLeg.getChild("left_hind_leg_tip");
        this.leftRearFoot = this.leftRearTip.getChild("left_hind_foot");
        this.rightFrontLeg = root.getChild("right_front_leg");
        this.rightFrontTip = this.rightFrontLeg.getChild("right_front_leg_tip");
        this.rightFrontFoot = this.rightFrontTip.getChild("right_front_foot");
        this.rightRearLeg = root.getChild("right_hind_leg");
        this.rightRearTip = this.rightRearLeg.getChild("right_hind_leg_tip");
        this.rightRearFoot = this.rightRearTip.getChild("right_hind_foot");
        // 物品预览不会走 setupAnim（本家 BEWLR 是直接调 renderToBufferInternal 的），
        // 所以先在构造里摆一份静态姿势，否则预览里脖子/尾巴是空的、头还停在原点。
        pose(DISPLAY_TIME, 0.0F, 0.0F, 0.0D, this.state(PREVIEW_KEY));
        // 构造时就把预览摆位量好：成品图标的倍率在 setupItemRender 里就要用（那条路比渲染更早）。
        this.measurePreview();
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(DragonGolemEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // 实体渲染这条路上永远不是预览模式（同时也是给静态标记复位的地方）。
        previewFactor = 0.0F;
        holderPreview = false;
        // 每只龙（以及预览）各用一份状态：共用会被物品栏里的图标搅乱，见 AnimState 的说明
        AnimState st = this.state(keyOf(entity));
        // 身体部件的材料 id：贴图门控要用（见 getTextureLocationInternal）。
        // 必须在下面那个 DISPLAY_TAG 提前 return 之前取。
        var mats = entity.getMaterials();
        int bodyIndex = DragonGolemPartType.BODY.ordinal();
        st.bodyMaterial = mats.size() > bodyIndex ? mats.get(bodyIndex).id() : null;
        st.bodyRoll = Mth.lerp(0.25F, st.bodyRoll, entity.getBodyRoll());
        // 俯冲/开火姿态：目标角度表在 DragonGolemEntity.pitchForPhase（那边算好、插值、同步），
        // 这里只做一点点帧间平滑 —— 关键是判定箱也读同一个值，模型和碰撞箱才不会再各走各的。
        st.bodyPitch = Mth.lerp(PITCH_LERP, st.bodyPitch, entity.getBodyPitch());
        // 张嘴：DIVE_PHASE_BREATH 是"喷息/龙弹前摇"那一档（见 DragonRangedGoal.enter），
        // 那几档里把嘴张开；其它时候交给本家那套"跟着拍翅开合"。
        float jawTarget = entity.getDivePhase() == DragonGolemEntity.DIVE_PHASE_BREATH ? 1.0F : 0.0F;
        st.jawOpen = Mth.lerp(JAW_LERP, st.jawOpen, jawTarget);
        // 手里/展示框里的"展示用傀儡"（本家给它们打了 ClientOnly 标记）固定成一个姿势，
        // 免得拿在手上一直在扇翅膀。
        if (entity.getTags().contains(DISPLAY_TAG)) {
            pose(DISPLAY_TIME, 0.0F, 0.0F, 0.0D, st);
            return;
        }
        float now = (float) entity.getDeltaMovement().length();
        if (!st.speedInit) {
            st.speed = now;
            st.speedInit = true;
        }
        st.speed = Mth.lerp(FLAP_SPEED_LERP, st.speed, now);
        pose(ageInTicks, netHeadYaw, headPitch, st.speed, st);
    }

    /**
     * 摆姿势：翅膀 / 腿 / 脖子 / 尾巴。抽出来是为了让"没有 setupAnim 的预览路径"
     * 也能在构造里调一次，拿到同样的静态外观。
     *
     * @param time      动画时间（tick）
     * @param netHeadYawDeg 头相对身体的偏航（度）
     * @param headPitchDeg  头的俯仰（度）
     * @param speed     移动速度（已经平滑过），决定扇翅快慢
     * @param st        这次渲染用哪一份动画状态（世界实体 / 物品预览是分开的）
     */
    private void pose(float time, float netHeadYawDeg, float headPitchDeg, double speed, AnimState st) {
        // 记下"这次渲染用的是哪份状态"，renderToBufferInternal 会拿它读俯仰/侧倾
        this.current = st;
        // 拍翅频率：悬停慢、飞行快。
        // 语义：phase 每 (1 / rate) tick 走完一轮，所以 rate = 0.04 → 25 tick 一次 ≈ 0.8 次/秒（待机）。
        // 之前是 0.22（一次只要 4.5 tick ≈ 4.4 次/秒），悬停时速度≈0 只吃下限，看起来就是"一直扑腾"。
        float rate = FLAP_BASE_RATE + Math.min(FLAP_SPEED_CAP, (float) speed * FLAP_SPEED_GAIN);
        // 相位增量累加：rate 变化时不会跳相（物品预览/世界实体切换时时间会跳，那一下按 0 处理）
        float delta = Float.isNaN(st.lastPoseTime) ? 0.0F : time - st.lastPoseTime;
        if (delta < 0.0F || delta > 20.0F) {
            delta = 0.0F;
        }
        st.lastPoseTime = time;
        st.flapPhase = (float) ((st.flapPhase + delta * rate) % 1.0D);
        float f = st.flapPhase * ((float) Math.PI * 2.0F);

        // ---- 下颌 ----
        // 本家原版就是这一行（写在 renderToBuffer 里，跟着拍翅相位开合 0~0.4 弧度）；
        // 喷息/龙弹前摇时 st.jawOpen → 1，用 lerp 锁到 JAW_OPEN_BREATH。
        if (this.jaw != null) {
            float flap = (Mth.sin(f) + 1.0F) * 0.2F;
            this.jaw.xRot = Mth.lerp(st.jawOpen, flap, JAW_OPEN_BREATH);
        }

        // ---- 翅膀（照搬末影龙的公式）----
        float wingXRot = 0.125F - Mth.cos(f) * 0.2F;
        float wingZRot = -((float) Math.sin(f) + 0.125F) * 0.8F;
        this.leftWing.xRot = wingXRot;
        this.leftWing.yRot = -0.25F;
        this.leftWing.zRot = wingZRot;
        this.rightWing.xRot = wingXRot;
        this.rightWing.yRot = 0.25F;
        this.rightWing.zRot = -wingZRot;
        float tipRot = ((float) Math.sin(f + 2.0F) + 0.5F) * 0.75F;
        this.leftWing.getChild("left_wing_tip").zRot = tipRot;
        this.rightWing.getChild("right_wing_tip").zRot = -tipRot;

        // ---- 躯干浮动 + 腿（照搬末影龙的固定角度）----
        float bobRaw = (float) (Math.sin(f - 1.0F) + 1.0D);
        this.bob = (bobRaw * bobRaw + bobRaw * 2.0F) * 0.05F;
        poseLeg(this.leftFrontLeg, this.leftFrontTip, this.leftFrontFoot, 1.3F, true);
        poseLeg(this.rightFrontLeg, this.rightFrontTip, this.rightFrontFoot, 1.3F, true);
        poseLeg(this.leftRearLeg, this.leftRearTip, this.leftRearFoot, 1.0F, false);
        poseLeg(this.rightRearLeg, this.rightRearTip, this.rightRearFoot, 1.0F, false);

        // ---- 前颈：从身体前上方连到头 ----
        // 关键：每段先记下"自己的锚点"再推进一步（末影龙就是这么写的）。反过来先推进再记录，
        // 整条脖子会比身体少走一格，于是脖子和身体之间就裂开一道缝。
        float yaw = (float) Math.toRadians(Mth.clamp(netHeadYawDeg, -60.0F, 60.0F));
        float pitch = (float) Math.toRadians(Mth.clamp(headPitchDeg, -40.0F, 40.0F)) * 0.5F;
        // 前颈起点（模型像素，模型空间里 +y 是"向下"）。这是整条脖子+头的动画基准高度，不要拿它
        // 去对判定箱：判定箱的高度换算见 DragonGolemEntity.partUp()（那里要把 MODEL_LIFT 和
        // 本家渲染器的 translate(0,-1.501,0) 一起算进去）。
        float x = 0.0F;
        float y = NECK_BASE_Y_PX;
        float z = -12.0F;
        for (int i = 0; i < FRONT_NECK; i++) {
            // 越靠近头，转到"头的角度"的比例越大，脖子才会从身体自然弯向脑袋。
            float t = (i + 1) / (float) (FRONT_NECK + 1);
            float segYaw = yaw * t;
            float segPitch = pitch * t + Mth.cos(i * 0.45F + f) * 0.15F;
            frontSeg[i] = new float[]{x, y, z, segYaw, segPitch};
            x -= Mth.sin(segYaw) * Mth.cos(segPitch) * NECK_STEP;
            y += Mth.sin(segPitch) * NECK_STEP;
            z -= Mth.cos(segYaw) * Mth.cos(segPitch) * NECK_STEP;
        }
        this.headX = x;
        this.headY = y;
        this.headZ = z;
        this.head.yRot = yaw;
        this.head.xRot = pitch;
        this.head.zRot = 0.0F;

        // ---- 尾巴：从身体后方向后甩 ----
        // 同样先记录再推进；起点 60 是让第一段和身体尾部（z 到 56）重叠，避免出现缝。
        float tx = 0.0F;
        float ty = 10.0F;
        float tz = 60.0F;
        for (int j = 0; j < TAIL; j++) {
            float segYaw = (float) Math.PI;
            float segPitch = Mth.sin(j * 0.45F + f) * 0.15F;
            tailSeg[j] = new float[]{tx, ty, tz, segYaw, segPitch};
            tx -= Mth.sin(segYaw) * Mth.cos(segPitch) * NECK_STEP;
            ty += Mth.sin(segPitch) * NECK_STEP;
            tz -= Mth.cos(segYaw) * Mth.cos(segPitch) * NECK_STEP;
        }
    }

    /** 腿的固定姿态 + 随身体浮动；前腿的"小腿"往回折，后腿向前折，跟末影龙一致。 */
    private void poseLeg(ModelPart leg, ModelPart tip, ModelPart foot, float base, boolean front) {
        leg.xRot = base + this.bob * 0.1F;
        tip.xRot = (front ? -0.5F : 0.5F) + (front ? -1.0F : 1.0F) * this.bob * 0.1F;
        foot.xRot = 0.75F + this.bob * 0.1F;
    }

    @Override
    public void renderToBufferInternal(DragonGolemPartType part, PoseStack pose, VertexConsumer vc,
                                       int light, int overlay, float r, float g, float b, float a) {
        pose.pushPose();
        if (previewFactor > 0.0F) {
            // 物品部件预览：先用"预览专用"那一份状态重新摆一次姿势。
            // 不这么做的话，图标会照着世界里那条龙当前姿态画（翅膀相位、俯冲低头全跟着走），
            // 而且反过来会把世界动物的动画状态改坏（就是"物品栏里有龙傀儡时翅膀就不扇了"那个 bug）。
            this.pose(DISPLAY_TIME, 0.0F, 0.0F, 0.0D, this.state(PREVIEW_KEY));
            // 物品预览：不做实体那套（抬高 / 放大 2 倍），直接把该部件的中心挪到原点再缩放。
            // 注意顺序——后面写的变换会先作用到顶点，所以这里等价于"先在模型坐标里平移，再缩放"。
            this.measurePreview();
            int idx = part.ordinal();
            float s = (AUTO_SCALE[idx] > 0.0F ? AUTO_SCALE[idx] : 0.5F) * previewFactor;
            float[] pivot = AUTO_PIVOT[idx] != null ? AUTO_PIVOT[idx] : new float[3];
            // ⚠ 先补 +0.5：原版 ItemRenderer 在调用自定义渲染器<b>之前</b>就做过
            // translate(-0.5,-0.5,-0.5)（它假设模型占满一个 [0,1]³ 的方块、中心在 (0.5,0.5,0.5)），
            // 而我们的模型中心在原点，那一步会把图标整体推到"左下各 8 像素"（= 正好半个格子）。
            // 所以这里在最外层把中心挪回 (0.5,0.5,0.5)，让它被减掉之后正好落在格子中心。
            // 写在最前面 = 最后作用到顶点，所以下面的旋转/缩放仍然是绕"部件自己的中心"做的。
            pose.translate(0.5F, 0.5F, 0.5F);
            // 在这里插入整体旋转（注意：放在 scale 之前）
            pose.mulPose(Axis.ZP.rotationDegrees(PREVIEW_PITCH)); // 先绕 Z 倾斜
            pose.mulPose(Axis.YP.rotationDegrees(PREVIEW_YAW));   // 再绕 Y 左右
            pose.scale(-s, -s, -s);                 // -X,-Y：模型是 Y 向下画的，X则是向右，让物品预览表现为 Y 向上，X向左
            // ⚠ 单位：pivot 量的是"模型像素"，而 PoseStack 的 translate 是"格" ——
            // 顶点在 ModelPart.Cube.compile 里已经 /16 了，所以这里必须 /16。
            pose.translate(-pivot[0] / 16.0F, -pivot[1] / 16.0F, -pivot[2] / 16.0F);
        } else {
            if (holderPreview) {
                // 成品图标同理：固定成静态姿势，别继承世界里的俯仰/侧倾/拍翅
                this.pose(DISPLAY_TIME, 0.0F, 0.0F, 0.0D, this.state(PREVIEW_KEY));
            }
            pose.translate(0.0F, -MODEL_LIFT, 0.0F);
            pose.mulPose(Axis.YP.rotationDegrees(MODEL_YAW));
            if (!holderPreview && Math.abs(this.current.bodyPitch) > 0.05F) {
                // 绕模型原点前后倾：俯冲低头、拉升抬头（枢轴就是用于抬高/缩放的那一点）
                pose.mulPose(Axis.XP.rotationDegrees(BODY_PITCH_SIGN * this.current.bodyPitch));
            }
            if (!holderPreview && Math.abs(this.current.bodyRoll) > 0.05F) {
                // 绕 Z 侧倾：转弯时压弯（和俯仰一样，枢轴是模型原点）
                pose.mulPose(Axis.ZP.rotationDegrees(BODY_ROLL_SIGN * this.current.bodyRoll));
            }
            pose.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        }

        switch (part) {
            case WINGS -> {
                // 左右翼合成一件：同一次调用里把两只翅膀都画出来
                this.leftWing.render(pose, vc, light, overlay, r, g, b, a);
                this.rightWing.render(pose, vc, light, overlay, r, g, b, a);
            }
            case HEAD -> {
                // 头部部件 = 头 + 5 段前颈（脖颈已经并进来了）。预览和实体走同一套，
                // 预览的"居中到物品格"由 measurePreview() 实测出来的 AUTO_PIVOT[HEAD] 负责。
                this.head.setPos(headX, headY, headZ);
                renderNeckChain(frontSeg, pose, vc, light, overlay, r, g, b, a);
                this.head.render(pose, vc, light, overlay, r, g, b, a);
            }
            case BODY -> {
                // 只剩重心那一块
                this.body.setPos(0.0F, 4.0F, 8.0F);
                this.body.render(pose, vc, light, overlay, r, g, b, a);
            }
            case TAIL -> renderNeckChain(tailSeg, pose, vc, light, overlay, r, g, b, a);
            case LEG -> {
                this.leftFrontLeg.render(pose, vc, light, overlay, r, g, b, a);
                this.rightFrontLeg.render(pose, vc, light, overlay, r, g, b, a);
                this.leftRearLeg.render(pose, vc, light, overlay, r, g, b, a);
                this.rightRearLeg.render(pose, vc, light, overlay, r, g, b, a);
            }
        }
        pose.popPose();
    }

    private void renderNeckChain(float[][] chain, PoseStack pose, VertexConsumer vc, int light,
                                 int overlay, float r, float g, float b, float a) {
        for (float[] seg : chain) {
            if (seg == null) {
                continue;
            }
            this.neck.setPos(seg[0], seg[1], seg[2]);
            this.neck.yRot = seg[3];
            this.neck.xRot = seg[4];
            this.neck.zRot = 0.0F;
            this.neck.render(pose, vc, light, overlay, r, g, b, a);
        }
    }

    // ---- 预览摆位实测（背包 / 手持 / 展示框）----

    /**
     * 量一次五个部件 + 整条龙在"预览姿势"下的包围盒。
     *
     * <p>必须先把姿势摆好（{@code pose(DISPLAY_TIME, ...)}）再调 —— 构造里和预览分支里都是这么用的。
     * 静态姿势恒定，所以全局只量一次。
     */
    private void measurePreview() {
        if (holderCenterPx != null) {
            return;
        }
        PoseStack probe = new PoseStack();
        BoundRecorder all = new BoundRecorder();
        for (DragonGolemPartType part : DragonGolemPartType.values()) {
            BoundRecorder rec = new BoundRecorder();
            this.measurePart(part, probe, rec);
            if (!rec.any) {
                continue;
            }
            AUTO_PIVOT[part.ordinal()] = rec.centerPx();
            AUTO_SCALE[part.ordinal()] = previewTargetPx(part) / rec.maxSpanPx();
            all.include(rec);
        }
        if (!all.any) {
            return;
        }
        holderCenterPx = all.centerPx();
        // 成品那条路里模型还会被 MODEL_SCALE 放大一次，所以外面这层要把它除掉
        holderScale = HOLDER_TARGET_PX / (all.maxSpanPx() * MODEL_SCALE);
        // 本家 GUI 悬浮预览用的额外倍率：把"整条龙 26 格长"缩到 GUI_PREVIEW_TARGET_BLOCKS 格。
        // 这里先换算成"世界格"（maxSpanPx 是模型像素：/16 得到格，再乘模型自己的 MODEL_SCALE）。
        float worldSpan = all.maxSpanPx() / 16.0F * MODEL_SCALE;
        guiPreviewScale = worldSpan > 1.0E-3F ? GUI_PREVIEW_TARGET_BLOCKS / worldSpan : 1.0F;
    }

    /** 取该部件的预览目标跨度（模型像素）；表里缺项就退回兜底值。 */
    private static float previewTargetPx(DragonGolemPartType part) {
        int idx = part.ordinal();
        return idx >= 0 && idx < PREVIEW_TARGET_PX.length
                ? PREVIEW_TARGET_PX[idx]
                : PREVIEW_TARGET_FALLBACK_PX;
    }

    /** 按"该部件真正渲染的那几个 ModelPart"量一次包围盒（和 renderToBufferInternal 的分派保持一致）。 */
    private void measurePart(DragonGolemPartType part, PoseStack probe, BoundRecorder rec) {
        if (part == DragonGolemPartType.WINGS) {
            this.leftWing.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            this.rightWing.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        } else if (part == DragonGolemPartType.HEAD) {
            this.head.setPos(this.headX, this.headY, this.headZ);
            this.head.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            this.measureChain(this.frontSeg, probe, rec);
        } else if (part == DragonGolemPartType.BODY) {
            this.body.setPos(0.0F, 4.0F, 8.0F);
            this.body.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        } else if (part == DragonGolemPartType.TAIL) {
            this.measureChain(this.tailSeg, probe, rec);
        } else {
            this.leftFrontLeg.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            this.rightFrontLeg.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            this.leftRearLeg.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            this.rightRearLeg.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** 脖子/尾巴：同一个 neck 部件被逐段摆开，量的时候要按每一段的姿态各算一次。 */
    private void measureChain(float[][] chain, PoseStack probe, BoundRecorder rec) {
        for (float[] seg : chain) {
            if (seg == null) {
                continue;
            }
            this.neck.setPos(seg[0], seg[1], seg[2]);
            this.neck.yRot = seg[3];
            this.neck.xRot = seg[4];
            this.neck.zRot = 0.0F;
            this.neck.render(probe, rec, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** 成品预览用的整条龙中心（模型像素）。 */
    public static float[] holderCenterPx() {
        return holderCenterPx != null ? holderCenterPx : new float[3];
    }

    /**
     * 成品预览"把整条龙挪到物品格中心"需要的平移量（单位：<b>格</b>）。
     *
     * <p><b>不能直接用 {@link #holderCenterPx()} / 16。</b>成品那条路的外层平移写在
     * {@code DragonGolemPartType.setupItemRender} 里，位置在模型自身的
     * {@code translate(0,-MODEL_LIFT,0)} + {@code scale(MODEL_SCALE)} <b>外侧</b>，
     * 而顶点在 {@code ModelPart.Cube.compile} 里已经 /16 —— 所以要把模型这两步也算进去：
     * <pre>
     *   模型中心（格）      = c_px / 16
     *   过一遍模型自身变换  = c_px/16 × MODEL_SCALE + (0, -MODEL_LIFT, 0)
     *   外层平移            = 取负
     * </pre>
     *
     * <p>少乘 MODEL_SCALE 的话，整条龙的横向中心会差 1.7 倍，图标会往物品格外面偏。
     */
    public static float[] holderCenterOffset() {
        float[] c = holderCenterPx();
        return new float[]{
                -c[0] * MODEL_SCALE / 16.0F,
                -c[1] * MODEL_SCALE / 16.0F + MODEL_LIFT,
                -c[2] * MODEL_SCALE / 16.0F};
    }

    /** 成品预览倍率（已经除掉了模型自己的 {@link #MODEL_SCALE}）。 */
    public static float holderScale() {
        return holderScale;
    }

    /** 本家 GUI 悬浮预览要额外缩多少（见 {@link #GUI_PREVIEW_TARGET_BLOCKS}）。 */
    public static float guiPreviewScale() {
        return guiPreviewScale;
    }

    /**
     * 只收顶点坐标的假 {@link VertexConsumer}：用来量"摆好姿势之后"的包围盒。
     * 走 {@code ModelPart.render} 意味着量到的就是真正会画出来的那批顶点（含子部件）。
     */
    private static final class BoundRecorder implements VertexConsumer {
        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;
        private boolean any;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this.record((float) x, (float) y, (float) z);
        }

        /** 兼容 float 版签名（1.20.1 的接口是 double，留着不吃亏）。 */
        public VertexConsumer vertex(float x, float y, float z) {
            return this.record(x, y, z);
        }

        private VertexConsumer record(float x, float y, float z) {
            this.any = true;
            this.minX = Math.min(this.minX, x);
            this.minY = Math.min(this.minY, y);
            this.minZ = Math.min(this.minZ, z);
            this.maxX = Math.max(this.maxX, x);
            this.maxY = Math.max(this.maxY, y);
            this.maxZ = Math.max(this.maxZ, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        /**
         * Forge 的 {@code IForgeVertexConsumer} 在 1.20.1 多加了这两条<b>抽象</b>方法
         * （不是 default 的），不实现就编译不过：BoundRecorder 不是抽象类，且未覆盖
         * {@code unsetDefaultColor()}。量包围盒用不到它们，空实现即可。
         */
        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }

        @Override
        public void endVertex() {
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        /** 中心（模型像素）：顶点在 compile 里已经 /16，所以乘回 16。 */
        private float[] centerPx() {
            return new float[]{(this.minX + this.maxX) * 8.0F, (this.minY + this.maxY) * 8.0F, (this.minZ + this.maxZ) * 8.0F};
        }

        /** 最大跨度（模型像素）。 */
        private float maxSpanPx() {
            float span = Math.max(this.maxX - this.minX, Math.max(this.maxY - this.minY, this.maxZ - this.minZ));
            return Math.max(span, 1.0E-4F) * 16.0F;
        }

        private void include(BoundRecorder other) {
            if (!other.any) {
                return;
            }
            this.any = true;
            this.minX = Math.min(this.minX, other.minX);
            this.minY = Math.min(this.minY, other.minY);
            this.minZ = Math.min(this.minZ, other.minZ);
            this.maxX = Math.max(this.maxX, other.maxX);
            this.maxY = Math.max(this.maxY, other.maxY);
            this.maxZ = Math.max(this.maxZ, other.maxZ);
        }
    }

    // ---- 贴图：材料 ID → 贴图路径，带两级回退 ----

    /**
     * 贴图命名：{@code dragom_golems:textures/entity/dragon/<材料路径>.png}。
     *
     * <p>材料 {@code dragom_golems:iron} → {@code dragon/iron.png}；
     * 发光层沿用本家的约定，材料 {@code netherite_emissive} → {@code dragon/netherite_emissive.png}。
     * 没画对应材料时退回 {@code dragon/base.png}。
     *
     * <p>注意资源路径只能用小写字母/数字/下划线/点和斜杠，所以贴图文件名必须是 ASCII——
     * 中文名的原始素材在导出时按这张表复制成了规范名。
     */
    private static final String TEXTURE_PREFIX = "textures/entity/dragon/";
    /** 基础龙贴图，所有没画贴图的材料的兜底。 */
    private static final ResourceLocation FALLBACK_BASE =
            Dragom_golems.id(TEXTURE_PREFIX + "base.png");
    /** 以前那张 enderdragon.png 留作二级兜底（万一 base.png 被删）。 */
    private static final ResourceLocation FALLBACK_LEGACY =
            Dragom_golems.id("textures/entity/enderdragon.png");
    /** 连本模组的贴图都没有时，用原版末影龙贴图，保证不会显示成紫黑格子。 */
    private static final ResourceLocation FALLBACK_VANILLA =
            new ResourceLocation("minecraft", "textures/entity/enderdragon/dragon.png");

    private final Map<ResourceLocation, ResourceLocation> textureCache = new HashMap<>();
    private ResourceManager cachedManager;

    @Override
    public ResourceLocation getTextureLocationInternal(ResourceLocation material) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        if (rm != cachedManager) {
            // 资源包换过（重载后 ResourceManager 是新实例），缓存作废。
            textureCache.clear();
            cachedManager = rm;
        }
        String path = material.getPath();
        ResourceLocation own = Dragom_golems.id(TEXTURE_PREFIX + path + ".png");
        if (path.endsWith("_emissive")) {
            // 发光层：本家会先问一次 "<材料>_emissive" 的贴图在不在，在才多渲染一遍。
            // 这里绝不能走兜底，否则整条龙会被当成发光层再画一遍。也不进缓存。
            return own;
        }
        if (exists(own)) {
            // 只缓存"确有其图"的命中：兜底结果依赖当前这条龙的身体材料，不能按材料 id 缓存。
            return textureCache.computeIfAbsent(material,
                    m -> Dragom_golems.id(TEXTURE_PREFIX + m.getPath() + ".png"));
        }
        // ---- 贴图门控：这个材料（或这件皮肤）我们没画 ----
        // 本家的时装（curios 的 golem_skin 槽里的 golem_facade）会把<b>整条龙</b>的材料 id
        // 换成皮肤那个材料 id（见本家 AbstractGolemRenderer.renderAllParts）；铸材料也可能铸上
        // 一个我们没画贴图的材料。原来的行为是整条龙变成兜底灰，现在改成
        // <b>退回它自己身体那份材料的贴图</b> —— 皮肤没画就不生效，龙看起来还是它自己的样子。
        ResourceLocation body = this.current.bodyMaterial;
        if (body != null && !body.equals(material)) {
            ResourceLocation bodyTexture = Dragom_golems.id(TEXTURE_PREFIX + body.getPath() + ".png");
            if (exists(bodyTexture)) {
                return bodyTexture;
            }
        }
        return findFallback();
    }

    private static ResourceLocation findFallback() {
        if (exists(FALLBACK_BASE)) {
            return FALLBACK_BASE;
        }
        return exists(FALLBACK_LEGACY) ? FALLBACK_LEGACY : FALLBACK_VANILLA;
    }

    private static boolean exists(ResourceLocation texture) {
        try {
            return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
        } catch (Exception e) {
            return false;
        }
    }
}
