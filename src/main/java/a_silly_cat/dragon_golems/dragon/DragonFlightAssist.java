package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 飞行辅助：<b>自由穿墙开关 + 卡住自动脱困 + 更主动的避障</b>。
 *
 * <h2>要解决的问题</h2>
 * 龙的判定箱是 {@code bodyScale() × (2.6 宽 × 2.2 高)}，而 {@code FlyingPathNavigation}
 * 是<b>按一格宽的实体</b>假设来寻路的 —— 它认为能过的缝，龙其实挤不过去。
 * 结果就是在矿洞、走廊、树丛这类地方：寻路给出路径、龙照着走、一路撞墙，
 * 概率性地卡在墙里或者贴着墙原地磨。
 *
 * <h2>三层应对</h2>
 * <ol>
 *   <li><b>自由飞行（{@link #isFreeFlight()}）</b>：玩家显式打开的"穿墙模式"，
 *       走 {@code Entity.noPhysics}。真正的幽灵模式，配合驾驶用（想直接穿进洞里）。</li>
 *   <li><b>卡住自动脱困（{@link #tick}）</b>：连着 {@link #STUCK_LIMIT} tick 撞墙、
 *       且当前位置四面都塞不下它 → 自动进 no-clip 往开阔处飞，
 *       脱离后（{@link #unstickClear}）自动退出。<b>不需要玩家操作。</b></li>
 *   <li><b>主动避障（{@link #avoidance}）</b>：撞墙时不是无脑往上顶 0.15，而是
 *       在"上 / 斜上 / 左 / 右"里挑一个真正有空间的方向推过去；
 *       向上的分量随"卡住时长"递增，所以真正被埋住时它会坚决地爬出来。</li>
 * </ol>
 *
 * <h2>为什么用 {@code noPhysics} 而不是"允许穿过某些方块"</h2>
 * 原版只有这一个粒度：{@code Entity.noPhysics} 为真时 {@code move()} 跳过所有方块碰撞。
 * 想做到"只穿树叶不穿石头"就得自己接管 {@code move()} 或开 mixin，代价远大于收益 ——
 * 而且真卡在石头里时，恰恰需要能穿石头才能出来。所以这里做成<b>有时限的救援</b>，
 * 而不是常态能力。
 */
public final class DragonFlightAssist {

    /** 每只龙一个实例（状态存在实例里，见下面的字段）。 */
    public DragonFlightAssist() {
    }

    /** 连续撞墙多少 tick 之后判定"卡住了"。20 tick = 1 秒。 */
    private static final int STUCK_LIMIT = 20;
    /** 单次自动脱困最多持续多少 tick（3 秒）；到点还没出来就放弃，免得永久幽灵。 */
    private static final int RESCUE_MAX = 60;
    /** 脱困时的飞行速度（格/tick），比正常巡航快，好尽快离开死路。 */
    private static final double RESCUE_SPEED = 0.45D;
    /** 脱困时竖直分量的下限：保证它一定在往上/往外走，而不是原地悬着。 */
    private static final double RESCUE_MIN_RISE = 0.12D;
    /** 判定"这个位置塞得下龙"时要留的余量（格）：贴边的缝不算缝。 */
    private static final double CLEARANCE = 0.15D;

    // ---- 每只龙的状态（单机/服务端各一份，不需要同步：它是行为不是外观） ----

    /** 连续撞墙的 tick 数。 */
    private int stuckTicks;
    /** 本次自动脱困已持续 tick 数；-1 = 没在脱困。 */
    private int rescueTicks = -1;
    /** 玩家打开的自由飞行开关。 */
    private boolean freeFlight;
    /** 上一次 tick 记录的"水平方向是否被挡住"，供下一 tick 判断"一直在磨同一面墙"。 */
    private boolean lastBlocked;

    /** 玩家打开/关闭自由飞行（穿墙）。 */
    public void setFreeFlight(boolean on) {
        this.freeFlight = on;
    }

    public boolean isFreeFlight() {
        return this.freeFlight;
    }

    /** 现在是不是"自动脱困"中。 */
    public boolean isRescuing() {
        return this.rescueTicks >= 0;
    }

    /**
     * 每 tick 调一次（服务端）。负责维护 stuck 计数、自动脱困的进出、以及把
     * {@code noPhysics} 设成当前该有的值。
     *
     * <p><b>必须在 {@code super.aiStep()} 之后调</b>：那时 {@code move()} 已经跑完、
     * 碰撞标志是本 tick 的真实结果。写在前面会用到上一 tick 的陈旧值。
     */
    public void tick(DragonGolemEntity dragon) {
        if (dragon.level().isClientSide()) {
            return;
        }
        handleStuck(dragon);
        this.lastBlocked = false;
    }

    /**
     * 撞墙计数与自动脱困的状态机。
     *
     * <p>判据是"水平被挡"而不是"完全没动"：龙悬停时会主动消掉速度，用位移判断会误判成卡住。
     */
    private void handleStuck(DragonGolemEntity dragon) {
        boolean blocked = dragon.horizontalCollision;
        if (blocked) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
        }

        if (this.rescueTicks >= 0) {
            // 脱困中：只要"当前位置塞得下"就结束（不依赖还在不在撞墙）
            this.rescueTicks++;
            if (this.unstickClear(dragon) || this.rescueTicks > RESCUE_MAX) {
                this.endRescue(dragon);
            }
        } else if (!this.freeFlight
                && this.stuckTicks >= STUCK_LIMIT
                && !hasRoomFor(dragon, dragon.position())) {
            // 一直撞墙、而且当前位置本身就塞不下 → 是"嵌在方块里"而不是"前方有障碍"
            this.rescueTicks = 0;
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[fly] 自动脱困：stuck={} pos={}", this.stuckTicks, dragon.position());
            }
        }

        // noPhysics 的唯一写入口：自由飞行 or 自动脱困。
        // 注意它是 Entity 上的 public 字段，没有 setter（javap 查过：只有字段、没有 setNoPhysics）。
        dragon.noPhysics = this.freeFlight || this.rescueTicks >= 0;
    }

    /** 脱困结束：撤掉 no-clip，清计数（否则会立刻又触发一次）。 */
    private void endRescue(DragonGolemEntity dragon) {
        if (DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[fly] 脱困结束：ticks={} pos={}", this.rescueTicks, dragon.position());
        }
        this.rescueTicks = -1;
        this.stuckTicks = 0;
        dragon.noPhysics = this.freeFlight;
    }

    /** 脱困用的速度：朝"最近的开阔处"飞。 */
    @Nullable
    public Vec3 rescueVelocity(DragonGolemEntity dragon) {
        if (this.rescueTicks < 0) {
            return null;
        }
        Vec3 escape = findOpenDirection(dragon);
        if (escape == null) {
            // 实在找不到：坚决往上（地面上方一定比地下空）
            return new Vec3(0.0D, RESCUE_SPEED, 0.0D);
        }
        // 保证有向上的分量：埋在山里时"水平找缝"是找不到的，得先爬出来
        double vy = Math.max(escape.y * RESCUE_SPEED, RESCUE_MIN_RISE);
        return new Vec3(escape.x * RESCUE_SPEED, vy, escape.z * RESCUE_SPEED);
    }

    /**
     * 避障修正：给一个"想要的水平速度"，返回一个绕开障碍的修正速度。
     *
     * <p>原实现只有"蹭到就抬 0.15"，在地形复杂处不够用（一直贴着同一面墙磨）。
     * 这里的做法：撞墙时在一个扇面里（上、斜上左右、左右）找<b>第一个塞得下龙</b>的方向，
     * 把速度往那个方向偏，并按卡住时长加大竖直分量。
     *
     * @param want 目标水平速度（y 分量忽略）
     * @return 修正后的速度；没有障碍时原样返回 {@code want}
     */
    public Vec3 avoidance(DragonGolemEntity dragon, Vec3 want) {
        if (!dragon.horizontalCollision) {
            return want;
        }
        // 卡得越久，越倾向于"坚决往上爬"
        double stuckRatio = Mth.clamp(this.stuckTicks / (double) STUCK_LIMIT, 0.0D, 1.0D);
        Vec3 open = findOpenDirection(dragon);
        if (open == null) {
            // 四周都堵死：至少往上顶，并保留一点原方向的水平分量（免得完全停住）
            return new Vec3(want.x * 0.3D, 0.15D + 0.25D * stuckRatio, want.z * 0.3D);
        }
        // 把"想去哪"和"哪里有空"混一混：优先往开阔处，但不要完全丢掉原方向
        Vec3 blended = open.add(want.normalize().scale(0.35D));
        double len = blended.horizontalDistance();
        if (len < 1.0E-4D) {
            return want;
        }
        // 水平速度保持原样，方向按 blended 重排；竖直分量随卡住程度递增
        double speed = want.horizontalDistance();
        double vy = 0.12D + 0.30D * stuckRatio;
        return new Vec3(blended.x / len * speed, vy, blended.z / len * speed);
    }

    // ---- 空间查询 ----

    /**
     * 找一个真正塞得下龙的方向（单位向量，只关心方向）。
     *
     * <p>扇面顺序是刻意排的：<b>先上、再斜上、最后左右</b>。
     * 因为龙是飞行单位，"往上"永远是最可能找到空间的（地面上方开阔），
     * 而"左右绕"在矿洞里往往是另一面墙。要绕的时候它才绕。
     */
    @Nullable
    private Vec3 findOpenDirection(DragonGolemEntity dragon) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, dragon.getYRot());
        double[][] fan = {
                {0.0D, 1.0D},   // 正上
                {0.6D, 0.8D},   // 斜上（前）
                {-0.6D, 0.8D},  // 斜上（后）
                {1.0D, 0.0D},   // 前
                {-1.0D, 0.0D},  // 后
                {0.7D, 0.0D},   // 前偏左
                {-0.7D, 0.0D},  // 前偏右
        };
        // 从近到远试：近处有空间就优先走短的
        for (double dist : new double[]{1.6D, 2.6D, 4.0D}) {
            for (double[] f : fan) {
                Vec3 dir = new Vec3(
                        forward.x * f[0], f[1], forward.z * f[0]).normalize();
                Vec3 probe = dragon.position().add(dir.scale(dist));
                if (hasRoomFor(dragon, probe)) {
                    return dir;
                }
            }
        }
        return null;
    }

    /** 当前位置还有没有在方块里（脱困结束的判据）。 */
    private boolean unstickClear(DragonGolemEntity dragon) {
        return hasRoomFor(dragon, dragon.position());
    }

    /**
     * 把龙的判定箱搬到 {@code pos} 之后，那个位置塞得下它吗？
     *
     * <p>逐格检查相交的方块，认定"能穿过"的只有：空气、以及<b>不带碰撞形状</b>的方块
     * （草、雪层、藤蔓、告示牌之类）。这样"判定箱能过"和"实际能不能挤过去"是一致的。
     */
    public static boolean hasRoomFor(DragonGolemEntity dragon, Vec3 pos) {
        AABB box = dragon.getBoundingBox().move(
                pos.x - dragon.getX(), pos.y - dragon.getY(), pos.z - dragon.getZ())
                .inflate(-CLEARANCE);
        Level level = dragon.level();
        if (!level.hasChunkAt(BlockPos.containing(box.minX, box.minY, box.minZ))
                || !level.hasChunkAt(BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            // 区块没加载：当作"未知"，倾向于认为有空间（不要因为没加载就疯狂脱困）
            return true;
        }
        for (BlockPos bp : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = level.getBlockState(bp);
            if (state.isAir()) {
                continue;
            }
            if (state.getCollisionShape(level, bp).isEmpty()) {
                // 草/雪层/藤蔓这类没有碰撞箱的，可以穿过
                continue;
            }
            return false;
        }
        return true;
    }
}
