package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

/**
 * B 档寻路：<b>用 A* 算出航路点，只把"下一个节点"当作转向目标</b>。
 *
 * <h2>解决的问题</h2>
 * 龙原先的移动是"朝目标点直接推速度"（{@code setDiveVelocity}），而目标点来自
 * 随机游走采样 / 主人的 XZ，<b>都不校验可达性</b>。主人在墙后、采样点落在墙里，
 * 龙就会一直朝那个点拱 —— 卡住了靠 {@link DragonFlightAssist} 的扇面探测与 no-clip 救援硬挤。
 * 而"寻路"（{@code DragonFlyingNavigation}）其实<b>从来没被调用过</b>：
 * 没有任何 goal 调 {@code moveTo/createPath}，所以 {@code PathNavigation.targetPos} 恒为 null，
 * {@code recomputePath()} 第一行就返回，路径一次都没生成过。
 *
 * <h2>设计取舍（为什么不让原版导航直接开龙）</h2>
 * <ol>
 *   <li><b>速度与高度仍归原来的 goal</b>：龙的高度是"脚下地形 + 悬停高度 + 呼吸"算出来的，
 *       交给 {@code MoveControl} 会把这套全部推翻。所以这里<b>只借路线</b>，不借方向盘。</li>
 *   <li><b>不碰 {@code PathNavigation} 的状态</b>：{@code PathNavigation.tick()} 只要发现
 *       {@code path != null} 就会去驱动 {@code MoveControl}，和我们的直写速度打架。
 *       所以这里直接用 {@link PathFinder} 自己算（和 {@code PathNavigation.createPath} 同一套参数），
 *       一个字段都不写回导航对象。</li>
 *   <li><b>只取路线的水平走向</b>：转向点用节点的 XZ、Y 用目标点的 Y ——
 *       竖向依旧由"悬停高度"决定，避免两套高度逻辑互相拉扯。
 *       （墙比悬停高度还高的场合，路线会绕过去；实在过不去还有飞行辅助的"卡住往上爬"。）</li>
 *   <li><b>算不出路不硬撑</b>：连续 {@code pathfinding.giveUpTicks} tick 没路线就报告"不可达"，
 *       由 goal（游走）换一个目标点，而不是继续对着墙拱。</li>
 * </ol>
 *
 * <h2>0.4 追加：直飞优先 / 幽灵直穿 / 预算</h2>
 * <ul>
 *   <li><b>直飞优先</b>：整条直线（身体盒 + 液体）都干净时直接返回目标点，<b>根本不跑 A*</b>。
 *       开阔地里 90% 的时间走这条，是"重新打开寻路"之后的性能主闸门。</li>
 *   <li><b>幽灵直穿</b>：{@code noPhysics} 时不寻路、不算"不可达"，直线的尽头就是目标。
 *       旧版即使幽灵也会去问 A*、算不出路线就站桩 —— 表现就是"能穿墙却不认路"。</li>
 *   <li><b>预算</b>：单次 400 节点、全服每 tick 最多 1 次搜索、单次超过 10ms 冷却 200 tick；
 *       并且每只龙<b>最多每秒算一次</b>。算不出路就原地停（goal 每 tick 只做一次廉价的重试，
 *       真正重算要等到下一秒）。</li>
 * </ul>
 *
 * <p>每只龙一个实例，状态只在自己身上（服务端跑 AI，客户端根本不会调用它）。
 */
public final class DragonPathFollower {

    /** 目标点移动超过这个距离就立刻重算（格）。 */
    private static final double GOAL_TOLERANCE = 2.0D;
    /** 走完这个距离内的节点就算"到过"，切下一个（格，按体型放宽：见 {@link #nodeReach()}）。 */

    /** A* 搜索半径的上限（格）：游走半径 72 + 余量就够用，再大只是白烧 CPU。 */

