package a_silly_cat.dragon_golems.dragon;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.SweepGolemEntity;
import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.content.config.DragonBodyConfig;
import a_silly_cat.dragon_golems.content.config.DragonBodyEntry;
import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import a_silly_cat.dragon_golems.network.DragonSkillPacket;
import dev.xkmc.modulargolems.content.config.GolemMaterial;
import dev.xkmc.modulargolems.content.entity.goals.FollowOwnerGoal;
import dev.xkmc.modulargolems.content.entity.goals.GolemMeleeGoal;
import dev.xkmc.modulargolems.content.entity.goals.GolemRandomStrollGoal;
import dev.xkmc.modulargolems.content.entity.goals.TeleportToOwnerGoal;
import dev.xkmc.modulargolems.content.entity.humanoid.weapon.GolemWeaponRegistry;
import dev.xkmc.modulargolems.content.entity.targeting.Golem3DTargetGoal;
import dev.xkmc.modulargolems.content.entity.mode.GolemMode;
import dev.xkmc.modulargolems.content.entity.mode.GolemModes;
import dev.xkmc.modulargolems.content.item.upgrade.IUpgradeItem;
import dev.xkmc.modulargolems.content.modifier.base.GolemModifier;
import dev.xkmc.modulargolems.content.modifier.special.BaseRangedAttackGoal;
import dev.xkmc.modulargolems.content.modifier.special.EarthquakeHelper;
import dev.xkmc.modulargolems.content.modifier.special.SonicAttackGoal;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import dev.xkmc.l2damagetracker.contents.attack.AttackEventHandler;
import dev.xkmc.l2damagetracker.contents.attack.CreateSourceEvent;
import dev.xkmc.l2serial.serialization.SerialClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
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
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
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
 * 另外在 {@link #aiStep()} 里补上"离地 N 格"的悬停高度（见 applyHover()）。这里没有搬末影龙的
 * {@code DragonPhaseManager}——那套 phase AI 会和傀儡的跟随/指令系统打架。
 *
 * <p><b>待办：三种攻击（撞人 / 吐息 / 吐龙弹）。</b>接入点先记在这里：
 * <ul>
 *   <li>索敌：沿用本家那套就行（{@code AbstractGolemEntity} 自带目标管理与仇恨），
 *       独立技能用 {@code GolemModifier.onRegisterGoals} 或这里的 {@code addGoal} 加自己的 Goal，
 *       Goal 里 {@code canUse()} 判断"有目标 + 距离合适 + 冷却好了"。</li>
 *   <li>撞人：可以直接复用本家 {@code GolemMeleeGoal} 的判定思路，照着写一段
 *       "锁定落点 → 直线冲刺 → 路径上结算伤害"；冲刺期间要暂时关掉 applyHover() 的抬升
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
     * 转向速度与侧倾现在全部走配置（{@code flight.*}），这里只留"物理上限"。
     *
     * <p>为什么挪进配置：这两组数就是"这条龙转得动不动"的全部手感来源 ——
     * 基础转速 {@code flight.turnRateBase}、玩家驾驶下限 {@code flight.riderTurnRate}、
     * 转弯半径 {@code flight.turnRadius}（ω = v / r）、体型惩罚 {@code flight.turnSizePenalty}、
     * 侧倾 {@code flight.maxRoll} / {@code flight.rollPerDegree}。
     * 以前它们写死在代码里，调一次要重新打包；现在测试的人自己就能拧。
     *
     * <p>为什么必须有"转弯半径"这一条：龙的飞行是 goal 直接写 {@code deltaMovement}，
     * 运动方向一 tick 就能掉头，而模型的 yaw 只能按转速慢慢转。没有它的话，
     * 高速时龙在画大圈而模型还朝着上一个方向，表现就是"转不过来 / 横着飞"。
     */
    private static final float MAX_TURN_HARD_CAP = 96.0F;
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

    /**
     * 直接指定机身侧倾（度）：绕圈喷息时用它压弯，不靠"本 tick 转角"去推。
     *
     * <p>顺手把低通状态 {@link #smoothedTurn} 也拉到对应值：绕圈结束的那一 tick
     * {@link #tickBodyRoll()} 接手时不会从 0 重新起跳（交接处看不到"先回正、再压下去"）。
     */
    public void setBodyRollDirect(float deg) {
        float limit = DragonGolemConfig.maxRoll();
        float clamped = Mth.clamp(deg, -limit, limit);
        this.bodyRollDirectThisTick = true;
        this.smoothedTurn = clamped / Math.max(0.01F, DragonGolemConfig.rollPerDegree());
        this.entityData.set(DATA_BODY_ROLL, clamped);
    }

    /**
     * 平滑地设置飞行速度：<b>水平分量从"当前速度"按 {@code lerp} 过渡过去，竖直分量直接采用</b>。
     *
     * <h2>为什么需要</h2>
     * 巡航是"永不刹停"的（到点立刻换下一个采样点，靠 {@code MIN_SPEED} 保持扇翅节奏），
     * 但采样点可能落在身后：速度矢量一瞬间反向、而模型只能按 {@link #turnRateFor} 慢慢转，
     * 表现就是<b>到了目的地顿一下、再往回蹭</b>（真机反馈的"违和感"来源）。
     * 转向速度治不了这个 —— 那一瞬间是"位移已经反了、朝向还没转"。
     *
     * <p>所以给轨迹本身加一层加速度上限：方向变化摊到几 tick 里，模型来得及转过去。
     * 竖直不参与平滑（悬停是双向收敛的，迟一 tick 就会撞天花板或沉下去）。
     */
    public void steerVelocitySmooth(Vec3 want, double lerp) {
        Vec3 current = this.diveVelocity != null ? this.diveVelocity : this.getDeltaMovement();
        double t = Mth.clamp(lerp, 0.0D, 1.0D);
        this.setDiveVelocity(new Vec3(
                Mth.lerp(t, current.x, want.x),
                want.y,
                Mth.lerp(t, current.z, want.z)));
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
     * 所以先低通再乘系数。不再转向时它会自己 lerp 回 0（见 {@link #tickBodyRoll()}）。
     */
    private float smoothedTurn;

    /** 上一 tick 的 yRot：压弯按"本 tick 实际转过多少度"算，这是它的参照点。 */
    private float prevRollYRot;

    /** 本 tick 的压弯已由 {@link #setBodyRollDirect(float)} 指定（绕圈喷息），{@link #tickBodyRoll()} 让位。 */
    private boolean bodyRollDirectThisTick;

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
     *   <li>{@link #RIDER_SINK} = "人陷进背里多深"（世界格，不随体型放大）：调小 = 坐得更高；</li>
     *   <li>{@link #RIDER_FORWARD_PX} 调大 = 往<b>前</b>挪（靠近颈根）、调小 = 往后挪到翅膀之间
     *       （前后仍按模型像素走，所以大体型时座位间距也跟着变大，符合"背上很宽"的直觉）。</li>
     * </ul>
     *
     * <h2>为什么不是"固定模型像素 × 体型"</h2>
     * 原来座位是一个固定模型像素值（12），世界高度 = {@code (2.861 − 12×PX) × 体型}，
     * 也就是<b>下沉量跟着体型一起放大</b>：1 倍时脚底在背脊表面下 0.85 格（腿藏进背里，
     * 看着就是"坐在背上"），4 倍的泰坦龙沉 3.4 格 —— 而乘客本人只有 1.8 格高，
     * 于是连头一起被埋进龙的身体模型里，从外面只剩一个影子。
     * 根因是"模型像素"对龙是等比的、对人不是。
     *
     * <p>现在改成锚在模型背脊<b>表面</b>上（{@link #RIDER_SURFACE_UP_PX} = 躯干方块顶面，
     * 本类 {@code TORSO_UP_PX} 的注释里写着原版躯干方块占 y=4..28 像素），
     * 再在世界空间减去固定深度 {@link #RIDER_SINK}。1 倍时与旧公式<b>严格相等</b>：
     * {@code (2.861 − 4×PX) − 0.85 = 1.586 = (2.861 − 12×PX)}。
     * 现在这组值（12 / 6）是"坐在肩背上、头颈后面"的位置。调过一次：
     * 上一版是 12 / 20，玩家反馈"坐在脖颈第一节和第二节之间的<b>上方一格</b>"——
     * 也就是 <b>太靠前</b>了，所以把 FORWARD 从 20 收到 6（20 像素 ≈ 往前 2.1 格，
     * 6 像素 ≈ 0.6 格，正好从颈根退到肩背）。UP 保持不变。
     *
     * <p>往前挪还会撞上另一件事：<b>龙的头部子碰撞箱就伸在玩家正前方</b>，
     * 坐得太靠前时准星射线会先命中自己这条龙（客户端瞄准那侧已经加了"排除坐骑"的过滤，
     * 但座椅靠后能让视锥更干净、也能少一点贴脸时的镜头穿模）。
     */
    private static final double RIDER_SURFACE_UP_PX = 4.0D;
    private static final double RIDER_FORWARD_PX = 6.0D;
    /**
     * 乘客脚底陷进背脊表面多深（<b>世界格</b>）：<b>按体型等比放大，但最多放大到 1 倍体型那一档</b>。
     *
     * <p>0.85 是"1 倍体型下原来那个 12 像素"折出来的等价值，所以 1 倍手感一个像素都没变；
     * 想让人坐得更高就调小（0 = 正好站在背面上），想更贴背就调大。
     *
     * <p>为什么要有"最多到 1 倍"这个上限：乘客本身不会跟着龙一起变大。
     * 等比放大的话 4 倍泰坦就沉 3.4 格（人只有 1.8 格高 → 整个人被埋进模型里）；
     * 反过来把它做成固定值时，缩小 4 档的龙（背脊表面才 0.6 格高）又会被"沉 0.85 格"
     * 塞到肚子底下。所以取两头的好处：缩小侧等比（和模型一致）、放大侧封顶（和乘客一致）。
     */
    private static final double RIDER_SINK = 0.85D;

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
        // 按"谁是玩家"回答，而不是按"谁在 0 号位"回答。
        // 原版 Entity#addPassenger 只在服务端把玩家插到 0 号位（玩家到得晚、傀儡先坐在
        // 驾驶座上时就是这么排的），客户端照服务端下发的 SetPassengers 包顺序重建，
        // 正常两边一致；但"有玩家就归玩家开"是这里的语义，扫一遍就不必赌这个顺序 ——
        // 顺序一旦错位，这里返回 null 会直接退化成"没人驾驶，龙自己飞、按键全无反应"。
        for (Entity passenger : this.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
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
     *
     * <p><b>只给 {@link #isMovable()} 用，别拿它当骑乘闸门</b>：{@link #startRidingFrom(Player)}
     * 曾经用它判"驾驶座有没有人"，那正好把"先放炮台傀儡、后上玩家"这条最常见的顺序挡死。
     * 该不该让人上只由 {@link #canAddPassenger} 回答。
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
        // ★ 这里**不要**为"骑手冲锋"返回 false（我试过一次，已回退）。
        //   当时的推理是"冲锋期间客户端不知道在冲锋，只跑悬停，会压过服务端的俯冲速度"，
        //   于是让它返回 false 想"把位置权威交给服务端"。真机结果是**龙彻底不能动**：
        //   骑着时乘客控制着这条龙，这个开关一关，客户端不再预测、服务端那侧也不再推进，
        //   两边互相等 —— 龙卡在俯冲姿态里一动不动（截图确认）。
        //   正确的做法是让客户端"预测对的东西"，而不是让它别预测。
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
     *   <li>座位 0 = 驾驶座。坐着玩家就由玩家驾驶（见 {@link DragonRiderControl}）；
     *       坐着傀儡则退化为"纯乘客"，龙的 AI 继续自己飞（这正是"傀儡骑在龙身上当炮台"）；</li>
     *   <li>其余座位给傀儡，数量上限 {@link #MAX_GOLEM_PASSENGERS}。</li>
     * </ul>
     *
     * <p><b>玩家不再被"驾驶座被占"挡住。</b>这里是全模组唯一一道"能不能上"的判定，
     * 现在的规则是：<b>只要这条龙上还没有别的玩家，玩家随时能上</b> —— 背上驮着傀儡、
     * 龙正在飞/俯冲/战斗、开着什么模式，一概不拦。已经在驾驶座上的傀儡不用先请下去：
     * 原版 {@code Entity#addPassenger} 在服务端会把玩家<b>插到 0 号位</b>
     * （{@code list.add(0, player)}，条件正是"首个乘客不是玩家"），傀儡整体后移一格，
     * 座位重排由 {@link #positionRider} 每 tick 跟着算。
     *
     * <p><b>为什么放开给傀儡不会破坏驾驶</b>：{@link #getControllingPassenger()} 只在
     * 乘客里<b>真的有玩家</b>时才返回非 null（傀儡不算），所以傀儡乘客不会顶掉玩家的驾驶权，
     * 也不会让 AI 的"有乘客就别动手"那批守卫误判。
     *
     * <p>本家 {@code AbstractGolemEntity} 一个载客方法都没覆写（只有
     * {@code DogGolemEntity} 有那六个），所以这里没有基类钩子可蹭，得自己写。
     */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (passenger instanceof Player) {
            // 玩家永远坐驾驶座，背上已经有傀儡也照上（原版会把他插到 0 号位，傀儡后移一格）。
            // 唯一保留的硬规则：一条龙上只能有一个玩家 —— 两个玩家会让
            // getControllingPassenger() 没法回答"谁在开"，WASD 到底听谁的。
            return this.getPassengers().stream().noneMatch(p -> p instanceof Player);
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
     * 其余乘客依次向后（座位号由 {@link #seatIndexOf} 算，玩家永远排在最前）。
     * 区别只是改成沿龙的机体方向、并复用本类已有的
     * {@link #rotateAndLift} 换算（所以龙低头/抬头时整排座位跟着转）。
     *
     * <p>返回值必须相对 {@code this.getX()/getY()/getZ()}（实体原点是脚底）。
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction setPos) {
        if (!this.hasPassenger(passenger)) {
            return;
        }
        int index = this.seatIndexOf(passenger);
        if (index < 0) {
            return;
        }
        // 每往后一个座位，沿机体方向退 SPACING 像素（模型像素，再经 rotateAndLift 换算成格）
        double forwardPx = RIDER_FORWARD_PX - index * RIDER_SEAT_SPACING_PX;
        // 竖向锚在"背脊表面"上（模型像素只用来找那个点），傀儡乘客再往上抬一点点，免得和玩家的腿重叠
        double surfacePx = RIDER_SURFACE_UP_PX - (index == 0 ? 0.0D : RIDER_PASSENGER_UP_PX);
        double[] upForward = this.rotateAndLift(surfacePx, forwardPx, this.getBodyPitch());
        double yaw = Math.toRadians(this.yBodyRot);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        // ★ 竖向 = 模型背脊表面 − 下沉量，而下沉量按体型等比放大、**最多放大到 1 倍体型那一档**：
        //   - 缩小体型（scale < 1）：下沉量与旧公式完全一致（表面与座位一起等比缩小），
        //     小体型的手感零变化 —— 不然"固定 0.85 格"会把缩小 4 档的龙（表面才 0.6 格高）
        //     里的乘客塞到肚子底下；
        //   - 放大体型（scale > 1）：下沉量钉在 0.85 格不再涨，于是泰坦也不会把人埋进身体里
        //     （见 RIDER_SINK 的说明）。
        //   横向与前后仍按模型像素 × 体型，所以大体型时座位自然铺开在更宽的背上。
        double sink = RIDER_SINK * Math.min(1.0D, this.bodyScale());
        setPos.accept(passenger,
                this.getX() + forwardX * upForward[1],
                this.getY() + upForward[0] - sink + passenger.getMyRidingOffset(),
                this.getZ() + forwardZ * upForward[1]);
    }

    /**
     * 判定箱在"当前乘客"面前允许的最大拾取半径。
     *
     * <h2>要解决的问题</h2>
     * 原版选中实体时（{@code ProjectileUtil.getEntityHitResult} / {@code GameRenderer.pick}）有两支：
     * <pre>
     * if (拾取箱.contains(射线起点))  → 无条件选中它，距离 = 0     // ← 不看 root vehicle
     * else if (拾取箱与射线相交)      → 同 root vehicle 的会被跳过   // ← 这一支才看
     * </pre>
     * 第一支就是"泰坦体型挡住骑手"的根：龙是 26 格长的多部件实体，子碰撞箱按模型等比放大，
     * 而玩家只有 1.8 格高 —— 普通体型时人的眼睛刚好在躯干箱顶面之上，泰坦（4 倍）时箱顶长了 4 倍、
     * 眼高没变，眼睛就被包进箱子里了，准星被离自己 0 格的箱子抓住，右键/攻击/放方块全被自己的龙吃掉。
     *
     * <h2>解法：收的是拾取箱，不是座位</h2>
     * 座椅保持"模型决定"（见 {@link #positionRider}），这里把<b>拾取箱</b>收到刚好不包住乘客眼睛为止：
     * 只要某个箱子的水平范围罩着某位乘客，它的最大半径就被限制成
     * {@code 乘客眼高 − 箱顶 − 0.05}。于是：
     * <ul>
     *   <li>普通体型：算出来通常比配置值还大，配置值原样生效（只有躯干箱会被削掉一点点）；</li>
     *   <li>泰坦体型：算出来是负数 → 拾取箱比碰撞箱还小（碰撞体积、伤害判定完全不受影响），
     *       眼睛落在箱子外面，"起点在箱子里就无条件选中"那一支自然不再命中自己的龙；</li>
     *   <li>{@code getRootVehicle()} 那条修复仍然负责"射线擦到箱子"那一支（见
     *       {@link DragonGolemPartEntity#getRootVehicle()}）。两条一起才闭环。</li>
     * </ul>
     *
     * <p>返回值会被夹在"不把 {@link AABB} 缩成反向"的范围内：{@code ProjectileUtil} 用
     * {@code box.inflate(radius)} 之后要拿去做射线求交，箱子的 min 一旦超过 max，
     * 那段除法会出现 Inf/NaN。几何上这个下限永远够用（箱高与体型成正比，而需要的收缩量比它小得多）。
     *
     * @param box        判定箱（本体的、或某个子箱的）
     * @param configured 配置里写的额外拾取半径
     */
    public float pickRadiusFor(AABB box, double configured) {
        double limit = Double.MAX_VALUE;
        for (Entity passenger : this.getPassengers()) {
            if (passenger.getX() < box.minX - PICK_EYE_MARGIN || passenger.getX() > box.maxX + PICK_EYE_MARGIN
                    || passenger.getZ() < box.minZ - PICK_EYE_MARGIN || passenger.getZ() > box.maxZ + PICK_EYE_MARGIN) {
                // 这个箱子不在该乘客身下（头/尾/翅膀通常都是这种），不必为它让步
                continue;
            }
            limit = Math.min(limit, passenger.getEyeY() - box.maxY - PICK_EYE_MARGIN);
        }
        double pick = Math.min(configured, limit);
        double shrinkLimit = Math.min(Math.min(box.getXsize(), box.getYsize()), box.getZsize()) * 0.5D - 0.05D;
        return (float) Math.max(pick, -shrinkLimit);
    }

    /** 判断"这个箱子是否罩着这位乘客"、以及"眼睛要留多少余量"时用的容差（格）。 */
    private static final double PICK_EYE_MARGIN = 0.05D;

    /**
     * 本体的额外拾取半径（格，见 {@link DragonGolemConfig#bodyPickRadius()}）。
     *
     * <p>原版默认是 0，也就是"选中范围 = 碰撞箱本身"。龙的本体判定箱只有 2.6 × 2.2（身体重心那一块），
     * 而模型有 26 格长 —— 玩家看着龙身点下去经常点不到，回收/开界面都别扭。给一点余量之后
     * 明显好点中；再大的余量交给子碰撞箱那边（它们本来就盖住整条龙）。
     *
     * <p>实际生效值会被 {@link #pickRadiusFor} 削一次：<b>任何罩着乘客的箱子都不许把乘客的眼睛
     * 包进拾取箱</b>（否则准星会被自己的坐骑吃掉，见那里的说明）。
     */
    @Override
    public float getPickRadius() {
        return this.pickRadiusFor(this.getBoundingBox(), DragonGolemConfig.bodyPickRadius());
    }

    /** 相邻两个座位之间沿机体方向的距离（模型像素，1 格 = 16 像素）。 */
    private static final double RIDER_SEAT_SPACING_PX = 9.0D;
    /** 傀儡乘客比驾驶座再抬高多少（模型像素；模型 y 越小越高，所以是从表面值里减掉它）。 */
    private static final double RIDER_PASSENGER_UP_PX = 3.0D;

    /**
     * 这个乘客坐在第几号座位（{@code 0} = 驾驶座，{@code < 0} = 不是乘客）。
     *
     * <p><b>刻意不用</b> {@code getPassengers().indexOf(passenger)}：座位分区是
     * "<b>玩家在前、傀儡在后</b>"，而乘客列表的顺序是<b>原版的规则</b> ——
     * {@code Entity#addPassenger} 只在服务端把玩家插到 0 号位（{@code list.add(0, player)}，
     * 客户端那次 {@code addPassenger} 走的是"追加到末尾"），客户端最终顺序靠服务端下发的
     * {@code SetPassengers} 包重建（{@code ClientPacketListener#handleSetEntityPassengersPacket}
     * 逐个 {@code startRiding(force=true)}）。正常两边一致，把分区规则写在这里是多一层保险：
     * 顺序真错位时顶多"谁坐前排"要重新算，而不是让驾驶者被排到后排去。
     */
    private int seatIndexOf(Entity passenger) {
        boolean driver = passenger instanceof Player;
        int players = 0;
        for (Entity p : this.getPassengers()) {
            if (p instanceof Player) {
                players++;
            }
        }
        // 傀儡从"玩家数量"这一位开始排（没有玩家时就是 0 号驾驶座，和以前一样）
        int seat = driver ? 0 : players;
        for (Entity p : this.getPassengers()) {
            if (p == passenger) {
                return seat;
            }
            if ((p instanceof Player) == driver) {
                seat++;
            }
        }
        return -1;
    }

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
        // ★ 这里**不要**再为"有敌人"开口子。
        //   我一度加过 `if (getTarget() != null) return true;`，理由是"停止模式下龙一直索敌
        //   却不出手，连自保都不行"。但用户明确澄清：**"停止模式下不攻击"就是设计本意** ——
        //   "停"是"把它放下、别动手"的指令，不是"换个地方打"。
        //   而且三个攻击 goal 的 canUse() 第一句都是 isMovable()，所以"不能移动"
        //   在实现上就等于"不能攻击"，这正是那个设计得以成立的地方。
        //   （注意：这不会影响骑乘升降 —— 那条走 tickVertical，只看 localFloorY 和按键。）
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
     * 玩家现在是不是拿着"骑乘手杖"。
     *
     * <p><b>按物品 id 认，不按类认。</b>手杖类在 {@code dev.xkmc.modulargolems} 里，
     * 而本家有些构建把它 jarjar 进别的包（龙这个包编译时就不一定拿得到那个类）。
     *
     * <p><b>两种形态都要认</b>：普通骑乘手杖 {@code modulargolems:rider_wand}，以及万能手杖切到
     * "骑乘"模式后换成的 {@code modulargolems:omnipotent_wand_rider} —— 后者是<b>另一个已注册物品</b>
     * （l2itemselector 切模式 = 换物品），只认路径里含 {@code rider_wand} 的那个会让这条路整条漏掉：
     * 我们的 handler 不管，本家的 {@code RiderWandItem.ride} 又只对狗
     * {@code startRiding}，玩家看到的就是"右键龙完全没反应"（关掉上游 mgdp 之后尤其明显）。
     */
    public static boolean canRideWith(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.contains("rider_wand") || path.equals("omnipotent_wand_rider");
    }

    /**
     * 上龙。服务端执行真正的 {@code startRiding}，客户端只回成功让手感一致
     * （和原版 {@code Animal.mobInteract} 里 {@code player.startRiding(this)} 的分端写法一致）。
     *
     * <p><b>不再要求"龙停着"</b>。这里原先有一道 {@link #isParked()} 闸门（"只能骑停着的龙"），
     * 真机反馈是"跟在身边的龙右键完全没反应"—— 玩家分不出龙是停着还是在跟着走，只会认为手杖坏了。
     * 本家的狗随时可上，上游 mgdp 的骑乘入口也没有这道限制，所以跟它们对齐：只要有空的驾驶座就让人上。
     * 正在俯冲/冲锋时也照上 —— 骑手一上去，各个 goal 就会因为
     * {@code getControllingPassenger()} 非空而自己收手（见 {@link #setTarget} 与
     * {@code DragonRiderControl}）。
     *
     * <p><b>也不再要求"驾驶座空着"</b>：背上已经驮着傀儡（炮台乘客）时玩家照样能上 ——
     * 原版 {@code Entity#addPassenger} 会把玩家插到 0 号位、傀儡整体后移一格，
     * 所以"玩家比傀儡晚到"不该被挡在门外。
     *
     * <p>真正拦人的只剩 {@link #canAddPassenger} 那一条：这条龙上已经有一个玩家
     * （唯一硬规则，见那里的说明）。另外还有一条<b>原版</b>的、不属于本模组的门槛：
     * 玩家刚下过车后的 {@code boardingCooldown}（60 tick = 3 秒，{@code Entity#canRide}），
     * 那是全游戏所有载具共用的防误触冷却，这里不动它。
     *
     * @return 这条龙现在是不是有人骑着了
     */
    public boolean startRidingFrom(Player player) {
        if (this.level().isClientSide()) {
            return true;
        }
        // 座位规则统一由 canAddPassenger 回答；这里不再单独看"驾驶位有没有人"——
        // 那个判据会把"傀儡先坐上去"误判成"满了"，正好是最常见的操作顺序（先放炮台再上人）。
        if (!this.canAddPassenger(player)) {
            return false;
        }
        player.startRiding(this);
        return this.getControllingPassenger() != null;
    }

    /**
     * 让一个傀儡作为乘客上龙。
     *
     * <p>入口是<b>本家的成品</b>：{@code GolemHolder.interactLivingEntity} —— 拿装好傀儡的成品右键龙，
     * 本家会在龙的位置召唤它，并对它调 {@code checkRide(龙)}。而本家 {@code checkRide} 只认狗
     * （金属傀儡还要求狗比它宽，类人傀儡认狗和马），所以"傀儡上龙当炮台"这条路在本家那边是断的；
     * 由我们的 {@code GolemCheckRideMixin} 接住"目标是龙"这一种情况，转到这里上背。
     *
     * <p>座位规则复用 {@link #canAddPassenger}：玩家只坐第一个位子、傀儡最多
     * {@link #MAX_GOLEM_PASSENGERS} 个乘客位。首个乘客是傀儡时它只是乘客（
     * {@link #getControllingPassenger()} 仍为 null），龙照旧自己飞 —— 这正是"空中炮艇"。
     *
     * @return 这个傀儡现在是不是在龙背上
     */
    public boolean rideAsPassenger(AbstractGolemEntity<?, ?> golem) {
        if (this.level().isClientSide()) {
            return true;
        }
        if (!this.canAddPassenger(golem)) {
            return false;
        }
        golem.startRiding(this);
        return golem.getVehicle() == this;
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
        // 诊断：骑手冲锋途中目标被清空，整个航线就废了（真机症状：phase=LINEUP 卡 37 tick
        // 然后 finish(target=null)）。目标被谁都可能清，光看"结果"分不出是谁，
        // 所以这里在"骑手冲锋期间把非 null 目标清成 null"时打一行**调用栈**。
        if (DragonDebug.RIDE && target == null && this.getTarget() != null && this.isRiderOrderedDive()) {
            StringBuilder trace = new StringBuilder();
            for (StackTraceElement el : new Throwable().getStackTrace()) {
                if (el.getClassName().startsWith("a_silly_cat.dragon_golems")) {
                    trace.append(el.getClassName().substring(el.getClassName().lastIndexOf('.') + 1))
                            .append('.').append(el.getMethodName()).append(':').append(el.getLineNumber())
                            .append(" <- ");
                }
            }
            Dragon_golems.LOGGER.info("[dive] 目标被清空！调用链: {}", trace);
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
        // 骑手冲锋是否在进行中 —— <b>必须同步</b>，见 isRiderOrderedDive() 的说明。
        this.entityData.define(DATA_RIDER_DIVE, false);
        // 本 tick 是否处于"瞄准态"（喷息/龙弹）。同步给客户端：渲染层据此把机身/头
        // 锁在瞄准方向，而不是跟着运动方向转（"侧着喷火"的根因）。
        this.entityData.define(DATA_AIMING, false);
    }

    /** 同步字段：本 tick 是否在瞄准（喷息/龙弹期间为真）。 */
    private static final EntityDataAccessor<Boolean> DATA_AIMING =
            SynchedEntityData.defineId(DragonGolemEntity.class, EntityDataSerializers.BOOLEAN);

    /** 是否处于瞄准态（客户端渲染用它把机身锁到瞄准方向）。 */
    public boolean isAiming() {
        return this.entityData.get(DATA_AIMING);
    }

    /** 同步字段：骑手冲锋进行中。客户端据此让出位置权威（见 isControlledByLocalInstance）。 */
    private static final EntityDataAccessor<Boolean> DATA_RIDER_DIVE =
            SynchedEntityData.defineId(DragonGolemEntity.class, EntityDataSerializers.BOOLEAN);

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
        // 同步给客户端：冲锋期间它必须让出位置权威，否则它的悬停会压过我们的俯冲速度
        // （"骑着不动、下龙才冲"的根因，见 isRiderDiveSynced 的说明）
        this.setRiderDiveSynced(true);
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
     * 骑手冲锋的<b>同步</b>镜像：{@link #isRiderOrderedDive()} 只在服务端算得对
     * （{@code riderDiveActive} 是普通字段），但下面这个方法<b>两端</b>都必须给出一致答案。
     *
     * <p><b>★ 这是"骑着时冲锋不动、下龙才冲出去"的机制性原因：</b>
     * 骑着时 {@link #isControlledByLocalInstance()} 返回 true，位置由<b>客户端</b>模拟；
     * 而客户端不跑 goal（{@code DragonDiveGoal.tick} 在服务端分支里），
     * 于是客户端<b>根本不知道在冲锋</b>，只跑 {@code applyHover()}（悬停）——
     * 客户端的悬停位置压过服务端写进 {@code diveVelocity} 的俯冲速度。玩家一下龙，
     * {@code isControlledByLocalInstance()} 变假、位置改由服务端裁定，俯冲就"突然能用"了。
     *
     * <p>所以冲锋期间要让客户端<b>让出位置权威</b>（{@code isControlledByLocalInstance()} 返回 false），
     * 而那个判断必须两端一致 —— 因此这条状态走 {@code entityData} 同步，而不是普通字段。
     *
     * <p>注意同步有一两 tick 延迟：起手那两 tick 可能仍有客户端预测，属于可接受的抖动；
     * 冲锋持续十几到几十 tick，主体部分走的是服务端权威。
     */
    public boolean isRiderDiveSynced() {
        return this.entityData.get(DATA_RIDER_DIVE);
    }

    /** 服务端专用：写同步位（{@code orderDive} / {@code clearRiderDive} 里调）。 */
    private void setRiderDiveSynced(boolean active) {
        this.entityData.set(DATA_RIDER_DIVE, active);
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
        // 同步位一起放下：客户端要把位置权威交还给驾驶管线
        this.setRiderDiveSynced(false);
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

    // ---- 自由行动（FREE_WANDER）的原点 ----

    /**
     * "自由行动"模式下这条龙的<b>家</b>（原点）；{@code null} = 还没记过。
     *
     * <p><b>为什么要自己记：</b>本家 {@code GolemModes.FREE_WANDER} 是
     * {@code new GolemMode(false, true, true, ...)} —— 第一个参数 {@code positioned = false}，
     * 也就是<b>它不记原点</b>。而 {@code AbstractGolemEntity.getTargetPos()} 对
     * {@code !hasPos()} 的模式返回的是<b>主人位置</b>，所以本家的"自由行动"实际上是
     * "跟着主人、但可以到处乱走"。
     *
     * <p>我们想要的"自由行动"是<b>有家</b>的：切到该模式时把当时位置记为原点，
     * 之后龙围绕原点自行游走、自行追打，离原点过远就回原点（而不是回主人身边）。
     * 用户明确要的就是这个（"傀儡有一个收回原点，然后可以自行追逐攻击"）。
     *
     * <p>用本家已有的锚点机制（{@code setMode(id, pos)} 传进来的那个 {@code BlockPos}）
     * 会更好，但它只对 {@code hasPos()} 的模式生效 —— 而 {@code FREE_WANDER} 恰好不是。
     * 所以这里在自己这侧补一个：第一次需要时就地记下，够用且不依赖上游改行为。
     */
    @Nullable
    private Vec3 freeWanderOrigin;

    /** 上次见到的模式，用来在"进出自由行动"时清掉原点。 */
    @Nullable
    private GolemMode lastSeenMode;

    /** 现在是不是"自由行动"模式。 */
    public boolean isFreeWander() {
        return this.getMode() == GolemModes.FREE_WANDER;
    }

    /**
     * 自由行动的原点；第一次调用时把当前位置记下来。
     *
     * <p>只在 {@link #isFreeWander()} 为真时有意义。进/出该模式时原点会被清掉
     * （见 {@link #tickFreeWanderOrigin()}），所以"重新切一次模式"等于"重新安家"。
     */
    public Vec3 freeWanderOrigin() {
        if (this.freeWanderOrigin == null) {
            this.freeWanderOrigin = this.position();
        }
        return this.freeWanderOrigin;
    }

    /**
     * 每 tick 维护原点：<b>模式一变就清</b>。
     *
     * <p>为什么必须清：不清的话"切到自由行动 → 安家 → 切走 → 再切回来"会沿用旧原点，
     * 而玩家第二次切的时候显然是想在<b>新位置</b>安家。清掉之后
     * {@link #freeWanderOrigin()} 会在下一次被问到时就地重记。
     */
    private void tickFreeWanderOrigin() {
        GolemMode now = this.getMode();
        if (this.lastSeenMode != now) {
            this.lastSeenMode = now;
            this.freeWanderOrigin = null;
        }
    }

    // ---- 飞行辅助（穿墙 / 脱困 / 避障） ----

    /**
     * 飞行辅助：自由穿墙开关 + 卡住自动脱困 + 主动避障。
     *
     * <p>状态是"行为"而不是"外观"，所以放在普通字段里、不走 {@code entityData} 同步：
     * 服务端是权威，客户端顶多差一两 tick 的表现。
     */
    private final DragonFlightAssist flightAssist = new DragonFlightAssist();
    /**
     * B 档寻路：A* 出航路点，龙只按航路点转向（见 {@link DragonPathFollower}）。
     *
     * <p>它是"目标点校验"的唯一出口：游走 / 返航 / 战斗靠拢都问它"现在该朝哪飞"，
     * 于是绕墙由原版 A* 负责，而速度、悬停高度、俯冲航线仍然由各自的 goal 管。
     */
    private final DragonPathFollower pathFollower = new DragonPathFollower(this);
    /**
     * <b>飞行执行层</b>：每 tick 唯一的速度出口（见 {@link DragonFlightController}）。
     *
     * <p>之前"谁能写 {@code deltaMovement}"散落在实体、四个 goal、飞行辅助里，每修一个 bug
     * 就多一条否决规则、彼此打架 —— 抽搐/穿墙/"想往上飞"都是这么来的。
     * 现在统一走：<b>goal 表意图 → 控制器选模式 → 安全层夹进高度带 → 写一次</b>。
     */
    private final DragonFlightController flightController = new DragonFlightController(this);

    /** B 档路径跟随器（服务端 AI 用；客户端不参与寻路）。 */
    public DragonPathFollower pathFollower() {
        return this.pathFollower;
    }

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

    /**
     * <b>回血"生效中"的常驻光效</b>：每 10 tick 在机身周围撒几颗 END_ROD。
     *
     * <p>为什么需要它：原来的光效<b>只在真的回血那一 tick 触发</b>（受伤 + 32 格内有存活水晶 +
     * 30 秒判定通过 + 不满血），其余时间完全静默 —— 所以"满血时靠近水晶"或"刚进 32 格还没到判定周期"
     * 看起来就是"没特效"。加上这一层之后，只要这一轮回血是<b>开启</b>状态（{@code isActive()}），
     * 机身周围就一直有能量流动，玩家能直接看出"水晶正在供能"。
     */
    private void crystalHealAura() {
        if (!this.crystalHeal.isActive() || this.tickCount % 10 != 0) {
            return;
        }
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        server.sendParticles(ParticleTypes.END_ROD,
                this.getX(), this.getY() + this.getBbHeight() * 0.6D, this.getZ(),
                3, this.getBbWidth() * 0.4D, this.getBbHeight() * 0.25D, this.getBbWidth() * 0.4D, 0.01D);
    }

    /** 飞行执行层（诊断/日志用）。 */
    public DragonFlightController flightController() {
        return this.flightController;
    }

    // ---- 执行层要用的访问器（同包可见） ----

    /** 技能航线速度（俯冲/拉升写的那个；{@code null} = 没人在跑技能）。 */
    @Nullable
    Vec3 scriptVelocity() {
        return this.diveVelocity;
    }

    /** 技能航线速度用掉了（和原来的 {@code this.diveVelocity = null} 等价）。 */
    void clearScriptVelocity() {
        this.diveVelocity = null;
    }

    /**
     * 当前招式意图被水平碰撞连续挡住多少 tick（0 = 没被挡）。
     *
     * <p>S3：执行层拥有"卡住"的判定权，goal 用这个数字决定要不要收招/换路，
     * 而不是自己硬推、等"位移停滞看门狗"事后补救。
     */
    public int flightIntentBlockedTicks() {
        return this.flightController.blockedTicks();
    }

    /** 这一 tick 是否要"保持瞄准"（开火时朝向由 goal 管）。 */
    boolean keepAim() {
        return this.keepAimThisTick;
    }

    /**
     * 悬停/别处要用的竖直速度 —— <b>直接就是唯一跟踪器</b>（{@link #trackedVerticalSpeed()}）。
     *
     * <p>以前这里是另一套判据（{@code |dy| ≤ 0.2 不动}、{@code 0.03 + dy×0.05}、上限 0.18），
     * 和 goal 里的 {@code hoverDelta()} 是同一件事的两份实现 —— 两边上限不同，
     * 在"贴天花板/贴地"的边界上就会打架。现在统一交给跟踪器。
     *
     * @return 本 tick 的竖直速度（可能为 0，但不再是 {@code null}，调用方不需要判空）
     */
    double hoverYSpeed() {
        return this.trackedVerticalSpeed();
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

    /**
     * 冲锋期间把背上的傀儡逼进<b>近战模式</b>。
     *
     * <p>真机反馈："龙傀儡冲刺时，傀儡来不及切换近战武器（其实是没切换）"。
     *
     * <p>机制（查了本家 2.6.34 源码 + 2.7.3 字节码）：
     * <pre>
     * AbstractGolemEntity.isInRangedMode() = getMode() == STAND || entityData(IS_IN_RANGE_ATTACK)
     * AbstractGolemEntity.hasRangeAttack() = false（由人形子类覆写）
     * GolemMeleeGoal.tickMove(): boolean hasRange = golem.hasRangeAttack() || ...
     * </pre>
     * 也就是说"有远程武器 + IS_IN_RANGE_ATTACK 为真"就等于<b>永远远程模式</b>，
     * 它会一直举着远程武器（而且 tickMove 里 hasRange 为真时还会 strafe 往后躲）。
     * 冲刺是拿身体撞过去，这一下必须是近战武器才有意义。
     *
     * <p>所以冲锋期间每 tick 把乘客的 {@code IS_IN_RANGE_ATTACK} 压成 false
     * —— 那是上游的公开 API（{@code setInRangeAttack}），不是我们自己造的开关。
     * 冲锋结束就不再干预，本家自己会按目标距离重新评估。
     *
     * <p><b>已知局限</b>：{@code isInRangedMode()} 还有 {@code getMode() == STAND} 那半边，
     * 所以"停止模式下背着傀儡冲刺"仍然压不住（那种情况要临时改乘客的模式，副作用更大，
     * 用户选择先不做）。
     */
    private void forceGolemPassengersMelee() {
        for (Entity passenger : this.getPassengers()) {
            if (passenger instanceof AbstractGolemEntity<?, ?> golem) {
                golem.setInRangeAttack(false);
            }
        }
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
        // 索敌：把本家那套"就近优先"的 Golem3DTargetGoal 换掉（优先级同样是 3，间隔 5 tick）。
        // 本家那套对地面傀儡够用，但龙是远程炮艇、龙息会被方块整个挡掉 ——
        // "最近的那个"经常隔着墙，它就对着墙喷一整轮。新 goal 的分数里带"射界通畅 +40"，
        // 详见 DragonTargetGoal。
        for (WrappedGoal wrapped : new ArrayList<>(this.targetSelector.getAvailableGoals())) {
            if (wrapped.getGoal() instanceof Golem3DTargetGoal) {
                this.targetSelector.removeGoal(wrapped.getGoal());
            }
        }
        this.targetSelector.addGoal(3, new DragonTargetGoal(this, 5));
    }

    /**
     * 一个 tick：AI、骑乘移动、碰撞全在这里面。
     *
     * <p><b>速度的唯一出口是 {@link DragonFlightController}</b>（见它的类注释）：
     * 本方法末尾那段 {@code if (!level.isClientSide)} 里只做三件事 —— 掷技能骰子、摘本家 goal、
     * 调 {@code flightController.tick(rider)}。以前这里是一大串
     * {@code riderDive / rider / embedded / diveVelocity / rescuing / hover} 的 if-else，
     * 每加一个需求就多一条"谁覆盖谁"的规则、彼此打架（抽搐 / 穿墙 / "想往上飞"都是这么来的）。
     *
     * <p><b>为什么必须写在 {@code super.aiStep()} 之后</b>：{@code Mob.aiStep} 里会调
     * {@code moveControl.tick()} 把竖直速度清零，接着原版骑乘管线（{@code travelRidden}）会用
     * {@code getRiddenInput × getRiddenSpeed} 重算水平与竖直速度 —— 先让它清、我们再覆盖，
     * 下一 tick 的 {@code travel} 才用得上。
     *
     * <p><b>不要改寻路/移动控制的目标高度</b>：寻路判断"有没有走到路径点"用的是三维距离，
     * 把目标 Y 抬高三格会让它永远差三格、卡在第一个路径点上不动（上一版就是这么把龙整不会动的）。
     * 高度只通过"执行层夹进 flightBand"来约束。
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
        // 自由行动的原点：模式一变就清（见 tickFreeWanderOrigin 的说明）。
        // 同样必须在 goal 取用之前 —— DragonIdleGoal 会问 freeWanderOrigin()。
        this.tickFreeWanderOrigin();
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
            // 冲锋（骑手指令 或 AI 自发）期间把背上的傀儡逼进近战模式 —— 撞过去那一下
            // 必须拿近战武器才有意义，见 forceGolemPassengersMelee() 的说明。
            boolean riderDive = rider != null && this.isRiderOrderedDive() && this.diveVelocity != null;
            if (riderDive || this.getDivePhase() != DIVE_PHASE_NONE) {
                this.forceGolemPassengersMelee();
            }
            // ★ 本 tick 的速度全部由飞行执行层决定（唯一出口）：
            //   骑手冲锋 > 骑手驾驶 > 重合脱出 > 技能航线 > 自动脱困 > 悬停，
            //   并统一在 flightBand() 的高度带内夹取竖直分量。
            //   以前这一大段 if/else 直接写在这里，每加一个需求就多一条"谁覆盖谁"的规则。
            //
            //   A（aiming 同步）：goal 在 super.aiStep() 里用 setKeepAim(true) 表达"这一 tick 要瞄准"，
            //   先把这件事同步给客户端（渲染层据此把机身锁到瞄准方向），再交给执行层消费。
            this.entityData.set(DATA_AIMING, this.keepAimThisTick);
            this.flightController.tick(rider);
            // ★ 压弯（侧倾）统一在这里算：只看"本 tick 实际转过的角度"，所以骑手用鼠标转、
            //   AI 巡航转（FlyingMoveControl 拧的机头）、俯冲/冲锋的航线转，三种全都吃得到。
            //   早先压弯只在 faceMovement() 里按"它自己转了多少"算，而 AI 巡航的转向走
            //   FlyingMoveControl、骑手的转向走 DragonRiderControl —— 两边都不经过那里，
            //   真机表现就是"平时转弯完全不压弯，只有俯冲/冲锋才压"。
            //   原先那个 relaxRoll() 也随之取消：yaw 不再变化时低通自己会把它 lerp 回 0。
            if (this.bodyRollDirectThisTick) {
                this.bodyRollDirectThisTick = false;
                // 让位给 goal 指定的角度，但参照点要跟上，别把这一段的转角攒到下一 tick
                this.prevRollYRot = this.getYRot();
            } else {
                this.tickBodyRoll();
            }
        }
        // 挨打远离的计时（执行层用它决定是否进入 RETREAT 模式）
        this.tickRetreat();
        // 战斗记忆：有目标 / 刚挨打 → 刷新；否则逐 tick 衰减。
        // 飞行辅助用它决定"战斗中能不能进入幽灵（穿墙）状态"（见 DragonGolemConfig.ghostDuringCombat）。
        if (this.getTarget() != null || this.hurtTime > 0) {
            this.markCombat();
        } else if (this.combatTicks > 0) {
            this.combatTicks--;
        }
        // 飞行辅助：撞墙计数、自动脱困的进出、noPhysics 的唯一写入口。
        // 必须在上面那段之后 —— 那里才刚决定本 tick 的速度。
        this.flightAssist.tick(this);
        // 诊断：移动/寻路全貌（{@code debug.flightLog}，默认关）
        this.logMovement();
        // 末地水晶治疗：每 30 秒一轮、每轮最多 12 个可用水晶，详见 DragonCrystalHeal 的说明。
        // 放在这里（服务端 tick 收尾）而不是 goal 里：它是"被动光环"，不该和 AI 决策抢调度。
        if (!this.level().isClientSide) {
            EndCrystal usedCrystal = this.crystalHeal.tick(this);
            if (usedCrystal != null) {
                this.setHealth(Math.min(this.getMaxHealth(), this.getHealth() + CRYSTAL_HEAL_AMOUNT));
                this.crystalHealEffects(usedCrystal);
            } else {
                // 没在回血这一 tick 也要有"生效中"的常驻光效（见 crystalHealAura 的说明）
                this.crystalHealAura();
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

    /**
     * 诊断：一行打出"目标点 → 航路点 → 竖直目标 → 速度/碰撞"的完整链条（{@code debug.flightLog}）。
     *
     * <p>为什么这样组织：龙的移动是三层 —— ① 某个 goal 给一个水平目标点；②
     * {@link DragonPathFollower} 用它算 3D 路线并给出"下一个航路点"；③ 水平/竖直速度由航路点
     * 或悬停高度决定。从外面看只能看到"它在乱飞"，这一行把三层的中间量全部摊开，
     * 尤其 {@code vert} 后面标了竖直目标的<b>来源</b>（"路线"还是"悬停"）。
     *
     * <p>另外单独抓"正在上升但头顶被挡"：那正是"一直想往上飞"的现场，WARN 级 + 1 秒限流，
     * 免得被常规 {@code [move]} 行淹没。
     */
    private void logMovement() {
        if (!DragonGolemConfig.flightLog() || this.level().isClientSide()) {
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        // ---- 报警：想往上飞但头顶顶着方块 ----
        if (velocity.y > 0.02D && this.verticalCollision && this.tickCount - this.lastRiseLogTick >= 20) {
            this.lastRiseLogTick = this.tickCount;
            Dragon_golems.LOGGER.warn(
                    "[move] 正在上升但头顶被挡：vy={} vertTarget={} hoverTarget={} steerY={} onRoute={} "
                            + "floor={} ceilBlock={} maxFeet={} pos={} goals={}",
                    fmt3(velocity.y), fmt2(this.flightVerticalTarget()), fmt2(this.hoverTargetY()),
                    this.steerYForLog(), this.pathFollower.onRoute(),
                    fmt2(this.localFloorY()), this.ceilBlockForLog(), this.maxFeetForLog(),
                    posStr(), runningGoals());
        }
        if (this.tickCount % DragonGolemConfig.flightLogInterval() != 0) {
            return;
        }
        Vec3 steer = this.pathFollower.steerPoint();
        double hover = this.hoverTargetY();
        boolean fromRoute = this.pathFollower.onRoute() && steer != null
                && Math.abs(steer.y - hover) > DragonGolemConfig.pathVerticalDeadband();
        Dragon_golems.LOGGER.info(
                "[move] #{} goal={} pos={} | steer={} onRoute={} | vert: target={} 来源={} hover={} "
                        + "floor={} ceilBlock={} maxFeet={} | vel=({}, {}, {}) | hColl={} vColl={} "
                        + "noPhys={} embed={} goals={}",
                this.tickCount, vecStr(this.pathFollower.goalPoint()), posStr(),
                vecStr(steer), this.pathFollower.onRoute(),
                fmt2(this.flightVerticalTarget()), fromRoute ? "路线" : "悬停", fmt2(hover),
                fmt2(this.localFloorY()), this.ceilBlockForLog(), this.maxFeetForLog(),
                fmt3(velocity.x), fmt3(velocity.y), fmt3(velocity.z),
                this.horizontalCollision, this.verticalCollision,
                this.noPhysics, this.flightAssist.isEmbedded(), runningGoals());
    }

    /** 诊断用：当前航路点的 Y（没有路线 → "na"）。 */
    private String steerYForLog() {
        Vec3 steer = this.pathFollower.steerPoint();
        return steer == null ? "na" : fmt2(steer.y);
    }

    /** 诊断用：头顶天花板方块 Y（没有 → "∞"）。 */
    private String ceilBlockForLog() {
        double ceil = this.ceilingBlockYAt(this.blockPosition().getX(), this.blockPosition().getZ(),
                this.ceilingScanStart());
        return ceil == NO_CEILING ? "∞" : fmt2(ceil);
    }

    /** 诊断用：天花板允许的最高脚部 Y（没有 → "∞"）。 */
    private String maxFeetForLog() {
        double limit = this.maxFeetYUnderCeiling(this.blockPosition().getX(), this.blockPosition().getZ(),
                this.ceilingScanStart());
        return limit == NO_CEILING ? "∞" : fmt2(limit);
    }

    /** 诊断用：当前正在跑的 goal 名字。 */
    private String runningGoals() {
        StringBuilder sb = new StringBuilder();
        for (WrappedGoal wrapped : this.goalSelector.getAvailableGoals()) {
            if (!wrapped.isRunning()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(wrapped.getGoal().getClass().getSimpleName());
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private String posStr() {
        return "(" + fmt2(this.getX()) + ", " + fmt2(this.getY()) + ", " + fmt2(this.getZ()) + ")";
    }

    private static String vecStr(@Nullable Vec3 v) {
        return v == null ? "na" : "(" + fmt2(v.x) + ", " + fmt2(v.y) + ", " + fmt2(v.z) + ")";
    }

    private static String fmt2(double v) {
        return String.format("%.2f", v);
    }

    private static String fmt3(double v) {
        return String.format("%.3f", v);
    }

    /** "上升但头顶被挡"告警的限流。 */
    private int lastRiseLogTick = -100;

    /**
     * 压弯（侧倾）：<b>只看本 tick 实际转过的角度</b>，谁转的都算。
     *
     * <p>这样 AI 巡航（{@code FlyingMoveControl} 把机头拧向运动方向）、骑手用鼠标转、
     * 俯冲/冲锋的航线接管，三种情况都会压弯 —— 早先只在 {@link #faceMovement(Vec3)} 里按
     * "它自己转了多少"算，于是只有后两种有压弯，平时巡航看着像"贴地平移"。
     *
     * <p>转角先低通再乘系数：幽匿那种 2~3 格/tick 的速度下瞬时转角经常顶到上限（±18°），
     * 直接用会让机身一直顶在极限角度、来回甩。不再转向时低通自己 lerp 回 0，
     * 所以不需要单独一个"收平"函数（老那版还漏了"骑手在场"的分支，会把压弯角永久留下）。
     *
     * <p>超过物理转速上限的转角（瞬移、强制对齐朝向）不算压弯，免得机身猛地横过来。
     */
    private void tickBodyRoll() {
        float yawDelta = Mth.wrapDegrees(this.getYRot() - this.prevRollYRot);
        this.prevRollYRot = this.getYRot();
        if (Math.abs(yawDelta) > MAX_TURN_HARD_CAP) {
            yawDelta = 0.0F;
        }
        this.smoothedTurn = Mth.lerp(ROLL_TURN_LERP, this.smoothedTurn, yawDelta);
        if (Math.abs(this.smoothedTurn) < ROLL_DEAD_ZONE) {
            this.smoothedTurn = 0.0F;
        }
        this.entityData.set(DATA_BODY_ROLL,
                Mth.clamp(this.smoothedTurn * DragonGolemConfig.rollPerDegree(),
                        -DragonGolemConfig.maxRoll(), DragonGolemConfig.maxRoll()));
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
        boolean los = this.hasClearShotTo(target);
        boolean diveOk = this.diveCooldown <= 0 && hDist >= this.diveMinH() && hDist <= this.diveMaxH()
                // ★ 洞窟模式不投票给俯冲：那里没有大弧度机动的空间（目标也只会在窄处），
                //   把票让给龙息/龙弹/音爆 —— 它们可以原地释放。开阔地照旧。
                && this.profile() == DragonProfile.ROAMING
                && this.diveRoomOk(target);
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
     * 有没有一轮技能"已经开打"（{@code onSkillStarted} 之后、{@code onSkillFinished} 之前）。
     *
     * <p>给 {@link DragonFollowTeleportGoal} 用：那一轮攻击正在占用它，此时回位等于把攻击打断。
     * 泰坦体型的真机症状就是"一直执行俯冲但完不成、总被传送拽回来"。
     */
    public boolean isSkillInProgress() {
        return this.skillInProgress;
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

    // ---- 空间预算（平地 / 山洞的行为分叉）----

    /**
     * <b>空间预算</b>：龙此刻周围"够不够它展开动作"。
     *
     * <h2>为什么要它（开发以来所有难点的共同原因）</h2>
     * 龙傀儡本体太大：1 倍就有 26 格长、2.6 格宽，绕圈半径 {@link #orbitRadius()} 又是 20+ 格。
     * 在山洞/工厂里，"边飞边绕圈扫射"这种动作<b>物理上就没有空间</b> —— 继续做只会贴着墙抽搐。
     * 与其在每个动作里加否决条件，不如先量一下空间，再<b>按空间选动作</b>：
     * <ul>
     *   <li>{@code roomy == true}（平地/开阔）：允许绕圈扫射、大半径机动（观感优先）；</li>
     *   <li>{@code roomy == false}（洞窟/室内）：<b>原地悬停扫射</b>，不做绕圈（不旋转、不贴墙）。</li>
     * </ul>
     *
     * <p>每 {@link #SPACE_CACHE_TICKS} tick 量一次，代价可控（8 方向 × 最多 12 次包围盒探测）。
     */
    public record SpaceBudget(double headroom, double clearance, boolean roomy) {
    }

    /** 量一次空间预算（缓存 {@link #SPACE_CACHE_TICKS} tick）。 */
    public SpaceBudget spaceBudget() {
        if (this.spaceCache != null && --this.spaceTicks > 0) {
            return this.spaceCache;
        }
        this.spaceTicks = SPACE_CACHE_TICKS;
        double headroom;
        double ceiling = this.ceilingBlockYAt(this.blockPosition().getX(), this.blockPosition().getZ(),
                this.ceilingScanStart());
        if (ceiling == NO_CEILING) {
            headroom = SPACE_UNBOUNDED;
        } else {
            headroom = ceiling - this.getY() - this.getBbHeight();
        }
        double clearance = this.probeClearance();
        double need = this.orbitRadius();
        boolean roomy = headroom >= 2.0D && clearance >= need * SPACE_ROOMY_FACTOR;
        this.spaceCache = new SpaceBudget(headroom, clearance, roomy);
        return this.spaceCache;
    }

    /**
     * 水平净空：8 个方向各探"整条龙塞得下"的最远距离（步长 2 格、上限 24 格），取<b>中位数</b>。
     *
     * <p>用中位数而不是最小值：贴着墙的一侧必然是 0，最小值会让所有场地都判成"挤"；
     * 中位数描述的是"整体有没有活动余地"。
     */
    private double probeClearance() {
        double[] free = new double[8];
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0D * i / 8.0D;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double reach = 0.0D;
            for (double d = 2.0D; d <= SPACE_PROBE_MAX; d += 2.0D) {
                if (!this.fitsAt(this.getX() + dx * d, this.getY(), this.getZ() + dz * d)) {
                    break;
                }
                reach = d;
            }
            free[i] = reach;
        }
        java.util.Arrays.sort(free);
        return (free[3] + free[4]) * 0.5D;
    }

    /** 把整条龙放到这个位置塞不塞得下（不移动实体，只做包围盒检测）。 */
    public boolean fitsAt(double x, double y, double z) {
        AABB box = this.getBoundingBox().move(x - this.getX(), y - this.getY(), z - this.getZ());
        return this.level().noCollision(this, box);
    }

    /** 空间预算缓存时长（tick）。 */
    private static final int SPACE_CACHE_TICKS = 10;
    /** 净空探测的最远距离（格）。 */
    private static final double SPACE_PROBE_MAX = 24.0D;
    /** 净空要 ≥ 绕圈半径 × 这个系数才算"开阔"。 */
    private static final double SPACE_ROOMY_FACTOR = 1.5D;
    /** 无天花板时的 headroom 取值。 */
    private static final double SPACE_UNBOUNDED = 999.0D;
    private SpaceBudget spaceCache;
    private int spaceTicks;

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
     * <p>速度低于"基础转向能维持的半径"时保持 {@code flight.turnRateBase}（慢速手感不变）；
     * 超过之后按 {@code ω = v / flight.turnRadius} 一起涨，转弯半径就钉在那个值附近 ——
     * 这样不管飞多快，模型都能真的朝着自己在飞的方向，而不是横着飘。
     *
     * @param horizontalSpeed 这一 tick 的水平速度（格/tick）
     */
    public float turnRateFor(double horizontalSpeed) {
        float byRadius = (float) Math.toDegrees(Math.max(0.0D, horizontalSpeed) / DragonGolemConfig.turnRadius());
        float base = Math.min(DragonGolemConfig.turnRateBase(), MAX_TURN_HARD_CAP);
        float rate = Math.max(base, byRadius);
        // 体型惩罚（默认 0 = 不惩罚）：大体型转起来更"重"，免得泰坦像雨刷一样原地甩
        double penalty = DragonGolemConfig.turnSizePenalty();
        if (penalty > 0.0D) {
            rate /= (float) (1.0D + penalty * Math.max(0.0D, this.bodyScale() - 1.0D));
        }
        return Mth.clamp(rate, 1.0F, MAX_TURN_HARD_CAP);
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
    /** 朝向 = 运动方向（执行层调用；同包可见）。 */
    void faceMovement(Vec3 velocity) {
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
        // 侧倾不在这里算 —— 统一由 tickBodyRoll() 按"本 tick 实际转过的角度"处理，
        // 这样 AI 巡航（FlyingMoveControl 拧的机头）与骑手鼠标转的也一起有压弯。
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
        // 属性刚算完：把"被原版静默夹掉"的那些打出来（材料/升级白铸是看不出来的，见 logAttributeOverflow）
        this.logAttributeOverflow();
        if (DragonDebug.RENDER) {
            // 排查第三方视觉升级（mgdp 的倒立 / 前后翻转 / 大风车 / 后空翻）为什么在龙身上没效果：
            // 它们的判据是"这张表里有没有那条 modifier"，所以两端各打一行来对比。
            StringBuilder sb = new StringBuilder();
            this.getModifiers().forEach((mod, lv) -> {
                ResourceLocation id = GolemTypes.MODIFIERS.get().getKey(mod);
                sb.append(id == null ? mod.getDescriptionId() : id.toString()).append('x').append(lv).append(' ');
            });
            Dragon_golems.LOGGER.info("[render] {}端 modifier 表（{} 条）：{}",
                    this.level().isClientSide() ? "客户" : "服务", this.getModifiers().size(), sb.toString().trim());
        }
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
        // 技能升级的"goal 类挂载点"：本家/compat 那批远程攻击 goal 默认整批摘掉（理由见 isForeignRangedGoal），
        // 打开 DragonGolemConfig.nativeSkillGoals() 就原样放回来 —— 靠 goal 出手的技能升级因此能生效。
        boolean keepSkillGoals = DragonGolemConfig.nativeSkillGoals();
        for (WrappedGoal wrapped : new ArrayList<>(this.goalSelector.getAvailableGoals())) {
            Goal goal = wrapped.getGoal();
            if (goal instanceof GolemMeleeGoal
                    || goal instanceof TeleportToOwnerGoal
                    // 这两个会把 MOVE 通道占死，或者对悬停的龙没有意义，交给 DragonIdleGoal
                    || goal instanceof FollowOwnerGoal
                    || goal instanceof GolemRandomStrollGoal
                    || (!keepSkillGoals && isForeignRangedGoal(goal))) {
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
    // 龙息 / 龙弹的伤害类型不再写死在这里：走数据包（content/config/DragonBodyEntry 的
    // breathDamageType）。内置默认 = dragon_golems:dragon_breath，幽匿身体那条 = dragon_golems:dragon_sonic。
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
     * <b>嘴（头部子碰撞箱的喷口）到目标碰撞箱中心的距离</b> —— 两个远程攻击的"真实射击距离"。
     *
     * <h2>为什么必须用它，而不是身体中心距离</h2>
     * 龙息和龙息弹都是<b>从头部喷口出发</b>的射线/锥体，而喷口在身体中心前方
     * {@link #mouthForward()} 格（1 倍约 9 格，泰坦 4 倍能到 30+ 格）。
     * 用身体中心距离判"能不能打"会出这种事：
     * <pre>
     * 身体中心距离 14.7 格（看着"很近"），但嘴在身体前方 30 格
     * → 嘴已经在目标<b>背后</b> → 锥体的 along 全为负 → 一个目标都打不到
     * </pre>
     * 真机日志就是这样：{@code [breath] miss=3 ovr=true len=14.7 dvx=0 dvz=0}（站桩喷、零伤害）。
     * 所以射程闸门必须用喷口距离，并且要求一个最小间隙（嘴不能插进目标里）。
     */
    public double mouthDistanceTo(Entity target) {
        return this.mouthPosition().distanceTo(target.getBoundingBox().getCenter());
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
     * <p><b>伤害类型由数据包决定</b>：{@code dragon_bodies} 表里的 {@code breathDamageType}
     * （内置：其它材料 = {@code dragon_golems:dragon_breath}，幽匿身体 = {@code dragon_golems:dragon_sonic}，
     * 定义见 {@code data/dragon_golems/damage_type/}）。前者吃护甲 / 抗性 / 附魔，和原版 {@code mob_attack} 同一档；
     * 后者额外躺在 {@code bypasses_armor} / {@code bypasses_enchantments} 里，穿护甲与附魔，
     * 但抗性药水仍然挡得住，和原版 {@code sonic_boom}、L2 音爆枪同一档。
     *
     * <p><b>为什么不用原版那两条：</b>原版的 {@code minecraft:bypasses_cooldown} 标签是<b>空的</b>
     * （连 {@code sonic_boom} 自己都不免无敌帧），而这条通道每 10 tick 就打一次 ——
     * 只要把目标顶进无敌帧，旁边的地面傀儡一拳上去就会被按差值削掉一截、甚至整个被吞。
     * 我们自己那两条伤害类型都塞进了 {@code bypasses_cooldown}，再配 {@link #hurtWithoutFrames}
     * 把目标原本的无敌帧原样放回去，于是"龙的持续输出"和"地面傀儡的单发重击"互不干扰。
     * <b>整合包换成自己的伤害类型时，要照这样也进 {@code bypasses_cooldown}</b>，
     * 否则龙息会把目标顶在无敌帧里、把旁边地面傀儡的伤害吞掉。
     *
     * <p><b>不要改用 MG 的 {@code echo_attack}：</b>那条连抗性、药水效果、无敌帧都穿，
     * 放在"每 10 tick 跳一次"的常态群攻上会明显超模。{@link DragonSkill#SONIC} 那个大招才用它
     * （15 秒一发，一次性爆发，真伤是它的卖点）。
     *
     * <p>不带横扫 —— 龙息本来就是靠锥体覆盖多个目标的。
     *
     * <p><b>同一次龙息里对同一个目标递减</b>（见 {@link #beginBreathVolley()}）：龙息是唯一不吃冷却的
     * 常态攻击，一轮 60 tick 打 6 跳，六跳都按满伤害算的话单体总量约 2.1 倍攻击力，比 30 秒一次的
     * 俯冲还高。递减之后总量回到 1 倍攻击力出头，"覆盖一片敌人"这个定位不变（每个目标各自记次数）。
     * 开关与步长见 {@code DragonGolemConfig.breathDiminishing*}。
     */
    public boolean breathDamage(LivingEntity target, float damageMult, double knockback) {
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE)
                * damageMult * this.breathDiminishing(target);
        boolean hurt = this.dealSkillDamage(target, this.breathSource(this.bodyEntry().damageType()), damage, knockback);
        if (hurt && this.breathVolley) {
            this.breathHits.merge(target.getId(), 1, Integer::sum);
        }
        return hurt;
    }

    // ---- 技能伤害的统一挂载点（技能升级靠它生效）----

    /**
     * <b>龙身上所有"技能伤害"的唯一出口 —— 技能升级要生效就必须走这里。</b>
     *
     * <p>傀儡装配<b>没有</b> {@code onAttack} / {@code onHit} 这类"打人时叫我一下"的回调
     * （整个 {@code GolemModifier} 里一个函数式参数都没有），它所有的攻击类升级都是
     * <b>靠真实伤害事件分发</b>的：
     * <pre>
     * target.hurt(带 golem 当攻击者的 DamageSource, 伤害)
     *   ├─ LivingAttackEvent → modifier.onAttackTarget / onKillTarget（本家 ModifierEventListeners）
     *   ├─ LivingHurtEvent   → modifier.onHurtTarget（药水效果 / 目标加成 / 各种命中特效）
     *   └─ l2damagetracker 的 AttackCache 链 → modifySource（穿甲）/ modifyDamage / finalizeHurtTarget（吸血）
     * </pre>
     * 所以"给龙补一个普通攻击"是错的方向（龙本来就没有近战，也没有 mob_weapon_api 那套"使用"AI），
     * <b>正确做法是让龙的技能伤害走真实伤害源</b>：攻击者 = 这条龙，来源由 {@code level.damageSources()} 造。
     *
     * <p><b>两个坑（都已踩过）：</b>
     * <ol>
     *   <li>{@code new DamageSource(...)} 直接 new 出来的源<b>不会触发 l2damagetracker 的
     *       {@code CreateSourceEvent}</b>（那个事件挂在 {@code DamageSources.source(...)} 上），
     *       于是"穿甲 / 破魔"这类改<b>伤害源</b>的升级对龙息完全无效 —— 这是"技能升级没效果"的主因之一。</li>
     *   <li>本家 {@code onAttackTarget} 前面还有一道 {@code golem.canAttack(目标)} 闸门：
     *       带 {@code PASSIVE}（坐骑升级）的龙平时连这个事件都进不去 —— 那是设计本意（被动），
     *       玩家按键时由 {@link #riderCombat} 临时放行。</li>
     * </ol>
     *
     * <p>这里统一走 {@link #hurtWithoutFrames}：把目标原本的无敌帧原样放回去，
     * 免得"每 10 tick 一跳"的龙息替别人占住无敌帧。想给龙加新技能（或者第三方想挂自己的招式）
     * 就直接调这个方法，其余那套升级管线会自动接上。
     *
     * @param source    伤害源；传 {@code null} 就退化成 {@code mobAttack(龙)}。想细分伤害类型就自己造
     * @param knockback 命中后的击退力度，{@code <= 0} 表示不推
     * @return 这一下有没有真的打进去（被免伤 / 无敌挡掉返回 false）
     */
    public boolean dealSkillDamage(LivingEntity target, @Nullable DamageSource source, float damage, double knockback) {
        DamageSource src = source == null ? this.damageSources().mobAttack(this) : source;
        // 先把 mob 归属记上（原版 hurt 内部也会记，这里对"绕过 hurt 的伤害类型"也保证生效）
        target.setLastHurtByMob(this);
        boolean hurt = this.hurtWithoutFrames(target, src, damage);
        if (hurt && knockback > 0.0D) {
            target.knockback(knockback, this.getX() - target.getX(), this.getZ() - target.getZ());
        }
        return hurt;
    }

    /** 一轮龙息里"每个目标已经挨了几跳"（目标 id → 次数），只在 {@link #breathVolley} 为真时有意义。 */
    private final HashMap<Integer, Integer> breathHits = new HashMap<>();
    /** 这一轮龙息是否在进行中（喷息开始前置真、goal 收工时置假）。 */
    private boolean breathVolley;

    /**
     * 开一轮龙息：清空"每个目标挨了几跳"的计数。
     *
     * <p>由两个喷息 goal 在<b>进入前摇</b>时调用（{@code DragonRangedGoal} 的 BREATH_CHARGE、
     * {@code DragonRiderSkillGoal.start()}），{@link #endBreathVolley()} 在它们的 {@code stop()} 里调。
     * 计数只在一轮之内有效，所以上一轮剩下的计数不会漏到下一轮。
     */
    public void beginBreathVolley() {
        this.breathHits.clear();
        this.breathVolley = true;
    }

    /** 收一轮龙息（和 {@link #beginBreathVolley()} 配对）。 */
    public void endBreathVolley() {
        this.breathVolley = false;
        // 这一轮完全没打中任何人 → 记一次"空轮"。
        // 连续空轮说明当前的距离/站位几何打不到（真机："旋转尝试攻击时重复打不到，
        // 来回调位置又调回原位，死循环"），下一轮要换几何而不是原地重来。
        if (this.breathHits.isEmpty()) {
            this.breathMissStreak++;
        } else {
            this.breathMissStreak = 0;
        }
        this.breathHits.clear();
    }

    /** 连续多少轮龙息完全没打中（0 = 上一轮有命中）。 */
    public int breathMissStreak() {
        return this.breathMissStreak;
    }

    /** 连续空轮计数（见 {@link #endBreathVolley()}）。 */
    private int breathMissStreak;

    /** 这一轮里同一个目标已经挨了几跳（0 = 还没挨过，下一跳按满伤害）。 */
    public int breathHitsOn(LivingEntity target) {
        return this.breathVolley ? this.breathHits.getOrDefault(target.getId(), 0) : 0;
    }

    /** 这一跳的递减倍率：{@code max(下限, 1 - 步长 × 已命中次数)}；不在某一轮里 / 配置关掉时恒为 1。 */
    private float breathDiminishing(LivingEntity target) {
        if (!this.breathVolley || !DragonGolemConfig.breathDiminishing()) {
            return 1.0F;
        }
        int hits = this.breathHitsOn(target);
        if (hits <= 0) {
            return 1.0F;
        }
        double step = DragonGolemConfig.breathDiminishingStep();
        double min = DragonGolemConfig.breathDiminishingMin();
        return (float) Math.max(min, 1.0D - step * hits);
    }

    /**
     * 按 id 从动态注册表里取我们自己的伤害类型；数据包被改坏时退回原版，免得整条通道哑火。
     *
     * <p><b>关键：造源之前要先过一遍 l2damagetracker 的 {@code CreateSourceEvent}。</b>
     * 本家所有"改<b>伤害源</b>"的升级（{@code armor_penetration} 穿甲、破魔、各种
     * {@code modifySource}）都挂在这个事件上，而它平时是由 {@code DamageSources.source(...)}
     * 那个<b>私有</b>重载触发的（l2damagetracker 自己 mixin 进去）——
     * 我们直接 {@code new DamageSource(...)} 就把它整条绕过去了，表现就是"装了穿甲升级、
     * 龙息照样被护甲挡"。所以这里手动构造同一个事件、手动调
     * {@code AttackEventHandler.onDamageSourceCreate}（它的返回值就是被监听器改造过的源）。
     *
     * <p>返回 null 有两种情况：没有监听器要改（照原样造源即可），或者这是客户端
     * （那个方法开头就 {@code if (level.isClientSide()) return null;} —— 客户端伤害只是表现，
     * 不用管）。
     */
    private DamageSource breathSource(ResourceLocation type) {
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, type);
        try {
            Registry<DamageType> registry = this.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
            if (!this.level().isClientSide()) {
                DamageSource created = AttackEventHandler.onDamageSourceCreate(
                        new CreateSourceEvent(registry, key, this, this));
                if (created != null) {
                    return created;
                }
            }
            return new DamageSource(registry.getHolderOrThrow(key), this);
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

    /**
     * 俯冲撞击时触发"<b>只有近战才会触发</b>"的那一族升级（本家 {@code EarthquakeHelper}：地震 / 跳劈）。
     *
     * <h2>为什么需要单独接这一条</h2>
     * 傀儡装配的攻击类升级其实分三族，只有中间那一族和"怎么打出去"有关：
     * <ol>
     *   <li><b>伤害事件族</b>（药水效果 / 目标加成 / 穿甲 / 吸血 / 击杀特效…）—— 与攻击方式无关，
     *       任何一次真实伤害都会触发，走 {@link #dealSkillDamage} 就够了；</li>
     *   <li><b>纯近战族</b>（{@code EarthquakeHelper} 的地震 / 跳劈，来自巨兽、猫灾、Mowzie 等材料）——
     *       本家只从 {@code GolemMeleeGoal} 的跳劈里问 {@code EarthquakeHelper.findInstance}，
     *       <b>龙没有近战攻击，所以这一族在龙身上原本完全是死的</b>（这就是"技能升级没效果"里
     *       最典型的一类）。这里把<b>俯冲撞击</b>当成龙的近战：撞上去那一刻按本家同一套冷却判定触发；</li>
     *   <li><b>goal 族</b>（炽焰喷射 / 死光 / 音波炮…）—— 各自生成自己的投射物，归
     *       {@code DragonGolemConfig.nativeSkillGoals()} 那个开关管，不是这里的事。</li>
     * </ol>
     *
     * <p><b>本家自己的闸门照旧遵守</b>：{@code findInstance} 在"带着乘客"时返回 null
     * （本家不想让驮着人的傀儡震地），被动（装坐骑升级）的龙不算 —— 这两条都不是我们加的。
     * 第一枪用 {@code findInstance}（带目标与射程判定，和本家近战完全一致），
     * 打不着（大体型的接触盒可能比 modifier 自己的射程更远）再退一步用
     * {@code findMountInstance}（"载具式撞击"那条路，狗骑乘时用的就是它），
     * 两者都尊重各自 modifier 的冷却。
     *
     * @param target 这一下撞到的目标（触发一次即可，调用方负责"一轮只调一次"）
     * @return 这一下有没有真的放出地震 / 跳劈
     */
    public boolean triggerMeleeUpgradesOnDive(LivingEntity target) {
        if (this.level().isClientSide() || !DragonGolemConfig.diveTriggersMelee() || target == null) {
            return false;
        }
        EarthquakeHelper.Instance instance = EarthquakeHelper.findInstance(this, target, this.distanceToSqr(target));
        if (instance == null && this.getPassengers().isEmpty()) {
            instance = EarthquakeHelper.findMountInstance(this);
        }
        if (instance == null) {
            return false;
        }
        instance.modifier().performEarthQuake(this, instance.lv());
        instance.addCD();
        return true;
    }

    /**
     * 龙弹的直击 / 溅射：伤害类型 {@code mob_projectile}，攻击者是龙
     * （所以药水升级、穿甲、吸血那一整套照样生效），也顺手把目标的无敌帧原样放回去。
     *
     * <p>击退方向按"弹丸 → 目标"算（不是龙 → 目标），这样擦边命中的推开方向看起来才对。
     */
    public boolean rocketDamage(LivingEntity target, net.minecraft.world.entity.projectile.Projectile ball,
                                float damageMult, double knockback) {
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * damageMult;
        boolean hurt = this.dealSkillDamage(target, this.damageSources().mobProjectile(ball, this), damage, 0.0D);
        if (hurt && knockback > 0.0D) {
            target.knockback(knockback, ball.getX() - target.getX(), ball.getZ() - target.getZ());
        }
        return hurt;
    }

    /**
     * 龙息用的粒子。
     *
     * <p><b>由数据包决定</b>：{@code dragon_bodies} 表里那条的 {@code breathParticle}
     * （内置：幽匿身体 → {@code minecraft:sonic_boom}、下界合金身体 → {@code minecraft:soul_fire_flame}、
     * 其它 → {@code minecraft:flame}）。整合包可以写任意粒子，包括带参数的写法
     * （如 {@code minecraft:dust{color:[1,0,0],scale:1.5}}）。
     *
     * <p>想整体换风格只改数据包，不用碰代码：龙息前摇、喷流、落点溅射、枪口火苗、以及音爆的前摇
     * 全都读它。
     */
    public ParticleOptions breathParticle() {
        return this.bodyEntry().breathParticle();
    }

    /**
     * 龙弹落点那团"残留云"用的粒子（{@code cloudParticle}）。
     *
     * <p>原来是硬编码的原版 {@code DRAGON_BREATH}（那种紫色），所有材料的龙都吐同一朵紫云 ——
     * 下界龙吐紫云比龙息还违和。现在和 {@link #breathParticle()} 同一套数据包映射，
     * 只是云不适合用"一次性"的音波环，内置的幽匿那档换成飘散的 {@code minecraft:sculk_soul}。
     */
    public ParticleOptions breathCloudParticle() {
        return this.bodyEntry().cloudParticle();
    }

    /**
     * 这条龙该用哪一份"身体主题"配置（数据包 {@code modulargolems_config/dragon_bodies}）。
     *
     * <p><b>匹配顺序</b>：
     * <ol>
     *   <li><b>身体部件</b>那份材料的条目 —— 不看它的 {@code match}，身体是这条龙的核心；</li>
     *   <li>其它部件里写了 {@code "match": "any"} 的条目（按材料表顺序，先命中先用）；</li>
     *   <li>{@code dragon_golems:default} 那条（数据包可覆盖它来统一改"其它材料"的表现）；</li>
     *   <li>都没有 → {@link DragonBodyEntry#FALLBACK}，正好等于改造前"其它材料"的老行为。</li>
     * </ol>
     *
     * <p>材料列表两端同步（贴图也靠它取），所以客户端问这个问题同样有效。
     */
    public DragonBodyEntry bodyEntry() {
        DragonBodyConfig cfg = DragonBodyConfig.get();
        if (cfg == null) {
            return DragonBodyEntry.FALLBACK;
        }
        // 1) 身体部件说了算
        for (GolemMaterial mat : this.getMaterials()) {
            if (mat.part() == DragonGolemItems.BODY.get()) {
                DragonBodyEntry e = cfg.entry(mat.id());
                if (e != null) {
                    return e;
                }
                break;
            }
        }
        // 2) 其它部件里声明了 match=any 的
        for (GolemMaterial mat : this.getMaterials()) {
            DragonBodyEntry e = cfg.entry(mat.id());
            if (e != null && e.matchesAnyPart()) {
                return e;
            }
        }
        // 3) default → 4) Java 侧兜底
        return cfg.fallback();
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
     *   <li>{@link DragonSkill#SONIC} 那个大招 —— <b>只有幽匿身体的龙才有</b>；
     *       其它材料的龙回到"俯冲 / 龙息 / 龙弹"三招；</li>
     *   <li>身体是幽匿时补挂本家音波 modifier（见 {@link #updateAttributes}）。</li>
     * </ul>
     * 材料列表是两端同步的（贴图也靠它取），所以客户端问这个问题同样有效。
     *
     * <p><b>注意：粒子和伤害类型已经搬去数据包了</b>（见 {@link #bodyEntry()}），
     * 这个方法现在只管"技能槽位"这一档，别再往它身上挂表现层的判断。
     */
    public boolean isSonicBody() {
        return this.bodyHasMaterial(SCULK_MATERIAL);
    }

    /**
     * 身体部件是不是下界合金。
     *
     * <p>表现层（灵魂火粒子、着火 5 秒、凋零）现在由数据包的 {@code modulargolems:netherite}
     * 那条决定；这里保留方法只为"以后要按身体材料开技能槽"时有个现成的判断。
     */
    public boolean isNetherBody() {
        return this.bodyHasMaterial(NETHERITE_MATERIAL);
    }

    /**
     * 龙息 / 龙弹带来的负面效果（<b>自带的那一层</b>，和傀儡装配的药水效果升级互不影响：
     * 那些是靠 {@code LivingHurtEvent} 自动附加的，见 {@code PotionAttackModifier}）。
     *
     * <p><b>由数据包决定</b>：对应条目的 {@code fireSeconds} 与 {@code effects}
     * （内置：幽匿身体 = 黑暗 + 虚弱 I（各 5 秒）；下界合金身体 = 着火 5 秒 + 凋零 I；
     * 其它 = 着火 3 秒）。整合包写了不存在的效果 id / 时长为 0 的条目会被跳过，
     * 只影响这一条，不会崩。
     */
    public void applyBreathEffects(LivingEntity target) {
        DragonBodyEntry entry = this.bodyEntry();
        if (entry.fireSeconds > 0) {
            target.setSecondsOnFire(entry.fireSeconds);
        }
        if (entry.effects == null) {
            return;
        }
        for (DragonBodyEntry.EffectEntry e : entry.effects) {
            if (e.effect == null || e.duration <= 0) {
                continue;
            }
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(e.effect);
            if (effect == null) {
                continue;
            }
            target.addEffect(new MobEffectInstance(effect, e.duration, e.amplifier), this);
        }
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

    /**
     * 属性基础值超过原版上限时打一行日志（配置项 {@code attributes.logOverflow}，默认开）。
     *
     * <p>原版 {@code RangedAttribute.sanitizeValue} 是<b>静默</b>夹取的：超上限的部分直接丢掉，
     * 不报错、不留痕 —— 表现就是"材料/升级明明装上了，属性面板却顶在一个整数上不动"，
     * 而且本家的属性界面还会用未夹取的 {@code getBaseValue()} 算 tooltip，
     * 于是"面板显示 1024、tooltip 显示 1400"。这一行日志是唯一的提示。
     *
     * <p>实例：幽匿身体的龙裸装就有 1000 血（上限 1024），再挂一级体型升级或坐骑升级就会被夹走
     * 200~576 血。默认由 {@code attributes.maxHealthCapFix}（内置版的 AttributeFix，
     * 只放宽最大生命）把上限抬开，所以正常情况下这一行不该出现；
     * 关掉那个开关、或者反射没能生效时，这里就是唯一的提示。
     */
    private void logAttributeOverflow() {
        if (!DragonGolemConfig.logAttributeOverflow() || this.level().isClientSide()) {
            return;
        }
        for (Attribute attribute : ForgeRegistries.ATTRIBUTES) {
            AttributeInstance ins = this.getAttribute(attribute);
            if (ins == null || !(attribute instanceof RangedAttribute ranged)) {
                continue;
            }
            double base = ins.getBaseValue();
            double max = ranged.getMaxValue();
            if (base > max) {
                Dragon_golems.LOGGER.info("[attr] {} 的基础值 {} 超过原版上限 {}，实际生效 {}（超出的部分被静默丢掉）",
                        attribute.getDescriptionId(), String.format("%.2f", base),
                        String.format("%.2f", max), String.format("%.2f", ins.getValue()));
            }
        }
    }

    // applyHover() 已经拆成 hoverYSpeed()（见上面"执行层要用的访问器"那一节）：
    // 这里只保留历史说明，避免后来的人再在实体里写 deltaMovement。

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
        // ★ 有傀儡乘客时<b>不再</b>降到待机高度。
        //   第一版写的是 `isParked() || hasGolemPassenger()` → 待机高度 3 格，
        //   理由当时是"停着让人打更稳"。真机反馈把这个理由推翻了：
        //     "傀儡乘坐时龙只能贴地，无法飞到 15 格（无论停止还是跟随）"——
        //   背着炮台的龙贴地飞，炮台的射界全被地形挡掉，"空中炮艇"这个定位就没了。
        //   所以货舱模式只保留"不随机游走"（见 tickGolemPassengerMode），高度回到正常档。
        double base = this.isParked() ? HOVER_HEIGHT_IDLE : HOVER_HEIGHT;
        return base * this.hoverHeightScale();
    }

    /**
     * 是不是"停着不动"的状态：手杖切到"停留"，或者待机满 30 秒。
     *
     * <p>只用于表现（{@link #hoverHeight()} 用它决定贴地还是正常档）。
     * <b>不要拿它当骑乘闸门</b>：早先 {@link #startRidingFrom(Player)} 用过它，
     * 真机反馈是"跟在身边的龙右键完全没反应"，已经去掉。
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
        // ★ 用<b>平滑后</b>的局部地面：阶梯台地 / 坑洼地形上，逐 tick 重算的"脚下地面"
        //   会让悬停目标上下跳 1~2 格 → hoverDelta 正负翻转 → 机身上下抖（看起来就是"抽搐"）。
        //   平滑只作用在"同一块地面附近"；换层、掉崖这类大跳变仍然立刻跟上。
        double floor = this.smoothFloorY();
        return this.hoverTargetYAtBlocks(this.blockPosition().getX(), this.blockPosition().getZ(), floor);
    }

    /**
     * 平滑后的"脚下局部地面"高度（低通，带大跳变直通）。
     *
     * <p>台地上每走一格地面就变 1 格，直接拿来当悬停基准会让竖直目标抖；
     * 这里用一阶低通（{@link #FLOOR_SMOOTH}）把它压平，但差值超过
     * {@link #FLOOR_SMOOTH_SNAP} 时直接跟随（换层、掉崖、上桥这类"真的换了地面"）。
     */
    private double smoothFloorY() {
        double floor = this.localFloorY();
        if (Double.isNaN(this.smoothedFloor) || Math.abs(floor - this.smoothedFloor) > FLOOR_SMOOTH_SNAP) {
            this.smoothedFloor = floor;
        } else {
            this.smoothedFloor += (floor - this.smoothedFloor) * FLOOR_SMOOTH;
        }
        return this.smoothedFloor;
    }

    /** 平滑后的局部地面高度（给诊断用；没算过时返回 NaN）。 */
    public double smoothedFloorY() {
        return this.smoothedFloor;
    }

    /** 局部地面的低通系数（每 tick 靠拢 8%）。 */
    private static final double FLOOR_SMOOTH = 0.08D;
    /** 局部地面差值超过这么多格就直通（换层 / 掉崖）。 */
    private static final double FLOOR_SMOOTH_SNAP = 4.0D;
    /** 平滑后的局部地面高度。 */
    private double smoothedFloor = Double.NaN;

    /**
     * <b>任意水平坐标</b>处的悬停目标（脚部 Y）。给寻路用：航线终点也要落在"那儿合理的高度"上。
     *
     * <p>和 {@link #hoverTargetY()} 同一套算法（局部地面 + 悬停高度，再被天花板按<b>身体高度</b>夹住），
     * 只是位置可以不是龙自己脚下。
     */
    public double hoverTargetYAt(double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        double floor = this.scanFloor(bx, bz, Mth.floor(this.getY())).y();
        return this.hoverTargetYAtBlocks(bx, bz, floor);
    }

    /** {@link #hoverTargetY()} / {@link #hoverTargetYAt} 的公共部分（已给定局部地面）。 */
    private double hoverTargetYAtBlocks(int x, int z, double floor) {
        double target = floor + this.hoverHeight();
        double limit = this.maxFeetYUnderCeiling(x, z, this.ceilingScanStart());
        if (limit != NO_CEILING) {
            target = Math.min(target, limit);
        }
        // 房间比龙还矮：贴着"能塞下的最低高度"，绝不沉到地板以下
        return Math.max(target, floor + MIN_HOVER_CLEARANCE);
    }

    /**
     * <b>头顶扫描的起点：脚上方半格</b>（不是头顶上方）。
     *
     * <h2>★ 这是"仍然会向上飞"的最后一个漏洞</h2>
     * 原来起点是 {@code getY() + bbHeight + 0.5}（头顶上方）。真机日志里：
     * <pre>
     * 龙在 y=85.0，hoverTarget=88.55，vColl=true（头顶撞着东西），[ride] surface=108
     * 扫描起点 = 85 + 2.2 + 0.5 = 87.7 → floor = 87 ← 从 87 往上扫
     * 挡它的方块在 y=86 ← 在起点下面，被跳过 → 判定"头顶没有天花板"
     * </pre>
     * 于是悬停目标保持 88.55（在挡块之上）→ 它继续往上顶 → 撞进去变成重合 → 脱出逻辑压下来
     * → 不重合了 → 目标又指回 88.55 → <b>来回抖 = "仍然想往上飞"</b>。
     *
     * <p>从<b>脚上方</b>开始扫，身体高度范围内的任何方块都会被视为"天花板"，
     * 于是 {@code 脚上限 = 那个方块 − bbHeight − 余量} 永远在挡块之下。
     */
    /** 天花板扫描的起点（从脚上方开始，身体高度范围内的任何方块都算天花板）。包内可见，供高度场使用。 */
    double ceilingScanStart() {
        return this.getY() + 0.5D;
    }

    /** 某一列脚下支撑面的 Y（静水/地面，忽略流水）。供高度场使用。 */
    public double supportYAt(int x, int z) {
        return this.scanFloor(x, z, Mth.floor(this.getY())).y();
    }

    /** 房间比龙还矮时的最低悬停余量（格）。供高度场使用。 */
    public static double minHoverClearance() {
        return MIN_HOVER_CLEARANCE;
    }

    /**
     * 头顶天花板允许的<b>最高脚部 Y</b>：已经扣掉<b>整个身体高度</b>与 {@code flight.ceilingClearance}。
     *
     * <h2>为什么必须扣身体高度（这是"机身穿进天花板"的根因）</h2>
     * 原来这里用的是 {@code hoverCeilingY()} —— 它只返回"天花板方块下方那一格"的 Y，
     * 那是<b>头能碰到的高度</b>，不是<b>脚能站的高度</b>。判定箱是 {@code bbHeight}（1 倍 2.2 格、
     * 泰坦 8.8 格），于是：
     * <ul>
     *   <li>1 倍体型：脚被夹到"天花板 − 1"，头顶超出 1.2 格；</li>
     *   <li>泰坦：头顶超出 7.8 格 —— 整个机身埋在天花板里（真机截图确认）。</li>
     * </ul>
     * 正确的关系是 {@code 脚 + 身体高度 ≤ 天花板方块下沿}。
     */
    public double maxFeetYUnderCeiling(int x, int z, double fromY) {
        double ceilingBlock = this.ceilingBlockYAt(x, z, fromY);
        if (ceilingBlock == NO_CEILING) {
            return NO_CEILING;
        }
        return ceilingBlock - this.getBbHeight() - DragonGolemConfig.ceilingClearance();
    }

    /**
     * <b>飞行高度带</b>：脚允许落在 {@code [minFeet, maxFeet]} 之间。
     *
     * <h2>为什么要有"带"，而不是"目标高度"</h2>
     * 以前每个模式各算一个目标高度，再各自加一条否决（悬停检查天花板、脱出检查重合、
     * 一票否决检查 {@code verticalCollision}…）—— 补丁叠补丁，彼此打架。
     * 现在只留这一个真相：<b>任何模式的竖直分量都会被夹进这条带</b>（见
     * {@link DragonFlightController} 的安全层），于是"顶着天花板往上飞"在结构上不可能发生。
     *
     * <ul>
     *   <li>{@code minFeet} = 局部地面 + {@link #MIN_HOVER_CLEARANCE}（别沉进地板）；</li>
     *   <li>{@code maxFeet} = 头顶天花板 − 身体高度 − {@code flight.ceilingClearance}
     *       （{@link #maxFeetYUnderCeiling}；没有天花板就是 {@code +∞}）；</li>
     *   <li>{@code fits} = 这个空间塞不塞得下整条龙（房间比身体还矮时为 false，
     *       此时带被压成一个点，龙只能贴着地板悬停，并且应该靠寻路离开）。</li>
     * </ul>
     */
    public record FlightBand(double minFeet, double maxFeet, boolean fits) {
    }

    /** 当前这一列（脚下）的飞行高度带，每 tick 算一次即可（{@link DragonFlightController} 用）。 */
    public FlightBand flightBand() {
        return this.flightBandAt(this.blockPosition().getX(), this.blockPosition().getZ());
    }

    /**
     * <b>任意水平坐标</b>处的飞行高度带。
     *
     * <p>给"把一段脚本轨迹压回可行空间"用（俯冲弧线就是典型）：轨迹上的每个点都夹进
     * <b>那个点自己的</b>高度带，于是"天花板太低"不再意味着"这招作废"，
     * 而是自动变成贴着带顶的<b>浅掠</b> —— <b>降级执行</b>，而不是中止或顶着。
     */
    public FlightBand flightBandAt(int x, int z) {
        double floor = this.scanFloor(x, z, Mth.floor(this.getY())).y();
        double min = floor + MIN_HOVER_CLEARANCE;
        double max = this.maxFeetYUnderCeiling(x, z, this.ceilingScanStart());
        if (max == NO_CEILING) {
            return new FlightBand(min, Double.MAX_VALUE, true);
        }
        return new FlightBand(min, max, min <= max);
    }

    /**
     * 头顶还有没有 {@code margin} 格以上的余量（相对于"脚 + 身体"）。
     *
     * <p>给"撞墙就抬一点"这类启发式用：没有这条的话，低房间里会出现
     * <b>撞墙 → 每 tick 往上抬 0.15 → 顶到天花板 → 继续抬 → 卡住</b> 的死循环
     * （真机反馈"有时它仍然会想尝试向上飞"的一部分）。
     */
    public boolean hasHeadroomFor(double margin) {
        double limit = this.maxFeetYUnderCeiling(this.blockPosition().getX(), this.blockPosition().getZ(),
                this.ceilingScanStart());
        return limit == NO_CEILING || limit - this.getY() > margin;
    }

    /** <b>高度场</b>：所有"能不能待在这个高度"的问题都由它回答（见 {@link DragonAltitudeField}）。 */
    private final DragonAltitudeField altitudeField = new DragonAltitudeField(this);

    /** 高度场（诊断 / 别的模块读取）。 */
    public DragonAltitudeField altitudeField() {
        return this.altitudeField;
    }

    /** 每 tick 缓存的高度目标（goal 一个 tick 里会问好几次）。 */
    private double altitudeObjective = Double.NaN;
    private int altitudeObjectiveTick = Integer.MIN_VALUE;
    /** 唯一竖直跟踪器的状态。 */
    private double verticalSpeed;
    private int verticalSpeedTick = Integer.MIN_VALUE;

    /**
     * <b>单一高度目标</b>：想去的高度由模式给，<b>可行区间由世界给</b>。
     *
     * <pre>
     * want = 有目标且开启凋零式 ? 目标脚 Y + offset × 体型 : 局部地面 + 悬停高度
     *        （航线节点更高时以航线为准：翻梁 / 绕地形）
     * objective = altitudeField.feasible(want, 下一航路点)   // ← 保证整条龙塞得下
     * </pre>
     *
     * <p>这是竖直方向<b>唯一</b>的目标来源：以前这里有"悬停 / 追击 / 航线 / 锁存 / 低通"五套逻辑，
     * 各自有死区和上限，在边界上互相跳（真机 {@code dy: -0.022 → +0.696 → -0.086}）。
     * 现在只剩"模式给想法、世界给约束"两步，剩下的交给 {@link #trackedVerticalSpeed()}。
     */
    public double flightVerticalTarget() {
        if (this.altitudeObjectiveTick == this.tickCount) {
            return this.altitudeObjective;
        }
        this.altitudeObjectiveTick = this.tickCount;
        double want;
        LivingEntity target = this.getTarget();
        double offset = DragonGolemConfig.flightTargetAltitudeOffset();
        if (target != null && target.isAlive() && offset > 0.0D) {
            // 凋零式：高度跟着目标走（上下都跟）；大体型留更多竖向间隙，但别无限放大
            want = target.getY() + offset * Math.min(3.0D, Math.max(1.0D, this.bodyScale()));
        } else {
            want = this.localFloorY() + this.hoverHeight();
        }
        Vec3 steer = this.pathFollower.steerPoint();
        boolean onRoute = steer != null && this.pathFollower.onRoute();
        if (onRoute && steer.y > want) {
            // 航路点更高：要翻梁 / 爬出低房间，以航线为准
            want = steer.y;
        }
        this.altitudeObjective = this.altitudeField.feasible(want, onRoute ? steer : null);
        return this.altitudeObjective;
    }

    /**
     * <b>唯一的竖直跟踪器</b>（临界阻尼），返回本 tick 的竖直速度。
     *
     * <pre>
     * v += (objective − y) · K − v · D        K = 0.06，D = 0.30
     * v  = clamp(v, −maxFall, +maxClimb)
     * </pre>
     *
     * <p>为什么要它：以前"目标 → 速度"这一步在四个 goal 里各写一遍（{@code hoverDelta()}），
     * 而且控制器还要再用 flightBand 否决一次竖直速度 —— 三处都在做同一件事，边界上必然打架。
     * 现在只有这一个函数：连续、无过冲、<b>不会在边界翻符号</b>，
     * 大误差自动加速（追头顶目标）、小误差自动减速（贴地细调），连死区都不需要。
     */
    public double trackedVerticalSpeed() {
        if (this.verticalSpeedTick == this.tickCount) {
            return this.verticalSpeed;
        }
        this.verticalSpeedTick = this.tickCount;
        double err = this.flightVerticalTarget() - this.getY();
        double v = this.verticalSpeed + (err * ALT_K - this.verticalSpeed * ALT_D);
        this.verticalSpeed = Mth.clamp(v, -ALT_MAX_FALL, ALT_MAX_CLIMB);
        return this.verticalSpeed;
    }

    /** 竖直跟踪器复位（上/下龙、传送、模式大切换时用，避免带着旧速度冲过去）。 */
    public void resetVerticalSpeed() {
        this.verticalSpeed = 0.0D;
        this.verticalSpeedTick = Integer.MIN_VALUE;
    }

    /** 跟踪器刚度（每格误差给多少加速度）。 */
    private static final double ALT_K = 0.06D;
    /** 跟踪器阻尼（越大越"稳"、越小越"弹"）。K/D = 稳态速度，0.06/0.30 = 0.2 格/tick。 */
    private static final double ALT_D = 0.30D;
    /** 最大上升速度（格/tick）：追头顶目标时的上限。 */
    private static final double ALT_MAX_CLIMB = 0.40D;
    /** 最大下降速度（格/tick）。 */
    private static final double ALT_MAX_FALL = 0.32D;

    /**
     * <b>目标相对高度（凋零式）</b>：竖直目标 = 目标脚 Y + 偏移，再夹进 [地面下限, 天花板]。
     *
     * <h2>为什么用这个，而不是"地面 + 悬停高度"</h2>
     * 原版凋零的追击就是这么做的：飞行高度跟着<b>目标高度</b>走（目标脚 Y + 约 5 格，再夹速度）。
     * 而"地面 + 悬停高度"会带来两个真机问题：
     * <ul>
     *   <li>目标在头顶 → 只仰头喷、<b>不爬升追击</b>（高度被脚下地面锁死）；</li>
     *   <li>目标在脚下 → 悬得太高、龙息角度很陡，观感差（大平地尤其明显）。</li>
     * </ul>
     * 现在高度跟着目标走（上下都跟），地面只保证"别沉进地板"、天花板只保证"别穿结构"。
     *
     * @return 目标相对高度；没有目标或关闭（{@code flight.targetAltitudeOffset = 0}）时返回 NaN
     */
    private double targetRelativeAltitude() {
        LivingEntity target = this.getTarget();
        double offset = DragonGolemConfig.flightTargetAltitudeOffset();
        if (target == null || !target.isAlive() || offset <= 0.0D) {
            return Double.NaN;
        }
        // 大体型要留更多竖向间隙（喷口在下巴、脖子长），但别无限放大
        double scaled = offset * Math.min(3.0D, Math.max(1.0D, this.bodyScale()));
        return target.getY() + scaled;
    }

    /** 把竖直目标夹到"头顶天花板允许的最高脚位"，顺便挡住穿天花板（不是限高）。 */
    private double clearCeiling(double target) {
        double limit = this.maxFeetYUnderCeiling(this.blockPosition().getX(), this.blockPosition().getZ(),
                this.ceilingScanStart());
        return limit == NO_CEILING ? target : Math.min(target, limit);
    }

    /**
     * 挨打 → 记录攻击者位置，进入"远离"状态（{@link #markRetreat}）。
     *
     * <p>放在 {@code hurt} 里而不是 goal 里：受击是<b>事件</b>，不该靠 goal 每 tick 轮询血量变化去猜。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt) {
            Entity attacker = source.getEntity();
            if (attacker != null) {
                this.markRetreat(attacker.position());
            }
        }
        return hurt;
    }

    // ---- 行动模式：开阔 / 洞窟 ----

    /**
     * <b>行动模式</b>：开阔地主动游走/绕圈/俯冲；洞窟里"低移动欲望"，以悬停和原地攻击为主。
     *
     * <p>判据是 {@link #spaceBudget()} 的 {@code roomy}，带 {@link #PROFILE_SWITCH_TICKS} 的滞回
     * （避免在洞口/门框处反复切换）。两个模式共用同一套攻击，只改"要不要移动、绕多大圈"。
     */
    public enum DragonProfile {
        ROAMING("ROAMING"),
        GUARDING("GUARDING");

        public final String label;

        DragonProfile(String label) {
            this.label = label;
        }
    }

    /** 当前行动模式（带滞回，见 {@link DragonProfile}）。 */
    public DragonProfile profile() {
        boolean roomy = this.spaceBudget().roomy();
        boolean roaming = this.profile == DragonProfile.ROAMING;
        if (roomy == roaming) {
            this.profileTicks = 0;
            return this.profile;
        }
        if (++this.profileTicks > PROFILE_SWITCH_TICKS) {
            this.profile = roaming ? DragonProfile.GUARDING : DragonProfile.ROAMING;
            this.profileTicks = 0;
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[mode] profile -> {}", this.profile.label);
            }
        }
        return this.profile;
    }

    /** 模式切换需要的连续 tick 数（40 = 2 秒）。 */
    private static final int PROFILE_SWITCH_TICKS = 40;
    private DragonProfile profile = DragonProfile.ROAMING;
    private int profileTicks;

    // ---- 挨打时远离 ----

    /**
     * 挨打之后短时间内<b>远离攻击者</b>（洞窟模式尤其需要：它没有空间做机动，只能先脱开）。
     *
     * <p>由 {@link #hurt} 触发，执行层用 {@link DragonFlightController.Mode#RETREAT} 接管速度；
     * 计时在 {@code aiStep} 里递减。
     */
    public void markRetreat(Vec3 from) {
        this.retreatTicks = RETREAT_TICKS;
        this.retreatFrom = from;
    }

    public int retreatTicks() {
        return this.retreatTicks;
    }

    /** 每 tick 递减远离计时（{@code aiStep} 调用）。 */
    public void tickRetreat() {
        if (this.retreatTicks > 0) {
            this.retreatTicks--;
        }
    }

    /** 远离攻击者的水平速度（竖直由执行层补 hoverDelta）。 */
    @Nullable
    public Vec3 retreatVelocity() {
        if (this.retreatFrom == null) {
            return null;
        }
        double dx = this.getX() - this.retreatFrom.x;
        double dz = this.getZ() - this.retreatFrom.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) {
            return Vec3.ZERO;
        }
        double speed = RETREAT_SPEED * this.flightSpeedFactor() / len;
        return new Vec3(dx * speed, 0.0D, dz * speed);
    }

    /** 远离状态持续多少 tick（30 = 1.5 秒）。 */
    private static final int RETREAT_TICKS = 30;
    /** 远离时的水平速度（基准，乘速度倍率）。 */
    private static final double RETREAT_SPEED = 0.5D;
    private int retreatTicks;
    private Vec3 retreatFrom;

    /** {@link #hoverTargetY()} 里"没找到天花板"的哨兵值；也是 {@link #ceilingYAt} 的返回值。 */
    public static final double NO_CEILING = Double.MAX_VALUE;
    /** 房间比龙还矮时，脚至少要离局部地面这么高（不许沉进地板）。 */
    private static final double MIN_HOVER_CLEARANCE = 0.5D;
    /** 往上找天花板时最多扫多少格（超过就当作开阔地，避免在高空做长扫描）。 */
    private static final int CEILING_SCAN = 32;
    /** 俯冲最低竖直空间的基础值（格），再按体型加 {@link #DIVE_MODEL_DROP}。 */
    private static final double DIVE_MIN_ROOM_BASE = 4.0D;
    /** 模型最低点在原点下方多少格（每 1 倍体型）；和 {@code DragonDiveGoal.skimHeight} 同一个数。 */
    private static final double DIVE_MODEL_DROP = 1.5D;
    /** 往下找地面时最多扫多少格。比天花板深一些：洞里悬停高度常有十几格。 */
    private static final int FLOOR_SCAN = 48;
    /**
     * {@link #liquidTopY} 往上找液面时最多走多少格。
     *
     * <p>32 够覆盖"龙泡在普通湖/岩浆湖里"的情况；瀑布那种长水柱就够不到了，
     * 但那也不该跟着爬到源头（见那里的说明）。
     */
    private static final int LIQUID_SCAN = 32;
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
     *
     * <p><b>★ 液面（水 / 岩浆）也算地面 —— 这是"龙会自己扎进水里、扎进岩浆里"的根因。</b>
     * 水和岩浆<b>都没有碰撞形状</b>，所以旧版会一路穿过它们去找湖底，于是
     * {@code hoverTargetY = 湖底 + 1 + 悬停高度} —— 湖稍微深一点这个高度就在液面<b>以下</b>，
     * 龙就稳稳地悬在湖底上方，也就是"飞进水里/岩浆里"。从高空下降还有第二条路：
     * 往下 {@link #FLOOR_SCAN} 格扫不到任何东西时，兜底值（世界地表 − {@link #FLOOR_FALLBACK}）
     * 同样落在液面以下。现在扫描先碰到液面就以<b>液面</b>为准，
     * 所以"在湖上悬停"= 悬在液面之上 {@link #hoverHeight()} 格。
     */
    public double localFloorY() {
        return this.scanFloor(this.blockPosition().getX(), this.blockPosition().getZ(),
                Mth.floor(this.getY())).y();
    }

    /**
     * 某个水平坐标下"最上面那层支撑面"是不是液体（水面 / 岩浆面）。
     *
     * <p>起点和 {@link #localFloorY()} 一样是龙当前的高度，所以语义就是
     * "龙飞到这个水平坐标时，脚底下那层是不是液体"。给随机游走的取样用：
     * 别把目标点定在湖/岩浆湖上（见 {@link #localFloorY()} 的说明）。
     */
    public boolean isLiquidFloorAt(int x, int z) {
        return this.scanFloor(x, z, Mth.floor(this.getY())).liquid();
    }

    /** {@link #scanFloor} 的结果：支撑面高度 + 那一层是不是液体。 */
    private record Floor(double y, boolean liquid) {
    }

    /**
     * 从 {@code startY} 往下找"支撑面"：第一个有碰撞形状的方块，或者第一层液体（水面 / 岩浆面）。
     *
     * <p>液体那一档要往上把<b>整根液柱的顶面</b>找出来（见 {@link #liquidTopY}）：
     * 已经泡在水里的龙若只按"当前这一格水的顶"算，就得一格一格往上磨，出水前要磨好几秒。
     *
     * <p><b>★ 流水（瀑布 / 水渠）不算支撑面</b>（见 {@code hover.flowingFluidIsFloor}）：
     * 流水柱可以很长（瀑布从崖顶一路到崖底），龙飞进去之后支撑面就成了"瀑布顶"，
     * 于是它每 tick 往上挪一格；从瀑布边缘蹭出去时支撑面又瞬间掉回地面 ——
     * 悬停目标在两者之间反复跳，表现就是"穿过瀑布时抽搐"。现在流水一律跳过、继续往下找，
     * 静水（湖泊、水池、海洋 —— 自然水体都是静水方块）照旧算支撑面。
     */
    private Floor scanFloor(int x, int z, int startY) {
        Level level = this.level();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = startY; y > startY - FLOOR_SCAN && y >= level.getMinBuildHeight(); y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!state.getCollisionShape(level, cursor).isEmpty()) {
                // 方块占据 [y, y+1)，所以站在它上面的高度就是 y+1
                return new Floor(y + 1.0D, false);
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                if (!fluid.isSource() && !DragonGolemConfig.flowingFluidIsFloor()) {
                    continue;   // 流水：不是支撑面，继续往下找
                }
                // 水草、海带这类"含水方块"的流体状态非空，所以它们和纯水一样算液面
                return new Floor(this.liquidTopY(x, z, y) + 1.0D, true);
            }
        }
        // 兜底：往下扫不到东西（悬在深渊/高空），用世界地表作参照。
        // 注意 MOTION_BLOCKING_NO_LEAVES 本身就把液体算作"挡高度"，所以这个兜底值也在液面之上。
        return new Floor(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - FLOOR_FALLBACK, false);
    }

    /**
     * 液柱顶面所在方块的 y（从 {@code fromY} 往上走，直到不再是液体）。
     *
     * <p>上限 {@link #LIQUID_SCAN}：瀑布那种从头顶一路流下来的水柱不该把龙带到源头去；
     * 够不到真正的液面也无所谓 —— 下一 tick 会重新算一次，一层层往上挪就是了
     * （<b>注意</b>：那说的是旧行为；现在流水不参与支撑面判定，见 {@link #scanFloor}）。
     *
     * <p><b>★ 往上走时遇到流水就停</b>（{@code hover.flowingFluidIsFloor} 为 false 时）：
     * 否则"瀑布砸进水池"那种地方，从池水往上会一路数到瀑布顶，
     * 龙又被带到瀑布顶上去了。停下来的位置就是<b>静水水体的真正液面</b>。
     */
    private int liquidTopY(int x, int z, int fromY) {
        Level level = this.level();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int last = Math.min(fromY + LIQUID_SCAN, level.getMaxBuildHeight() - 1);
        for (int y = fromY + 1; y <= last; y++) {
            probe.set(x, y, z);
            FluidState fluid = level.getBlockState(probe).getFluidState();
            if (fluid.isEmpty()) {
                return y - 1;
            }
            if (!fluid.isSource() && !DragonGolemConfig.flowingFluidIsFloor()) {
                return y - 1;   // 上面是流水（瀑布/水渠）：静水液面到此为止
            }
        }
        return last;
    }

    /**
     * 水流 / 岩浆流能不能推动这条龙（{@code hover.pushedByFluid}，默认不能）。
     *
     * <p>原版用它决定要不要把流体的流向施加到实体上（浮力与游泳走的是另一套，不受影响）。
     * 龙是"悬停 + 每 tick 由 goal 直接写速度"的单位，被水流推着走只会和它的速度控制打架：
     * 瀑布里竖直方向被水往下拽、控制端在往上抬，表现就是原地抽搐。
     */
    @Override
    public boolean isPushedByFluid() {
        return DragonGolemConfig.pushedByFluid();
    }

    /**
     * <b>从龙嘴到目标胸口，中间有没有被方块挡住</b>（真实方块射线）。
     *
     * <h2>为什么不能用 {@code hasLineOfSight}</h2>
     * 本家的 {@code AbstractGolemEntity.hasLineOfSight} 在傀儡带 {@code GolemFlags.SEE_THROUGH}
     * （某些材料的"透视"升级）时会<b>直接返回 true</b> —— 穿墙也算"看得见"。而我们关心的不是"感知"，
     * 是<b>龙息打不打得到</b>：锥体判定与喷流都被方块挡（{@code breathReach} 就是这么算的）。
     * 所以这里用 {@code level.clip(嘴 → 胸口, Block, NONE)}：撞到方块且距离明显小于目标距离 → 被挡。
     *
     * <p>留 0.5 格容差：目标自己贴着墙/站在半砖上时，射线可能擦到它脚下的方块。
     *
     * <p>用途：① 索敌加权（优先打"打得着"的敌人，见 {@link DragonTargetGoal}）；
     * ② 喷息/龙弹/靠拢前的开火判断（不再对着墙喷满 60 tick）。
     */
    public boolean hasClearShotTo(LivingEntity target) {
        Vec3 from = this.mouthPosition();
        Vec3 to = target.getBoundingBox().getCenter();
        double dist = from.distanceTo(to);
        if (dist < 1.0E-3D) {
            return true;
        }
        BlockHitResult hit = this.level().clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return hit.getLocation().distanceTo(from) >= dist - 0.5D;
    }

    /** 打上"正在战斗"的标记（有目标 / 挨打 / 出手时刷新）。
     *
     * <p>意义见 {@link #inCombat()}：幽灵（穿墙）状态只在<b>战斗外</b>才允许进入。
     */
    public void markCombat() {
        this.combatTicks = DragonGolemConfig.ghostCombatMemoryTicks();
    }

    /**
     * 现在算不算"战斗中"。
     *
     * <p>判据是"有目标"或"最近 {@code ghost.combatMemoryTicks}(默认 100 = 5 秒) 内打过/被打过"——
     * 单看 {@code hurtTime}（只有 10 tick）太短，战斗里被围在墙边挨一轮打、间隙一过就又算"非战斗"。
     *
     * <p>为什么这件事重要：<b>幽灵状态下龙息/技能会被方块挡住</b>，也就是"战斗无效"。这个结果对
     * 穿墙来说是正确的 —— 但前提是"<b>战斗开始前就进去</b>"。所以自动幽灵（脱困 / 常驻）在战斗中
     * 一律不进入（{@code ghost.duringCombat} 可回退），这样就不会出现"龙卡在墙里打不到人、
     * 又一直保持穿墙"的僵局。
     */
    public boolean inCombat() {
        return this.combatTicks > 0 || this.getTarget() != null;
    }

    /** {@link #inCombat()} 的记忆窗口（tick，逐 tick 递减）。 */
    private int combatTicks;

    /** 骑手冲锋的"上一 tick 位置"已经挪进 {@link DragonFlightController}（只用于诊断日志）。 */

    /**
     * 头顶第一层挡路方块的下沿 Y（再往下留一格余量）；正上方 {@link #CEILING_SCAN} 格内
     * 全是空气就返回 {@link #NO_CEILING}。
     *
     * <p>用 {@code getCollisionShape().isEmpty()} 判断"挡不挡路"，所以草、雪层、藤蔓
     * 这些没有碰撞箱的方块不算天花板 —— 和 {@code DragonFlightAssist.hasRoomFor} 是同一套判据。
     */
    /**
     * 头顶第一层挡路方块的下沿 Y（再往下留一格余量）；正上方 {@link #CEILING_SCAN} 格内
     * 全是空气就返回 {@link #NO_CEILING}。
     *
     * <p>用 {@code getCollisionShape().isEmpty()} 判断"挡不挡路"，所以草、雪层、藤蔓
     * 这些没有碰撞箱的方块不算天花板 —— 和 {@code DragonFlightAssist.hasRoomFor} 是同一套判据。
     *
     * <p><b>注意这是"头能碰到的高度"，不是"脚能站的最高高度"</b>：
     * 要后者请用 {@link #maxFeetYUnderCeiling}（它扣掉了身体高度）。
     */
    public double ceilingYAt(int x, int z, double fromY) {
        double block = this.ceilingBlockYAt(x, z, fromY);
        return block == NO_CEILING ? NO_CEILING : block - 1.0D;
    }

    /** 头顶第一层挡路方块<b>本身的 Y</b>（不是它下方那格）；扫不到返回 {@link #NO_CEILING}。 */
    public double ceilingBlockYAt(int x, int z, double fromY) {
        Level level = this.level();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < CEILING_SCAN; i++) {
            int y = Mth.floor(fromY) + i;
            if (y >= level.getMaxBuildHeight()) {
                break;
            }
            cursor.set(x, y, z);
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                return y;
            }
        }
        return NO_CEILING;
    }

    /** 目标头顶到天花板之间还有多少竖直空间（格）；头顶没有天花板就是 {@link Double#MAX_VALUE}。 */
    public double headroomAbove(LivingEntity target) {
        double ceiling = this.ceilingYAt(Mth.floor(target.getX()), Mth.floor(target.getZ()),
                target.getY() + target.getBbHeight() + 0.5D);
        return ceiling == NO_CEILING ? Double.MAX_VALUE : ceiling - target.getY();
    }

    /**
     * 一次俯冲需要的最低竖直空间（格）。
     *
     * <p>模型最低点在原点下方约 {@code 1.5 × 体型} 格（见 {@code DragonDiveGoal.skimHeight}），
     * 再加上一段弧线余量 —— 比这个矮就别俯冲了：那不是"俯冲"，是拿脑门顶天花板。
     * 1 倍约 5.5 格、泰坦 4 倍约 10 格。
     */
    public double diveMinRoom() {
        return DIVE_MIN_ROOM_BASE + DIVE_MODEL_DROP * Math.max(1.0D, this.combatGeoScale());
    }

    /**
     * 目标上方够不够打一轮俯冲。
     *
     * <p>只给 AI 用：骑手指令（按 R）不看这条 —— 那是玩家明确要的，见 {@code DragonDiveGoal.canUse}。
     */
    public boolean diveRoomOk(LivingEntity target) {
        if (!DragonGolemConfig.diveRoomCheck()) {
            return true;
        }
        return this.headroomAbove(target) >= this.diveMinRoom();
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
        // 用我们自己的飞行导航：按体型放宽路径点容忍度 + 换带体型余量的节点评估器。
        //
        // 注意：<b>真正在用的寻路不在这里</b>。龙的 AI 走 DragonPathFollower 自己算 A* 路线
        // （理由：PathNavigation.tick() 一旦发现有路径就会去驱动 MoveControl，
        // 而这条龙的速度与悬停高度必须由各自的 goal 掌着）。这个导航对象是"万一以后有人
        // 改用原版导航开龙"的兜底，两边的口径（体型余量、容忍度）保持一致。
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
     *
     * <p>这条是<b>俯冲（含掠过）</b>的结算口，走本家的横扫管线
     * （{@code performRangedDamage → performDamageTarget}），所以材料提供的
     * {@code modulargolems:sweep} 会对撞击点周围一起生效。伤害源是
     * {@code damageSources().mobAttack(龙)}，也就是一个"真正的傀儡攻击"——
     * 技能升级那套（命中特效 / 击杀特效 / 伤害源改造）照常触发。
     * 无敌帧同样打完就还回去（见 {@link #dealSkillDamage}），免得俯冲把旁边傀儡的伤害吞掉。
     */
    @Override
    protected boolean performDamageTarget(Entity target, float damage, double knockback) {
        if (!(target instanceof LivingEntity living)) {
            return false;
        }
        return this.dealSkillDamage(living, this.damageSources().mobAttack(this), damage, knockback);
    }
}
