package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 龙的<b>飞行执行层</b>：<b>每 tick 唯一的速度出口</b>。
 *
 * <h2>为什么要把它抽出来</h2>
 * 之前"谁能写 {@code deltaMovement}"散落在实体、四个 goal、飞行辅助里，于是每修一个 bug
 * 就多一条否决规则，而这些规则彼此不知道对方存在 —— 表现就是抽搐、穿墙、"想往上飞"。
 * 这个类把执行层收成一条流水线：
 *
 * <pre>
 * goal（只表达意图） ──► DragonFlightController（选模式 → 算期望速度 → 安全层 → 写一次）
 *                                 │
 *                                 └─► Entity.move()（原版物理）
 * </pre>
 *
 * <h2>模式优先级（写死在一处，不再分散）</h2>
 * <ol>
 *   <li>{@link Mode#RIDER_DIVE} 骑手冲锋：航线接管，连玩家输入都让位；</li>
 *   <li>{@link Mode#RIDER} 骑手驾驶：由 {@code DragonRiderControl} 在别处写，这里不动；</li>
 *   <li>{@link Mode#ESCAPE} 重合脱出：{@code noPhysics} 只是物理安全，方向必须由这里给；</li>
 *   <li>{@link Mode#SCRIPT} 技能航线（俯冲/拉升）；</li>
 *   <li>{@link Mode#RESCUE} 自动脱困；</li>
 *   <li>{@link Mode#HOVER} 悬停（没人管的时候维持高度）。</li>
 * </ol>
 *
 * <h2>S1：唯一的安全层</h2>
 * 所有模式算出来的期望速度都过 {@link #applySafety(Vec3)}：
 * <b>把竖直分量夹进飞行高度带</b>（{@link DragonGolemEntity#flightBand()}），
 * 带外上方绝不许继续上升、带外下方绝不许继续下沉。
 * 于是"顶着天花板往上飞"在结构上不可能，不需要再给每个模式单独打补丁。
 *
 * <p>这一版（S0+S1）只做"搬家 + 收口"，动作细节与原来逐行一致；
 * 后续 S2 会把"俯冲超出高度带"从"夹平"改成"降级成浅俯冲"。
 */
public final class DragonFlightController {

    /** 本 tick 由谁接管速度。 */
    public enum Mode {
        RIDER_DIVE("RIDER_DIVE"),
        RIDER("RIDER"),
        ESCAPE("ESCAPE"),
        RETREAT("RETREAT"),
        SCRIPT("SCRIPT"),
        RESCUE("RESCUE"),
        HOVER("HOVER");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private final DragonGolemEntity dragon;

    /** 上一次 tick 选中的模式（诊断用）。 */
    private Mode mode = Mode.HOVER;

    /** 骑手冲锋的"上一 tick 位置"，只用于诊断日志。 */
    private Vec3 riderDivePrevPos = Vec3.ZERO;

    public DragonFlightController(DragonGolemEntity dragon) {
        this.dragon = dragon;
    }

    public Mode mode() {
        return this.mode;
    }

    /**
     * 每 tick 调一次（服务端 {@code aiStep}，{@code super.aiStep()} 之后）。
     *
     * <p>必须在 {@code super.aiStep()} 之后：那时 {@code moveControl.tick()} 与原版骑乘管线
     * 已经把竖直速度清过一遍，我们再写才是"最终值"。
     */
    public void tick(@Nullable Player rider) {
        Vec3 script = this.dragon.scriptVelocity();
        if (rider != null && this.dragon.isRiderOrderedDive() && script != null) {
            this.mode = Mode.RIDER_DIVE;
            this.tickRiderDive(script);
            return;
        }
        if (rider != null) {
            // 骑手驾驶：速度由 DragonRiderControl/travelRidden 写，这里只让出控制权
            this.mode = Mode.RIDER;
            this.dragon.setKeepAim(false);
            return;
        }
        if (this.dragon.flightAssist().isEmbedded()) {
            this.mode = Mode.ESCAPE;
            this.applyEscapeSafety(this.dragon.flightAssist().embeddedEscape(this.dragon));
            this.dragon.clearScriptVelocity();
            return;
        }
        if (this.dragon.retreatTicks() > 0 && script == null) {
            // 挨打后短暂远离攻击者 —— 但**只在没有招式要执行时**才独占速度。
            // 有招式时见 applyIntent 末尾的"后撤偏置"：边打边退，不会变成单纯逃跑。
            Vec3 away = this.dragon.retreatVelocity();
            if (away != null) {
                this.mode = Mode.RETREAT;
                Double hy = this.dragon.hoverYSpeed();
                this.applySafety(new Vec3(away.x, hy == null ? 0.0D : hy, away.z));
                return;
            }
        }
        if (script != null) {
            this.mode = Mode.SCRIPT;
            this.applyIntent(script);
            this.dragon.clearScriptVelocity();
            return;
        }
        if (this.dragon.flightAssist().isRescuing()) {
            this.mode = Mode.RESCUE;
            Vec3 escape = this.dragon.flightAssist().rescueVelocity(this.dragon);
            if (escape != null) {
                this.applyEscapeSafety(escape);
            } else {
                this.dragon.setNoGravity(true);
                this.dragon.fallDistance = 0.0F;
            }
            return;
        }
        this.mode = Mode.HOVER;
        this.dragon.setKeepAim(false);
        Double hoverY = this.dragon.hoverYSpeed();
        if (hoverY == null) {
            return;      // 已经在目标高度附近：什么都不写（和原 applyHover 的早退一致）
        }
        Vec3 current = this.dragon.getDeltaMovement();
        this.applySafety(new Vec3(current.x, hoverY, current.z));
    }

    /**
     * <b>招式意图</b>：goal 给的是"想这么飞"，是否真的这么飞由这里决定。
     *
     * <h2>S3：执行层拥有"卡住"的判定权</h2>
     * 以前 goal 写死速度往前推，撞墙了也继续推 —— 真机里喷息绕圈进死角、俯冲顶着墙磨，
     * 都是"goal 不知道前面走不通"造成的。现在：
     * <ul>
     *   <li>意图带水平分量、而龙正在水平碰撞 → {@link #blockedTicks} 累加；</li>
     *   <li>连续撞够 {@link #INTENT_BLOCK_LIMIT} tick → 判定"这个意图在这个地形执行不了"，
     *       <b>水平分量清零（原地悬停）</b>，竖直仍归安全层的 flightBand 管；</li>
     *   <li>把计数暴露给 goal（{@link #blockedTicks()}），goal 就能提前收招/换路，
     *       而不是靠"位移停滞看门狗"这种事后补救。</li>
     * </ul>
     */
    private void applyIntent(Vec3 desired) {
        boolean pushing = desired.x * desired.x + desired.z * desired.z > 1.0E-6D;
        if (pushing && this.dragon.horizontalCollision) {
            this.blockedTicks++;
        } else {
            this.blockedTicks = Math.max(0, this.blockedTicks - 2);
        }
        // ★ 平滑降速，而不是"一撞就归零"：
        //   第一版是硬切（blockedTicks > 10 → 水平分量直接 0），而 horizontalCollision 是会闪的
        //   （擦一下地形就是一拍 true），于是速度在"有/无"之间跳 → 真机表现为喷息抽搐。
        //   现在用一阶低通把水平分量收到 0 / 放回 1，瞬时擦碰只是轻轻收油门。
        double targetThrottle = this.blockedTicks > INTENT_BLOCK_LIMIT ? 0.0D : 1.0D;
        this.throttle += (targetThrottle - this.throttle) * INTENT_THROTTLE_LERP;
        double k = this.throttle;
        Vec3 v = new Vec3(desired.x * k, desired.y, desired.z * k);
        // ★ 后撤偏置（而不是独占模式）：挨打时在招式的速度上叠一个"远离攻击者"的分量，
        //   这样它是"边打边退"，不会因为挨一下就整轮不还手（真机：被打几次就变成纯逃跑）。
        if (this.dragon.retreatTicks() > 0) {
            Vec3 away = this.dragon.retreatVelocity();
            if (away != null) {
                double ax = v.x + away.x * RETREAT_BIAS;
                double az = v.z + away.z * RETREAT_BIAS;
                double len = Math.sqrt(ax * ax + az * az);
                if (len > RETREAT_MAX) {
                    ax *= RETREAT_MAX / len;
                    az *= RETREAT_MAX / len;
                }
                v = new Vec3(ax, v.y, az);
            }
        }
        this.applySafety(v);
    }

    /** 后撤偏置的权重（招式速度 + 远离分量 × 这个系数）。 */
    private static final double RETREAT_BIAS = 1.2D;
    /** 后撤偏置后的水平速度上限（避免它被一击弹飞老远）。 */
    private static final double RETREAT_MAX = 0.9D;

    /** 当前招式意图的水平"油门"（0 = 完全停推挤，1 = 全速执行；诊断用）��� */
    public double throttle() {
        return this.throttle;
    }

    /** 当前招式意图连续被水平碰撞挡住多少 tick（0 = 没被挡；goal 用它决定要不要收招）。 */
    public int blockedTicks() {
        return this.blockedTicks;
    }

    /** 连续撞墙多少 tick 之后判定"意图执行不了"并停住水平推挤（10 = 0.5 秒）。 */
    private static final int INTENT_BLOCK_LIMIT = 10;
    /** 水平"油门"的低通系数：越小越平滑（0.15 ≈ 7 tick 走完 2/3 路程）。 */
    private static final double INTENT_THROTTLE_LERP = 0.15D;
    /** 招式意图被水平碰撞挡住的连续 tick 数。 */
    private int blockedTicks;
    /** 招式意图的水平油门（0~1，平滑值）。 */
    private double throttle = 1.0D;


    /** 骑手冲锋：航线接管 + 原有的诊断/朝向处理（玩家指令，不套 {@link #applyIntent} 的"卡住就停"）。 */
    private void tickRiderDive(Vec3 vel) {
        this.dragon.setNoGravity(true);
        this.dragon.setDeltaMovement(vel);
        this.dragon.fallDistance = 0.0F;
        if (DragonDebug.RIDE && this.dragon.tickCount % 10 == 0) {
            Dragon_golems.LOGGER.info("[dive] 骑手冲锋速度 vel={} prevPos={} nowPos={} noPhysics={} hColl={}",
                    vel, this.riderDivePrevPos, this.dragon.position(),
                    this.dragon.noPhysics, this.dragon.horizontalCollision);
        }
        this.riderDivePrevPos = this.dragon.position();
        this.faceIfNotAiming(vel);
        this.dragon.clearScriptVelocity();
    }

    /**
     * <b>唯一的安全层</b>：把期望速度夹进飞行高度带，然后写一次。
     *
     * <ul>
     *   <li>{@code 下一 tick 的 Y > 带顶} → 竖直分量压成 ≤ 0（不许再上升）；
     *       这一条同时替代了以前散落的"天花板否决""重合期间不许上升"两处补丁；</li>
     *   <li>{@code 下一 tick 的 Y < 带底} → 竖直分量压成 ≥ 0（不许继续沉下去），
     *       但<b>脱出/脱困例外</b>：见 {@link #applyEscapeSafety(Vec3)}。</li>
     * </ul>
     * 水平分量原样保留（绕墙由寻路/避障负责，不在这里重复判断）。
     */
    private void applySafety(Vec3 desired) {
        this.applySafety(desired, true);
    }

    /**
     * 脱出/脱困用的安全层：<b>保留"不许上升"，但不限制向下</b>。
     *
     * <h2>为什么（真机日志）</h2>
     * 身体正嵌在方块里时，{@code localFloorY()} 会把"它身体所在的那块方块"当成地板，
     * 于是 {@code band.minFeet} 反而落在它<b>上面</b> —— 安全层据此把向下的脱出速度压成 0，
     * 结果卡在方块里出不来：{@code mode=重合脱出 stuck=78 dy=0.000}，三秒一格没动。
     *
     * <p>而脱出方向本来就逐个探过 {@code hasRoomFor}（落点塞得下整条龙才会被选中），
     * 所以"向下"在这里是安全的，不该再被一个用错基准算出来的下限拦住。
     */
    private void applyEscapeSafety(Vec3 desired) {
        this.applySafety(desired, false);
    }

    private void applySafety(Vec3 desired, boolean clampToBandBottom) {
        DragonGolemEntity.FlightBand band = this.dragon.flightBand();
        double y = desired.y;
        double next = this.dragon.getY() + y;
        if (next > band.maxFeet()) {
            y = Math.min(y, 0.0D);
        }
        if (clampToBandBottom && next < band.minFeet()) {
            y = Math.max(y, 0.0D);
        }
        Vec3 v = new Vec3(desired.x, y, desired.z);
        this.dragon.setNoGravity(true);
        this.dragon.setDeltaMovement(v);
        this.dragon.fallDistance = 0.0F;
        this.faceIfNotAiming(v);
    }

    /** 开火中（{@code keepAim}）朝向由 goal 的 aimAt 管；否则朝运动方向。 */
    private void faceIfNotAiming(Vec3 vel) {
        if (this.dragon.keepAim()) {
            this.dragon.setKeepAim(false);
        } else {
            this.dragon.faceMovement(vel);
        }
    }
}