    /** 单次 A* 的搜索半径硬上限（格）：再远的目标不寻路，退回直飞/站桩。 */
    private static final double SEARCH_RANGE = 16.0D;
    /** 单次 A* 最多访问多少节点（PathFinder 的第二个构造参数）：防止矿道里搜索爆炸卡死服务器。 */
    private static final int MAX_VISITED_NODES = 400;
    /** 单次寻路耗时超过这个值（毫秒）就断定"这条地形不适合 A*"，暂停寻路一段时间。 */
    private static final long SLOW_PATH_MS = 10L;
    /** 暂停多少 tick（10 秒） */
    private static final int SLOW_PATH_COOLDOWN = 200;
    /**
     * 两次 A* 之间的最短间隔（tick）：20 = 每秒最多一次。
     *
     * <p>用户明确要求"每秒算一次，没路径就停在那里"：A* 的超量计算是卡顿来源，
     * 所以这里不做任何高频重算。
     */
    private static final int MIN_REPATH_TICKS = 20;
    /**
     * 全服单 tick 最多跑几次 A*（所有龙共用）。一次就够：A* 本身可能十几毫秒，
     * 一秒一只龙已经能接受；多只龙排队，超预算的直接等下一秒。
     */
    private static final int MAX_SEARCHES_PER_TICK = 1;
    private static long searchBudgetTick = Long.MIN_VALUE;
    private static int searchBudgetUsed;

    private final DragonGolemEntity dragon;
    /** 体型余量评估器：整个生命周期复用（它自带节点缓存，每次 {@code findPath} 前会被 prepare 清空）。 */
    private final DragonFlyNodeEvaluator evaluator = new DragonFlyNodeEvaluator();
    /**
     * 放宽版评估器（不做体型检查）。
     *
     * <p>给"起点被自己判死"的重试用：龙贴着柱子/压着天花板时，它自己的位置在严格判定下
     * 就是 BLOCKED，`getStart()` 可能拿不到节点 → `findPath` 直接返回 null → noRoute →
     * 站桩不动、也不攻击。这时用放宽版再算一次，至少能先飞出来。
     */
    private final DragonFlyNodeEvaluator lenientEvaluator = new DragonFlyNodeEvaluator(false, 0.0D);
    /** 这个 tick 之前不再寻路（上一次寻路太慢的自保护）。 */
    private int pathBlockedUntil = Integer.MIN_VALUE;
    /** 下一次允许重试 A* 的 tick（失败后不要每 tick 重算，否则会吃满主线程）。 */
    private int nextPathTry;

    @Nullable
    private Path path;
    /** 当前这条路线通向哪里（判断"目标有没有动"）。 */
    @Nullable
    private Vec3 goal;
    /**
     * 直飞缓存：这一轮"身体盒 + 液体"都干净、不需要 A* 的目标。
     *
     * <p>它存在的意义是性能：开阔地里 90% 的时间都是直飞，如果每 tick 都去问
     * "要不要重算路线"，A* 就会白白吃掉主线程。命中它时 {@code path == null}，
     * 但 {@link #noRoute()} 为 false。
     */
    @Nullable
    private Vec3 directGoal;
    /** {@link #directGoal} 已经用了多少 tick（到 {@code pathRepathTicks} 就重新验证一次）。 */
    private int directAge;

    /** 当前追踪的目标点（诊断用；只含水平上有意义的坐标）。 */
    @Nullable
    public Vec3 goalPoint() {
        return this.goal;
    }
    /** 这一 tick 建议朝哪个点飞；null = 没有路线，调用方自己直奔目标。 */
    @Nullable
    private Vec3 steerPoint;
    /** {@link #steerPoint} 是航线节点（true）还是路线终点（false）；见 {@link #onRoute()}。 */
    private boolean steerOnRoute;
    /** 距离上次重算的 tick 数。 */
    private int age;
    /** 连续多少次重算都失败。 */
    private int failTicks;
    /** 当前目标处算出来的悬停高度（脚部 Y），只在重算路线时更新一次。 */
    private double steerGoalY;
    /** 上一 tick 拉直时一次跳过了几个节点（诊断用；0 = 没拉直）。 */
    private int smoothedNodes;

