package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * <b>龙息动画：一个目标点 + 一个 P 控制器</b>。
 *
 * <h2>为什么改成这样</h2>
 * 旧版是"切向速度 + 径向修正 + 竖直抬升"三个分量每 tick 相加，相位切换时还要换一次竖直目标，
 * 于是进入喷息后会出现小抽动。而"进入喷息前那段"（靠近 / 后撤 / 抬高）之所以丝滑，
 * 是因为它只有一个目标点、速度是"朝那个点的误差 × 增益"。
 *
 * <p>现在动画也用同一个写法：
 * <ol>
 *   <li>在球面上算一个<b>缓慢推进的期望位置</b>（相位推进，目标点连续移动）；</li>
 *   <li>速度 = {@code clamp((期望位置 − 当前位置) × 增益, ±上限)}；</li>
 *   <li>水平 / 竖直各做一次低通，消除相位切换那一瞬的阶跃。</li>
 * </ol>
 *
 * <p>于是"绕圈 / 站位 / 抬高度"不再是三个互相拉扯的意图，而是"目标点本来就在球面的那个高度上"。
 *
 * <p>朝向不在这里写：调用方（{@code DragonRangedGoal.tickBreath}）每 tick 先 {@code aim(target)}，
 * 这里再写一次会变成"每 tick 两次 yaw"（会过冲、看起来像抽搐）。
 */
public final class DragonBreathAnimation {

    /** 跟随增益：越小越柔、越大越"追得紧"。 */
    private static final double GAIN = 0.22D;
    /** 水平速度上限（格/tick）。 */
    private static final double MAX_HORIZONTAL = 0.60D;
    /** 竖直速度上限（格/tick）。 */
    private static final double MAX_VERTICAL = 0.22D;

    /** 相位（弧度）。 */
    private double angle;
    /** 球面半径（水平投影）。 */
    private double radius = 14.0D;
    /** 是否已初始化。 */
    private boolean active;
    /** 低通后的速度。 */
    private double smoothVx;
    private double smoothVz;
    private double smoothVy;
    /** 上一 tick 摆出来的速度：调用方还要再叠一次熔岩规避/避障。 */
    private Vec3 lastVelocity = Vec3.ZERO;

    public Vec3 lastVelocity() {
        return this.lastVelocity;
    }

    /** 一轮喷息开始时调用：定半径、从当前方位接上相位。 */
    public void begin(DragonGolemEntity dragon, LivingEntity target) {
        // ★ 洞穴半径 = 龙的"安全站位距离"（近战够不到 + 嘴够得着），不是固定的嘴前伸量：
        //   目标宽 3 格时原版近战距离就接近 6 格，站在嘴边 8 格以内照样挨打。
        this.radius = dragon.inCave()
                ? Math.max(3.0D, dragon.caveStandoff(target))
                : Math.max(6.0D, dragon.orbitRadius());
        this.angle = Math.atan2(dragon.getZ() - target.getZ(), dragon.getX() - target.getX());
        this.smoothVx = 0.0D;
        this.smoothVz = 0.0D;
        this.smoothVy = 0.0D;
        this.lastVelocity = Vec3.ZERO;
        this.active = true;
    }

    /** 一轮喷息结束时调用。 */
    public void end() {
        this.active = false;
    }

    public boolean active() {
        return this.active;
    }

    public double radius() {
        return this.radius;
    }

    public double angleDegrees() {
        return Math.toDegrees(this.angle);
    }

    /**
     * 每 tick 摆一次。
     *
     * @param verticalUnused 兼容旧签名（竖直目标由这里自己算）
     */
    public void tick(DragonGolemEntity dragon, LivingEntity target, double verticalUnused) {
        if (!this.active) {
            this.begin(dragon, target);
        }
        boolean cave = DragonGolemConfig.caveHoldBreath() && dragon.inCave();
        // 洞穴里挨打后站位要再退一点：半径每 tick 只增不减地跟上 caveStandoff
        // （hurtTime 那 10 tick 会给 standoff 加 1.5 格）。
        if (cave) {
            this.radius = Math.max(this.radius, dragon.caveStandoff(target));
        }
        // ---- 1) 期望高度 ----
        // 洞穴：直接用 entity 的"洞穴战斗高度"（目标头顶 + attackHeight，含岩浆液面兜底）；
        // 平原：悬停高度。
        double wantY = cave ? dragon.combatAltitudeY() : dragon.hoverTargetY();
        // ---- 2) 相位推进（洞穴慢、平原按线速度）----
        double omega = cave
                ? 0.03D
                : Math.max(0.01D, DragonGolemConfig.breathOrbitSpeed() * dragon.flightSpeedFactor()
                        / Math.max(1.0D, this.radius));
        this.angle += omega;
        double px = target.getX() + Math.cos(this.angle) * this.radius;
        double pz = target.getZ() + Math.sin(this.angle) * this.radius;
        // ---- 3) 速度 = 朝期望点的误差 × 增益（限幅 + 低通）----
        double wantVx = Mth.clamp((px - dragon.getX()) * GAIN, -MAX_HORIZONTAL, MAX_HORIZONTAL);
        double wantVz = Mth.clamp((pz - dragon.getZ()) * GAIN, -MAX_HORIZONTAL, MAX_HORIZONTAL);
        double wantVy = Mth.clamp((wantY - dragon.getY()) * GAIN, -MAX_VERTICAL, MAX_VERTICAL);
        this.smoothVx = Mth.lerp(0.25D, this.smoothVx, wantVx);
        this.smoothVz = Mth.lerp(0.25D, this.smoothVz, wantVz);
        this.smoothVy = Mth.lerp(0.20D, this.smoothVy, wantVy);
        dragon.setBodyRollDirect(cave ? 0.0F : (float) DragonGolemConfig.breathOrbitBank());
        this.lastVelocity = new Vec3(this.smoothVx, this.smoothVy, this.smoothVz);
        dragon.setDiveVelocity(this.lastVelocity);
    }

    /** 沿圆弧的角速度（弧度/tick），诊断用。 */
    public double angularSpeed(DragonGolemEntity dragon) {
        return Mth.clamp(DragonGolemConfig.breathOrbitSpeed() * dragon.flightSpeedFactor()
                / Math.max(1.0D, this.radius), 0.0D, 0.5D);
    }
}
