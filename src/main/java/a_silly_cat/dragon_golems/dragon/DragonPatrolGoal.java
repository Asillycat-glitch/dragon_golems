package a_silly_cat.dragon_golems.dragon;

import dev.xkmc.modulargolems.content.capability.PathConfig;
import dev.xkmc.modulargolems.content.entity.mode.GolemModes;
import dev.xkmc.modulargolems.content.item.card.PathRecordCard;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

/**
 * 龙的"飞行巡逻"：让 ROUTE 模式真的会沿着 {@code route} 槽里的巡逻手册飞一圈。
 *
 * <h2>为什么不用本家的 ROUTE</h2>
 * 本家的数据链其实已经通了：
 * <ul>
 *   <li>{@link PathConfig#getPath} 会先读 {@code getRouteSlot()}，里面是
 *       {@link PathRecordCard} 就直接用物品里记录的点；</li>
 *   <li>{@code getTargetPos()} 在 {@code GolemModes.ROUTE} 下返回 {@code path[stage]}。</li>
 * </ul>
 * 但"腿"是断的：
 * <ol>
 *   <li>推进阶段的 {@code PatrolMode.tick()} 只被 {@code FollowOwnerGoal} 调用，
 *       而我们的 {@code stripNativeGoals()} 把这个 goal 摘了；</li>
 *   <li>就算把它放回去，{@code PatrolMode.tick} 开头还要求"在水里或在地上"，
 *       飞在天上的龙永远不满足。</li>
 * </ol>
 * 所以本类自己飞、自己推进阶段。移动仍然走 {@code setDiveVelocity} +
 * {@code DragonFlightAssist.avoidance}（和 {@link DragonIdleGoal} 同一条管线），
 * <b>不碰 {@code PathNavigation}</b>——那条路按三维距离判定"是否到达"，
 * 把目标 Y 抬高以后会永远差几格、卡死在第一个点上（见 {@code DragonGolemEntity.aiStep} 的警告）。
 *
 * <h2>点在地上、龙在天上</h2>
 * 玩家用巡逻手册右键记录的点通常是地面方块。这里统一做一次"抬高"：
 * {@link #raiseToFlightLevel(BlockPos)} 把目标 Y 抬到
 * {@code 点.y + 抬高量 × 体型}，再用 {@code localFloorY()}/{@code ceilingFeetLimit()}
 * 夹在地板和天花板之间（洞里/矿道里不会为了抬高而顶穿岩层）。
 *
 * <h2>和战斗的关系</h2>
 * 默认"打架优先"：只要 {@code dragonCurrentTarget()} 不为空，本 goal 就停；
 * 战斗结束（目标清空）再回到<b>原来那个点</b>继续，不会因为打了一架就跳点。
 */
public class DragonPatrolGoal extends Goal {

    /** 读到地面点时，至少抬高这么多格（再按体型放大）。 */
    private static final double RAISE_BASE = 8.0D;
    /** 体型每大 1 倍，再多抬这么多格（泰坦体型不会被抬得过近地面）。 */
    private static final double RAISE_PER_SCALE = 4.0D;
    /** 离地最小净空：点在地下/贴地时也不低于这个高度。 */
    private static final double MIN_CLEARANCE = 4.0D;
    /** 到点的水平判定半径（再乘体型）。 */
    private static final double ARRIVE_RADIUS = 3.5D;
    /** 到点的垂直判定容差（格）。 */
    private static final double ARRIVE_VERTICAL = 3.0D;
    /** 到点后悬停多少 tick 再走去下一个点。 */
    private static final int DWELL_TICKS = 20;
    /** 单个点最多追多少 tick，超时就跳下一个（防卡墙/卡树/被地形挡住）。 */
    private static final int STAGE_TIMEOUT = 400;
    /** 路径缓存刷新间隔（tick）。手册本身也会被换/改，所以还要比对物品。 */
    private static final int PATH_REFRESH = 20;

    /** 巡航速度上限（格/tick，还会乘 {@code flightSpeedFactor()}）。 */
    private static final double CRUISE_SPEED = 0.55D;
    /** 最低速度，保证不会"快到点就停住不走了"。 */
    private static final double MIN_SPEED = 0.12D;
    /** 水平接近速度：距离 × 这个系数，再夹在上面两档之间。 */
    private static final double HORIZONTAL_GAIN = 0.06D;
    /** 竖直接近速度。 */
    private static final double VERTICAL_GAIN = 0.09D;
    private static final double VERTICAL_MAX = 0.35D;

    private final DragonGolemEntity dragon;

    private List<PathRecordCard.Pos> cachedPath = List.of();
    private ItemStack cachedStack = ItemStack.EMPTY;
    private int pathRefresh;

    private int stageTicks;
    private int dwellTicks;

    public DragonPatrolGoal(DragonGolemEntity dragon) {
        this.dragon = dragon;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.ready() && !this.path().isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return this.ready() && !this.path().isEmpty();
    }

    /** 三种"该不该由巡逻接管"的共同条件。 */
    private boolean ready() {
        if (this.dragon.getMode() != GolemModes.ROUTE) {
            return false;
        }
        if (this.dragon.isFreeWander()) {
            return false;
        }
        if (!this.dragon.isMovable() || this.dragon.isInSittingPose() || this.dragon.isLeashed()) {
            return false;
        }
        // 有人开 / 有傀儡乘客：交给玩家或原版管线，AI 不抢方向。
        // 背傀儡的"货舱模式"本来就要求原地悬停（见 DragonGolemEntity.tickGolemPassengerMode），
        // 巡逻不能把它带跑。
        if (this.dragon.getControllingPassenger() != null || this.dragon.isGolemCargoMode()) {
            return false;
        }
        // 打架优先：有目标就让战斗 goal（优先级 2/3）接管，打完再回来
        return this.dragon.dragonCurrentTarget() == null;
    }