    /** 上一 tick 路线拉直跳过的节点数（诊断用）。 */
    public int smoothedNodes() {
        return this.smoothedNodes;
    }

    public DragonPathFollower(DragonGolemEntity dragon) {
        this.dragon = dragon;
    }

    /**
     * 这一 tick 该朝哪个点飞。
     *
     * <p><b>高度控制与寻路的接口：</b>{@code goal} 只需要给水平坐标，竖直坐标由
     * {@link DragonGolemEntity#hoverTargetYAt} 按<b>目的地自己的地面与天花板</b>算出来 ——
     * 于是 A* 是在"那儿合理的高度层"上搜索，而不是像以前那样"按龙当前的高度"平推，
     * 结果只能搜出一条贴着当前高度的走廊。
     *
     * @param goal  最终想去的地方（只用 x/z；y 会被目的地的悬停高度覆盖）
     * @param reach 到目标的"到达射程"（节点判定精度，和原版 {@code createPath(pos, reach)} 一致，一般 1~2）
     * @return 建议的转向点（含<b>航路点自己的 Y</b>，见 {@link DragonGolemEntity#flightVerticalTarget}）；
     *         <b>永远不会返回 null</b> —— 没有路线时就是目标点本身（退回旧行为）
     */
    public Vec3 steer(Vec3 goal, int reach) {
        if (this.dragon.level().isClientSide()) {
            return goal;
        }
        // ★ 幽灵模式（noPhysics）：它本来就能穿墙，任何"绕路"都是多余的。
        //   旧版即使幽灵也照样去问 A*、算不出路线就站桩 —— 表现就是"明明能穿墙却不会穿"。
        if (this.dragon.noPhysics) {
            this.reset();
            this.steerPoint = new Vec3(goal.x, this.dragon.getY(), goal.z);
            this.steerOnRoute = false;
            return this.steerPoint;
        }
        if (!DragonGolemConfig.pathEnabled()) {
            return goal;
        }
        // 直飞缓存命中：目标没怎么动 + 还没到重新验证的时间 → 继续直飞，不碰 A*
        if (this.directValid(goal)) {
            this.directAge++;
            this.steerPoint = new Vec3(goal.x, this.dragon.getY(), goal.z);
            this.steerOnRoute = false;
            return this.steerPoint;
        }
        // 目的地的悬停高度（地面 + 悬停高度，被天花板按身体高度夹住）——
        // 只在"该重算路线"的时候扫一次（一次扫描是 48 格地面 + 32 格天花板，别每 tick 做）
        if (this.needsRecompute(goal)) {
            this.steerGoalY = this.dragon.hoverTargetYAt(goal.x, goal.z);
            Vec3 withY = new Vec3(goal.x, this.steerGoalY, goal.z);
            // ★ 直飞优先：整条直线（当前高度）上身体都塞得下、且不沾液体 → 根本不跑 A*。
            //   开阔地/直走廊里这是绝大多数情况，也是"性能保护"的第一道闸门。
            if (this.directClear(goal)) {
                this.directGoal = goal;
                this.directAge = 0;
                this.path = null;
                this.goal = goal;
                this.failTicks = 0;
                this.age = 0;
                this.nextPathTry = 0;
                this.steerPoint = new Vec3(goal.x, this.dragon.getY(), goal.z);
                this.steerOnRoute = false;
                return this.steerPoint;
            }
            this.directGoal = null;
            this.recompute(goal, withY, reach);
        }
        this.age++;
        Vec3 withY = new Vec3(goal.x, this.steerGoalY, goal.z);
        this.advanceAlongPath(withY);
        return this.steerPoint != null ? this.steerPoint : withY;
    }

