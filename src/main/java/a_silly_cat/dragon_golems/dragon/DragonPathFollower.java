package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
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
 * <p>每只龙一个实例，状态只在自己身上（服务端跑 AI，客户端根本不会调用它）。
 */
public final class DragonPathFollower {

    /** 目标点移动超过这个距离就立刻重算（格）。 */
    private static final double GOAL_TOLERANCE = 2.0D;
    /** 走完这个距离内的节点就算"到过"，切下一个（格，按体型放宽：见 {@link #nodeReach()}）。 */
    private static final double MIN_NODE_REACH = 1.5D;
    /** A* 搜索半径的上限（格）：游走半径 72 + 余量就够用，再大只是白烧 CPU。 */
    private static final double MAX_RANGE = 96.0D;
    /** 两次 A* 之间的最短间隔（tick）：目标每 tick 都在变时（换点、战斗目标乱跳）的限流。 */
    private static final int MIN_REPATH_TICKS = 4;

    private final DragonGolemEntity dragon;
    /** 体型余量评估器：整个生命周期复用（它自带节点缓存，每次 {@code findPath} 前会被 prepare 清空）。 */
    private final DragonFlyNodeEvaluator evaluator = new DragonFlyNodeEvaluator();

    @Nullable
    private Path path;
    /** 当前这条路线通向哪里（判断"目标有没有动"）。 */
    @Nullable
    private Vec3 goal;
    /** 这一 tick 建议朝哪个点飞；null = 没有路线，调用方自己直奔目标。 */
    @Nullable
    private Vec3 steerPoint;
    /** 距离上次重算的 tick 数。 */
    private int age;
    /** 连续多少次重算都失败。 */
    private int failTicks;
    /** 当前目标处算出来的悬停高度（脚部 Y），只在重算路线时更新一次。 */
    private double steerGoalY;

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
        if (!DragonGolemConfig.pathEnabled() || this.dragon.level().isClientSide()) {
            return goal;
        }
        // 目的地的悬停高度（地面 + 悬停高度，被天花板按身体高度夹住）——
        // 只在"该重算路线"的时候扫一次（一次扫描是 48 格地面 + 32 格天花板，别每 tick 做）
        if (this.needsRecompute(goal)) {
            this.steerGoalY = this.dragon.hoverTargetYAt(goal.x, goal.z);
            this.recompute(goal, new Vec3(goal.x, this.steerGoalY, goal.z), reach);
        }
        this.age++;
        Vec3 withY = new Vec3(goal.x, this.steerGoalY, goal.z);
        this.advanceAlongPath(withY);
        return this.steerPoint != null ? this.steerPoint : withY;
    }

    /** 这个目标"够不着"：要么连路线都算不出来，要么算出来的路线是"截断"的（{@code canReach == false}）。 */
    public boolean noRoute() {
        return this.failTicks >= DragonGolemConfig.pathGiveUpTicks() || unreachable(this.path);
    }

    /** 当前路线给出的转向点（给 {@link DragonFlightAssist} 的避障用）；没有路线时 null。 */
    @Nullable
    public Vec3 steerPoint() {
        return this.steerPoint;
    }

    /** 强制下一 tick 重算（撞墙时用：路线已经不可信了）。 */
    public void invalidate() {
        this.age = Integer.MAX_VALUE / 2;
        this.path = null;
    }

    /** 目标达成/放弃时清干净，免得下次复用旧路线。 */
    public void reset() {
        this.path = null;
        this.goal = null;
        this.steerPoint = null;
        this.age = 0;
        this.failTicks = 0;
    }

    // ---- 内部 ----

    private boolean needsRecompute(Vec3 goal) {
        if (this.path == null || this.path.isDone() || this.goal == null) {
            return true;
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
        Path found = this.computePath(withY, reach);
        if (found == null) {
            this.path = null;
            this.steerPoint = null;
            this.failTicks++;
            return;
        }
        this.failTicks = 0;
        this.path = found;
        this.steerPoint = null;
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
        try {
            BlockPos start = this.dragon.blockPosition();
            BlockPos target = BlockPos.containing(goal);
            double follow = this.dragon.getAttributeValue(Attributes.FOLLOW_RANGE);
            double want = Math.sqrt(start.distSqr(target)) + 8.0D;
            float maxRange = (float) Mth.clamp(Math.max(follow, want), follow, MAX_RANGE);
            int offset = Mth.floor(maxRange + 8.0D);
            PathNavigationRegion region = new PathNavigationRegion(this.dragon.level(),
                    start.offset(-offset, -offset, -offset), start.offset(offset, offset, offset));
            PathFinder finder = new PathFinder(this.evaluator, offset);
            return finder.findPath(region, this.dragon, ImmutableSet.of(target), maxRange, reach, 1.0F);
        } catch (Throwable t) {
            // 寻路失败（区块没加载、极端数值…）不该把 AI 弄崩：这一轮按"没有路线"处理
            return null;
        }
    }

    /**
     * 推进到"还没走到的第一个节点"，把它作为转向点。
     *
     * <p><b>★ 转向点带上航路点自己的 Y。</b>这是"高度控制与寻路统合"的另一半：
     * 以前这里写死 {@code goal.y}（= 目标的悬停高度），航线的竖向信息被整个丢掉，
     * 于是"翻过一道梁 / 从低房间爬出去"这类路线<b>只被当成水平走向</b>，
     * 龙照样按自己的悬停高度平飞，撞了墙才靠脱困乱挤。
     * 现在 Y 一起给出去，由 {@link DragonGolemEntity#flightVerticalTarget} 决定要不要跟
     * （死区 {@code pathfinding.verticalDeadband} 之外的才跟，避免巡航时一直点头）。
     */
    private void advanceAlongPath(Vec3 goal) {
        if (this.path == null) {
            this.steerPoint = null;
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
                // 节点的 Y 就是"脚能到的那个方块"（评估器也是按脚底采样的），不给 +0.5
                this.steerPoint = new Vec3(node.x + 0.5D, node.y, node.z + 0.5D);
                return;
            }
            this.path.advance();
        }
        // 路线走完：能到达就直奔目标；是"截断路线"（canReach == false）就停在最后一个节点 ——
        // 那个节点是 A* 能找到的、离目标最近的可达点，到了就悬停等着（goal 会按 noRoute() 换目标），
        // 而不是继续对着墙拱。
        if (this.path.canReach()) {
            this.steerPoint = goal;
            return;
        }
        Node end = this.path.getEndNode();
        this.steerPoint = end != null
                ? new Vec3(end.x + 0.5D, end.y, end.z + 0.5D)
                : goal;
    }

    /** 切节点的判定半径：和 {@code maxDistanceToWaypoint} 一个思路，按体型放宽。 */
    private double nodeReach() {
        return Math.max(MIN_NODE_REACH, this.dragon.getBbWidth() * 0.5D);
    }
}