    @Override
    public void start() {
        this.stageTicks = 0;
        this.dwellTicks = 0;
    }

    @Override
    public void tick() {
        List<PathRecordCard.Pos> path = this.path();
        if (path.isEmpty()) {
            return;
        }
        int stage = Math.floorMod(this.dragon.getPatrolStage(), path.size());
        Vec3 target = this.raiseToFlightLevel(path.get(stage).pos(), path.get(stage).level());
        if (target == null) {
            // 维度不对：这个点不属于这里，直接跳下一个
            this.dragon.advancePatrolStage();
            this.stageTicks = 0;
            return;
        }
        this.stageTicks++;

        double dh = Math.sqrt((target.x - this.dragon.getX()) * (target.x - this.dragon.getX())
                + (target.z - this.dragon.getZ()) * (target.z - this.dragon.getZ()));
        boolean arrived = dh <= ARRIVE_RADIUS * this.dragon.bodyScale()
                && Math.abs(target.y - this.dragon.getY()) <= ARRIVE_VERTICAL;

        if (arrived) {
            if (++this.dwellTicks >= DWELL_TICKS) {
                this.dragon.advancePatrolStage();
                this.stageTicks = 0;
                this.dwellTicks = 0;
            }
            // 到点悬停：只保留高度保持，水平速度归零
            double vy = Mth.clamp((target.y - this.dragon.getY()) * VERTICAL_GAIN,
                    -VERTICAL_MAX, VERTICAL_MAX);
            this.dragon.setDiveVelocity(new Vec3(0.0D, vy, 0.0D));
            return;
        }
        this.dwellTicks = 0;

        if (this.stageTicks > STAGE_TIMEOUT) {
            // 追不到（卡在墙后 / 点在封闭空间）：跳过这个点，别让龙原地顶墙
            this.dragon.advancePatrolStage();
            this.stageTicks = 0;
            return;
        }

        // ---- 水平速度 ----
        double factor = this.dragon.flightSpeedFactor();
        double vx = 0.0D;
        double vz = 0.0D;
        if (dh > 0.05D) {
            double speed = Mth.clamp(dh * HORIZONTAL_GAIN, MIN_SPEED * factor, CRUISE_SPEED * factor);
            vx = (target.x - this.dragon.getX()) / dh * speed;
            vz = (target.z - this.dragon.getZ()) / dh * speed;
        }

        // ---- 竖直速度：朝抬高后的目标高度，同时被地板/天花板夹住 ----
        double wantY = Mth.clamp(target.y,
                this.dragon.localFloorY() + MIN_CLEARANCE,
                this.dragon.ceilingFeetLimit() - 1.0D);
        double vy = Mth.clamp((wantY - this.dragon.getY()) * VERTICAL_GAIN,
                -VERTICAL_MAX, VERTICAL_MAX);

        // 避障和待机/战斗走同一条管线：撞墙时不要只会往上顶
        Vec3 adjusted = this.dragon.flightAssist().avoidance(this.dragon, new Vec3(vx, vy, vz));
        this.dragon.setDiveVelocity(adjusted);
    }

    @Override
    public void stop() {
        this.dragon.setDiveVelocity(null);
        this.stageTicks = 0;
        this.dwellTicks = 0;
    }

    /**
     * 把"记录在地面上的点"换算成龙的实际飞行目标。
     *
     * <p>公式：{@code y = 点.y + RAISE_BASE + RAISE_PER_SCALE × (体型 - 1)}，
     * 再夹到 {@code [localFloorY + MIN_CLEARANCE, ceilingFeetLimit - 1]}。
     * 夹天花板是必须的：矿道/洞穴里直接抬 8 格会顶穿岩层，而 {@code ceilingFeetLimit()}
     * 正是我们为龙算好的"头顶还有多少空间"。
     *
     * @return 目标点；维度不匹配时返回 {@code null}
     */
    private @Nullable Vec3 raiseToFlightLevel(BlockPos pos, ResourceLocation pointDim) {
        ResourceLocation here = this.dragon.level().dimension().location();
        if (!here.equals(pointDim)) {
            return null;
        }
        double scale = Math.max(1.0D, this.dragon.bodyScale());
        double y = pos.getY() + RAISE_BASE + RAISE_PER_SCALE * (scale - 1.0D);
        y = Math.max(y, this.dragon.localFloorY() + MIN_CLEARANCE);
        double ceiling = this.dragon.ceilingFeetLimit();
        if (ceiling > this.dragon.localFloorY()) {
            y = Math.min(y, ceiling - 1.0D);
        }
        return new Vec3(pos.getX() + 0.5D, y, pos.getZ() + 0.5D);
    }

    /**
     * 巡逻点的来源。
     *
     * <p>仍然走本家的 {@link PathConfig#getPath}：它会优先读 {@code getRouteSlot()}
     * 里的巡逻手册，也顺便兼容"配置卡里存的路线"和敌对傀儡的阵营路线。
     * 手册的 NBT 每次读都要解析，所以这里按 {@link #PATH_REFRESH} tick + 物品变化做缓存。
     */
    private List<PathRecordCard.Pos> path() {
        ItemStack stack = this.dragon.getRouteSlot().getItem();
        if (++this.pathRefresh >= PATH_REFRESH || !ItemStack.matches(stack, this.cachedStack)) {
            this.pathRefresh = 0;
            this.cachedStack = stack.copy();
            List<PathRecordCard.Pos> path = PathConfig.getPath(this.dragon);
            this.cachedPath = path == null ? List.of() : path;
        }
        return this.cachedPath;
    }
}