    /**
     * 这个目标"现在没有可走路线"。
     *
     * <p>判据只有两条：A* 返回了截断路线（{@code canReach == false}），或者上一次 A* 直接失败。
     * 这时 goal 会<b>原地停住</b>（用户要求"没路径就停在那里"），但每 tick 仍调用
     * {@link #steer} 做一次廉价检查；真正的 A* 重算被 {@link #nextPathTry} 限成每秒一次。
     */
    public boolean noRoute() {
        // 幽灵模式没有"够不着"这个概念：直线的尽头就是目标
        if (this.dragon.noPhysics) {
            return false;
        }
        if (this.directGoal != null) {
            return false;
        }
        if (this.path != null) {
            return unreachable(this.path);
        }
        return this.failTicks > 0;
    }

    /** 当前路线的节点数（诊断用；没路线时 0）。 */
    public int pathNodeCount() {
        return this.path == null ? 0 : this.path.getNodeCount();
    }

    /** 连续失败次数（诊断/日志用）。 */
    public int failTicks() {
        return this.failTicks;
    }

    /** 下一次允许 A* 重试的 tick（诊断/日志用）。 */
    public int nextPathTry() {
        return this.nextPathTry;
    }

    /** 当前路线给出的转向点（给 {@link DragonFlightAssist} 的避障用）；没有路线时 null。 */
    @Nullable
    public Vec3 steerPoint() {
        return this.steerPoint;
    }

    /**
     * 当前转向点是不是"航线上的节点"（true）还是"路线的终点目标"（false）。
     *
     * <p>这个标志就是<b>把竖直方向交给寻路</b>的开关：在航线上时，龙应该跟着航路点的高度
     * 爬升/下降（3D 跟随）；只有走完路线/没有路线时，才回到"悬停高度"那套。
     */
    public boolean onRoute() {
        return this.steerOnRoute;
    }

    /** 强制下一 tick 重算（撞墙时用：路线已经不可信了）。 */
    public void invalidate() {
        this.age = Integer.MAX_VALUE / 2;
        this.path = null;
        this.directGoal = null;
        this.nextPathTry = 0;
    }

    /** 目标达成/放弃时清干净，免得下次复用旧路线。 */
    public void reset() {
        this.path = null;
        this.goal = null;
        this.steerPoint = null;
        this.steerOnRoute = false;
        this.age = 0;
        this.failTicks = 0;
        this.directGoal = null;
        this.directAge = 0;
        this.nextPathTry = 0;
    }

    // ---- 内部 ----

    /**
     * 直飞缓存还有效吗：目标没怎么动 + 没到重新验证的时间。
     */
    private boolean directValid(Vec3 goal) {
        return this.directGoal != null
                && this.directAge < DragonGolemConfig.pathRepathTicks()
                && this.directGoal.distanceToSqr(goal) <= GOAL_TOLERANCE * GOAL_TOLERANCE;
    }

