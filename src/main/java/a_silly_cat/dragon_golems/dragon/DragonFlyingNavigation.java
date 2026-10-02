package a_silly_cat.dragon_golems.dragon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;

/**
 * 龙的飞行寻路：<b>按体型放宽"切下一个路径点"的容忍度 + 让重算请求一定生效</b>。
 *
 * <h2>为什么要自己做（本家的答案）</h2>
 * 模块化傀儡本家有一个 {@code FastGroundPathNavigation extends GroundPathNavigation}，
 * 它只重写了一个方法 —— {@code recomputePath()}，在里面做了两件事：
 * <ol>
 *   <li><b>按体型调大"允许偏航"的阈值</b>：把 {@code maxDistanceToWaypoint} 设成
 *       {@code max(0.75, mob.getBbWidth() / 2)}。原版默认 0.75 格，对判定箱几个格宽的巨物
 *       太苛刻 —— 它很难精确走到路径点上，于是永远"够不到"当前路径点、不切下一个，
 *       最后表现为原地磨或绕圈。</li>
 *   <li><b>绕过原版的重算节流</b>。这一条比看上去重要：原版
 *       {@code PathNavigation.recomputePath()} 自己就带节流 ——
 *       <pre>
 *       if (level.getGameTime() - timeLastRecompute &gt; 20) { ...真重算... }
 *       else { hasDelayedRecomputation = true; }      // 不够 20 tick 就只置个标记
 *       </pre>
 *       而那个 {@code hasDelayedRecomputation} 标记<b>没有任何地方消费</b>
 *       （{@code isDone()} 只看 {@code path == null || path.isDone()}）。
 *       也就是说：<b>20 tick 内的重算请求等于被静默丢弃</b>。
 *       本家干脆整个重写、不做这个节流。</li>
 * </ol>
 *
 * <h2>我们额外要解决的那一条</h2>
 * 原版<b>只在 goal 需要新路径时才 createPath</b> —— {@code Mob} 里没有任何
 * "撞墙就重算"的调用。所以龙撞上墙之后会<b>继续朝同一个路径点推</b>，直到当前路径走完。
 * 这就是"撞墙后不会停止寻路、重新找路线"的根因。
 * 对策在 {@link DragonFlightAssist}：判定卡住就显式调 {@link #recomputePath()}。
 * <b>正因为本类去掉了节流，那一下才会真的重算</b> —— 原版那版会被丢弃。
 */
public class DragonFlyingNavigation extends FlyingPathNavigation {

    /** 原版默认的容忍度；比它更小的值没意义（那等于比原版还苛刻）。 */
    private static final float MIN_TOLERANCE = 0.75F;
    /**
     * 我们自己记的"到达判定射程"。
     *
     * <p>为什么要记：原版 {@code PathNavigation.reachRange} 是 <b>private</b>
     * （javap 查过：{@code targetPos} / {@code reachRange} 都是 private，
     * 只有 {@code getTargetPos()} 是 public），重算路径时拿不到它。
     * 而 goal 调 {@code moveTo(x, y, z, reachRange)} 时会把射程传进来，
     * 所以在这里顺手缓存一份 —— 值是 goal 自己选的，和原版行为一致。
     */
    private int cachedReachRange = 1;

    public DragonFlyingNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    /** 缓存 goal 指定的射程，供 {@link #recomputePath()} 复用。 */
    @Override
    public boolean moveTo(double x, double y, double z, double speed) {
        // 第 4 个形参名是 speed（1.20.1 的签名），但原版把它当"到达射程"用：
        // PathNavigation.moveTo 内部就是 setSpeedModifier(speed) + createPath(targetPos, (int) speed)。
        // 所以这里照它的语义缓存。默认 1 和原版 reachRange 的初值一致。
        if (speed >= 1.0D) {
            this.cachedReachRange = (int) speed;
        }
        return super.moveTo(x, y, z, speed);
    }

    /**
     * 按体型放宽"当前路径点算不算走到了"。
     *
     * <p>判定箱每宽 1 格，容忍度加 0.5 格 —— 和本家的 {@code getBbWidth() / 2} 同一公式。
     * 龙默认 2.6 格宽 → 1.3 格。注意原版构造里这个字段的初值是 <b>0.5</b>、
     * {@code GroundPathNavigation} 里是 0.75，对几格宽的巨物都太苛刻。
     */
    @Override
    protected void trimPath() {
        this.maxDistanceToWaypoint = Math.max(MIN_TOLERANCE, this.mob.getBbWidth() / 2.0F);
        super.trimPath();
    }

    /**
     * 重算路径，<b>不做任何节流</b>。
     *
     * <p>走的是和原版一样的重建流程（清 path → {@code createPath(targetPos, reachRange)}
     * → 记时间），只是把那两个 {@code if (距上次重算 > 20 tick)} 判断去掉 —— 见类注释：
     * 原版那条 else 分支只置一个没人消费的标记，所以"想重算"根本得不到执行。
     *
     * <p>没有加频率保护是有意的：{@link DragonFlightAssist} 只在"判定卡住"时调它，
     * 而那个判定本身带 20 tick 门槛与 60 tick 的脱困上限，不会每 tick 触发。
     * 万一将来别处高频调用，应该在那一边限制，而不是把节流加回来
     * （加回来就等于又变回"请求被丢弃"）。
     */
    @Override
    public void recomputePath() {
        BlockPos target = this.getTargetPos();
        if (target == null) {
            return;
        }
        this.path = null;
        this.path = this.createPath(target, this.cachedReachRange);
        this.timeLastRecompute = this.level.getGameTime();
        this.hasDelayedRecomputation = false;
    }

    /**
     * 飞行单位可以更激进地"切角"。
     *
     * <p>原版 {@code FlyingPathNavigation} 已经放宽了部分路径类型；这里把
     * {@code OPEN} / {@code WALKABLE} 也允许切角 —— 龙体型大、转向慢，
     * 老老实实贴着拐角绕反而更容易蹭墙。
     */
    @Override
    public boolean canCutCorner(BlockPathTypes type) {
        return super.canCutCorner(type)
                || type == BlockPathTypes.OPEN
                || type == BlockPathTypes.WALKABLE;
    }

    /** 把"当前路径"暴露出来只为了日志/诊断，返回 null 表示没有路径。 */
    @Nullable
    public Path currentPathForDebug() {
        return this.path;
    }
}
