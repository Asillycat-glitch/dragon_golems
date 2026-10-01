package a_silly_cat.dragom_golems.dragon;

import dev.xkmc.modulargolems.content.entity.common.SweepGolemEntity;
import a_silly_cat.dragom_golems.Dragom_golems;
import dev.xkmc.modulargolems.content.config.GolemMaterial;
import dev.xkmc.modulargolems.content.entity.goals.FollowOwnerGoal;
import dev.xkmc.modulargolems.content.entity.goals.GolemMeleeGoal;
import dev.xkmc.modulargolems.content.entity.goals.GolemRandomStrollGoal;
import dev.xkmc.modulargolems.content.entity.goals.TeleportToOwnerGoal;
import dev.xkmc.modulargolems.content.entity.humanoid.weapon.GolemWeaponRegistry;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.content.modifier.special.BaseRangedAttackGoal;
import dev.xkmc.modulargolems.content.modifier.special.SonicAttackGoal;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import dev.xkmc.l2serial.serialization.SerialClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.entity.PartEntity;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 傀儡龙本体。
 *
 * <p>继承 {@link SweepGolemEntity}（而不是 {@code AbstractGolemEntity}）是为了保留武器系统：
 * 主手武器、远程武器那套都挂在 {@code SweepGolemEntity} + {@link GolemWeaponRegistry} 上，
 * 以后要给龙上武器不用改继承链。
 *
 * <p><b>{@code @SerialClass} 不能删。</b>本家的 {@code AbstractGolemEntity.saveAdditional} 会用
 * l2serial 把整个实体自动序列化（存档 / 收回 / 生成同步包三条路都走它），而 l2serial 只认带这个
 * 注解的类。少了它，实体就存不进档、收不回物品、主人 UUID 也会在重载后丢失。
 * 以后往这个类里加需要持久化的字段，记得同时加 {@code @SerialClass.SerialField}。
 *
 * <p>飞行沿用原版"悦灵/蜜蜂"那一套（{@link FlyingMoveControl} + {@link FlyingPathNavigation} 三维寻路），
 * 另外在 {@link #aiStep()} 里补上"离地 N 格"的悬停高度（见 {@link #applyHover()}）。这里没有搬末影龙的
 * {@code DragonPhaseManager}——那套 phase AI 会和傀儡的跟随/指令系统打架。
 *
 * <p><b>待办：三种攻击（撞人 / 吐息 / 吐龙弹）。</b>接入点先记在这里：
 * <ul>
 *   <li>索敌：沿用本家那套就行（{@code AbstractGolemEntity} 自带目标管理与仇恨），
 *       独立技能用 {@code GolemModifier.onRegisterGoals} 或这里的 {@code addGoal} 加自己的 Goal，
 *       Goal 里 {@code canUse()} 判断"有目标 + 距离合适 + 冷却好了"。</li>
 *   <li>撞人：可以直接复用本家 {@code GolemMeleeGoal} 的判定思路，照着写一段
 *       "锁定落点 → 直线冲刺 → 路径上结算伤害"；冲刺期间要暂时关掉 {@link #applyHover()} 的抬升
 *       （俯冲到目标高度）。</li>
 *   <li>吐息 / 吐龙弹：属于远程，需要在 Goal 里播放前摇 + 生成投射物（本家
 *       {@code BaseRangedAttackGoal} / 各 compat 的 {@code *AttackGoal} 是现成范例），
 *       动作期间保持悬停高度不要俯冲。</li>
 * </ul>
 */
@SerialClass
public class DragonGolemEntity extends SweepGolemEntity<DragonGolemEntity, DragonGolemPartType> {

    /**
     * 龙的武器表。第一版是空表：只保留近战（近战走 {@code AbstractGolemEntity} 自己的
     * {@code GolemMeleeGoal}），弓/弩/枪械那类行为等确定 AI 之后再往里加。
     */
    public static final GolemWeaponRegistry<DragonGolemEntity> WEAPONS = new GolemWeaponRegistry<>();

    /** 悬停转向速度（度/tick），和原版悦灵一致。 */
    private static final int FLY_MAX_TURN = 20;

    /**
     * 期望的离地高度（格）：<b>判定箱底面离地面多少格</b>，模型脚底就钉在这个底面（见 MODEL_LIFT 的说明）。
     *
     * <p>龙的设计定位是"空中炮艇"，模型最底面至少离地 10 格，所以这里取 10。
     * 将来做冲刺/俯冲攻击时要临时压低这个高度（或在技能里接管竖直速度，本实体的
     * {@link #setDiveVelocity} 就是为这个准备的）。
     */
    public static final double HOVER_HEIGHT = 10.0D;
    /**
     * "原地不动待机"时的悬停高度：3 格。
     *
     * <p>注意触发条件不是"没有目标"，而是{@link DragonIdleGoal}真正进入<b>原地悬停待机</b>
     * （没目标满 30 秒）那一档，见 {@link #setIdleSettled(boolean)}。平时巡航、以及刚打完架还没
     * 停下来的时候都保持作战高度 10 格，所以"看到它贴地飘着"就等于"它已经待机了"，也为以后的
     * 骑龙飞行做准备（主人能直接走上去）。
     */
    public static final double HOVER_HEIGHT_IDLE = 3.0D;
    /**
     * 悬停高度随体型放大的指数：<b>0.5 = 开方</b>（4 倍体型 → 2 倍高度；10 格 → 20 格）。
     *
     * <p>为什么必须放大：这两个高度是"<b>离地多少格</b>"，而模型是跟着体型一起长的。
     * 泰坦升级（+300% 体型）时长身体有 100 多格、尾巴甩在身后 70 格，还贴在离地 10 格的话
     * 连自己半个身位都不到，坡地上整条尾巴会直接从地形里穿过去，看起来就是"趴在地上飞"。
     *
     * <p>为什么不线性：线性在 4 倍体型时是 40 格，而龙息锥只有 {@code DragonRangedGoal.CONE_LENGTH}(30) 格、
     * 龙弹射程 44 格 —— 飞那么高就再也够不着地面目标了。开方是"跟着体型涨、但涨得比体型慢"的折中。
     * 想让大体型飞得更高就把这个值调大（1.0 = 线性），想让它更贴地就调小（0.0 = 完全不吃体型）。
     */
    private static final double HOVER_SCALE_EXP = 0.5D;
    /** 基准移动速度：和 createAttributes 里写的 MOVEMENT_SPEED 一致，用来把材料/升级的移速换算成倍率。 */
    public static final double BASE_MOVEMENT_SPEED = 0.3D;
    /** 上升速度上限（格/tick），约 3 格/秒，太快会像窜天猴。 */
    private static final double HOVER_RISE_SPEED = 0.2D;
    /** 下降速度上限（格/tick）。悬停得是双向的，不然被顶高了就再也下不来。 */
    private static final double HOVER_FALL_SPEED = 0.12D;
    /**
     * 基础转向速度（度/tick）：18°/tick = 360°/秒，180° 掉头约 0.5 秒。
     *
     * <p>这是<b>慢速时的下限</b>，不是固定值：速度上来之后实际转速由 {@link #turnRateFor} 按
     * {@link #TURN_RADIUS} 一起放大。
     */
    private static final float MAX_TURN_PER_TICK = 18.0F;
    /**
     * 转弯半径（格）：速度一超过"基础转向能维持的半径"，转向速度就按 {@code ω = v / r} 一起涨，
     * 于是<b>不管飞多快，转弯半径都钉在这个值附近</b>。
     *
     * <p>为什么必须有这个：龙的飞行是 goal 直接写 {@code deltaMovement}，运动方向一 tick 就能掉头，
     * 而模型的 yaw 只能按转速慢慢转。原来转速写死 18°/tick —— 1 格/tick 时转弯半径约 3 格，模型跟得上；
     * 幽匿 / 泰坦那种 3 格/tick 时转弯半径变成 9.5 格，龙在画大圈而模型还朝着上一个方向，
     * 表现就是"转不过来 / 横着飞"。
     *
     * <p>调它 = 调"这条龙有多灵活"：调大转弯更沉更重，调小更灵活。
     */
    private static final double TURN_RADIUS = 4.0D;
    /** 转向速度上限（度/tick）：96°/tick ≈ 0.26 秒转 360°，再高就纯属防极端数值了。 */
    private static final float MAX_TURN_HARD_CAP = 96.0F;
    /** 侧倾：本 tick 转过 1° → 机身倾斜多少度（转弯越急压弯越明显）。 */
    private static final float ROLL_PER_DEGREE = 1.2F;
    /** 侧倾上限（度）。原来 42° 在高速转弯时会一直顶格，看起来像在甩尾。 */
    private static final float MAX_ROLL = 28.0F;
    /** 侧倾用的"转向速率"平滑系数：用低通后的值算倾斜，急转不会一帧打到顶。 */
    private static final float ROLL_TURN_LERP = 0.3F;
    /** 低于这个转速就算"没在转"，直接把侧倾归零（避免悬停时留着一丝压弯）。 */
    private static final float ROLL_DEAD_ZONE = 0.4F;

    // ---- 俯冲阶段（同步给客户端，只用于渲染姿态）----
    public static final byte DIVE_PHASE_NONE = 0;
    public static final byte DIVE_PHASE_APPROACH = 1;
    public static final byte DIVE_PHASE_DIVE = 2;
    public static final byte DIVE_PHASE_RETREAT = 3;
    /** 铲地（贴地掠过，沿途帧伤）。 */
    public static final byte DIVE_PHASE_PLOW = 4;
    /** 拉升回悬停高度。 */
    public static final byte DIVE_PHASE_CLIMB = 5;
    /** 悬停开火（龙息 / 龙弹前摇）：轻微低头，嘴里聚粒子。 */
    public static final byte DIVE_PHASE_BREATH = 6;
    /** 掠过攻击的"下降半程"：浅俯冲。 */
    public static final byte DIVE_PHASE_SKIM = 7;
    /** 掠过攻击的"上升半程"：浅拉升。 */
    public static final byte DIVE_PHASE_PULL = 8;

    private static final EntityDataAccessor<Byte> DATA_DIVE_PHASE =
            SynchedEntityData.defineId(DragonGolemEntity.class, EntityDataSerializers.BYTE);
    /** 机身侧倾（度）：转弯时 bank。由服务端在 {@link #faceMovement} 里算好、同步给客户端渲染。 */
    private static final EntityDataAccessor<Float> DATA_BODY_ROLL =
            SynchedEntityData.defineId(DragonGolemEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 机体俯仰（度，正 = 低头）：<b>服务端算好同步出去</b>。
     *
     * <p>必须同步而不是各算各的：子碰撞箱要跟着这个俯仰一起转（不然龙低头时模型沉下去、
     * 判定箱还挂在原地，表现就是"打不到龙头"），而俯仰原来只在客户端模型里插值，
     * 服务端根本不知道。现在两边都读这一个值。
     */
    private static final EntityDataAccessor<Float> DATA_BODY_PITCH =
            SynchedEntityData.defineId(DragonGolemEntity.class, EntityDataSerializers.FLOAT);

    // ---- 子碰撞箱几何（照抄原版末影龙的思路，数值按我们自己的模型换算）----
    // 这几个数字必须和 DragonGolemModel 的 MODEL_SCALE / FRONT_NECK / NECK_STEP 保持一致
    // （故意复制而不是 import 客户端的模型类：实体是两端共用的类，不想在服务端碰到客户端类型）。
    //
    // 下面这一整组都是「1 倍体型」的量。真正换算成世界偏移时统一走 rotateAndLift()，
    // 它会再乘一次本体的体型倍率 bodyScale()（详情见那个方法的注释）。
    /** 模型整体缩放（= DragonGolemModel.MODEL_SCALE）。 */
    private static final double MODEL_SCALE = 1.7D;
    /** 前颈段数与每段步长（像素），= DragonGolemModel.FRONT_NECK / NECK_STEP。 */
    private static final int FRONT_NECK = 5;
    private static final double NECK_STEP = 10.0D;
    /** 模型 1 像素 = 多少格（<b>1 倍体型下</b>）：网格 1 格 = 16 像素，整体再乘 MODEL_SCALE。 */
    private static final double PX = MODEL_SCALE / 16.0D;
    /** 和 {@code DragonGolemModel.MODEL_LIFT} 同源：模型整体被下压了多少格（1 倍体型下）。 */
    private static final double MODEL_LIFT = 1.36D;
    /** 本家 {@code LivingEntityRenderer} 在 {@code scale(-1,-1,1)} 之后又 {@code translate(0,-1.501,0)}。 */
    private static final double RENDER_LIFT = 1.501D;

    /** 当前姿态阶段对应的目标俯仰（度，正 = 低头）。和模型里的 PITCH_* 是同一张表。 */
    private static float pitchForPhase(byte phase) {
        return switch (phase) {
            case DIVE_PHASE_APPROACH -> -22.0F;
            case DIVE_PHASE_DIVE -> 55.0F;
            case DIVE_PHASE_RETREAT -> -32.0F;
            case DIVE_PHASE_PLOW -> 8.0F;
            case DIVE_PHASE_CLIMB -> -40.0F;
            case DIVE_PHASE_BREATH -> 10.0F;
            case DIVE_PHASE_SKIM -> 16.0F;
            case DIVE_PHASE_PULL -> -18.0F;
            default -> 0.0F;
        };
    }

    /** 服务端插值用的俯仰值。 */
    private float smoothPitch;

    /**
     * 这一 tick 只保留瞄准、不让"朝向 = 运动方向"接管朝向。
     *
     * <p>喷息改成"边飞边喷"之后，运动方向是<b>切线</b>（绕圈），而要打的方向是目标 —— 两者垂直，
     * 如果还让 {@link #faceMovement} 按运动方向转，身子就会侧过去、龙息跟着甩开。
     */
    private boolean keepAimThisTick;

    /** 供开火 goal 调用：本 tick 别用运动方向覆盖朝向。 */
    public void setKeepAim(boolean keep) {
        this.keepAimThisTick = keep;
    }

    /** 直接指定机身侧倾（度）：绕圈喷息时用它压弯，不靠 faceMovement 的转角去推。 */
    public void setBodyRollDirect(float deg) {
        this.entityData.set(DATA_BODY_ROLL, Mth.clamp(deg, -MAX_ROLL, MAX_ROLL));
    }

    /** 每 tick 更新俯仰并同步出去（判定箱和客户端模型都读 {@link #getBodyPitch()}）。 */
    private void tickBodyPitch() {
        this.smoothPitch = Mth.lerp(0.25F, this.smoothPitch, pitchForPhase(this.getDivePhase()));
        this.entityData.set(DATA_BODY_PITCH, this.smoothPitch);
    }

    /**
     * 把"模型像素偏移"换算成世界偏移，<b>并且跟着机体俯仰一起旋转</b>。
     *
     * <p>为什么不能直接乘 PX：模型空间里 +y 是"向下"、+z 是"向后"（本家渲染器在做模型之前先
     * {@code scale(-1,-1,1)}，MC 模型都这么画：humanoid 头在 y=-8、脚在 y=+24），中间还叠了两次平移
     * —— 本家的 {@code translate(0,-1.501,0)} 和模型自己的 {@code translate(0,-MODEL_LIFT,0)}，
     * 两次写在 flip 之后都会被反号，最终都变成"往上抬"。再加上模型的俯仰是绕<b>模型原点</b>转的，
     * 所以合成下来是：
     *
     * <pre>
     *   先按俯仰绕模型原点转一次（和模型 pose 里 mulPose(XP, bodyPitch) 同轴同号）：
     *     y' = y·cosθ − z·sinθ        (y = up 像素，z = −forward 像素)
     *     z' = y·sinθ + z·cosθ
     *   世界高度 = MODEL_LIFT + RENDER_LIFT − y' × PX
     *   世界前后 = −z' × PX
     * </pre>
     *
     * <p>校验：本家 humanoid 的脚底在模型里 y=24 像素，{@code 0 + 1.501 − 24/16 = 0} 正好落地 ✓；
     * 俯仰为 0 时退化成 {@code lift − px × PX}。左右（side）不参与俯仰，照旧 {@code px × PX}。
     * <p><b>最后整条再乘一次 {@link #bodyScale()}。</b>本家渲染器在 {@code setupRotations} 之后先
     * {@code scale(getScale())}、再 {@code translate(0,-1.501,0)}，而姿态矩阵是"后写的先作用"，
     * 所以那个 -1.501 也被体型倍率放大；模型里的 {@code translate(0,-MODEL_LIFT,0)} 同样在
     * scale 之后，一起放大。既然模型和本体判定箱都跟着体型走，子箱的偏移也必须跟着走 ——
     * 原来这里只按 1 倍算，装泰坦升级（+300% 体型 = 4 倍）时 15 个箱子会全挤在身体中心一小团里。
     *
     * @return {@code [世界高度, 世界前后]}（都相对实体 position）
     */
    private double[] rotateAndLift(double upPx, double forwardPx, float pitchDeg) {
        double a = Math.toRadians(pitchDeg);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double y = upPx;
        double z = -forwardPx;          // 模型 +z 是向后
        double yr = y * cos - z * sin;
        double zr = y * sin + z * cos;
        double scale = this.bodyScale();
        return new double[]{(MODEL_LIFT + RENDER_LIFT - yr * PX) * scale, -zr * PX * scale};
    }

    /**
     * 体型倍率：子碰撞箱、悬停高度、传送距离共用同一个值。
     *
     * <p>就是本家 {@code AbstractGolemEntity.getScale()}（{@code GOLEM_SIZE / 本类默认值}，再乘重铸系数）。
     * 渲染器（{@code AbstractGolemRenderer.scale}）和本体判定箱（{@code LivingEntity.getDimensions}）
     * 用的都是它，所以子箱必须用同一个值，不能自己另算一套。
     *
     * <p>夹在 {@code [0.25, 8]}：材质表还没同步、展示用假实体（{@code ClientOnly}）这些情况下
     * 本家会返回 1，正好是我们要的兜底；而第三方升级可能给出很极端的值
     * （傀儡地牢的泰坦升级 +300% 体型 → 4 倍），直接喂进判定箱会做出几百格宽的箱子。
     */
    public double bodyScale() {
        return Mth.clamp((double) this.getScale(), 0.25D, 8.0D);
    }

    /** 头（含前颈）中心：前颈从 z=-12 像素出发，每段 10 像素。 */
    private static final double HEAD_FORWARD_PX = 12.0D + FRONT_NECK * NECK_STEP;
    /** 头（含前颈）在模型里的高度（像素）：前颈起点，和 DragonGolemModel.NECK_BASE_Y_PX 同源。 */
    private static final double HEAD_UP_PX = 20.0D;
    /**
     * 嘴在头部件枢轴<b>前方</b>多少像素。
     *
     * <p>本家末影龙网格的 head 部件是：{@code upperlip} 从 z=-24 起（12×5×16）、
     * {@code upperhead} 从 z=-10 起（16×16×16）、{@code jaw} 枢轴在 (0,4,-8) 且盒子再往前 16 像素
     * 也到 z=-24。也就是说<b>头部件枢轴在嘴巴后方 24 像素处</b>，而原来这里只加了 6 ——
     * 龙息粒子和龙弹其实是从<b>颅骨内部</b>冒出来的（1 倍差 1.9 格，泰坦 4 倍差 7.7 格，很明显）。
     *
     * <p>取 20 = 从嘴唇尖端往里收 4 像素，正好落在"嘴里"。
     */
    private static final double MOUTH_FORWARD_PX = 20.0D;
    /**
     * 前颈中段补箱的位置（模型像素，正 = 前方）。
     *
     * <p>原来只有头尖那一个箱子（中心 62 像素、盖到 50.7 像素），脖子从 12 一直空到 50 像素，
     * 也就是「头箱和躯干箱之间有一段约 4 格的空洞，打脖子会打空」。这里取 5 段脖子里的第 2、4 段
     * （中心 27 / 47 像素），和躯干箱（盖到 +19.3 像素）、头箱（从 +50.7 像素起）首尾相接。
     */
    private static final double[] NECK_SEGMENTS_PX = {27.0D, 47.0D};
    /** 前颈箱的中心高度（像素）：和前颈起点同高。 */
    private static final double NECK_UP_PX = HEAD_UP_PX;
    /**
     * 尾巴：模型里从 z=+60 像素起，每段 10 像素，共 12 段（后方 60 → 180 像素）。
     *
     * <p>每 2 段放一个箱子（1/3/5/7/9/11 → 中心 70/90/110/130/150/170 像素）。
     * 箱子边长 2.0 格 ≈ 18.8 像素、间距 20 像素，相邻箱只差 1.2 像素 —— 等于连续覆盖。
     * 原来只有 3 个箱子（间距 50 像素），整条尾巴约 6 成没有判定，中间还留着两个 3.5 格的大空档。
     */
    private static final double TAIL_START_PX = 60.0D;
    private static final int[] TAIL_SEGMENTS = {1, 3, 5, 7, 9, 11};
    private static final double TAIL_UP_PX = 10.0D;
    /**
     * 躯干：模型身体是 24 × 24 × 64 像素的长条（≈ 2.55 × 2.55 × 6.8 格），
     * 而本体的判定箱只有 2.6 × 2.6，身体两端全都没盖住 —— 表现就是"打龙腹/龙背打不到"。
     * 这里用 4 个箱子沿身体中线摆一排补上（正 = 前方，和头部一样）。
     *
     * <p>第 4 个（-54 像素）是补身体最后端：身体模型到 -56 像素为止，原来最远的箱子在 -36，
     * 剩下 2 格多没盖住，而第一段尾巴箱要到 -61.6 像素才开始。
     */
    private static final double[] TORSO_SEGMENTS_PX = {8.0D, -14.0D, -36.0D, -54.0D};
    /** 躯干箱的中心高度：取原版躯干方块的中心（方块占 y=4..28 像素，加 PartPose 偏移后中心 ≈ 16）。 */
    private static final double TORSO_UP_PX = 16.0D;
    /** 双翼：中心横向偏移与前后位置（模型里翅膀从身体往两侧伸）。 */
    private static final double WING_SIDE_PX = 32.0D;
    /** 翅膀箱的中心高度：原版翅膀骨骼在 y=5 像素。 */
    private static final double WING_UP_PX = 5.0D;
    private static final double WING_FORWARD_PX = 0.0D;

    /** 头（连着头那一段前颈的末端）。 */
    private final DragonGolemPartEntity headPart;
    /** 前颈中段的补箱，长度 = {@link #NECK_SEGMENTS_PX}。 */
    private final DragonGolemPartEntity[] neckParts;
    /** 躯干补箱，长度 = {@link #TORSO_SEGMENTS_PX}。 */
    private final DragonGolemPartEntity[] torsoParts;
    /** 尾巴补箱，长度 = {@link #TAIL_SEGMENTS}。 */
    private final DragonGolemPartEntity[] tailParts;
    private final DragonGolemPartEntity leftWingPart;
    private final DragonGolemPartEntity rightWingPart;
    /**
     * Forge 要的连续子实体数组。
     *
     * <p>顺序 = id 分配顺序（{@code setId} 里按 {@code idx + 1} 排），所以调整这里的顺序会改变
     * 每个子箱的网络 id。这不影响正确性（子箱 id 只在本体被 tracking 时用一次），但别在运行时改长度。
     */
    private final DragonGolemPartEntity[] parts;

    /**
     * 俯冲/爬升期间由 {@link DragonDiveGoal} 每 tick 写入的期望速度（格/tick）。
     * {@code null} = 这一 tick 交回悬停与寻路。
     *
     * <p>写在实体上而不是 goal 里直接改速度，是因为 {@code Mob.aiStep} 里的
     * {@code moveControl.tick()} 会清掉竖直速度：只有在本方法（{@code super.aiStep()} 之后）
     * 写 {@code deltaMovement} 才拿得稳，悬停当初就是这个原因这么写的。
     */
    @Nullable
    private Vec3 diveVelocity;
    /** "摘掉本家近战 goal"的扫描倒计时，20 tick 兜一次。 */
    private int meleeCheckTimer;
    /**
     * 是否处于"原地不动待机"（{@link DragonIdleGoal} 沉降满 30 秒）。
     *
     * <p>由待机 goal 每 tick 写入；{@link #hoverHeight()} 用它决定该悬停在 10 格还是 3 格。
     * 注意它<b>不是</b>简单的"没有目标"：刚打完架、目标飞走、正在巡航时都还是作战高度。
     */
    private boolean idleSettled;
    /** 我们自己补挂上去的音波 goal（用来精确摘掉，不碰别人挂的）。 */
    private final ArrayList<Goal> sonicGoals = new ArrayList<>();

    // ---- 攻击调度 ----
    //
    // 设计：俯冲是"大招"、吃自己的冷却；**龙弹和音爆共享一个冷却**（CD 好了只放其中一个，
    // 由 pickSkill 在可用的技能里掷骰子）；**龙息没有冷却**，专门用来填满大招之间的空档
    // （原来的问题是两个大招同时冷却时龙什么都不干，变成"超长真空期"）。
    // 多个大招同时就绪时**掷骰子**决定先出哪个 —— 只看距离判定的话，某些距离上只剩一种可用，
    // 会变成一直复读同一个动作。

    /** 龙的四种攻击。 */
    public enum DragonSkill {
        /** 俯冲掠过。 */
        DIVE,
        /** 龙息锥。 */
        BREATH,
        /** 龙弹（抛射物 + 落点溅射）。 */
        ROCKET,
        /**
         * 音爆（瞬时线状穿透，回响伤害）。
         *
         * <p><b>只有幽匿身体的龙才有这一招</b>（见 {@link #isSonicBody()}）：音波是幽匿/监守者的东西，
         * 铁龙、下界龙喷的是火。
         *
         * <p>和 {@link #ROCKET} <b>共享冷却</b>：一次 CD 只放其中一个。
         */
        SONIC
    }

    /** 大招就绪时谁先出的权重（龙息不参与掷骰子）。 */
    private static final double W_DIVE = 0.5D;
    private static final double W_ROCKET = 0.5D;
    private static final double W_SONIC = 0.5D;
    /**
     * 各技能在 <b>1 倍体型</b>下的有效距离。
     *
     * <p>这些现在只是<b>基准值</b>：实际距离见 {@link #diveMinH()} / {@link #diveMaxH()} /
     * {@link #breathRange()} —— 它们都按 {@link #combatGeoScale()} 放大。原因很直接：
     * 泰坦升级（+300% 体型 = 4 倍）之后光是龙头就有 30 多格长，而原来整个交战包线只有 24~34 格，
     * 头比交战线还长 → 无论绕圈半径怎么设，都会出现"头越过目标、火往回喷"。
     */
    public static final double SKILL_DIVE_MIN_H = 4.0D;
    public static final double SKILL_DIVE_H = 34.0D;
    public static final double SKILL_BREATH = 24.0D;
    public static final double SKILL_ROCKET = 44.0D;
    /** 音爆光束的 1 倍基准长度（格）——和本家回响炮（SonicCannonBehavior）一样是 17。 */
    private static final double SONIC_BEAM_LENGTH = 17.0D;
    /** 音爆的触发距离比光束再远一点，让 goal 有机会先飞进去对准。 */
    private static final double SONIC_RANGE_MARGIN = 4.0D;
    /** 喷息绕圈半径的下限（格，身体中心 → 目标）。 */
    private static final double ORBIT_RADIUS_MIN = 11.0D;
    /**
     * 绕圈时希望"龙嘴离目标"至少留这么多格（见 {@link #orbitRadius()}）。
     *
     * <p>取 2.5 是给幽匿音波那 16 格闸门留余量：1 倍体型下绕圈半径 ≈ 11.2，
     * 龙悬停 10 格 → 三维 ≈ 15.0 格，还在本家 {@code SonicAttackGoal} 的 16 格之内。
     * 再大就会把音波挤掉（那是本家硬编码的，改不了）。
     */
    private static final double ORBIT_MOUTH_CLEARANCE = 2.5D;
    /** 进入喷息的闸门要比绕圈半径再远这么多：否则龙在半径以内进入，会先"头越过目标"一段。 */
    private static final double BREATH_RANGE_MARGIN = 8.0D;
    /**
     * 冷却：{@code max(下限, 基准 / 攻击速度)}，攻速越高越短。
     *
     * <p>攻速 4（龙的基准值）时：俯冲 30 秒；<b>龙弹 / 音爆共享的那条 15 秒</b>
     * （原来龙弹单独吃 20 秒 —— 现在一次 CD 只放一个技能，所以把 CD 降下来，
     * 整体出手频率不至于变低）。
     *
     * <p>龙息不吃这两个数，随时可用。
     */
    private static final int DIVE_CD_MIN = 600;
    private static final double DIVE_CD_BASE = 2400.0D;
    private static final int BLAST_CD_MIN = 300;
    private static final double BLAST_CD_BASE = 1200.0D;
    /** 掷出来却一直没被执行（目标跑掉/打不着）就重掷。 */
    private static final int SKILL_PENDING_TIMEOUT = 60;
    /**
     * 一次"已开打"的技能最多允许占住调度器多久（tick），超了就当它已经结束、放行重掷。
     *
     * <p>这是给 {@link #skillInProgress} 兜底的看门狗：正常路径由 {@link #onSkillFinished} 解除，
     * 但如果某个 goal 在 {@code onSkillStarted} 之后、真正打出去之前就被叫停
     * （典型的：龙弹瞄准到一半目标丢了 → {@code executed} 还是 null → 不会上报 onSkillFinished），
     * 执行态就会一直挂着，{@code pendingSkill} 再也重掷不了 —— 表现是"龙突然不打人了"。
     *
     * <p>取值要大于最长的技能：俯冲 {@code LINEUP_MAX(160) + PASS_MAX(200) = 360}。
     */
    private static final int SKILL_IN_PROGRESS_MAX = 400;

    @Nullable
    private DragonSkill pendingSkill;
    /** 俯冲 / 龙弹各自的冷却（tick）。 */
    private int diveCooldown;
    /**
     * 龙弹 / 音爆<b>共享</b>的冷却（tick）。
     *
     * <p>两者共用一条：CD 好了以后由 {@link #pickSkill} 在可用的技能里掷骰子，
     * 只放其中一个，然后这条 CD 重新开始计时。
     */
    private int blastCooldown;
    private int skillPendingTicks;
    /**
     * 这一轮的技能是否已经<b>开打</b>。开打之后就不再走 {@link #SKILL_PENDING_TIMEOUT} 重掷：
     * 一轮龙息是 20+60+10 = 90 tick，比 60 还长，不暂停的话会喷到一半被重掷，
     * 于是"这一轮的技能"被算到下一轮头上（冷却与出手归属全乱）。
     */
    private boolean skillInProgress;
    /** {@link #skillInProgress} 是什么时候置上的，供 {@link #SKILL_IN_PROGRESS_MAX} 看门狗判超时。 */
    private int skillStartedTick;
    /**
     * 侧倾用的平滑转向速率（度/tick）。
     *
     * <p>直接拿"本 tick 转过的角度"算倾斜会抖（转速上限一到就是 ±18°，倾斜立刻顶格），
     * 所以先低通再乘系数。停止移动后要把它慢慢收回去，否则会留着最后那个压弯角度。
     */
    private float smoothedTurn;

    public DragonGolemEntity(EntityType<DragonGolemEntity> type, Level level) {
        super(WEAPONS, type, level);
        // 飞行怪的移动控制在构造里换掉（原版 Mob 没有 createMoveControl 这个钩子）。
        this.moveControl = new FlyingMoveControl(this, FLY_MAX_TURN, true);
        // 子碰撞箱：头 1 + 前颈 2 + 躯干 4 + 尾巴 6 + 双翼 2 = 15 个。身体重心那一块就是本体自己的判定箱。
        // 这里给的宽高全是「1 倍体型」，实际尺寸由 DragonGolemPartEntity.getDimensions 乘体型倍率。
        this.headPart = new DragonGolemPartEntity(this, "head", 2.4F, 2.4F);
        this.neckParts = new DragonGolemPartEntity[NECK_SEGMENTS_PX.length];
        for (int i = 0; i < this.neckParts.length; i++) {
            this.neckParts[i] = new DragonGolemPartEntity(this, "neck", 2.0F, 2.0F);
        }
        // 躯干补箱：本体的判定箱只盖住重心那一块，剩下的长条靠这几个
        this.torsoParts = new DragonGolemPartEntity[TORSO_SEGMENTS_PX.length];
        for (int i = 0; i < this.torsoParts.length; i++) {
            this.torsoParts[i] = new DragonGolemPartEntity(this, "torso", 2.4F, 2.4F);
        }
        this.tailParts = new DragonGolemPartEntity[TAIL_SEGMENTS.length];
        for (int i = 0; i < this.tailParts.length; i++) {
            this.tailParts[i] = new DragonGolemPartEntity(this, "tail", 2.0F, 2.0F);
        }
        this.leftWingPart = new DragonGolemPartEntity(this, "left_wing", 5.0F, 1.0F);
        this.rightWingPart = new DragonGolemPartEntity(this, "right_wing", 5.0F, 1.0F);
        DragonGolemPartEntity[] all = new DragonGolemPartEntity[
                1 + this.neckParts.length + this.torsoParts.length + this.tailParts.length + 2];
        int n = 0;
        all[n++] = this.headPart;
        for (DragonGolemPartEntity part : this.neckParts) {
            all[n++] = part;
        }
        for (DragonGolemPartEntity part : this.torsoParts) {
            all[n++] = part;
        }
        for (DragonGolemPartEntity part : this.tailParts) {
            all[n++] = part;
        }
        all[n++] = this.leftWingPart;
        all[n] = this.rightWingPart;
        this.parts = all;
    }

    /** 子箱 id 必须是本体 id 的后继（Forge 的 MC-158205 修复写法），否则网络同步会串。 */
    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.parts != null) {
            for (int i = 0; i < this.parts.length; i++) {
                this.parts[i].setId(id + i + 1);
            }
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return this.parts;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_DIVE_PHASE, DIVE_PHASE_NONE);
        this.entityData.define(DATA_BODY_ROLL, 0.0F);
        this.entityData.define(DATA_BODY_PITCH, 0.0F);
    }

    /** 当前机体俯仰（度，正 = 低头）。判定箱摆位和客户端模型都读它。 */
    public float getBodyPitch() {
        return this.entityData.get(DATA_BODY_PITCH);
    }

    /** 机身侧倾（度）：客户端渲染用。 */
    public float getBodyRoll() {
        return this.entityData.get(DATA_BODY_ROLL);
    }

    /** 调试用：把本体与各子碰撞箱（头/尾/双翼）打成一个字符串，排查"判定箱到底在哪"。 */
    public String debugBoxes() {
        StringBuilder sb = new StringBuilder();
        appendBox(sb, "body", this.getBoundingBox());
        for (DragonGolemPartEntity part : this.parts) {
            appendBox(sb, part.getPartName(), part.getBoundingBox());
        }
        return sb.toString();
    }

    private static void appendBox(StringBuilder sb, String name, net.minecraft.world.phys.AABB box) {
        sb.append(name).append("=[")
                .append(String.format("%.2f", box.minX)).append("..").append(String.format("%.2f", box.maxX)).append(' ')
                .append(String.format("%.2f", box.minY)).append("..").append(String.format("%.2f", box.maxY)).append(' ')
                .append(String.format("%.2f", box.minZ)).append("..").append(String.format("%.2f", box.maxZ))
                .append("] ");
    }

    /** 当前俯冲阶段：客户端拿它决定身体俯仰姿态。 */
    public byte getDivePhase() {
        return this.entityData.get(DATA_DIVE_PHASE);
    }

    public void setDivePhase(byte phase) {
        this.entityData.set(DATA_DIVE_PHASE, phase);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // 回位传送：优先级 1，替换本家那套"三维 30 格就传送"的 TeleportToOwnerGoal
        this.goalSelector.addGoal(1, new DragonFollowTeleportGoal(this));
        // 待机移动（停留/盘旋）：优先级 4，接替被摘掉的 FollowOwnerGoal。
        // 本家那个 goal 的导航终点是主人的脚底，而龙被悬停顶在离地 HOVER_HEIGHT 格，
        // 于是它永远"到不了"却一直占着 MOVE —— 那就是"XZ 完全不移动"的原因。
        this.goalSelector.addGoal(4, new DragonIdleGoal(this));
        // 俯冲攻击：优先级 2（比待机/回位高），有目标时抢占 MOVE 跑完整套 LOOP → DIVE → PLOW → CLIMB，
        // 冷却期间 canUse() 返回 false，控制权自动交回 DragonIdleGoal 去随机游走。
        this.goalSelector.addGoal(2, new DragonDiveGoal(this));
        // 远程：优先级 3（低于俯冲、高于待机）。俯冲冷却那 30 秒就是它的开火窗口。
        this.goalSelector.addGoal(3, new DragonRangedGoal(this));
        // 音爆：优先级 2，和俯冲并列 —— 都是"被骰子抽中才上"的大招。
        // 必须比远程（3）高：这一轮抽中的是音爆时，要把 MOVE 从龙息/靠拢那里抢过来。
        // 同一优先级不会打架：whose canUse() 只看 wantsSkill(自己的技能)，而 pendingSkill 只有一个值。
        this.goalSelector.addGoal(2, new DragonSonicGoal(this));
    }

    /**
     * 悬停高度。
     *
     * <p><b>不要改寻路/移动控制的目标高度</b>：寻路判断"有没有走到路径点"用的是三维距离，
     * 把目标 Y 抬高三格会让它永远差三格、卡在第一个路径点上不动（上一版就是这么把龙整不会动的）。
     * 所以这里只在实体自己身上补一个上升速度：寻路照旧管水平移动，高度由我们自己纠正。
     *
     * <p>放在 {@code super.aiStep()} 之后是因为 {@code Mob.aiStep} 里会调 {@code moveControl.tick()}
     * 把竖直速度清零，先让它清、我们再覆盖，下一 tick 的 {@code travel} 才用得上。
     */
    @Override
    public void aiStep() {
        super.aiStep();
        // 俯仰由服务端算好同步出去（客户端只读，别自己写 entityData），再摆判定箱
        if (!this.level().isClientSide) {
            this.tickBodyPitch();
        }
        // 子碰撞箱跟着本体走（两端都要算：客户端要用它做准星命中）
        this.tickParts();
        if (!this.level().isClientSide) {
            this.diagnoseAttack();
            // 掷骰子选技能（两个攻击 goal 都靠这个决定自己该不该上）
            this.tickSkillRoll();
            // 摘掉本家挂上去的、我们不想要的 goal（近战 / 三维距离传送）
            this.disableNativeGoals();
            if (this.diveVelocity != null) {
                // 俯冲 / 拉升阶段：技能直接接管速度，悬停必须让位（它只会往上顶）。
                this.setNoGravity(true);
                this.setDeltaMovement(this.diveVelocity);
                this.fallDistance = 0.0F;
                if (this.keepAimThisTick) {
                    // 开火中：朝向由 goal 的 aimAt 管（绕圈喷息时运动方向是切线，不能拿来当朝向）
                    this.keepAimThisTick = false;
                } else {
                    // 我们自己写速度就没有 move control 帮忙转向了，这里补上"朝向 = 运动方向"
                    this.faceMovement(this.diveVelocity);
                }
                this.diveVelocity = null;
            } else {
                this.keepAimThisTick = false;
                // 这条路上没人写速度、也就没人更新侧倾，把它慢慢收平（否则会留着上一次的压弯角度）
                this.relaxRoll();
                this.applyHover();
            }
        }
    }

    /** 没有运动时把侧倾慢慢收回水平。 */
    private void relaxRoll() {
        if (this.smoothedTurn == 0.0F) {
            return;
        }
        this.smoothedTurn = Mth.lerp(ROLL_TURN_LERP, this.smoothedTurn, 0.0F);
        if (Math.abs(this.smoothedTurn) < ROLL_DEAD_ZONE) {
            this.smoothedTurn = 0.0F;
        }
        this.entityData.set(DATA_BODY_ROLL,
                Mth.clamp(this.smoothedTurn * ROLL_PER_DEGREE, -MAX_ROLL, MAX_ROLL));
    }

    // ---- 攻击调度（供 DragonDiveGoal / DragonRangedGoal 查询）----

    /**
     * 每 tick 决定"这一轮该用哪个技能"。
     *
     * <p>顺序是：两个大招 CD 各自递减 → 都就绪就掷骰子选一个 → 大招都用不了时<b>直接用龙息</b>
     * （龙息没有冷却，就是拿来填这两段空档的）→ 连龙息都够不着（超距/没视线）就返回 null，
     * 交给 {@link DragonRangedGoal} 的 CLOSE_IN 飞过去。
     */
    private void tickSkillRoll() {
        LivingEntity target = this.getTarget();
        if (this.diveCooldown > 0) {
            this.diveCooldown--;
        }
        if (this.blastCooldown > 0) {
            this.blastCooldown--;
        }
        if (target == null || !target.isAlive()) {
            this.pendingSkill = null;
            this.skillPendingTicks = 0;
            this.skillInProgress = false;
            return;
        }
        if (this.pendingSkill != null) {
            // 选出来一直没能执行（比如目标飞走了）就重选，避免卡死。
            // 但已经开打的那一轮不算"没能执行"，否则长技能会被自己的超时清掉。
            if (this.skillInProgress
                    && this.tickCount - this.skillStartedTick > SKILL_IN_PROGRESS_MAX) {
                // 看门狗：goal 在真正打出去之前就被叫停了（executed 还是 null → 没上报 onSkillFinished），
                // 执行态会一直挂着。这里强制放行，免得龙从此不再出手。
                this.skillInProgress = false;
            }
            if (!this.skillInProgress && ++this.skillPendingTicks > SKILL_PENDING_TIMEOUT) {
                this.pendingSkill = null;
                this.skillPendingTicks = 0;
            }
            return;
        }
        this.pendingSkill = this.pickSkill(target);
        this.skillPendingTicks = 0;
    }

    /**
     * 选这一轮用什么。
     *
     * <p>顺序：<b>在"这一轮可用的"大招之间按权重掷骰子</b>（俯冲一条 CD、龙弹/音爆共用另一条）
     * → 大招都用不了就退回龙息（它没有冷却）→ 连龙息都够不着就返回 null，交给 CLOSE_IN 飞过去。
     *
     * <p>只在可用的技能之间归一化权重，所以"某个距离上只剩一种可用"时它必然是那个，
     * 不会出现"抽到了打不着"的轮次。
     */
    @Nullable
    private DragonSkill pickSkill(LivingEntity target) {
        double dist = this.distanceTo(target);
        double hDist = Mth.sqrt((float) ((target.getX() - this.getX()) * (target.getX() - this.getX())
                + (target.getZ() - this.getZ()) * (target.getZ() - this.getZ())));
        boolean los = this.hasLineOfSight(target);
        boolean diveOk = this.diveCooldown <= 0 && hDist >= this.diveMinH() && hDist <= this.diveMaxH();
        // 龙弹和音爆共享一条 CD：CD 没好两个都不能上。
        // 音爆还要身体是幽匿（音波系），其它材料的龙不参与这个骰子。
        boolean blastReady = this.blastCooldown <= 0;
        boolean rocketOk = blastReady && dist <= SKILL_ROCKET && los;
        boolean sonicOk = blastReady && this.isSonicBody() && dist <= this.sonicRange() && los;

        double total = (diveOk ? W_DIVE : 0.0D)
                + (rocketOk ? W_ROCKET : 0.0D)
                + (sonicOk ? W_SONIC : 0.0D);
        if (total > 0.0D) {
            double roll = this.getRandom().nextDouble() * total;
            if (diveOk) {
                roll -= W_DIVE;
                if (roll < 0.0D) {
                    return DragonSkill.DIVE;
                }
            }
            if (rocketOk) {
                roll -= W_ROCKET;
                if (roll < 0.0D) {
                    return DragonSkill.ROCKET;
                }
            }
            // 走到这里只可能是 sonicOk（total 正好是可用项权重之和）
            return DragonSkill.SONIC;
        }
        // 大招都在冷却（或够不着）→ 龙息兜底，它没有冷却
        if (dist <= this.breathRange() && los) {
            return DragonSkill.BREATH;
        }
        return null;
    }

    /** 本轮待执行的技能（null = 大招都在冷却且龙息也够不着，这趟只走位）。 */
    @Nullable
    public DragonSkill pendingSkill() {
        return this.pendingSkill;
    }

    public boolean wantsSkill(DragonSkill skill) {
        return this.pendingSkill == skill;
    }

    /**
     * 某个 goal <b>真正开始执行</b>这一轮的技能时调用（龙息前摇 / 龙弹瞄准 / 俯冲 LINEUP）。
     *
     * <p>作用是把"待办超时重掷"暂停掉：一轮龙息 20+60+10 = 90 tick &gt;
     * {@link #SKILL_PENDING_TIMEOUT}(60)，不暂停的话这一轮会被它自己的超时清掉，
     * 冷却与出手归属随之错位。
     */
    public void onSkillStarted(DragonSkill skill) {
        if (this.pendingSkill != skill) {
            return;
        }
        this.skillInProgress = true;
        this.skillStartedTick = this.tickCount;
        this.skillPendingTicks = 0;
    }

    /**
     * 某个 goal 跑完一轮后调用。只有"确实执行了这个待办技能"时才清空并开始它自己的冷却，
     * 所以"只是飞过去（CLOSE_IN）"不会白白吃掉一次出手机会。
     *
     * <p>龙息不设冷却 —— 它就是用来填大招冷却空档的。
     * <p><b>龙弹和音爆共用 {@link #blastCooldown}：谁出手就由谁开始计时，另一个跟着一起进 CD。</b>
     */
    public void onSkillFinished(DragonSkill skill) {
        this.skillInProgress = false;
        if (this.pendingSkill != skill) {
            return;
        }
        this.pendingSkill = null;
        this.skillPendingTicks = 0;
        if (skill == DragonSkill.DIVE) {
            this.diveCooldown = this.skillCooldown(DIVE_CD_MIN, DIVE_CD_BASE);
        } else if (skill == DragonSkill.ROCKET || skill == DragonSkill.SONIC) {
            this.blastCooldown = this.skillCooldown(BLAST_CD_MIN, BLAST_CD_BASE);
        }
    }

    private int skillCooldown(int min, double base) {
        double attackSpeed = Math.max(2.0D, this.getAttributeValue(Attributes.ATTACK_SPEED));
        return Math.max(min, (int) Math.round(base / attackSpeed));
    }

    // ---- 下面这些是给调试/以后扩展用的读取口 ----

    /** 俯冲还剩多久可再出（tick）。 */
    public int diveCooldown() {
        return this.diveCooldown;
    }

    /** 龙弹 / 音爆<b>共享</b>的冷却还剩多久（tick）。 */
    public int blastCooldown() {
        return this.blastCooldown;
    }

    /** 音爆光束长度（格）：1 倍基准 × 体型。 */
    public double sonicBeamLength() {
        return SONIC_BEAM_LENGTH * this.combatGeoScale();
    }

    /** 抽中音爆的距离闸门（三维）：比光束略远一点，好让 goal 先飞进去对准。 */
    public double sonicRange() {
        return this.sonicBeamLength() + SONIC_RANGE_MARGIN;
    }

    /** 供 {@link DragonDiveGoal} 写入本 tick 的期望速度；传 {@code null} 表示交回悬停。 */
    public void setDiveVelocity(@Nullable Vec3 velocity) {
        this.diveVelocity = velocity;
    }

    /**
     * 飞行速度倍率。材料/升级改的是 {@code Attributes.MOVEMENT_SPEED}（百分比），
     * 但我们的移动是直接写速度、绕开了 {@code MoveControl}，所以那些加成原本一点不生效。
     * 待机游走、回位、俯冲、铲地、拉升的各个速度常量都乘这个倍率，材料与速度升级才真正有手感。
     *
     * <p>基准 {@link #BASE_MOVEMENT_SPEED}（0.3）→ 倍率 1；幽匿那种 +0.5 速度 → 1.5 倍；
     * 金材料 weight -0.4 → 0.6 倍。夹在 [0.4, 3.0] 防极端数值。
     */
    public double flightSpeedFactor() {
        double speed = this.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return Mth.clamp(speed / BASE_MOVEMENT_SPEED, 0.4D, 3.0D);
    }

    /**
     * "速度倍率"的统一出口：{@link #flightSpeedFactor()} 夹到 ≥ 1。
     *
     * <p>给各个 goal 放大那些<b>跟速度有关的几何量</b>用的：活动半径、到达判定、回位距离、
     * 竖向爬升上限…… 它们原本全是死写的绝对值，而水平速度会乘 {@code flightSpeedFactor()}
     * （幽匿 +0.5 移速、泰坦 +100% 移速都算在里面，最高 3 倍）。速度涨了而它们不涨，就会出现
     * "一 tick 就冲过目标点、在原地打转""爬升永远追不上地形"这类问题。
     *
     * <p>1 倍速度时返回 1，所以基准配置的手感和现在完全一样。
     */
    public double speedScale() {
        return Math.max(1.0D, this.flightSpeedFactor());
    }

    /**
     * 远程 / 俯冲的<b>几何</b>随体型放大的倍率。
     *
     * <p>= {@link #bodyScale()}，但缩小时按 1 算：小体型的龙本来就够得着，缩了只会更够不着。
     * 速度不用乘它 —— {@link #flightSpeedFactor()} 已经在管速度了，包线变大、速度不变，
     * 相对节奏才是对的。
     */
    public double combatGeoScale() {
        return Math.max(1.0D, this.bodyScale());
    }

    /** 俯冲的最小水平距离（1 倍基准 × 体型）。 */
    public double diveMinH() {
        return SKILL_DIVE_MIN_H * this.combatGeoScale();
    }

    /** 俯冲的最大水平距离（1 倍基准 × 体型）：4 倍体型时 34 → 136 格。 */
    public double diveMaxH() {
        return SKILL_DIVE_H * this.combatGeoScale();
    }

    /**
     * 喷息时想保持的绕圈半径（格，从身体中心量到目标）。
     *
     * <p>取 {@code max(ORBIT_RADIUS_MIN, 嘴的前伸量 + ORBIT_MOUTH_CLEARANCE)}：
     * 半径是量到身体中心的，而龙头在身体前方 8.7 格（1 倍）到 35 格（泰坦 4 倍）——
     * 半径不够大时龙嘴会直接穿过目标，火从背后倒着喷出来。
     */
    public double orbitRadius() {
        return Math.max(ORBIT_RADIUS_MIN, this.mouthForward() + ORBIT_MOUTH_CLEARANCE);
    }

    /**
     * 进入喷息的距离闸门（三维，身体中心 → 目标）。
     *
     * <p><b>必须 ≥ {@link #orbitRadius()} 并且留出余量。</b>否则龙会在半径以内就进入喷息，
     * 然后一边喷一边往外漂 —— 那段时间里头是越过目标的，就是"向后喷火"。
     * 1 倍体型：{@code max(24, 11.2 + 8) = 24}（和改之前一样）；泰坦 4 倍：约 45 格。
     */
    public double breathRange() {
        return Math.max(SKILL_BREATH, this.orbitRadius() + BREATH_RANGE_MARGIN);
    }

    /**
     * 当前水平速度下允许的转向速度（度/tick）。
     *
     * <p>速度低于"基础转向能维持的半径"时保持 {@link #MAX_TURN_PER_TICK}（慢速手感不变）；
     * 超过之后按 {@code ω = v / TURN_RADIUS} 一起涨，转弯半径就钉在 {@link #TURN_RADIUS} 附近 ——
     * 这样不管飞多快，模型都能真的朝着自己在飞的方向，而不是横着飘。
     *
     * @param horizontalSpeed 这一 tick 的水平速度（格/tick）
     */
    public float turnRateFor(double horizontalSpeed) {
        float byRadius = (float) Math.toDegrees(Math.max(0.0D, horizontalSpeed) / TURN_RADIUS);
        return Mth.clamp(Math.max(MAX_TURN_PER_TICK, byRadius), MAX_TURN_PER_TICK, MAX_TURN_HARD_CAP);
    }

    /**
     * 让实体朝向自己的运动方向。
     *
     * <p>为什么必须手动做：龙的移动现在由 {@link DragonIdleGoal} / 俯冲技能直接写速度，
     * 而 {@code FlyingMoveControl} 是唯一会调 {@code setYRot} 的地方（它按"想去哪"转向），
     * 绕开它之后 yRot 就没人更新了 —— 表现就是"一直在盘旋，但身子永远朝着同一个方向"。
     *
     * <p>另外 {@code Mob} 的身体朝向由 {@code BodyRotationControl} 管：**移动中**身体追 yRot，
     * **站着不动**时身体追"头部朝向"，而头会被 LookAtPlayer / RandomLookAround 随机转，
     * 所以这里顺手把 yHeadRot 钉回 yRot，免得待机时整条龙跟着乱转头。
     *
     * <p>转速由 {@link #turnRateFor} 按当前速度给出，所以速度越快转得越快、转弯半径不会跟着膨胀。
     */
    private void faceMovement(Vec3 velocity) {
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float applied = 0.0F;
        if (horizontal > 1.0E-3D) {
            // 原版约定：朝向 0 = +Z，前方向量 = (-sin, 0, cos)，所以用 atan2(dz, dx) - 90
            float want = (float) (Mth.atan2(velocity.z, velocity.x) * 180.0D / Math.PI) - 90.0F;
            float delta = Mth.wrapDegrees(want - this.getYRot());
            float maxTurn = this.turnRateFor(horizontal);
            applied = Mth.clamp(delta, -maxTurn, maxTurn);
            this.setYRot(this.getYRot() + applied);
        }
        // 侧倾 = 本 tick 实际转过的角度 × 系数（像飞机压弯）。
        // 在服务端算：客户端那边 yRot/yRotO 是插值值，推出来的"转向速率"会抖。
        // 先低通再换算：幽匿那种 2~3 格/tick 的速度下，瞬时转角经常就是 ±18°（转速上限），
        // 直接用瞬时值会让机身一直顶在极限角度、来回甩。
        this.smoothedTurn = Mth.lerp(ROLL_TURN_LERP, this.smoothedTurn, applied);
        float roll = Math.abs(this.smoothedTurn) < ROLL_DEAD_ZONE
                ? 0.0F
                : Mth.clamp(this.smoothedTurn * ROLL_PER_DEGREE, -MAX_ROLL, MAX_ROLL);
        this.entityData.set(DATA_BODY_ROLL, roll);
        // 直接把身体朝向也拉到 yRot：vanilla 的 BodyRotationControl 每 tick 只朝 yRot 转 15° 还带滞回，
        // 等它慢慢跟就会"转向慢半拍"。这里自己写，模型、子碰撞箱（按 yBodyRot 摆位）立刻对齐。
        this.yBodyRot = this.getYRot();
        this.setYHeadRot(this.getYRot());
    }

    /**
     * 按身体朝向摆放子碰撞箱。
     *
     * <p>参数是<b>模型像素</b>（side / up / forward，正 = 右/上/前），换算成世界偏移时统一走
     * {@link #rotateAndLift}：它会把"模型 y 向下、模型 z 向后、两次 lift 平移、以及机体俯仰"一次性算进去，
     * 所以龙低头/抬头时头尾的判定箱会<b>跟着模型一起转</b>（这就是"模型动了判定箱不动"的修复）。
     */
    private void tickParts() {
        double yaw = Math.toRadians(this.yBodyRot);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        float pitch = this.getBodyPitch();
        // 体型变了就按新倍率重算子箱判定箱。本家 checkSize() 只刷本体（本体是它自己那一份），
        // 子箱是独立实体、没人替它刷，所以放在每 tick 摆位之前。
        float scale = (float) this.bodyScale();
        for (DragonGolemPartEntity part : this.parts) {
            part.refreshForScale(scale);
        }

        this.placePart(this.headPart, 0.0D, HEAD_UP_PX, HEAD_FORWARD_PX, pitch,
                forwardX, forwardZ, rightX, rightZ);
        for (int i = 0; i < this.neckParts.length; i++) {
            this.placePart(this.neckParts[i], 0.0D, NECK_UP_PX, NECK_SEGMENTS_PX[i], pitch,
                    forwardX, forwardZ, rightX, rightZ);
        }
        for (int i = 0; i < this.torsoParts.length; i++) {
            this.placePart(this.torsoParts[i], 0.0D, TORSO_UP_PX, TORSO_SEGMENTS_PX[i], pitch,
                    forwardX, forwardZ, rightX, rightZ);
        }
        for (int i = 0; i < this.tailParts.length; i++) {
            // 负 forward：模型里 +Z 是尾巴方向，"前方"是 -Z
            double forward = -(TAIL_START_PX + TAIL_SEGMENTS[i] * NECK_STEP);
            this.placePart(this.tailParts[i], 0.0D, TAIL_UP_PX, forward, pitch,
                    forwardX, forwardZ, rightX, rightZ);
        }
        this.placePart(this.leftWingPart, -WING_SIDE_PX, WING_UP_PX, WING_FORWARD_PX, pitch,
                forwardX, forwardZ, rightX, rightZ);
        this.placePart(this.rightWingPart, WING_SIDE_PX, WING_UP_PX, WING_FORWARD_PX, pitch,
                forwardX, forwardZ, rightX, rightZ);
    }

    /** 参数全是模型像素（side / up / forward，正 = 右/上/前），pitch 是当前的机体俯仰（度）。 */
    private void placePart(DragonGolemPartEntity part, double sidePx, double upPx, double forwardPx,
                           float pitch, double forwardX, double forwardZ, double rightX, double rightZ) {
        double[] upForward = this.rotateAndLift(upPx, forwardPx, pitch);
        // 左右也乘体型倍率：模型（渲染器的 scale）和判定箱（LivingEntity.getDimensions）都跟着体型走
        double side = sidePx * PX * this.bodyScale();
        double x = this.getX() + rightX * side + forwardX * upForward[1];
        double y = this.getY() + upForward[0];
        double z = this.getZ() + rightZ * side + forwardZ * upForward[1];
        part.setPos(x, y, z);
        // 不做插值：子箱必须严格跟住本体，否则玩家的准星会打空
        part.xo = x;
        part.yo = y;
        part.zo = z;
        part.xOld = x;
        part.yOld = y;
        part.zOld = z;
    }

    /**
     * 让幽匿材料的"音波"真正落到龙身上。
     *
     * <p>本家的 {@code SonicModifier.canExistOn} 是硬编码的：
     * {@code part == GolemItems.GOLEM_BODY.get()} —— 只认<b>金属傀儡的身体物品</b>。龙的 body 是另一个
     * 物品，于是 {@code GolemPart.parseMaterial} 会把音波这条 modifier 直接丢掉：材料铸得上去、
     * 属性也算得对，但龙就是不会音波。
     *
     * <p>这里在属性刷新之后补一刀：只要<b>身体部件</b>是幽匿，就把音波加回 modifier 表、并注册它自己
     * 的 goal（本家那套，从"胸口"位置 {@code position + 1.6} 发射，和龙息各打各的、互不干扰）。
     *
     * <p><b>只能按 id 从注册表里取，不能写成 {@code GolemModifiers.SONIC.get()}：</b>
     * 装了 mgdp 之类的 mod 时，{@code modulargolems:sonic_boom} 会被它们替换成自己的
     * {@code SonicBoomModifier}（同样是 {@code GolemModifier} 的子类，但不是 {@code SonicModifier}），
     * 用泛型入口取会直接 ClassCastException —— 那个异常发生在 {@code GolemHolder.summon} 里，
     * 表现就是"带幽匿身体的龙放不下来"。
     */
    @Override
    public void updateAttributes(ArrayList<GolemMaterial> materials, ArrayList<IUpgradeItem> upgrades, UUID owner) {
        super.updateAttributes(materials, upgrades, owner);
        if (this.level().isClientSide()) {
            return;
        }
        // 本家刚把 modifier goal 重挂了一遍（modifierGoals 是 private，我们只能扫 goalSelector），
        // 立刻摘掉不想要的那些 —— 否则最长要等 disableNativeGoals 那 20 tick 的兜底窗口，
        // 期间本家近战（换手 / 读档时 reassessWeaponGoal 会把它加回优先级 3）会和龙的技能一起出手。
        this.stripNativeGoals();
        // 先精确撤掉上一轮我们补挂的那些 goal（本家重算属性时会重建 modifier 表，这里跟着重建）
        for (Goal goal : this.sonicGoals) {
            this.goalSelector.removeGoal(goal);
        }
        this.sonicGoals.clear();
        boolean bodySculk = false;
        for (GolemMaterial mat : materials) {
            if (mat.part() == DragonGolemItems.BODY.get() && mat.id().equals(SCULK_MATERIAL)) {
                bodySculk = true;
                break;
            }
        }
        if (!bodySculk) {
            return;
        }
        // 注意这里是 Forge 的 IForgeRegistry（不是原版 Registry），取值用 getValue
        GolemModifier sonic = GolemTypes.MODIFIERS.get().getValue(SONIC_MODIFIER_ID);
        if (sonic == null) {
            return;
        }
        // 别人（第三方 mod）已经把这条放行了的话就别插手，让它自己那套走
        if (sonic.canExistOn(DragonGolemItems.BODY.get())) {
            return;
        }
        this.getModifiers().put(sonic, 1);
        sonic.onRegisterGoals(this, 1, (priority, goal) -> {
            this.sonicGoals.add(goal);
            this.goalSelector.addGoal(priority, goal);
        });
    }

    /**
     * 临时诊断：有目标却"什么都不做"时，每 5 秒打一行说明原因。
     *
     * <p>排查"发射一次以后就再也攻击"用的：一行里同时给出目标、距离、视线、能不能动、
     * 当前姿态阶段，以及 1~4 号 goal 谁在跑（带 {@code *} 的那个）。定位完把
     * {@link #ATTACK_DIAGNOSTIC} 改成 false 就不再有输出。
     */
    private static final boolean ATTACK_DIAGNOSTIC = false;

    private void diagnoseAttack() {
        if (!ATTACK_DIAGNOSTIC || this.tickCount % 100 != 0) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (target == null) {
            return;
        }
        StringBuilder goals = new StringBuilder();
        for (WrappedGoal wrapped : this.goalSelector.getAvailableGoals()) {
            if (wrapped.getPriority() > 4) {
                continue;
            }
            goals.append(wrapped.getPriority())
                    .append(':')
                    .append(wrapped.getGoal().getClass().getSimpleName())
                    .append(wrapped.isRunning() ? "*" : "")
                    .append(' ');
        }
        Dragom_golems.LOGGER.info(String.format(
                "[atk] target=%s dist=%.1f hDist=%.1f los=%s movable=%s sit=%s passenger=%s phase=%d goals=[%s]",
                target.getName().getString(), this.distanceTo(target),
                Math.sqrt((target.getX() - this.getX()) * (target.getX() - this.getX())
                        + (target.getZ() - this.getZ()) * (target.getZ() - this.getZ())),
                this.hasLineOfSight(target), this.isMovable(), this.isInSittingPose(),
                this.getControllingPassenger() != null, this.getDivePhase(), goals.toString().trim()));
    }

    /**
     * 摘掉本家的近战 goal（{@link GolemMeleeGoal}）。
     *
     * <p>它是 {@code GolemWeaponManager} 在构造时按优先级 3 挂上的，手里没武器时默认就走它。
     * {@code AbstractWeaponManager} 只在 {@code meleeActive == false} 时才会再加回去，
     * 所以摘一次基本就够了；读档 / 换手会触发重新评估，这里每 20 tick 兜一遍更稳。
     */
    private void disableNativeGoals() {
        if (this.meleeCheckTimer > 0) {
            this.meleeCheckTimer--;
            return;
        }
        this.meleeCheckTimer = 20;
        this.stripNativeGoals();
    }

    /** 真正干活的那一半：把不想要的本家 goal 从选择器里摘掉（{@code updateAttributes} 会立刻复用一次）。 */
    private void stripNativeGoals() {
        for (WrappedGoal wrapped : new ArrayList<>(this.goalSelector.getAvailableGoals())) {
            Goal goal = wrapped.getGoal();
            if (goal instanceof GolemMeleeGoal
                    || goal instanceof TeleportToOwnerGoal
                    // 这两个会把 MOVE 通道占死，或者对悬停的龙没有意义，交给 DragonIdleGoal
                    || goal instanceof FollowOwnerGoal
                    || goal instanceof GolemRandomStrollGoal
                    || isForeignRangedGoal(goal)) {
                this.goalSelector.removeGoal(goal);
            }
        }
    }

    /**
     * 是不是本家（含 compat 材料）那一批<b>远程攻击 goal</b>。
     *
     * <p><b>为什么整批摘掉：</b>那批 goal 全是照"站在地上的傀儡"写的 ——
     * {@code BaseRangedAttackGoal.canUse} 用三维 {@code distanceToSqr} 比固定射界
     * （{@code near}/{@code far}，多数是 0~48），出手点在自身坐标（对龙来说是<b>身体中心</b>，
     * 不是嘴），而且伤害走原版 {@code hurt}，<b>会把目标重新顶进无敌帧</b> ——
     * 我们刚给龙息做完的"独立无敌帧"会被它们抵消掉。龙的判定箱、嘴部位置、悬停高度都跟它们对不上，
     * 所以先整批摘掉；以后要放回来，得逐个改成"从 {@code mouthPosition()} 发射 + 射程按体型缩放"。
     *
     * <p>不在此列的：{@code PickupGoal}（捡东西）{@code setFlags} 为空、只扫自身 AABB，不影响我们；
     * {@code EnderTeleportGoal}（末影传送）不是伤害技能，也留着。
     */
    private static boolean isForeignRangedGoal(Goal goal) {
        // 本家自己的两个类能直接引用，按类型判
        if (goal instanceof BaseRangedAttackGoal || goal instanceof SonicAttackGoal) {
            return true;
        }
        // compat 的独立 goal 不能按类引用（那些 mod 不在我们的编译依赖里），按"包名 + 类名后缀"认：
        // IgnisFireballAttackGoal / HarbingerDeathBeamAttackGoal / ScyllaLightningAttackGoal / BlazeAttackGoal …
        String name = goal.getClass().getName();
        return name.startsWith("dev.xkmc.modulargolems.") && name.endsWith("AttackGoal");
    }

    /**
     * 俯冲撞击的结算入口：走本家的横扫管线（{@code performRangedDamage}），
     * 所以材料提供的 {@code modulargolems:sweep} 会对撞击点周围一起生效，
     * 伤害则按基准攻击力乘一个倍率。
     */
    public boolean diveImpact(LivingEntity target, float damageMult, double knockback) {
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * damageMult;
        return this.performRangedDamage(target, damage, knockback);
    }

    /**
     * 俯冲路径上"擦到"的敌人：只结算这一个目标，<b>不走横扫</b>。
     * 横扫是围绕目标再打一圈，路径伤害如果每段都触发横扫，同一个敌人会在同一 tick 里被重复结算。
     */
    public boolean diveGraze(LivingEntity target, float damageMult, double knockback) {
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * damageMult;
        return this.performDamageTarget(target, damage, knockback);
    }

    // ---- 远程攻击（DragonRangedGoal / DragonGolemFireball 用）----

    /** 本家幽匿材料的 id（音波特判用）。 */
    private static final ResourceLocation SCULK_MATERIAL = new ResourceLocation("modulargolems", "sculk");
    /** 龙息（普通 / 下界身体）的伤害类型，见 {@code data/dragom_golems/damage_type/dragon_breath.json}。 */
    private static final ResourceLocation BREATH_DAMAGE_TYPE = Dragom_golems.id("dragon_breath");
    /** 幽匿身体的音波龙息：额外穿护甲与附魔，见同目录的 {@code dragon_sonic.json} 与 {@code data/minecraft/tags/damage_type/}。 */
    private static final ResourceLocation SONIC_DAMAGE_TYPE = Dragom_golems.id("dragon_sonic");
    /** 本家音波 modifier 的 id（必须按 id 取：第三方 mod 会把这条换成自己的实现）。 */
    private static final ResourceLocation SONIC_MODIFIER_ID = new ResourceLocation("modulargolems", "sonic_boom");
    /** 本家下界合金材料的 id（龙息附带凋零用）。 */
    private static final ResourceLocation NETHERITE_MATERIAL = new ResourceLocation("modulargolems", "netherite");

    /**
     * 嘴的位置（世界坐标）：头部件的前方一点，龙息和龙弹都从这里出。
     *
     * <p>算法和 {@link #tickParts()} 摆头部件用的是同一套（前颈 5 段 × 每段 10 像素 + 头本身），
     * 所以粒子看起来永远是从嘴巴里出来，而不是从身体里。多出来那 6 像素是"嘴里"。
     */
    public Vec3 mouthPosition() {
        double yaw = Math.toRadians(this.yBodyRot);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        // 和头部件同一套换算（含机体俯仰），这样低头喷息时粒子还是从嘴里出来
        double[] upForward = rotateAndLift(HEAD_UP_PX + 2.0D, HEAD_FORWARD_PX + MOUTH_FORWARD_PX,
                this.getBodyPitch());
        return new Vec3(this.getX() + forwardX * upForward[1], this.getY() + upForward[0],
                this.getZ() + forwardZ * upForward[1]);
    }

    /**
     * 嘴在身体中心<b>正前方</b>的水平距离（格，已经含机体俯仰和体型倍率）。
     *
     * <p>绕圈喷息要用它：绕圈半径是从<b>身体中心</b>量的，而龙头在身体前方这么远，
     * 所以"嘴离目标还有多远" = |绕圈半径 − 这个值|。1 倍体型约 7.2 格、泰坦（4 倍）约 29 格 ——
     * 半径小于它时龙头就直接穿到目标身上甚至背后去了。
     */
    public double mouthForward() {
        return Math.abs(this.rotateAndLift(HEAD_UP_PX + 2.0D, HEAD_FORWARD_PX + MOUTH_FORWARD_PX,
                this.getBodyPitch())[1]);
    }

    /**
     * 朝一个水平位置慢慢转头（度/tick 上限）。
     *
     * <p>为什么不能直接用 {@code lookAt}：那玩意每 tick 能拽 30°，看起来像抽搐；而且它只改头，
     * 身体和子碰撞箱要等到 {@code BodyRotationControl} 慢慢跟。这里和 {@link #faceMovement} 一样
     * 一次性把 yRot / yBodyRot / yHeadRot 对齐，模型和判定箱立刻一致。
     */
    public void aimAt(double x, double z, float maxDegPerTick) {
        float want = (float) (Mth.atan2(z - this.getZ(), x - this.getX()) * 180.0D / Math.PI) - 90.0F;
        float delta = Mth.wrapDegrees(want - this.getYRot());
        float applied = Mth.clamp(delta, -maxDegPerTick, maxDegPerTick);
        this.setYRot(this.getYRot() + applied);
        this.yBodyRot = this.getYRot();
        this.setYHeadRot(this.getYRot());
    }

    /**
     * 龙息的一跳伤害。
     *
     * <p><b>走我们自己的伤害类型</b>（{@code data/dragom_golems/damage_type/}）：
     * 普通 / 下界身体用 {@code dragon_breath}（吃护甲、吃抗性、吃附魔，和原版 {@code mob_attack} 同一档）；
     * <b>幽匿身体用 {@code dragon_sonic}</b> —— 额外躺在 {@code bypasses_armor} / {@code bypasses_enchantments} 里，
     * 穿护甲与附魔，但抗性药水仍然挡得住，和原版 {@code sonic_boom}、L2 音爆枪同一档。
     *
     * <p><b>为什么不用原版那两条：</b>原版的 {@code minecraft:bypasses_cooldown} 标签是<b>空的</b>
     * （连 {@code sonic_boom} 自己都不免无敌帧），而这条通道每 10 tick 就打一次 ——
     * 只要把目标顶进无敌帧，旁边的地面傀儡一拳上去就会被按差值削掉一截、甚至整个被吞。
     * 我们自己那两条伤害类型都塞进了 {@code bypasses_cooldown}，再配 {@link #hurtWithoutFrames}
     * 把目标原本的无敌帧原样放回去，于是"龙的持续输出"和"地面傀儡的单发重击"互不干扰。
     *
     * <p><b>不要改用 MG 的 {@code echo_attack}：</b>那条连抗性、药水效果、无敌帧都穿，
     * 放在"每 10 tick 跳一次"的常态群攻上会明显超模。{@link DragonSkill#SONIC} 那个大招才用它
     * （15 秒一发，一次性爆发，真伤是它的卖点）。
     *
     * <p>不带横扫 —— 龙息本来就是靠锥体覆盖多个目标的。
     */
    public boolean breathDamage(LivingEntity target, float damageMult, double knockback) {
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * damageMult;
        DamageSource source = this.breathSource(this.isSonicBody() ? SONIC_DAMAGE_TYPE : BREATH_DAMAGE_TYPE);
        target.setLastHurtByMob(this);
        boolean hurt = this.hurtWithoutFrames(target, source, damage);
        if (hurt && knockback > 0.0D) {
            target.knockback(knockback, this.getX() - target.getX(), this.getZ() - target.getZ());
        }
        return hurt;
    }

    /** 按 id 从动态注册表里取我们自己的伤害类型；数据包被改坏时退回原版，免得整条通道哑火。 */
    private DamageSource breathSource(ResourceLocation type) {
        try {
            return new DamageSource(
                    this.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                            .getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, type)),
                    this);
        } catch (RuntimeException e) {
            return this.damageSources().mobAttack(this);
        }
    }

    /**
     * 打一下，但<b>不把目标顶进无敌帧</b>。
     *
     * <p>原版 {@code LivingEntity.hurt} 只要命中成功就会把目标的 {@code invulnerableTime} 重置成 20 tick，
     * 于是"每 10 tick 跳一次"的龙息会让目标长期处在无敌帧里：地面上的傀儡一拳上去，
     * 要么被按差值削掉一截，要么直接吞掉。
     *
     * <p>所以这里把目标原本的 {@code invulnerableTime} 记下来、打完再放回去
     * （{@code Entity.invulnerableTime} 是 public 字段，**不用 mixin**）。我们自己的伤害照样进得去
     * （伤害类型在 {@code bypasses_cooldown} 里，不靠无敌帧判定），而且<b>不会替别人占住无敌帧</b>。
     *
     * <p>{@code lastHurt} 是 protected 改不了 —— 它只参与"无敌帧内的差值结算"，
     * 而且我们的命中把它压低反而让后面的大伤害进得更完整，所以放着不管。
     */
    private boolean hurtWithoutFrames(LivingEntity target, DamageSource source, float damage) {
        int saved = target.invulnerableTime;
        boolean hurt = target.hurt(source, damage);
        target.invulnerableTime = saved;
        return hurt;
    }

    /** 龙弹的直击/溅射：伤害类型 {@code mob_projectile}，攻击者是龙（所以药水升级照样生效）。 */
    public boolean rocketDamage(LivingEntity target, net.minecraft.world.entity.projectile.Projectile ball,
                                float damageMult, double knockback) {
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * damageMult;
        boolean hurt = target.hurt(this.damageSources().mobProjectile(ball, this), damage);
        if (hurt && knockback > 0.0D) {
            target.knockback(knockback, ball.getX() - target.getX(), ball.getZ() - target.getZ());
        }
        return hurt;
    }

    /**
     * 龙息用的粒子。
     *
     * <p>主题映射：<b>幽匿 → 音波</b>（监守者那一套）、<b>下界合金 → 灵魂火</b>、其它 → 普通火焰。
     * （原来这里是反的：灵魂火给了幽匿，而"下界"那档反倒只是普通火焰 —— 灵魂沙/灵魂土本来就在下界。）
     *
     * <p>想整体换风格就只改这一个方法：龙息前摇、喷流、落点溅射、枪口火苗、以及音爆的前摇
     * 全都读它。
     */
    public ParticleOptions breathParticle() {
        if (this.isSonicBody()) {
            return ParticleTypes.SONIC_BOOM;
        }
        return this.isNetherBody() ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME;
    }

    /**
     * 龙弹落点那团"残留云"用的粒子。
     *
     * <p>原来是硬编码的原版 {@code DRAGON_BREATH}（那种紫色），所有材料的龙都吐同一朵紫云 ——
     * 下界龙吐紫云比龙息还违和。现在和 {@link #breathParticle()} 同一套映射，
     * 只是云不适合用"一次性"的音波环，幽匿那档换成飘散的 {@code SCULK_SOUL}。
     */
    public ParticleOptions breathCloudParticle() {
        if (this.isSonicBody()) {
            return ParticleTypes.SCULK_SOUL;
        }
        return this.isNetherBody() ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.DRAGON_BREATH;
    }

    /**
     * 这条龙是不是"音波系"：<b>身体（BODY）那个部件</b>用了幽匿。
     *
     * <p><b>主题看的是身体部件，不是"任意一个部件"。</b>五个部件可以混装五种材料，
     * 如果按"任意部件"判定，一条幽匿爪子 + 下界身体的龙到底算哪一系就要靠一个武断的优先级；
     * 身体是这条龙的核心，也是本家音波 modifier 的挂点（见 {@link #updateAttributes} 里
     * {@code mat.part() == DragonGolemItems.BODY.get()} 那条判断），所以统一由它说了算。
     * 想改成"混装任意部件都算"的话，把 {@link #bodyHasMaterial} 换回 {@link #hasMaterial} 即可。
     *
     * <p>音波在设定上就是幽匿 / 监守者的东西，所以这些全挂在它身上：
     * <ul>
     *   <li>龙息的粒子和伤害类型（喷的是音波，不是火）；</li>
     *   <li>{@link DragonSkill#SONIC} 那个大招 —— <b>只有幽匿身体的龙才有</b>；
     *       其它材料的龙回到"俯冲 / 龙息 / 龙弹"三招。</li>
     * </ul>
     * 材料列表是两端同步的（贴图也靠它取），所以客户端问这个问题同样有效。
     */
    public boolean isSonicBody() {
        return this.bodyHasMaterial(SCULK_MATERIAL);
    }

    /** 身体部件是不是下界合金（灵魂火那一系，见 {@link #isSonicBody()} 的说明）。 */
    public boolean isNetherBody() {
        return this.bodyHasMaterial(NETHERITE_MATERIAL);
    }

    /**
     * 龙息 / 龙弹带来的负面效果（<b>自带的那一层</b>，和傀儡装配的药水效果升级互不影响：
     * 那些是靠 {@code LivingHurtEvent} 自动附加的，见 {@code PotionAttackModifier}）。
     *
     * <ul>
     *   <li>幽匿身体：黑暗 + 虚弱 I（各 5 秒，够喷息的三秒之后还留一点余味）；</li>
     *   <li>下界合金身体：着火 5 秒 + 凋零 I；</li>
     *   <li>其它：着火 3 秒。</li>
     * </ul>
     */
    public void applyBreathEffects(LivingEntity target) {
        if (this.isSonicBody()) {
            target.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0), this);
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0), this);
            return;
        }
        if (this.isNetherBody()) {
            target.setSecondsOnFire(5);
            target.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0), this);
            return;
        }
        target.setSecondsOnFire(3);
    }

    /**
     * <b>身体部件</b>用了这个材料才返回 true（主题判定的唯一入口）。
     *
     * <p>不能用"任意部件"：五个部件可以混装，那样一条龙会同时满足好几个主题，
     * 只能靠一个武断的优先级去压，玩家也看不懂自己这条龙算哪一系。
     */
    private boolean bodyHasMaterial(ResourceLocation id) {
        for (GolemMaterial mat : this.getMaterials()) {
            if (mat.part() == DragonGolemItems.BODY.get() && mat.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 身上任意一个部件用了这个材料就返回 true。 */
    private boolean hasMaterial(ResourceLocation id) {
        for (GolemMaterial mat : this.getMaterials()) {
            if (mat.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void applyHover() {
        double dy = hoverTargetY() - this.getY();
        if (Math.abs(dy) <= 0.2D) {
            return;
        }
        if (dy < 0.0D) {
            // 高于目标高度：慢慢压回去。
            // 原来只有"往上顶"这一半，所以被顶高过（比如俯冲拉升过头）就再也降不下来，
            // 表现就是"离地至少 5 格"。
            this.setNoGravity(true);
            Vec3 cur = this.getDeltaMovement();
            double fall = Mth.clamp(dy * 0.05D, -HOVER_FALL_SPEED, -0.02D);
            this.setDeltaMovement(cur.x, fall, cur.z);
            this.fallDistance = 0.0F;
            return;
        }
        // 头顶顶着方块（矿洞、屋里）就别硬往上顶了，免得贴着天花板抽搐。
        BlockPos above = BlockPos.containing(this.getX(), this.getY() + this.getBbHeight(), this.getZ());
        if (!this.level().getBlockState(above).isAir()) {
            return;
        }
        this.setNoGravity(true);
        // 直接给竖直速度。注意<b>不能</b>只 setYya：yya 是"输入"，还要乘以 moveControl 设的速度，
        // 而空闲悬停时飞行移动控制压根不设速度（还是 0），乘出来永远是 0——上一版就是这么没升起来的。
        // 直接改 deltaMovement 不会被 travel 清掉（moveRelative 是往现有速度上叠，不是覆盖）。
        Vec3 v = this.getDeltaMovement();
        double vy = Mth.clamp(0.03D + dy * 0.05D, 0.03D, HOVER_RISE_SPEED);
        this.setDeltaMovement(v.x, vy, v.z);
        this.fallDistance = 0.0F;
    }

    /** 待机 goal 每 tick 同步"是不是已经进入原地悬停待机"。 */
    public void setIdleSettled(boolean settled) {
        this.idleSettled = settled;
    }

    public boolean isIdleSettled() {
        return this.idleSettled;
    }

    /**
     * 当前该悬停的离地高度。
     *
     * <p>只有"原地不动待机"才降到 {@link #HOVER_HEIGHT_IDLE}(3)：要么正在巡航/刚打完架还没停下来，
     * 要么一进入待机就贴地飘着。{@code getTarget() == null} 那个判断是保险——真出现目标时立刻回到
     * 作战高度，不会因为滞后一 tick 还挂在 3 格。
     *
     * <p>两个基准值都会再乘 {@link #hoverHeightScale()}（体型越大飞得越高，见那里的说明）。
     * 待机高度也跟着涨：那一档本来就是"给骑乘用的固定高度"，体型大了还贴 3 格等于钻地。
     */
    public double hoverHeight() {
        double base = this.idleSettled && this.getTarget() == null ? HOVER_HEIGHT_IDLE : HOVER_HEIGHT;
        return base * this.hoverHeightScale();
    }

    /** 悬停高度的体型倍率：{@code max(1, bodyScale())} 的 {@link #HOVER_SCALE_EXP} 次方。 */
    public double hoverHeightScale() {
        double scale = this.bodyScale();
        // 缩小（重铸惩罚、缩小类升级）不降高度：贴地反而更容易把身体插进地形
        return scale <= 1.0D ? 1.0D : Math.pow(scale, HOVER_SCALE_EXP);
    }

    /** 当前水平位置的地表高度 + 期望离地高度（不算树叶，免得把树冠当地面）。俯冲结束时就回到这个高度。 */
    public double hoverTargetY() {
        BlockPos pos = this.blockPosition();
        return this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())
                + this.hoverHeight();
    }

    /**
     * 基础属性。生命/攻击这些 BASE 类属性会被材料数值直接覆盖（铁龙 = 材料值 × 部件权重 ×
     * 种类倍率），这里的数字只是没装材料时的兜底；速度、跟随距离、击退这些材料不管，靠这里。
     *
     * <p><b>GOLEM_SIZE = 5 是"尺寸基准档位"，不是 5 格。</b>本家的
     * {@code AbstractGolemEntity.getScale()} 返回的是 {@code 当前值 / 本类注册的默认值}，
     * 而渲染（{@code AbstractGolemRenderer.scale}）和判定箱（{@code LivingEntity.getDimensions()}
     * → {@code .scale(getScale())}）都乘这个倍率——所以<b>默认值本身不改变大小</b>，
     * 5 只是"一倍"的基准点，改它只会改变升级/重铸的相对幅度：
     * 本家 size_up 每级 +0.5，基准 1 时是 +50%，基准 5 时只有 +10%。
     * （本家自己的金属/类人/狗也是这种抽象档位：3 / 2.5 / 1。）
     *
     * <p>注意这会影响将来的载客公式：本家狗型用的是
     * {@code min(size * 2 - 1, 3)}，基准 5 时开局就已经吃满 3 个座位，
     * 再升 size_up 只会变大、不再加座位。详见 docs/MOUNT.md。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 8.0D)
                .add(Attributes.ATTACK_DAMAGE, 4.0D)
                .add(Attributes.ATTACK_SPEED, 4.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 0.4D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FLYING_SPEED, 0.4D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 35.0D)
                .add(ForgeMod.ENTITY_REACH.get(), 1.5D)
                .add(GolemTypes.GOLEM_JUMP.get(), 0.5D)
                // 尺寸基准档位：5。判定箱与模型在 size = 5 时就是"一倍"，
                // 也就是 DragonGolemItems 里 .sized(2.4F, 2.6F) 与 DragonGolemModel 的 MODEL_SCALE。
                .add(GolemTypes.GOLEM_SIZE.get(), 5.0D)
                // 必须有这一条：本家 SweepGolemEntity.performRangedDamage 第一行就读
                // getAttributeValue(GOLEM_SWEEP)，属性表里没有它会直接抛
                // IllegalArgumentException: Can't find attribute modulargolems:golem_sweep
                // （俯冲撞击时的崩溃就是这个）。默认 0 = 不横扫，材料给了 modulargolems:sweep 才连带打周围。
                .add(GolemTypes.GOLEM_SWEEP.get())
                .add(GolemTypes.GOLEM_REGEN.get())
                .add(GolemTypes.DYNAMIC_REDUCTION.get());
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        nav.setCanPassDoors(true);
        return nav;
    }

    /** 飞行怪不吃摔落伤害。 */
    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    /**
     * 伤害结算。过一遍原版 {@code hurt}，让材料/升级的伤害管线照常生效；
     * {@code knockback} 参数以前是丢掉的，现在按"龙 → 目标"方向推开（原版 hurt 内部也是这么推的），
     * 这样俯冲撞击的击退才有意义。
     */
    @Override
    protected boolean performDamageTarget(Entity target, float damage, double knockback) {
        if (!(target instanceof LivingEntity living)) {
            return false;
        }
        living.setLastHurtByMob(this);
        boolean hurt = living.hurt(damageSources().mobAttack(this), damage);
        if (hurt && knockback > 0.0D) {
            living.knockback(knockback, this.getX() - living.getX(), this.getZ() - living.getZ());
        }
        return hurt;
    }
}