    /**
     * 从当前位置到目标的<b>直线</b>（按当前脚底高度）能不能整条飞过去。
     *
     * <p>判据是"把龙的身体盒搬到每个采样点"都塞得下、且不沾液体（水/岩浆）。
     * 命中就不跑 A* —— 这是打开寻路之后的主性能闸门，也是"开阔地不再抽风"的关键。
     * 采样间距 1 格：身体宽 2.6 格，1 格宽的小柱子不可能藏在两个采样点之间。
     */
    private boolean directClear(Vec3 goal) {
        Vec3 from = this.dragon.position();
        double dx = goal.x - from.x;
        double dz = goal.z - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-3D) {
            return true;
        }
        // 采样间距按体宽放大：大体型一采样就是几千个方块，1 格间距纯属浪费；
        // 半个体宽以内的小障碍不可能从体宽那么大的判定箱旁边漏过去。
        double step = Math.max(1.0D, Math.min(4.0D, this.dragon.getBbWidth() * 0.5D));
        int steps = Math.max(2, (int) Math.ceil(dist / step));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 probe = new Vec3(from.x + dx * t, from.y, from.z + dz * t);
            if (!DragonFlightAssist.canTravelThrough(this.dragon, probe,
                    Math.max(0.3D, DragonGolemConfig.pathLavaMargin()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 领取本 tick 的 A* 预算。
     *
     * <p>所有龙共用 {@link #MAX_SEARCHES_PER_TICK} 个名额；超了就等下一秒。
     * 服务器主线程只有一个，这样最多把寻路开销压在"每 tick 1 条路线"以内。
     */
    private static boolean takeSearchBudget(DragonGolemEntity dragon) {
        long now = dragon.level().getGameTime();
        if (searchBudgetTick != now) {
            searchBudgetTick = now;
            searchBudgetUsed = 0;
        }
        return ++searchBudgetUsed <= MAX_SEARCHES_PER_TICK;
    }

    private boolean needsRecompute(Vec3 goal) {
        if (this.path == null || this.path.isDone() || this.goal == null) {
            // ★ 失败/超预算后间隔 MIN_REPATH_TICKS 再试：不能因为 path==null 就每 tick 重算。
            //   旧版这正是"矿道里卡死服务器"的那条路径。
            return this.dragon.tickCount >= this.nextPathTry;
        }
        // 最短重算间隔：目标每 tick 都在变时（游走换点、战斗目标乱跳）不能每 tick 算一次 A*
        if (this.age < MIN_REPATH_TICKS) {
            return false;
        }
        if (this.goal.distanceToSqr(goal) > GOAL_TOLERANCE * GOAL_TOLERANCE) {
            return true;
        }
        return this.age >= DragonGolemConfig.pathRepathTicks();
    }

    private void recompute(Vec3 goal, Vec3 withY, int reach) {
        this.goal = goal;
        this.age = 0;
        // 上一次寻路太慢 / 本 tick 预算用完：这一秒不再碰 A*，原地停住。
        // 下一 tick goal 仍会调 steer()，但 nextPathTry 会把真正的重算挡到 1 秒后。
        if (this.dragon.tickCount < this.pathBlockedUntil || !takeSearchBudget(this.dragon)) {
            this.path = null;
            this.steerPoint = null;
            this.steerOnRoute = false;
            this.failTicks = 1;
            this.nextPathTry = this.dragon.tickCount + MIN_REPATH_TICKS;
            return;
        }
        Path found = this.computePath(withY, reach);
        if (DragonGolemConfig.flightLog()) {
            this.logRecompute(withY, reach, found);
        }
        if (found == null) {
            this.path = null;
            this.steerPoint = null;
            this.failTicks = 1;
            this.nextPathTry = this.dragon.tickCount + MIN_REPATH_TICKS;
            return;
        }
        this.failTicks = 0;
        this.path = found;
        this.steerPoint = null;
        // 即使找到路线，也至少等一秒再重算：path.isDone() 的短路线（1~2 个节点）
        // 否则会让下一 tick 又跑一次 A*。
        this.nextPathTry = this.dragon.tickCount + MIN_REPATH_TICKS;
    }

    /** 诊断：把这次寻路的输入与结果打出来（{@code debug.flightLog}）。 */
    private void logRecompute(Vec3 goalWithY, int reach, @Nullable Path found) {
        if (found == null) {
            Dragon_golems.LOGGER.info("[path] recompute goal=({}, {}) goalY={} reach={} → null（连搜索都没开始）",
                    fmt(this.goal == null ? goalWithY.x : this.goal.x),
                    fmt(this.goal == null ? goalWithY.z : this.goal.z), fmt(goalWithY.y), reach);
            return;
        }
        // 路线前几个节点：直接看出它是"平飞"还是"先爬升/绕行"
        StringBuilder nodes = new StringBuilder();
        int shown = Math.min(6, found.getNodeCount());
        for (int i = 0; i < shown; i++) {
            Node n = found.getNode(i);
            nodes.append('(').append(n.x).append(',').append(n.y).append(',').append(n.z).append(')');
        }
        Dragon_golems.LOGGER.info("[path] recompute goal=({}, {}) goalY={} reach={} → nodes={} canReach={} 前{}个节点={}",
                fmt(this.goal == null ? goalWithY.x : this.goal.x),
                fmt(this.goal == null ? goalWithY.z : this.goal.z),
                fmt(goalWithY.y), reach, found.getNodeCount(), found.canReach(), shown, nodes);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /**
     * 这条路线"到得了吗"。
     *
     * <p><b>关键事实：原版 A* 永远不会返回"没有路"。</b>{@code PathFinder} 找不到通路时，
     * 会把"离目标最近的那个节点"拼成一条 {@code canReach() == false} 的路线返回；
     * {@code createPath(...)} 返回 null 只意味着"这次连搜索都没开始"（目标集为空 /
     * 在最小建筑高度之下 / 当前位置不允许寻路）。所以"这个目标够不着"只能靠
     * {@link Path#canReach()} 判定 —— 本家傀儡装配从来没读过它，这也是"隔着墙对着目标拱"
     * 能在本家身上出现的原因之一。
     */
    private static boolean unreachable(@Nullable Path path) {
        return path != null && !path.canReach();
    }

    /**
     * 自己算一条路线：参数和 {@code PathNavigation.createPath(BlockPos, reach)} 同一套，
     * 唯一的区别是<b>不写回 {@code PathNavigation} 的任何状态</b>，并换成带体型余量的评估器。
     *
     * <p><b>搜索半径必须自己按目标距离放大。</b>{@code PathFinder} 的 {@code maxRange} 是
     * "从<b>起点</b>算的搜索半径"（原版用的是实体的跟随距离，龙是 35 格），
     * 而游走半径跟着速度倍率能到 72 格 —— 照抄 35 的话，超过 35 格的目标一律搜不到，
     * A* 会退化成"截断路线"，于是每个远点都被判成够不着、疯狂换点。
     * 这里取 {@code max(跟随距离, 到目标的距离 + 8)}，并封顶 {@link #MAX_RANGE}。
     */
    @Nullable
    private Path computePath(Vec3 goal, int reach) {
        long started = System.nanoTime();
        try {
            BlockPos start = this.dragon.blockPosition();
            BlockPos target = BlockPos.containing(goal);
            double follow = this.dragon.getAttributeValue(Attributes.FOLLOW_RANGE);
            double want = Math.sqrt(start.distSqr(target)) + 8.0D;
            float maxRange = (float) Math.min(SEARCH_RANGE, Math.max(follow, want));
            int offset = Mth.floor(Math.min(maxRange, SEARCH_RANGE) + 4.0D);
            PathNavigationRegion region = new PathNavigationRegion(this.dragon.level(),
                    start.offset(-offset, -offset, -offset), start.offset(offset, offset, offset));
            // ★ 第二个构造参数是"最多访问多少节点"，不是搜索半径！
            PathFinder finder = new PathFinder(this.evaluator, MAX_VISITED_NODES);
            Path found = finder.findPath(region, this.dragon, ImmutableSet.of(target), maxRange, reach, 1.0F);
            // ★ 严格判定连起点都算 BLOCKED 时（贴着柱子/顶着天花板），用放宽版再算一次。
            //   不做这一步的话，"当前位置不算可通行"会直接变成 noRoute → 站桩不攻击。
            if (found == null) {
                PathFinder relaxed = new PathFinder(this.lenientEvaluator, MAX_VISITED_NODES / 2);
                found = relaxed.findPath(region, this.dragon, ImmutableSet.of(target), maxRange, reach, 1.0F);
                if (found != null && DragonGolemConfig.pathLog()) {
                    Dragon_golems.LOGGER.info("[path] 严格评估无起点 → 放宽版重新找到 {} 个节点", found.getNodeCount());
                }
            }
            // ★ 耗时自保护：一次寻路超过 SLOW_PATH_MS 就说明"这块地形不适合 A*"，
            //   这条龙接下来一段时间直接降级为直飞，绝不让第二次再卡住服务器。
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            if (DragonGolemConfig.pathLog()) {
                Dragon_golems.LOGGER.info("[path] A* {} nodes={} canReach={} {}ms",
                        found == null ? "null" : "ok",
                        found == null ? 0 : found.getNodeCount(),
                        found != null && found.canReach(), elapsedMs);
            }
            if (elapsedMs > SLOW_PATH_MS) {
                this.pathBlockedUntil = this.dragon.tickCount + SLOW_PATH_COOLDOWN;
                if (DragonGolemConfig.pathLog()) {
                    Dragon_golems.LOGGER.warn("[path] 寻路过慢 {}ms → 降级直飞 {} tick",
                            elapsedMs, SLOW_PATH_COOLDOWN);
                }
            }
            return found;
        } catch (Throwable t) {
            // 寻路失败（区块没加载、极端数值…）不该把 AI 弄崩：这一轮按"没有路线"处理
            return null;
        }
    }

    /**
     * 推进到"还没走到的第一个节点"，把它作为转向点；并做<b>路线拉直</b>。
     *
     * <p><b>★ 转向点带上航路点自己的 Y。</b>这是"高度控制与寻路统合"的另一半：
     * 以前这里写死 {@code goal.y}（= 目标的悬停高度），航线的竖向信息被整个丢掉，
     * 于是"翻过一道梁 / 从低房间爬出去"这类路线<b>只被当成水平走向</b>，
     * 龙照样按自己的悬停高度平飞，撞了墙才靠脱困乱挤。
     * 现在 Y 一起给出去，由 {@link DragonGolemEntity#flightVerticalTarget} 决定要不要跟
     * （死区 {@code pathfinding.verticalDeadband} 之外的才跟，避免巡航时一直点头）。
     *
     * <p><b>★ 路线拉直（string-pulling）：</b>A* 的节点是逐格八叉/二十六邻域搜出来的，
     * 天然是锯齿；照着"下一个节点"飞会一直小幅修正方向（抖），而且容易贴着转角蹭过去。
     * 现在从当前节点往后看 {@code pathfinding.smoothingLookahead} 个节点，
     * 取"直线可见（方块射线不被挡）的最远那个"当转向点 —— 这就是经典的路径拉直，
     * 路线更直、转向更少、离墙也更远。被挡就立刻停（后面的更不可能可见）。
     */
    private void advanceAlongPath(Vec3 goal) {
        if (this.path == null) {
            this.steerPoint = null;
            this.steerOnRoute = false;
            return;
        }
        double reach = this.nodeReach();
        while (!this.path.isDone()) {
            Node node = this.path.getNextNode();
            if (node == null) {
                break;
            }
            double dx = node.x + 0.5D - this.dragon.getX();
            double dy = node.y + 0.5D - this.dragon.getY();
            double dz = node.z + 0.5D - this.dragon.getZ();
            if (dx * dx + dy * dy + dz * dz > reach * reach) {
                break;
            }
            this.path.advance();
        }
        if (!this.path.isDone()) {
            Node aim = this.smoothedAim();
            if (aim != null) {
                // 节点的 Y 就是"脚能到的那个方块"（评估器也是按脚底采样的），不给 +0.5。
                // ★ steerOnRoute = true：竖直方向跟着这个节点走（3D 跟随），不再由悬停逻辑接管。
                this.steerPoint = new Vec3(aim.x + 0.5D, aim.y, aim.z + 0.5D);
                this.steerOnRoute = true;
                return;
            }
        }
        // 路线走完：能到达就直奔目标；是"截断路线"（canReach == false）就停在最后一个节点 ——
        // 那个节点是 A* 能找到的、离目标最近的可达点，到了就悬停等着（goal 会按 noRoute() 换目标），
        // 而不是继续对着墙拱。
        if (this.path.canReach()) {
            this.steerPoint = goal;
            this.steerOnRoute = false;
            return;
        }
        Node end = this.path.getEndNode();
        this.steerPoint = end != null
                ? new Vec3(end.x + 0.5D, end.y, end.z + 0.5D)
                : goal;
        this.steerOnRoute = false;
    }

    /**
     * 路线拉直：从当前节点往后找"直线可见的最远节点"，并把中间的节点直接跳过。
     *
     * <p>可见性用<b>方块射线</b>（{@code level.clip}，不含液体）判断 —— 和
     * {@link DragonGolemEntity#hasClearShotTo} 是同一套判据。样本上限
     * {@code pathfinding.smoothingLookahead}（0 = 关闭拉直）。
     *
     * @return 拉直后的转向节点；关闭拉直或没有更远的可见节点时返回当前节点
     */
    @Nullable
    private Node smoothedAim() {
        int lookahead = DragonGolemConfig.pathSmoothingLookahead();
        Node current = this.path.getNextNode();
        if (current == null || !DragonGolemConfig.pathEnabled()) {
            return current;
        }
        if (lookahead <= 0) {
            return current;
        }
        int start = this.path.getNextNodeIndex();
        int limit = Math.min(start + lookahead, this.path.getNodeCount() - 1);
        Node best = current;
        int bestIndex = start;
        for (int i = start + 1; i <= limit; i++) {
            Node candidate = this.path.getNode(i);
            if (candidate == null) {
                break;
            }
            if (!this.clearLineTo(candidate)) {
                break;
            }
            best = candidate;
            bestIndex = i;
        }
        if (bestIndex > start) {
            // 把中间的节点标成已走：下一 tick 不用再从旧节点开始扫
            this.path.setNextNodeIndex(bestIndex);
            this.smoothedNodes = bestIndex - start;
        } else {
            this.smoothedNodes = 0;
        }
        return best;
    }

    /**
     * 从龙的当前位置到某个节点，<b>整条龙</b>能不能直线飞过去。
     *
     * <p>以前这里是一根从身体中心到节点的射线（{@code level.clip}）——
     * 射线能穿过 1 格缝，可 2.6 格宽的龙穿不过去：拉直之后航线看起来"通"，
     * 实际一飞就撞柱子（真机反馈："判定自己能飞过去的地方其实进不去"）。
     *
     * <p>现在沿线段每 0.5 格取一个采样点，把<b>龙的判定箱</b>搬到那里逐点检查
     * （{@link DragonFlightAssist#hasRoomFor}）——和碰撞解算同一个口径。
     * 只在"重算路线 + 拉直"时调用（每 20 tick 一次），采样量可以接受。
     */
    private boolean clearLineTo(Node node) {
        Vec3 from = this.dragon.position();
        Vec3 to = new Vec3(node.x + 0.5D, node.y, node.z + 0.5D);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // 和 directClear 同理：按体宽放大采样间距，避免泰坦体型把拉直变成性能黑洞
        double step = Math.max(0.5D, Math.min(2.0D, this.dragon.getBbWidth() * 0.25D));
        int steps = Math.max(2, (int) Math.ceil(dist / step));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 probe = new Vec3(from.x + dx * t, from.y + dy * t, from.z + dz * t);
            // ★ 拉直也必须避开液体：碰撞箱为空不代表模型不会蹭进岩浆/水面
            if (!DragonFlightAssist.canTravelThrough(this.dragon, probe,
                    Math.max(0.3D, DragonGolemConfig.pathLavaMargin()))) {
                return false;
            }
        }
        return true;
    }

    /** 切节点的判定半径：和 {@code maxDistanceToWaypoint} 一个思路，按体型放宽。 */
    private double nodeReach() {
        // ★ 别按"半个体宽"提前切点：bbWidth 2.6 时那是 1.3 格，会在拐角处提前切弯、
        //   拐角内侧就是柱子（真机"不绕柱子、贴贴蹭蹭"的直接原因）。
        //   取 1/4 体宽（最小 0.6）——只影响"到达判定"，不影响寻路本身。
        return Math.max(0.6D, this.dragon.getBbWidth() * 0.25D);
    }
}
