package a_silly_cat.dragon_golems.dragon;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.SweepGolemEntity;
import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.network.DragonSkillPacket;
import dev.xkmc.modulargolems.content.config.GolemMaterial;
import dev.xkmc.modulargolems.content.entity.goals.FollowOwnerGoal;
import dev.xkmc.modulargolems.content.entity.goals.GolemMeleeGoal;
import dev.xkmc.modulargolems.content.entity.goals.GolemRandomStrollGoal;
import dev.xkmc.modulargolems.content.entity.goals.TeleportToOwnerGoal;
import dev.xkmc.modulargolems.content.entity.humanoid.weapon.GolemWeaponRegistry;
import dev.xkmc.modulargolems.content.entity.mode.GolemModes;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.content.modifier.special.BaseRangedAttackGoal;
import dev.xkmc.modulargolems.content.modifier.special.SonicAttackGoal;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import dev.xkmc.l2serial.serialization.SerialClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
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
 * <p>飞行沿用原版"悦灵/蜜蜂"那一套（{@link FlyingMoveControl} + 我们自己的
 * {@link DragonFlyingNavigation}（继承自 {@code FlyingPathNavigation}）三维寻路），
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
public class DragonGolemEntity extends SweepGolemEntity<DragonGolemEntity, DragonGolemPartType>
        implements DragonTargetSource {

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
    /**
     * 骑乘俯仰（度，正 = 低头）：玩家驾驶时跟着视角，模型和子碰撞箱一起读它。
     *
     * <p>和 {@code DATA_BODY_PITCH} 分开是因为语义不同：那个是"俯冲/喷息姿态"（由
     * {@link #pitchForPhase} 推出来），这个是"玩家视角"。{@link #getBodyPitch()} 会按
     * 有没有被驾驶挑一个返回，所以判定箱和模型不需要知道区别。
     */
    private static final EntityDataAccessor<Float> DATA_RIDER_PITCH =
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

    // ---- 载客 / 驾驶 ----
    //
    // 本家那套载客能力写在 DogGolemEntity 里（不是"坐骑升级"提供的），龙一个方法都没继承到，
    // 所以这里自己接一遍。玩家驾驶的按键处理在 {@link DragonRiderControl}：
    // 空格上升、Shift 下降、WASD 前后左右、鼠标决定机头朝向与俯仰。
    //
    // 上龙的入口有两处（都要，理由见各自说明）：{@link #canRideWith(net.minecraft.world.item.ItemStack)}
    // 配套的 Forge 事件 {@code DragonRideHandler}（拦"骑乘手杖"），以及
    // {@link #startRidingFrom(Player)}（兜住直接右键那条路）。

    /**
     * 乘客坐在背上的位置（模型像素，正 = 上/前）：<b>这两个数是调"人坐在哪儿"的唯一旋钮。</b>
     *
     * <p>换算走 {@link #rotateAndLift}，和头部件、{@link #mouthPosition()} 是同一套，
     * 所以龙低头/抬头时座位会跟着一起转，不会出现"龙俯冲、人还水平吊在空中"。
     * 返回值必须相对 {@code this.getX()/getY()/getZ()}（实体原点是脚底），
     * 模型的抬高量（{@code MODEL_LIFT + RENDER_LIFT}）本来就含在 rotateAndLift 里。
     *
     * <p><b>怎么调：</b>
     * <ul>
     *   <li>{@link #RIDER_UP_PX} 调大 = 人坐得更高（贴着脖子/翅膀根）、调小 = 更贴近龙背；</li>
     *   <li>{@link #RIDER_FORWARD_PX} 调大 = 往<b>前</b>挪（靠近颈根）、调小 = 往后挪到翅膀之间。</li>
     * </ul>
     * 现在这组值（12 / 6）是"坐在肩背上、头颈后面"的位置。调过一次：
     * 上一版是 12 / 20，玩家反馈"坐在脖颈第一节和第二节之间的<b>上方一格</b>"——
     * 也就是 <b>太靠前</b>了，所以把 FORWARD 从 20 收到 6（20 像素 ≈ 往前 2.1 格，
     * 6 像素 ≈ 0.6 格，正好从颈根退到肩背）。UP 保持不变。
     *
     * <p>往前挪还会撞上另一件事：<b>龙的头部子碰撞箱就伸在玩家正前方</b>，
     * 坐得太靠前时准星射线会先命中自己这条龙（客户端瞄准那侧已经加了"排除坐骑"的过滤，
     * 但座椅靠后能让视锥更干净、也能少一点贴脸时的镜头穿模）。
     */
    private static final double RIDER_UP_PX = 12.0D;
    private static final double RIDER_FORWARD_PX = 6.0D;

    /**
     * 谁在开这条龙：<b>只有第一个乘客是玩家时</b>才由他操控，否则交回 AI。
     *
     * <p>傀儡乘客刻意不在这里返回 —— 傀儡没有输入源（不像本家狗那样能读
     * {@code AbstractGolemEntity} 的意图），把它算成"驾驶者"会让
     * {@code travelRidden} 整条管线空转、龙反而动不了。
     * 傀儡在背上时的正确形态是"龙自己飞/自己打，傀儡当炮台"。
     */
    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return this.getFirstPassenger() instanceof Player player ? player : null;
    }

    /**
     * 驾驶位上有没有"占用者"——<b>玩家或傀儡乘客都算</b>。
     *
     * <p>和 {@link #getControllingPassenger()} 的区别很重要：那个回答"谁在操控"，
     * 这个回答"驾驶位是否已被占用 / 背上是不是有人"。
     *
     * <p><b>为什么必须分开：</b>{@link #isMovable()} 原来用
     * {@code getControllingPassenger() != null} 当判据，而傀儡不在其中 ——
     * 于是"龙先切停止模式落地 → 让傀儡上背"这条最自然的操作路径上，
     * {@code isMovable()} 返回 false，<b>所有攻击 goal（俯冲/龙息/音爆）的
     * {@code canUse()} 全部返回 false</b>，表现就是"傀儡坐在龙背上，龙却完全不袭击"。
     */
    public boolean hasDriverSeatOccupied() {
        return !this.getPassengers().isEmpty();
    }

    /** 背上坐着的第一个乘客（可能是玩家，也可能是傀儡）。 */
    @Nullable
    public Entity firstPassengerOrNull() {
        return this.getFirstPassenger();
    }

    /**
     * 玩家驾驶时"两边各算一次输入"，和原版地面坐骑一样。
     *
     * <p>原版只在 {@code isControlledByLocalInstance()} 为真时才算输入：不覆写的话
     * <b>服务端永远为假</b>（它不是客户端、也没有 {@code isEffectiveAi}），驾驶会退化成
     * 纯客户端表现 —— 单机看不出来，联机时所有操作都被服务端退回。有玩家操控时强制为真即可。
     */
    @Override
    public boolean isControlledByLocalInstance() {
        if (this.getControllingPassenger() instanceof Player) {
            return true;
        }
        return super.isControlledByLocalInstance();
    }

    /**
     * 原版骑乘管线的"每 tick 驾驶一步"（{@code travelRidden} 里、算速度之前调）。
     *
     * <p>只做两件事：按转速把机头转向玩家视角、把机体俯仰同步成玩家视角的俯仰。
     * 速度不在这里写 —— 原版紧接着就会用 {@link #getRiddenInput} × {@link #getRiddenSpeed}
     * 重算一遍，写了也会被覆盖（理由见 {@link DragonRiderControl} 的类注释）。
     */
    @Override
    protected void tickRidden(Player player, Vec3 travelVector) {
        super.tickRidden(player, travelVector);
        DragonRiderControl.tickBody(this, player);
    }

    /**
     * WASD → 沿机头的前后 + 左右。<b>返回的是输入方向（分量约 -1..1），不是速度</b>，
     * 原版会再乘 {@link #getRiddenSpeed}；把速度算进来会乘多一次。
     */
    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 travelVector) {
        return DragonRiderControl.riddenInput(player);
    }

    /** 飞多快：材质 / 升级的移速倍率在这里生效。 */
    @Override
    protected float getRiddenSpeed(Player player) {
        return DragonRiderControl.riddenSpeed(this);
    }

    /**
     * 谁能坐在龙背上：<b>玩家当驾驶座，傀儡当炮台乘客</b>。
     *
     * <p>座位规则（1 + N）：
     * <ul>
     *   <li>第一个乘客 = 驾驶座。是玩家就由玩家驾驶（见 {@link DragonRiderControl}）；
     *       是傀儡则退化为"纯乘客"，龙的 AI 继续自己飞（这正是"傀儡骑在龙身上当炮台"）；</li>
     *   <li>其余座位给傀儡，数量上限 {@link #MAX_GOLEM_PASSENGERS}。</li>
     * </ul>
     *
     * <p><b>为什么放开给傀儡不会破坏驾驶</b>：{@link #getControllingPassenger()} 只在
     * 首个乘客是 {@code Player} 时才返回非 null，所以傀儡乘客不会顶掉玩家的驾驶权，
     * 也不会让 AI 的"有乘客就别动手"那批守卫误判。
     *
     * <p>本家 {@code AbstractGolemEntity} 一个载客方法都没覆写（只有
     * {@code DogGolemEntity} 有那六个），所以这里没有基类钩子可蹭，得自己写。
     */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (passenger instanceof Player) {
            // 玩家只能坐驾驶座，且只有第一个位置
            return this.getPassengers().isEmpty();
        }
        if (passenger instanceof AbstractGolemEntity<?, ?>) {
            // 傀儡：驾驶座被占了也能上来（那就是乘客位），但总数有上限
            return this.getPassengers().size() < 1 + MAX_GOLEM_PASSENGERS;
        }
        return false;
    }

    /** 驾驶座之外还能坐几个傀儡乘客。 */
    private static final int MAX_GOLEM_PASSENGERS = 3;

    /**
     * 乘客坐在背上：<b>沿机体轴向排座</b>。
     *
     * <p>这是本家狗的排座公式（{@code positionRider}）搬到龙身上：驾驶座（index 0）靠前、
     * 其余乘客依次向后。区别只是改成沿龙的机体方向、并复用本类已有的
     * {@link #rotateAndLift} 换算（所以龙低头/抬头时整排座位跟着转）。
     *
     * <p>返回值必须相对 {@code this.getX()/getY()/getZ()}（实体原点是脚底）。
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction setPos) {
        if (!this.hasPassenger(passenger)) {
            return;
        }
        int index = this.getPassengers().indexOf(passenger);
        if (index < 0) {
            return;
        }
        // 每往后一个座位，沿机体方向退 SPACING 像素（模型像素，再经 rotateAndLift 换算成格）
        double forwardPx = RIDER_FORWARD_PX - index * RIDER_SEAT_SPACING_PX;
        // 驾驶座再抬高一点：玩家的视线要越过龙背
        double upPx = RIDER_UP_PX + (index == 0 ? 0.0D : RIDER_PASSENGER_UP_PX);
        double[] upForward = this.rotateAndLift(upPx, forwardPx, this.getBodyPitch());
        double yaw = Math.toRadians(this.yBodyRot);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        setPos.accept(passenger,
                this.getX() + forwardX * upForward[1],
                this.getY() + upForward[0] + passenger.getMyRidingOffset(),
                this.getZ() + forwardZ * upForward[1]);
    }

    /** 相邻两个座位之间沿机体方向的距离（模型像素，1 格 = 16 像素）。 */
    private static final double RIDER_SEAT_SPACING_PX = 9.0D;
    /** 傀儡乘客比驾驶座再抬高多少（模型像素）：免得和玩家的腿重叠。 */
    private static final double RIDER_PASSENGER_UP_PX = 3.0D;

    /**
     * 乘客跟着机体转 —— <b>但驾驶者的视角必须是自由的</b>。
     *
     * <p>原来这里连驾驶者的 {@code yRot} 一起扳到龙的朝向，结果就是<b>骑着龙左右转不了鼠标</b>：
     * 视角每 tick 被强行拉回机头方向。原版的做法是相反的 —— 船的 {@code onPassengerTurned}
     * <b>只夹非驾驶座</b>的乘客（驾驶者自己就是掌舵的人，凭什么限制他看哪）。
     *
     * <p>所以现在只做一件事：让乘客的<b>身体</b>朝向跟着龙走，{@code yRot}（也就是视角）不动。
     * 非驾驶座乘客（以后如果开放多座位）仍然会被限制，照本家的做法。
     */
    @Override
    public void onPassengerTurned(Entity passenger) {
        passenger.setYBodyRot(this.getYRot());
    }

    /**
     * "停住"（{@code GolemModes.STAND}）时不许动 —— 但**玩家在开的时候必须放开**。
     *
     * <p>原来直接返回 {@code getMode().isMovable() && !isInSittingPose()}，而"停留"的命令
     * 正好把 {@code movable} 设成 false。于是"停着才能骑、骑上却动不了"：玩家骑上去之后
     * WASD 一点反应都没有。有人驾驶时一律视为可动，控制权交给玩家。
     */
    @Override
    public boolean isMovable() {
        // 驾驶位有人（玩家在开，或者背上驮着傀儡当炮台）就一律视为可动。
        // 不能只看 getControllingPassenger()：傀儡不在其中，那会让 STAND 模式下的
        // "龙+傀儡"组合彻底打不了架（见 hasDriverSeatOccupied 的说明）。
        if (this.hasDriverSeatOccupied()) {
            return true;
        }
        // ★ 有敌人就解禁。
        //   真机反馈："停止模式下龙一直索敌却完全不出手（不会龙息也不会龙息弹）"。
        //   根因：龙息 / 龙息弹 / 冲锋三个攻击 goal 的 canUse() 第一句都是
        //   `if (!isMovable() ...) return false;`，而 STAND（停止）模式的 movable 是 false
        //   —— 于是"停下来"等于"关掉全部攻击手段"，连自保都不行。
        //   这不是"取消停止模式"：只是"有敌人时别装死"。敌人一走（getTarget() 变 null）
        //   它就立刻回到待机，行为与原来完全一致。
        LivingEntity enemy = this.getTarget();
        if (enemy != null && enemy.isAlive()) {
            return true;
        }
        return super.isMovable();
    }

    /**
     * 上乘客要做两件事：把龙从"原地贴地待机"里叫醒（不然骑着一条贴地的龙会起不来），
     * 以及重算"货舱模式"（傀儡上来 → 抑制盘旋，见 {@link #onPassengersChanged()}）。
     */
    @Override
    protected void addPassenger(Entity passenger) {
        this.setIdleSettled(false);
        super.addPassenger(passenger);
        this.onPassengersChanged();
    }

    /**
     * 让本家的<b>骑乘手杖</b>能点到这条龙。
     *
     * <p>手杖的判定链是 {@code ConfigCard.getFilter(user).test(golem) && (golem.canWandModify(user)
     * || golem.getControllingPassenger() instanceof Player)}，而 {@code canWandModify} 在
     * {@code AbstractGolemEntity} 里会看"配置卡锁没锁"；龙身上根本没有配置卡那一套，所以这里放行。
     */
    @Override
    public boolean canWandModify(Player player) {
        return true;
    }

    /**
     * 玩家现在是不是拿着"骑乘手杖"（本家 {@code modulargolems:rider_wand} / 万能手杖的骑乘模式）。
     *
     * <p><b>按物品 id 认，不按类认。</b>手杖类在 {@code dev.xkmc.modulargolems} 里，
     * 而本家有些构建把它 jarjar 进别的包（龙这个包编译时就不一定拿得到那个类）；
     * 万能手杖虽然同一个类，但它的"模式"是 l2itemselector 的选中态，会换成
     * {@code omnipotent_wand_rider} 这个 id 出现，所以拿"包含 rider_wand"来认最稳。
     */
    public static boolean canRideWith(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.getPath().contains("rider_wand");
    }

    /**
     * 上龙。服务端执行真正的 {@code startRiding}，客户端只回成功让手感一致
     * （和原版 {@code Animal.mobInteract} 里 {@code player.startRiding(this)} 的分端写法一致）。
     *
     * <p><b>只有"停着"的龙能上</b>（{@link #isParked()}：手杖切"停留"，或者待机满 30 秒）。
     * 理由很直接：正常飞行是 0.2 格/tick 起，玩家跑速 0.1 出头，追着一条正在猛冲的龙点手杖
     * 是点不中的；而且真要骑上去也会立刻被它带飞。想放宽就改这一个判断。
     *
     * @return 这条龙现在是不是有人骑着了
     */
    public boolean startRidingFrom(Player player) {
        if (this.level().isClientSide()) {
            return true;
        }
        if (this.hasDriverSeatOccupied() || !this.canAddPassenger(player)) {
            return false;
        }
        if (!this.isParked()) {
            return false;
        }
        player.startRiding(this);
        return this.getControllingPassenger() != null;
    }

    /**
     * 驾驶期间不索敌。
     *
     * <p>各个攻击 goal 本来就靠 {@code getControllingPassenger()} 自己收手（见 {@link DragonIdleGoal} 等），
     * 这里再把"已经挂上的目标"和"已经掷出来的技能"清掉，免得出现"玩家在开、龙还在朝地面喷息"。
     */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && this.isRiderControlled()) {
            return;
        }
        super.setTarget(target);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_DIVE_PHASE, DIVE_PHASE_NONE);
        this.entityData.define(DATA_BODY_ROLL, 0.0F);
        this.entityData.define(DATA_BODY_PITCH, 0.0F);
        // 骑乘俯仰（玩家驾驶时跟着视角）：同样"服务端算、两端读"
        this.entityData.define(DATA_RIDER_PITCH, 0.0F);
    }

    /**
     * 当前机体俯仰（度，正 = 低头）。判定箱摆位和客户端模型都读它。
     *
     * <p>被玩家驾驶时读的是骑乘俯仰（跟玩家视角，见 {@link DragonRiderControl}），
     * 其它时候才是 {@code DATA_BODY_PITCH}（由俯冲/喷息姿态 {@link #pitchForPhase} 推出来）。
     */
    public float getBodyPitch() {
        if (this.isRiderControlled()) {
            return DragonRiderControl.riderPitch(this);
        }
        return this.entityData.get(DATA_BODY_PITCH);
    }

    /**
     * 这条龙现在是不是玩家在开（<b>两端都有效</b>）。
     *
     * <p>直接问同步过来的乘客列表：这样客户端的模型也能走"骑乘俯仰"那条分支。
     * 不要换成"服务端在 {@code aiStep} 里写一个 boolean"——客户端不跑那段服务端分支，
     * 那个标记在客户端会永远是 false，表现就是"龙身上坐着人、模型却按俯冲姿态摆"。
     *
     * <p>按 tick 缓存：判定箱 15 个子箱每 tick 各自问一次，没必要每次都去翻乘客列表。
     */
    public boolean isRiderControlled() {
        if (this.riderControlledTick != this.tickCount) {
            this.riderControlledTick = this.tickCount;
            this.riderControlled = this.getControllingPassenger() != null;
        }
        return this.riderControlled;
    }

    /** {@link #isRiderControlled()} 的缓存值与本 tick 的 tickCount（-1 = 还没算过）。 */
    private boolean riderControlled;
    private int riderControlledTick = -1;

    /** 机身侧倾（度）：客户端渲染用。 */
    public float getBodyRoll() {
        return this.entityData.get(DATA_BODY_ROLL);
    }

    /**
     * 骑乘俯仰（度，正 = 低头）：玩家驾驶时跟着视角，模型和判定箱一起低头/抬头。
     *
     * <p><b>这个 EntityDataAccessor 为什么必须声明在实体类里：</b>{@code SynchedEntityData.defineId}
     * 要求"定义者和被定义者必须是同一个类"，否则每次加载都会刷一条
     * {@code defineId called for: class ... from class ...} 警告（对，会一直刷）。
     * 原来它写在 {@link DragonRiderControl} 里，日志里就出现了这条警告，
     * 所以挪到这里、并在那里只留读写方法。
     */
    public float getRiderPitch() {
        return this.entityData.get(DATA_RIDER_PITCH);
    }

    public void setRiderPitch(float pitch) {
        this.entityData.set(DATA_RIDER_PITCH, pitch);
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

    // ---- 骑手技能指令（客户端按键 → 包 → 这里） ----

    /** 骑手指定的技能；{@code -1} = 没有。取值见 {@code DragonSkillPacket.SKILL_*}。 */
    private int orderedSkill = -1;
    /** 骑手指定的瞄准点（世界坐标）。 */
    @Nullable
    private Vec3 commandedAim;
    /**
     * 本 tick 是不是"正在执行骑手指令"。
     *
     * <p>它是 {@link #canAttackType()} / {@link #canBeSeenAsEnemy()} 唯一的开关：
     * 装了坐骑升级的龙带 {@code PASSIVE}，平时必须保持被动；而玩家按键让它开火的那几秒
     * 又必须能真的打到人（本家的攻击管线到处都要过 {@code canAttack}，绕不过去）。
     * 所以只有这一小段时间解除被动，指令一结束立刻收回。
     */
    private boolean riderCombat;
    /** 三个技能各自的冷却（tick），索引即 {@code SKILL_*}。 */
    private final int[] riderCooldowns = new int[3];

    /** 冲锋的冷却（tick）：30 秒。比 AI 的俯冲冷却宽松一点，因为这是玩家主动付出手感的技能。 */
    private static final int RIDER_DIVE_CD = 600;
    /** 龙息 / 龙弹的冷却（tick）：3 秒。够玩家连喷，又不至于一秒一发刷屏。 */
    private static final int RIDER_BLAST_CD = 60;
    /**
     * 瞄准点允许的最大距离（格）：比客户端算的 48 稍宽，留出网络延迟期间玩家往前飞的那一段。
     * 超过就<b>夹到射程内</b>而不是拒绝——拒绝会让"飞得快时按键没反应"变得很难解释。
     */
    private static final double RIDER_AIM_MAX = 64.0D;

    /**
     * 接一条骑手的技能指令（服务端）。<b>这里做全部校验，不信任客户端。</b>
     *
     * <p>校验项：发指令的人确实骑着这条龙、技能序号合法、冷却好了、瞄准点在合理距离内。
     * 瞄准点离嘴太近（比如贴脸）就沿视角方向推远一点，免得锥体长度算出来是负的。
     *
     * @return 是否受理
     */
    public boolean onRiderCommand(Player player, int skill, double x, double y, double z, int entityId) {
        if (DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[ride] server got skill={} entity={} from={} riding={}", skill,
                    entityId, player.getName().getString(), player.getVehicle());
        }
        if (this.level().isClientSide() || player != this.getControllingPassenger()) {
            return false;
        }
        if (skill < 0 || skill > DragonSkillPacket.SKILL_MAX) {
            return false;
        }
        if (this.riderCooldowns[skill] > 0) {
            // 冷却中：给个咔哒声提示，不然玩家会以为按键没生效
            this.playSound(SoundEvents.NOTE_BLOCK_HAT.get(), 0.6F, 0.6F);
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[ride] skill={} rejected by cooldown ({})",
                        skill, this.riderCooldowns[skill]);
            }
            return false;
        }
        Vec3 aim = new Vec3(x, y, z);
        Vec3 mouth = this.mouthPosition();
        double dist = mouth.distanceTo(aim);
        if (dist > RIDER_AIM_MAX) {
            aim = mouth.add(aim.subtract(mouth).normalize().scale(RIDER_AIM_MAX));
        } else if (dist < 2.0D) {
            // 贴脸（或者瞄准点就在自己身上）：沿"龙的机头方向"推开，别让方向退化成随机值
            double yaw = Math.toRadians(this.getYRot());
            aim = mouth.add(-Math.sin(yaw), 0.0D, Math.cos(yaw)).scale(2.0D);
        }
        this.orderedSkill = skill;
        this.commandedAim = aim;
        this.riderCooldowns[skill] = skill == DragonSkillPacket.SKILL_DIVE ? RIDER_DIVE_CD : RIDER_BLAST_CD;
        this.getLookControl().setLookAt(aim.x, aim.y, aim.z);
        if (skill == DragonSkillPacket.SKILL_DIVE) {
            // 冲锋要先有目标才能起飞（见 orderDive 的说明）。拿不到有效目标就整条指令作废
            // 并退还冷却 —— 否则玩家会"按了 R 但什么都没发生，还白扣 30 秒"。
            if (!this.orderDive(entityId, aim)) {
                this.orderedSkill = -1;
                this.commandedAim = null;
                this.riderCooldowns[skill] = 0;
                return false;
            }
        }
        return true;
    }

    /**
     * 把"冲锋"排进俯冲调度，交给 {@link DragonDiveGoal} 去飞那套航线。
     *
     * <p>为什么不自己实现一遍俯冲：那套航线（LINEUP → DIVE → PLOW → CLIMB）连同撞击结算、
     * 无敌帧处理都已经调好并实测过了，重写只会引入新的手感差异。
     *
     * <p><b>关键点：那套航线是"追着目标飞"的。</b>{@code DragonDiveGoal.canUse()} 要求
     * {@code getTarget() != null}、且水平距离落在 {@code diveMinH()..diveMaxH()}（默认 4~34 格）。
     * 而龙带 {@code PASSIVE} 时服务端自己索敌会被本家挡掉，所以这里必须用客户端带过来的
     * "准星命中谁"来补目标 —— 第一版就是漏了这一步，表现是"按 R 完全没反应"。
     *
     * @param entityId 客户端准星命中的实体 id；{@code -1} 表示没瞄到实体
     * @param aim      客户端算出的瞄准点（命中实体时就是它的判定箱中心）
     * @return 能不能起飞
     */
    private boolean orderDive(int entityId, Vec3 aim) {
        LivingEntity target = null;
        String source = "none";
        // ① 命令手杖指定的目标优先：那是玩家"明确指定"的意图，而且射程 64 格（远超准星），
        //    比"骑着龙时用准星瞄"更符合直觉 —— 先用手杖点一下要打谁，再按 R 冲过去。
        //    forcedTarget 是上游 AbstractGolemEntity 的 public 字段，命令手杖的
        //    resetTarget() 会写它；TargetManager.predicateTarget 见到它就返回 FORCED，
        //    是一条跳过 canAttackType（也就是跳过 PASSIVE）的强制通道。
        if (this.forcedTarget != null && this.forcedTarget.isAlive()
                && this.forcedTarget != this.getControllingPassenger()) {
            target = this.forcedTarget;
            source = "forcedTarget(command wand)";
        }
        // ② 其次用客户端带过来的"准星命中谁"
        if (target == null && entityId >= 0 && this.level() instanceof ServerLevel server
                && server.getEntity(entityId) instanceof LivingEntity living && living.isAlive()) {
            target = living;
            source = "crosshair";
        }
        // ③ 都没有：在瞄准点附近兜一次（准星贴着目标但没有精确命中时能救回来）
        if (target == null) {
            AABB box = new AABB(aim, aim).inflate(6.0D);
            double best = Double.MAX_VALUE;
            for (LivingEntity candidate : this.level().getEntitiesOfClass(LivingEntity.class, box)) {
                if (!this.predicateTarget(candidate)) {
                    continue;
                }
                // 别把自己人（骑手 / 同一载具上的乘客）当成目标
                if (candidate == this.getControllingPassenger()
                        || candidate.isPassengerOfSameVehicle(this)) {
                    continue;
                }
                double d = candidate.position().distanceToSqr(aim);
                if (d < best) {
                    best = d;
                    target = candidate;
                    source = "near-aim";
                }
            }
        }
        if (target == null) {
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[ride] dive aborted: no valid target (aim={} entityId={})",
                        aim, entityId);
            }
            return false;
        }
        // setTargetRaw 是上游自己的"绕开索敌校验"入口（只过 canAttack，不过 canAttackType）：
        // 用它而不是自己写一个 super.setTarget 包装，免得和上游的后手处理脱节。
        this.setTargetRaw(target);
        if (DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[ride] dive ordered via {} target={} hDist={}", source,
                    target.getName().getString(), String.format("%.2f", this.horizontalDistanceTo(target)));
        }
        this.pendingSkill = DragonSkill.DIVE;
        this.skillPendingTicks = 0;
        this.skillInProgress = false;
        // 骑手冲锋标志：由 DiveGoal.stop() 清除，不跟 pendingSkill 同生共死
        // （否则 stop() 里清掉 pendingSkill 会让 isRiderOrderedDive() 立刻变假，见它的说明）
        this.riderDiveActive = true;
        // 开一个诊断窗口：接下来 40 tick 里 DiveGoal 的每次 canUse 判定都会被记录
        this.riderDiveTraceUntil = this.tickCount + 40;
        return true;
    }

    /** 到某个目标的水平距离（俯冲的可用距离是水平判定的，见 {@code DragonDiveGoal.canUse}）。 */
    private double horizontalDistanceTo(LivingEntity target) {
        return Math.sqrt(Math.pow(target.getX() - this.getX(), 2) + Math.pow(target.getZ() - this.getZ(), 2));
    }

    /** 有没有等着执行的骑手指令。 */
    public boolean hasRiderOrder() {
        return this.orderedSkill >= 0;
    }

    // ---- 给"背上的傀儡乘客"用的共享目标接口 ----
    // 接口本体在 DragonTargetSource（顶层）：嵌在泛型类里会形成循环继承，javac 报 cyclic inheritance。

    /**
     * {@link DragonTargetSource} 的实现。
     *
     * <p>顺序：骑手指定的（{@code forcedTarget}，命令手杖写的）→ 本家目标槽。
     */
    @Override
    public LivingEntity dragonCurrentTarget() {
        if (this.forcedTarget != null && this.forcedTarget.isAlive()) {
            return this.forcedTarget;
        }
        LivingEntity t = this.getTarget();
        return t != null && t.isAlive() ? t : null;
    }

    @Override
    public void dragonForceTarget(@Nullable LivingEntity target) {
        this.setTargetRaw(target);
    }

    /**
     * 这一轮的俯冲是不是<b>骑手按 R 下命令</b>要的（而不是 AI 自己掷骰子抽中的）。
     *
     * <p>用途：驾驶管线（{@code DragonRiderControl.tickBody/tickVertical}）与
     * {@code DragonDiveGoal.canUse/canContinueToUse} 靠它判断"要不要给这条航线让路"。
     *
     * <p><b>★ 判据必须只看 {@link #riderDiveActive}，绝不能看 {@code pendingSkill}：</b>
     * 后者会被 {@code stop() → onSkillFinished()} <b>自己清掉</b>，于是形成自我拆台的循环 ——
     * goal 一停就 pendingSkill=null → 本方法变假 → 驾驶管线立刻恢复清目标/清速度 →
     * 下一次 canContinueToUse 再凭"非骑手指令"砍一刀。
     * 真机日志就是这条：{@code ordered=0 pending=null} 导致 canContinueToUse=false，
     * 而 orderedSkill 明明还挂着（=玩家那条指令根本没被撤销）。
     */
    public boolean isRiderOrderedDive() {
        return this.getControllingPassenger() != null && this.riderDiveActive;
    }

    /**
     * "骑手冲锋正在执行"的标志，由 {@link #orderDive} 置位、{@link DragonDiveGoal#stop()} 清除。
     *
     * <p>它刻意<b>不</b>跟 {@code pendingSkill} 同生共死（见 {@link #isRiderOrderedDive()} 的说明）。
     */
    private boolean riderDiveActive;

    /** 由 {@link DragonDiveGoal#stop()} 调用：这一轮骑手冲锋结束了，交回驾驶管线。 */
    public void clearRiderDive() {
        this.riderDiveActive = false;
    }

    /**
     * 骑手指令受理之后的一段"诊断窗口"（tick）。
     *
     * <p>给 {@link DragonDiveGoal#canUse()} 用：它要记录"指令受理后每一次判定走到哪一步"。
     * 不能用"每 N tick 采样"——{@code pendingSkill} 只存活一 tick，采样窗口几乎不可能撞上，
     * 实测结果是<b>一条日志都没打出来</b>。所以改成事件驱动：受理那一刻开一个 40 tick 的窗，
     * 窗内每 tick 都记录。
     */
    private int riderDiveTraceUntil = -1;

    /** 现在是不是在"骑手冲锋诊断窗口"内。 */
    public boolean inRiderDiveTraceWindow() {
        return this.riderDiveTraceUntil >= 0 && this.tickCount <= this.riderDiveTraceUntil;
    }

    /** 当前骑手指令的技能序号；没有则 -1。 */
    public int riderOrderedSkill() {
        return this.orderedSkill;
    }

    /** 骑手指定的瞄准点；没有则 null。 */
    @Nullable
    public Vec3 riderAim() {
        return this.commandedAim;
    }

    /** 骑手技能 goal 收工时调用（清指令；冷却在 {@link #onRiderCommand} 里起步、每 tick 递减）。 */
    public void clearRiderOrder() {
        this.orderedSkill = -1;
        this.commandedAim = null;
    }

    // ---- 飞行辅助（穿墙 / 脱困 / 避障） ----

    /**
     * 飞行辅助：自由穿墙开关 + 卡住自动脱困 + 主动避障。
     *
     * <p>状态是"行为"而不是"外观"，所以放在普通字段里、不走 {@code entityData} 同步：
     * 服务端是权威，客户端顶多差一两 tick 的表现。
     */
    private final DragonFlightAssist flightAssist = new DragonFlightAssist();

    /** 每次回血回复的血量。和原版末影龙一致：1 点 = 半颗心。 */
    private static final float CRYSTAL_HEAL_AMOUNT = 1.0F;

    /**
     * 治疗光效的节流：每 N 次回血才起一次脉冲（回血是 0.5 秒一次，每 2 次 = 1 秒一发）。
     * 比"每 4 次"更频繁是有意的 —— 现在每次只放一小段粒子，多放几次反而更连续。
     */
    private static final int CRYSTAL_FX_INTERVAL = 2;
    /** 一发脉冲在"水晶 → 龙"这条线上走多少 tick 走完。 */
    private static final int CRYSTAL_PULSE_TICKS = 6;

    /** 治疗光效的计数器（决定什么时候起下一发）。 */
    private int crystalFxTicks;
    /** 当前这发脉冲已经走了几个 tick；-1 = 没有在走的脉冲。 */
    private int crystalPulseTicks = -1;
    /** 当前这发脉冲的起点（水晶）。 */
    private Vec3 crystalPulseFrom;
    /** 当前这发脉冲的终点（龙受击点）。 */
    private Vec3 crystalPulseTo;

    /**
     * 水晶治疗的光效。
     *
     * <p><b>为什么得自己做：</b>原版<b>没有</b>回血特效 —— javap 查过 {@code EnderDragon}
     * 和 {@code EndCrystal}，治疗那条路上一个 {@code addParticle} 都没有
     * （水晶到龙的那道光束纯粹是渲染层读 {@code DATA_BEAM_TARGET} 画出来的）。
     * 所以"看不见在回血"是必然的，得自己补。
     *
     * <p><b>为什么不是"整条线铺满粒子"</b>（第一版就是这么做的，已废弃）：<br>
     * 一是<b>看不见流动</b>——粒子均匀撒在 32 格上，看着像一团静止的灰雾，不像能量在输送；<br>
     * 二是<b>开销大</b>——每 2 秒沿整条线撒一遍，在末地那种开阔地形里粒子量很可观。
     * 现在改成<b>一颗沿直线飞过去的脉冲光点</b>：沿途只放少量粒子，
     * 到位时在龙身上炸开一圈。既看得出"能量从水晶流向龙"，粒子量也降了一个数量级。
     */
    private void crystalHealEffects(EndCrystal crystal) {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        // ---- 推进已经在飞的那发脉冲 ----
        if (this.crystalPulseTicks >= 0) {
            this.crystalPulseTicks++;
            if (this.crystalPulseTicks >= CRYSTAL_PULSE_TICKS) {
                this.crystalPulseArrived(server);
            } else {
                double u = this.crystalPulseTicks / (double) CRYSTAL_PULSE_TICKS;
                Vec3 p = this.crystalPulseFrom.lerp(this.crystalPulseTo, u);
                // 光点本体 + 一点点拖尾：拖着才看得出方向
                server.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 2, 0.05D, 0.05D, 0.05D, 0.0D);
            }
        }

        // ---- 起一发新的（受节流控制） ----
        if (++this.crystalFxTicks < CRYSTAL_FX_INTERVAL) {
            return;
        }
        this.crystalFxTicks = 0;
        this.crystalPulseFrom = crystal.position().add(0.0D, 0.6D, 0.0D);
        this.crystalPulseTo = this.position().add(0.0D, this.getBbHeight() * 0.5D, 0.0D);
        this.crystalPulseTicks = 0;
        // 起点先给一小簇，交代"这一发是从这块水晶出来的"
        server.sendParticles(ParticleTypes.END_ROD,
                this.crystalPulseFrom.x, this.crystalPulseFrom.y, this.crystalPulseFrom.z,
                4, 0.15D, 0.15D, 0.15D, 0.02D);
    }

    /** 脉冲打到龙身上：撒一圈绿色十字 + 一声清亮的钟鸣。 */
    private void crystalPulseArrived(ServerLevel server) {
        this.crystalPulseTicks = -1;
        Vec3 to = this.crystalPulseTo;
        server.sendParticles(ParticleTypes.HAPPY_VILLAGER, to.x, to.y, to.z,
                8, this.getBbWidth() * 0.35D, this.getBbHeight() * 0.3D, this.getBbWidth() * 0.35D, 0.0D);
        server.playSound(null, this.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.NEUTRAL, 0.7F, 1.5F);
    }

    /** 飞行辅助（goal 拿它做避障修正，按键拿它开关自由飞行）。 */
    public DragonFlightAssist flightAssist() {
        return this.flightAssist;
    }

    /**
     * 末地水晶治疗器：每 30 秒一轮、每轮最多认 12 块水晶（见 {@link DragonCrystalHeal}）。
     *
     * <p>为什么不像原版末影龙那样"附近有水晶就无限回"：那样基地里放一块水晶的龙就
     * 打不死了。这里把水晶当消耗品、按轮结算，给了一个明确的总量上限。
     */
    private final DragonCrystalHeal crystalHeal = new DragonCrystalHeal();

    /** 水晶治疗器（给展示/调试用）。 */
    public DragonCrystalHeal crystalHeal() {
        return this.crystalHeal;
    }

    /**
     * 开关"自由飞行"（穿墙）。
     *
     * <p>真正的幽灵模式：走原版 {@code Entity.noPhysics}，{@code move()} 会跳过所有方块碰撞。
     * 给玩家一个显式开关，是因为"判定箱 2.6 格宽"在某些地形里物理上就进不去；
     * 与其做一堆半吊子的"只穿树叶"逻辑，不如给一个明确的能力 + 一个自动兜底
     * （后者见 {@link DragonFlightAssist} 的自动脱困）。
     */
    public void setFreeFlight(boolean on) {
        this.flightAssist.setFreeFlight(on);
        // noPhysics 是 Entity 上的 public 字段，没有 setter（javap 查过）
        this.noPhysics = on || this.flightAssist.isRescuing();
    }

    public boolean isFreeFlight() {
        // 直接读 noPhysics：它是原版同步字段（SharedFlags bit 2），服务端设完客户端就能看到。
        // 不另做同步 —— 少一个包、也少一处"两边不一致"的可能。
        return this.noPhysics;
    }

    // ---- 骑手升降键（客户端 DragonRideInputPacket） ----

    /** bit0 = 按着上升键；bit1 = 按着下降键。由包每 tick 同步（只在变化时发包）。 */
    private int riderInputFlags;

    /**
     * 接一条骑手的升降键状态（服务端）。
     *
     * <p>只认"当前确实骑着这条龙"的人，其它一律忽略（防止伪造包）。
     */
    public void onRiderInput(Player player, int flags) {
        if (this.level().isClientSide() || player != this.getControllingPassenger()) {
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[ride] server DROPPED input flags={} (client={} controlling={})",
                        flags, this.level().isClientSide(), this.getControllingPassenger());
            }
            return;
        }
        this.riderInputFlags = flags;
        if (DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[ride] server got input flags={} up={} down={}",
                    flags, this.riderWantsUp(), this.riderWantsDown());
        }
    }

    /** 骑手是不是按着上升键。 */
    public boolean riderWantsUp() {
        return (this.riderInputFlags & 1) != 0;
    }

    /** 骑手是不是按着下降键。 */
    public boolean riderWantsDown() {
        return (this.riderInputFlags & 2) != 0;
    }

    /**
     * "母体 + 所有子碰撞箱"有没有和方块重合。
     *
     * <p>给 {@link DragonFlightAssist} 的"常驻幽灵"检查用：用户的要求是
     * <b>"每 30s 监测一次龙的子母模型是否和墙体有重合"</b> —— 必须连子箱一起看，
     * 否则会出现"母体在外面、头/翅膀埋在石头里"被误判成已经出来。
     *
     * <p>判据用方块碰撞形状（{@code getCollisionShape().isEmpty()}），所以草、雪层、藤蔓
     * 这类没有碰撞箱的方块不算"墙" —— 和 {@code hasRoomFor} / {@code hoverCeilingY} 同一套。
     */
    public boolean isBodyOverlappingBlocks() {
        if (this.overlapsBlocks(this.getBoundingBox())) {
            return true;
        }
        for (DragonGolemPartEntity part : this.parts) {
            if (part != null && this.overlapsBlocks(part.getBoundingBox())) {
                return true;
            }
        }
        return false;
    }

    /** 单个 AABB 有没有和带碰撞形状的方块相交。 */
    private boolean overlapsBlocks(AABB box) {
        Level level = this.level();
        return BlockPos.betweenClosedStream(box)
                .anyMatch(pos -> !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty());
    }

    /**
     * 下了乘客：重算货舱模式、放下冲锋标志，并且<b>把玩家挪到一个真正站得住的地方</b>。
     *
     * <p><b>为什么要挪玩家</b>（真机 bug：骑上龙之后<b>下不来</b>）：原版
     * {@code ServerLevel.removePassenger} 会把乘客放到
     * "{@code move(-bbWidth, 0, -bbWidth)} 扫到的第一个水平不重叠的位置"，<b>只看水平</b>。
     * 而这条龙的判定箱高 2.2 格、背上还有傀儡乘客，落点常常正好落在
     * 龙自己的子碰撞箱或另一个乘客身上；原版找不到位置就<b>静默放弃</b>（日志里没有任何异常），
     * 于是玩家按 Shift 毫无反应、一直挂在龙背上。
     *
     * <p>兜底策略：先试脚下，再试四周，最后放到头顶。
     * 只有在原版给的落点确实和实体相撞时才动手，正常情况不改变原版行为。
     */
    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        this.riderInputFlags = 0;
        // 骑手走了 → 冲锋标志必须一起放下。
        // 否则（玩家在冲锋途中按 Shift 下龙）它会一直挂着，而 isRiderOrderedDive()
        // 因为"没有乘客"已经开始返回 false，标志却仍是 true —— 状态不一致，
        // 驾驶管线与 DiveGoal 的让路判断就会互相打架。这是"下不来"那次留下的最可疑线索。
        if (passenger instanceof Player) {
            this.riderDiveActive = false;
            if (this.orderedSkill == DragonSkillPacket.SKILL_DIVE) {
                this.orderedSkill = -1;
                this.commandedAim = null;
            }
        }
        this.onPassengersChanged();
        if (this.level() instanceof ServerLevel server && passenger instanceof Player player) {
            this.placeDismountedPlayerSafely(server, player);
        }
    }

    /**
     * 下龙兜底：原版落点被占住时，把玩家挪到"脚下 / 四周 / 头顶"第一个不撞的地方。
     *
     * <p>用 {@code noCollision} 判定，它会同时考虑方块和实体（含龙自己的子碰撞箱）。
     */
    private void placeDismountedPlayerSafely(ServerLevel server, Player player) {
        if (server.noCollision(player, player.getBoundingBox())) {
            // 原版给的落点本来就站得住，不动它
            return;
        }
        double baseY = player.getY();
        // 候选：先向下找地面，再向四周，最后向上（宁可落在龙背上也不要下不来）
        double[][] offsets = {
                {0.0D, 0.0D}, {1.5D, 0.0D}, {-1.5D, 0.0D}, {0.0D, 1.5D}, {0.0D, -1.5D},
                {2.5D, 0.0D}, {-2.5D, 0.0D}, {0.0D, 2.5D}, {0.0D, -2.5D}, {0.0D, 0.0D},
        };
        double[] dy = {0.0D, -0.5D, -1.0D, -1.5D, -2.0D, -2.5D, 0.5D, 1.0D, 1.5D, 2.0D};
        for (int i = 0; i < offsets.length; i++) {
            double y = baseY + dy[i];
            // 别把玩家塞进世界底部
            if (y < server.getMinBuildHeight()) {
                continue;
            }
            AABB box = player.getBoundingBox().move(offsets[i][0], dy[i], offsets[i][1]);
            if (!server.noCollision(player, box)) {
                continue;
            }
            player.moveTo(player.getX() + offsets[i][0], y, player.getZ() + offsets[i][1],
                    player.getYRot(), player.getXRot());
            // 落点在半空时别让这一下算成摔落
            player.fallDistance = 0.0F;
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[ride] 下龙兜底：原版落点被占，已挪到 offset=({}, {})",
                        offsets[i][0], dy[i]);
            }
            return;
        }
    }

    /**
     * 有傀儡乘客时进入"货舱模式"：<b>龙悬停在原地、不再到处盘旋</b>。
     *
     * <p>为什么要抑制盘旋：傀儡在背上射击时，龙自己绕着圈飞会让射手永远瞄不稳
     * （而且傀儡的弹道是按自己的朝向算的，龙一转就全歪了）。所以只要背上有傀儡，
     * 就把龙切进"原地待机悬停"——它仍然会跟随主人（超过阈值会被拉回来）、照常索敌开火，
     * 只是不再自己随机游走。
     *
     * <p><b>为什么不靠 {@code setIdleSettled} 实现：</b>那个字段的<b>正常管理者</b>是
     * {@link DragonIdleGoal}，它每 tick 都会重算并覆盖（见它 tick 里的
     * {@code setIdleSettled(this.settled)}）。在这里置位活不过一 tick。
     * 所以货舱模式改成<b>每 tick 重新判定</b>，判定点放在 {@code aiStep} 里
     * （见 {@link #tickGolemPassengerMode()}），并且<b>只在没有玩家驾驶时</b>生效 ——
     * 玩家在开的时候，飞不飞、往哪飞完全由玩家说了算。
     */
    private void onPassengersChanged() {
        // 上车/下车只负责"立刻生效一次"，后续由 tickGolemPassengerMode 维持
        this.tickGolemPassengerMode();
    }

    /**
     * 每 tick 维护"货舱模式"：有傀儡乘客且<b>没有玩家驾驶</b>时，
     * 让待机寻路进入原地待机（不再随机游走）。
     *
     * <p>注意调用点必须在 {@link DragonIdleGoal} 之上游一点：本方法在
     * {@code aiStep} 开头跑，而 goal 的 {@code canUse/tick} 在 {@code super.aiStep()}
     * 里跑，所以这里写下的状态会被当 tick 的 goal 立刻看到。
     */
    private void tickGolemPassengerMode() {
        if (this.level().isClientSide()) {
            return;
        }
        boolean cargo = this.hasGolemPassenger() && this.getControllingPassenger() == null;
        if (cargo && !this.idleSettled) {
            this.setIdleSettled(true);
        }
    }

    /** 背着傀儡当炮台（没有玩家驾驶）。 */
    public boolean isGolemCargoMode() {
        return this.hasGolemPassenger() && this.getControllingPassenger() == null;
    }

    /** 背上是不是有傀儡乘客（不含玩家）。 */
    public boolean hasGolemPassenger() {
        for (Entity passenger : this.getPassengers()) {
            if (passenger instanceof AbstractGolemEntity<?, ?>) {
                return true;
            }
        }
        return false;
    }

    /** 背上傀儡乘客的数量。 */
    public int golemPassengerCount() {
        int count = 0;
        for (Entity passenger : this.getPassengers()) {
            if (passenger instanceof AbstractGolemEntity<?, ?>) {
                count++;
            }
        }
        return count;
    }

    /**
     * {@code PASSIVE} 只用来关掉"它自己索敌"，<b>不该挡玩家让它打谁</b>。
     *
     * <p>本家 {@code canAttackType} 直接返回 {@code !hasFlag(PASSIVE)}，而它同时被"能不能打"和
     * "能不能被当敌人"复用（见 {@code canAttack} 的最后一行）。龙这边<b>只有在执行骑手指令的那几秒</b>
     * 才解除这条限制（{@link #riderCombat}），其余时候交回本家的判定——所以装了坐骑升级的龙
     * 平时依旧是完全被动的（不索敌、不被当敌人），只有玩家按键那一下才真有攻击性。
     */
    @Override
    public boolean canAttackType(EntityType<?> type) {
        return this.riderCombat || super.canAttackType(type);
    }

    /**
     * 同理：被人骑着下命令时要能被别的 mob 当作敌人，平时（被动）不行。
     */
    @Override
    public boolean canBeSeenAsEnemy() {
        return this.riderCombat || super.canBeSeenAsEnemy();
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
        // 骑手按键技能：优先级同样是 2（前排），但它的 canUse 只看"有没有骑手指令"，
        // 所以和上面几个不会互相抢 —— 有指令时它必然拿到 MOVE，没指令时它压根不上。
        this.goalSelector.addGoal(2, new DragonRiderSkillGoal(this));
    }

    /**
     * 一个 tick：AI、骑乘移动、碰撞全在这里面。
     *
     * <p><b>{@link #applyHover()} 为什么要写在 {@code super.aiStep()} 之后</b>（这条以前记在悬停那一段，
     * 顺手挪到这里，因为它同样约束着驾驶时的竖直速度）：{@code Mob.aiStep} 里会调
     * {@code moveControl.tick()} 把竖直速度清零，接着原版骑乘管线（{@code travelRidden}）会用
     * {@code getRiddenInput × getRiddenSpeed} 重算水平与竖直速度 —— 先让它清、我们再覆盖，
     * 下一 tick 的 {@code travel} 才用得上。所以"高度"这一类直接写 {@code deltaMovement.y} 的东西
     * （悬停、俯冲、驾驶升降）统统在 {@code super.aiStep()} 之后补。
     *
     * <p><b>不要改寻路/移动控制的目标高度</b>：寻路判断"有没有走到路径点"用的是三维距离，
     * 把目标 Y 抬高三格会让它永远差三格、卡在第一个路径点上不动（上一版就是这么把龙整不会动的）。
     * 所以高度只在实体自己身上补速度，寻路照旧只管水平。
     */
    @Override
    public void aiStep() {
        // 骑手指令的"解除被动"窗口：有指令、或者冲锋正在飞（pendingSkill 还挂着）时才算。
        // 放在最前面，因为本 tick 里 goal 的 canUse/setTarget 都会问 canAttackType。
        this.riderCombat = this.getControllingPassenger() != null
                && (this.orderedSkill >= 0 || this.pendingSkill == DragonSkill.DIVE);
        // "货舱模式"每 tick 维护一次（背上傀儡 + 没玩家 → 原地悬停不游走）。
        // 必须在 super.aiStep() 之前：goal 的取用发生在那里面。
        this.tickGolemPassengerMode();
        // 玩家在开：水平方向交给原版骑乘管线（travel → travelRidden → getRiddenInput/Speed，
        // 见 DragonRiderControl 的类注释），这里只负责"没人在开"时的收尾。
        Player rider = DragonRiderControl.rider(this);
        if (!this.level().isClientSide && rider == null) {
            DragonRiderControl.relax(this);
        }
        super.aiStep();
        // 竖直：原版骑乘不管升降（没人给输入的 y 分量），而 noGravity 下没人写 Y 速度就永远不动。
        // 必须在 super.aiStep() 之后 —— 写早了会被 travelRidden 里那一步冲掉。
        if (!this.level().isClientSide && rider != null) {
            DragonRiderControl.tickVertical(this, rider);
        }
        // 俯仰由服务端算好同步出去（客户端只读，别自己写 entityData），再摆判定箱。
        // 被驾驶时不走姿态表：那套会按俯冲阶段把俯仰拉回 0，和玩家视角打架
        // （驾驶时的俯仰在 tickRidden → DragonRiderControl.tickBody 里写）。
        if (!this.level().isClientSide && !this.isRiderControlled()) {
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
            // 竖直速度的三种来源，<b>互斥</b>：
            //   1) 有骑手：由 tickVertical 在 super.aiStep() 之后写（见上面的调用），
            //      这里两个分支都不能走 —— 尤其不能走 applyHover()，它会按"回悬停目标高度"
            //      把刚写进去的上升速度压回去。这正是"按空格反而被压下来、上限卡在待机高度 3 格"
            //      的原因（真机日志：服务端确实收到了 up=true 并写了 +0.08，随后被悬停覆盖）。
            //   2) 技能接管（diveVelocity 非 null）：俯冲/拉升直接写速度。
            //   3) 都没人管：悬停自己维持高度。
            if (rider != null) {
                this.keepAimThisTick = false;
            } else if (this.diveVelocity != null) {
                // 俯冲 / 拉升阶段：技能直接接管速度，悬停必须让位（它只会往上顶）。
                this.setNoGravity(true);
                Vec3 vel = this.diveVelocity;
                // 避障只作用在"自己飞"的场合：俯冲那套航线是刻意贴着地形掠过的，
                // 给它加避障会把航线扯歪（撞墙时航线本来就该自己结束，见 DragonDiveGoal）。
                this.setDeltaMovement(vel);
                this.fallDistance = 0.0F;
                if (this.keepAimThisTick) {
                    // 开火中：朝向由 goal 的 aimAt 管（绕圈喷息时运动方向是切线，不能拿来当朝向）
                    this.keepAimThisTick = false;
                } else {
                    // 我们自己写速度就没有 move control 帮忙转向了，这里补上"朝向 = 运动方向"
                    this.faceMovement(vel);
                }
                this.diveVelocity = null;
            } else if (this.flightAssist.isRescuing()) {
                // 卡在方块里（自动脱困中）：交给飞行辅助往开阔处飞，别让悬停把它按回墙里
                this.setNoGravity(true);
                Vec3 escape = this.flightAssist.rescueVelocity(this);
                if (escape != null) {
                    this.setDeltaMovement(escape);
                    this.faceMovement(escape);
                }
                this.fallDistance = 0.0F;
            } else {
                this.keepAimThisTick = false;
                // 这条路上没人写速度、也就没人更新侧倾，把它慢慢收平（否则会留着上一次的压弯角度）
                this.relaxRoll();
                this.applyHover();
            }
        }
        // 飞行辅助：撞墙计数、自动脱困的进出、noPhysics 的唯一写入口。
        // 必须在上面那段之后 —— 那里才刚决定本 tick 的速度。
        this.flightAssist.tick(this);
        // 末地水晶治疗：每 30 秒一轮、每轮最多 12 个可用水晶，详见 DragonCrystalHeal 的说明。
        // 放在这里（服务端 tick 收尾）而不是 goal 里：它是"被动光环"，不该和 AI 决策抢调度。
        if (!this.level().isClientSide) {
            EndCrystal usedCrystal = this.crystalHeal.tick(this);
            if (usedCrystal != null) {
                this.setHealth(Math.min(this.getMaxHealth(), this.getHealth() + CRYSTAL_HEAL_AMOUNT));
                this.crystalHealEffects(usedCrystal);
            }
        }
        // 诊断：定期报一次"竖直相关的全部状态"。排查"按了上升却不动 / 一直往上飞"
        // 必须同时看到 y / 竖直速度 / 水平速度 / 重力 / 目标高度 / 地表高度 / 各模式标志
        // —— 只看其中一两个会一直猜错。
        if (DragonDebug.RIDE && !this.level().isClientSide && this.tickCount % 40 == 0) {
            // 地表高度单独取一次：hoverTarget 是"地表 + 悬停高度"，把它们拆开才能看出
            // "是地表读错了"还是"悬停高度算错了"（这两种的修法完全不同）。
            int surface = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    this.blockPosition().getX(), this.blockPosition().getZ());
            Vec3 v = this.getDeltaMovement();
            Dragon_golems.LOGGER.info(
                    "[ride] state y={} dy={} dxz={} noGravity={} diveVel={} surface={} hoverBase={} hoverTarget={} settled={} parked={} golemPax={} rider={} dive={}",
                    String.format("%.2f", this.getY()),
                    String.format("%.3f", v.y),
                    String.format("%.3f", v.horizontalDistance()),
                    this.isNoGravity(),
                    this.diveVelocity != null,
                    surface,
                    String.format("%.2f", this.hoverHeight()),
                    String.format("%.2f", this.hoverTargetY()),
                    this.idleSettled, this.isParked(), this.golemPassengerCount(),
                    this.getControllingPassenger() != null, this.getDivePhase());
        }
        // 诊断：飞行辅助的状态（卡住计数 / 有没有空间 / 脱困 / noPhysics）。
        // 和上面那行分开打，方便对照"同一时刻的悬停目标和飞行辅助状态"。
        if (DragonDebug.RIDE && !this.level().isClientSide && this.tickCount % 40 == 20) {
            this.flightAssist.logState(this);
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
        // 骑手技能的冷却：和 AI 的那两条完全分开，驾驶期间互不影响
        for (int i = 0; i < this.riderCooldowns.length; i++) {
            if (this.riderCooldowns[i] > 0) {
                this.riderCooldowns[i]--;
            }
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
        Dragon_golems.LOGGER.info(String.format(
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
    /** 龙息（普通 / 下界身体）的伤害类型，见 {@code data/dragon_golems/damage_type/dragon_breath.json}。 */
    private static final ResourceLocation BREATH_DAMAGE_TYPE = Dragon_golems.id("dragon_breath");
    /** 幽匿身体的音波龙息：额外穿护甲与附魔，见同目录的 {@code dragon_sonic.json} 与 {@code data/minecraft/tags/damage_type/}。 */
    private static final ResourceLocation SONIC_DAMAGE_TYPE = Dragon_golems.id("dragon_sonic");
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
     * <p><b>走我们自己的伤害类型</b>（{@code data/dragon_golems/damage_type/}）：
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
        //
        // ★ 这里原来只看"一个方块坐标"（BlockPos.containing(getY() + getBbHeight())），
        //   而那个点是浮点取整的 —— 只要那**一个**位置恰好是空气就允许上升，
        //   可实际挡路的是判定箱（高 2.2 格）跨到的那几个方块。
        //   真机表现：龙卡在 y=-0.20、头上 2.0 处有方块，但那个单点判成空气，
        //   于是每个 tick 都给它 +0.19 的上升速度 → vColl=true、y 却纹丝不动。
        //   现在改成问"天花板有没有把悬停目标压低" —— 一句话同时覆盖"多个方块挡路"
        //   和"实际可用空间"，不再依赖单个方块的取整巧合。
        //   注意比较基准也要用局部地面（不再用世界地表），否则洞里会误判。
        double unclamped = this.localFloorY() + this.hoverHeight();
        if (this.hoverTargetY() < unclamped - 0.1D) {
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
     * <p>降到 {@link #HOVER_HEIGHT_IDLE}(3) 的档位有两个：
     * <ol>
     *   <li>{@link #idleSettled}：{@link DragonIdleGoal} 在"没目标满 30 秒"时写上的原地待机；</li>
     *   <li><b>"停留"命令（{@code GolemModes.STAND}）</b>——这条是后补的，原因见下。</li>
     * </ol>
     *
     * <p><b>为什么"停留"必须也算待机档：</b>{@code STAND} 的 {@code movable=false}，
     * 于是 {@code isMovable()} 为假 → {@link DragonIdleGoal#canUse()} 为假 → <b>那个 goal 根本不跑</b>
     * → {@link #setIdleSettled(boolean)} 永远不会被写成 true。原来只看 {@code idleSettled}，
     * 结果"让龙停下"反而把它钉在<b>作战高度 10 格</b>上：手杖的"停留"对它等于"悬在十格高、
     * 玩家在地面 entity_reach 只有 3 格，怎么都够不着"。现在停留模式直接按待机高度悬停，
     * 也就是"你让它停，它就下来"，骑乘（只能待机骑的上）才有前提。
     *
     * <p>{@code getTarget() == null} 那个判断是保险——真出现目标时立刻回到作战高度，
     * 不会因为滞后一 tick 还挂在 3 格。
     *
     * <p>两个基准值都会再乘 {@link #hoverHeightScale()}（体型越大飞得越高，见那里的说明）。
     * 待机高度也跟着涨：那一档本来就是"给骑乘用的固定高度"，体型大了还贴 3 格等于钻地。
     */
    public double hoverHeight() {
        // 背上有傀儡乘客时也用待机高度：那是"停着让人打"的姿态，低一点更稳。
        // （巡航作战高度 10 格是给"自己游走开火"用的，傀儡乘客要的是稳定平台。）
        double base = this.isParked() || this.hasGolemPassenger() ? HOVER_HEIGHT_IDLE : HOVER_HEIGHT;
        return base * this.hoverHeightScale();
    }

    /**
     * 是不是"停着不动、等人来骑"的状态：手杖切到"停留"，或者待机满 30 秒。
     *
     * <p>骑乘入口（{@link #startRidingFrom(Player)}）也用它：不允许"追着一条正在猛冲的龙骑上去"，
     * 只能等它停下来。想放宽就把这个判断去掉（或者改成"只要没在俯冲就行"）。
     */
    public boolean isParked() {
        return this.getMode() == GolemModes.STAND || (this.idleSettled && this.getTarget() == null);
    }

    /** 悬停高度的体型倍率：{@code max(1, bodyScale())} 的 {@link #HOVER_SCALE_EXP} 次方。 */
    public double hoverHeightScale() {
        double scale = this.bodyScale();
        // 缩小（重铸惩罚、缩小类升级）不降高度：贴地反而更容易把身体插进地形
        return scale <= 1.0D ? 1.0D : Math.pow(scale, HOVER_SCALE_EXP);
    }

    /**
     * 悬停高度参照系（<b>见 {@link #localFloorY()} 的说明</b>）。
     *
     * <p>期望高度 = {@code max(脚下的局部地面, 世界地表 − FLOOR_FALLBACK) + hoverHeight()}，
     * 再被头顶天花板夹住。
     *
     * <p><b>★ 为什么不再直接用世界地表高度</b>（这是整个"一直往上飞"系列的根因）：
     * {@code getHeight(MOTION_BLOCKING_NO_LEAVES)} 返回的是<b>整个世界柱最高的固体方块</b>，
     * 对"飞在洞穴里的实体"毫无意义。实测：龙在矿洞里 y=22，而它读到 70 —— 于是
     * <pre>
     * hoverTarget = 70 + 10 = 80（够不到） → 每个 tick 顶格上爬 → 撞洞顶 → 掉 → 再爬
     * </pre>
     * 我先后用"天花板夹取""applyHover 别硬顶"去救，都是在<b>给一个错误的基准打补丁</b>。
     * 正确的参照系是"<b>它自己脚下的地面 + 头顶的天花板</b>"，与世界地表无关。
     *
     * <p>对骑乘那条路也是根本解：{@code DragonRiderControl.clampVertical} 原来算
     * {@code minY = 世界地表 + 判定箱高} 去"防止钻地"，在洞里就变成 {@code minY = 72.2}，
     * 玩家按下降却算出"要往上抬 49 格"。基准换成局部地面之后那个夹取自动就对了。
     */
    public double hoverTargetY() {
        double target = this.localFloorY() + this.hoverHeight();
        double ceiling = this.hoverCeilingY();
        return ceiling == NO_CEILING ? target : Math.min(target, ceiling);
    }

    /** {@link #hoverTargetY()} 里"没找到天花板"的哨兵值。 */
    private static final double NO_CEILING = Double.MAX_VALUE;
    /** 往上找天花板时最多扫多少格（超过就当作开阔地，避免在高空做长扫描）。 */
    private static final int CEILING_SCAN = 32;
    /** 往下找地面时最多扫多少格。比天花板深一些：洞里悬停高度常有十几格。 */
    private static final int FLOOR_SCAN = 48;
    /**
     * "局部地面"的兜底：往下扫不到任何方块时，允许向下取到世界地表再往下这么多格。
     *
     * <p>为什么要兜底：从高空往下看、或者悬在熔岩湖/虚空上方时，脚下 FLOOR_SCAN 格内
     * 可能真的是空的。这时用世界地表作为参照是合理的（而不是认为"没有地面"）。
     * 取 16 是"悬停高度最大档（10 × 体型倍率）"的余量。
     */
    private static final double FLOOR_FALLBACK = 16.0D;

    /**
     * "龙自己脚下的地面"高度（局部，非世界地表）；扫不到就退回
     * {@code 世界地表 − FLOOR_FALLBACK}。
     *
     * <p>只找<b>有碰撞形状</b>的方块（草/雪层/藤蔓不算地面），和 {@code hasRoomFor}
     * 是同一套判据。这样"站在草丛上"和"站在石头上"的参照一致。
     */
    public double localFloorY() {
        Level level = this.level();
        int x = this.blockPosition().getX();
        int z = this.blockPosition().getZ();
        int startY = Mth.floor(this.getY());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = startY; y > startY - FLOOR_SCAN && y >= level.getMinBuildHeight(); y--) {
            cursor.set(x, y, z);
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                // 方块占据 [y, y+1)，所以站在它上面的高度就是 y+1
                return y + 1.0D;
            }
        }
        // 兜底：往下扫不到东西（悬在深渊/熔岩上方/高空），用世界地表作参照
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - FLOOR_FALLBACK;
    }

    /**
     * 头顶第一层挡路方块的下沿 Y（再往下留一格余量）；正上方 {@link #CEILING_SCAN} 格内
     * 全是空气就返回 {@link #NO_CEILING}。
     *
     * <p>用 {@code getCollisionShape().isEmpty()} 判断"挡不挡路"，所以草、雪层、藤蔓
     * 这些没有碰撞箱的方块不算天花板 —— 和 {@code DragonFlightAssist.hasRoomFor} 是同一套判据。
     */
    private double hoverCeilingY() {
        Level level = this.level();
        double from = this.getY() + this.getBbHeight() + 0.5D;
        int x = this.blockPosition().getX();
        int z = this.blockPosition().getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < CEILING_SCAN; i++) {
            int y = Mth.floor(from) + i;
            if (y >= level.getMaxBuildHeight()) {
                break;
            }
            cursor.set(x, y, z);
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                // 目标 = 障碍下沿再减一格（留出身体高度），但这个值会再由 hoverTargetY 取 min
                return y - 1.0D;
            }
        }
        return NO_CEILING;
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
        // 用我们自己的飞行寻路：按体型放宽路径点容忍度、并让"卡住时重算"真的生效。
        // 原版 FlyingPathNavigation 有两个坑（详见 DragonFlyingNavigation 的类注释）：
        //   1. maxDistanceToWaypoint 默认 0.75 格，几格宽的龙永远"够不到"路径点，不切下一个；
        //   2. recomputePath() 自带 20 tick 节流，超出就只置一个没人消费的标记 →
        //      "撞墙了赶紧重算"这个请求被静默丢弃。
        DragonFlyingNavigation nav = new DragonFlyingNavigation(this, level);
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
