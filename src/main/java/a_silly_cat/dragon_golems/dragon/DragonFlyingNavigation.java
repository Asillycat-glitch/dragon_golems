package a_silly_cat.dragon_golems.dragon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 龙的飞行导航：<b>按体型放宽"切下一个路径点"的容忍度</b>。
 *
 * <h2>先纠正三条早先写错的注释（对着 2.7.3 字节码核过）</h2>
 * <ol>
 *   <li>本家的 {@code FastGroundPathNavigation} 重写的是 {@code followThePath()}，<b>不是</b>
 *       {@code recomputePath()}；{@code recomputePath()} 原样继承原版（含 20 tick 节流）。</li>
 *   <li>它那行 {@code maxDistanceToWaypoint} 的赋值<b>和原版逐字相同</b>
 *       （{@code bbWidth > 0.75 ? bbWidth / 2 : 0.75 - bbWidth / 2}），并不是"按体型调大"。</li>
 *   <li>原版的 {@code hasDelayedRecomputation} <b>是被消费的</b>（{@code PathNavigation.tick()}
 *       开头就会调 {@code recomputePath()}），20 tick 内的请求只是延后一 tick，不是被丢弃。</li>
 * </ol>
 * 也就是说：<b>不需要为了"重算生效"去删节流</b>。
 *
 * <h2>现在这个类实际上不负责寻路</h2>
 * 龙的 AI 走 {@link DragonPathFollower} 自己算路线（速度与悬停高度都还归各自的 goal，
 * 不能让 {@code PathNavigation.tick()} 抢走 {@code MoveControl}）。所以这里的
 * {@link #trimPath()} 在这个类被真正用来"跟路"之前都是备用逻辑 ——
 * 注意 {@code followThePath()} 每 tick 都会重写那个字段，真跟路时它也会被覆盖。
 *
 * <p>保留它是因为：万一以后有人改用原版导航开龙，这里的容忍度与
 * {@link DragonFlyNodeEvaluator}（体型余量）都是同一个口径的兜底。
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

    /**
     * 换成带<b>体型余量</b>的评估器（见 {@link DragonFlyNodeEvaluator}）。
     *
     * <p>原版 {@code FlyNodeEvaluator} 只看方块格、不看实体尺寸，2.6 格宽的龙会被规划进 1 格缝。
     * 注意：<b>现在真正在用的寻路不在这个导航对象上</b> —— 龙的 AI 走 {@link DragonPathFollower}
     * 自己算路线（理由见那里的类注释：不让 {@code PathNavigation.tick()} 抢走移动控制权）。
     * 这里换评估器是为了"万一以后有人真的用起导航"时行为一致，属于同一个口径的兜底。
     */
    @Override
    protected net.minecraft.world.level.pathfinder.PathFinder createPathFinder(int maxPathLength) {
        this.nodeEvaluator = new DragonFlyNodeEvaluator();
        this.nodeEvaluator.setCanFloat(true);
        return new net.minecraft.world.level.pathfinder.PathFinder(this.nodeEvaluator, maxPathLength);
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
     * 重算当前路径（不节流），供"撞墙了就重新规划"使用。
     *
     * <p><b>纠正</b>：原版的 20 tick 节流并不会把请求丢掉 —— {@code PathNavigation.tick()}
     * 开头就会消费 {@code hasDelayedRecomputation} 并调用 {@code recomputePath()}，
     * 20 tick 内提的请求只是延后一 tick 生效。这里做成"立刻生效"是为了配合
     * {@link DragonFlightAssist} 的卡住判定（它本身已经有 20 ticket 门槛与重试上限），
     * 不是为了绕过什么。
     *
     * <p>当前 AI 走的是 {@link DragonPathFollower}，它自己算路线、不写这里的字段，
     * 所以这个方法只在"有人真的用导航开龙"时才有意义。
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
     * <b>不允许切角</b>（交回原版飞行判定）。
     *
     * <h2>为什么改回来</h2>
     * 这里以前主动放行 {@code OPEN / WALKABLE} 的切角，理由写的是"龙体型大、转向慢，
     * 老老实实贴着拐角绕反而更容易蹭墙"。真机结论正好相反：<b>允许切角 = A* 会在墙角抄近路，
     * 飞过去的那条直线离墙角更近</b>，于是龙一路贴着墙角蹭 —— 这正是"墙边抽搐"的主要来源之一。
     *
     * <p>现在只保留原版判定：拐角不切，路线宁可多绕一格。配合
     * {@code pathfinding.bodyMargin}（节点的身体余量）与"路线拉直"，整体会离墙更远。
     */
    @Override
    public boolean canCutCorner(BlockPathTypes type) {
        return super.canCutCorner(type);
    }

    /** 把"当前路径"暴露出来只为了日志/诊断，返回 null 表示没有路径。 */
    @Nullable
    public Path currentPathForDebug() {
        return this.path;
    }

    /**
     * 当前路径上下一个还没走到的路径点（世界坐标，取节点中心）；没有路径或已走完则 null。
     *
     * <p>给 {@link DragonFlightAssist} 的避障用：撞墙时最可靠的方向不是"扇面里碰运气"，
     * 而是<b>寻路已经算好的那条路线</b>。真机反馈"龙往一根柱子上撞、绕不过去"，
     * 就是因为避障只在自己的小扇面里挑方向，没有利用已经存在的路径。
     *
     * <p>注意 {@code Path.getNextNode()} 在"路径为空"时可能返回 null（javap 查过签名），
     * 所以这里两个都判。
     */
    @Nullable
    public Vec3 currentPathNode() {
        if (this.path == null || this.path.isDone()) {
            return null;
        }
        net.minecraft.world.level.pathfinder.Node node = this.path.getNextNode();
        if (node == null) {
            return null;
        }
        // +0.5 取方块中心：节点坐标是方块整数坐标，直接当方向会偏半格
        return new Vec3(node.x + 0.5D, node.y + 0.5D, node.z + 0.5D);
    }
}
