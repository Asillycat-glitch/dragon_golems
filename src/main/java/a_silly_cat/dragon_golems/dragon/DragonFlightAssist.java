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
    /** 判定卡住后，再等多少 tick 才去强制重算路径（先给它一个自己走通的机会）。 */
    private static final int REROUTE_AFTER = STUCK_LIMIT + 10;
    /** 还是走不通时，每隔多少 tick 再重算一次（每次都按"现在在哪"重新规划）。 */
    private static final int REROUTE_EVERY = 15;
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
    /** 连续多少次"重算路径之后目标点还是同一个"。 */
    private int repathAttempts;
    /** 上一次重算后看到的路径点（用来判断"重算有没有换目标"）。 */
    @Nullable
    private BlockPos lastRepathNode;
    /** 强制回退还剩多少 tick；> 0 时避障会朝"背离当前路径点"的方向走。 */
    private int retreatTicks;
    /**
     * 同一个路径点重算多少次仍不通就强制回退。
     *
     * <p>真机反馈"遇到死角（凹陷处）不会回头，或者回头概率小"：凹陷的几何不变，
     * 重算出来的候选点经常还是同一个，于是"重算 → 还是那个点 → 又撞"死循环。
     */
    private static final int REPATH_GIVE_UP = 3;
    /** 强制回退持续多少 tick（1.5 秒）。 */
    private static final int RETREAT_TICKS = 30;
    /**
     * 上一 tick 是否"当前位置塞不下龙"（嵌在方块里）。
     *
     * <p>嵌住时必须让 {@code noPhysics} 为真 —— 否则碰撞解算会每个 tick 把实体猛推出去，
     * 骑手的高度输入会被彻底淹没（见 {@link #handleStuck} 的注释）。
     */
    private boolean embedded;

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
    }

    /**
     * 诊断：报一行飞行辅助的状态。
     *
     * <p>由实体在 {@code tickCount % 40 == 20} 时调用 —— 和 {@code [ride] state}（% 40 == 0）
     * 错开 20 tick，这样"同一秒里悬停目标和飞行辅助各自的看法"能对照着看，
     * 排查"一直往上飞是悬停算错还是脱困在爬"必须同时有这两侧的数据。
     */
    public void logState(DragonGolemEntity dragon) {
        if (!DragonDebug.RIDE) {
            return;
        }
        Dragon_golems.LOGGER.info(
                "[fly] stuck={} room={} embedded={} rescue={} noPhysics={} free={} dy={} hColl={} vColl={} pos={}",
                this.stuckTicks, hasRoomFor(dragon, dragon.position()),
                this.embedded, this.rescueTicks, dragon.noPhysics, this.freeFlight,
                String.format("%.3f", dragon.getDeltaMovement().y),
                dragon.horizontalCollision, dragon.verticalCollision, dragon.position());
    }

    /**
     * 撞墙计数与自动脱困的状态机。
     *
     * <p>判据是"水平被挡"而不是"完全没动"：龙悬停时会主动消掉速度，用位移判断会误判成卡住。
     */
    private void handleStuck(DragonGolemEntity dragon) {
        boolean blocked = dragon.horizontalCollision;
        // "当前位置塞不下"本身就是最硬的卡住信号。
        // 为什么必须把它单独拿出来（真机踩到的死锁）：
        //   嵌在方块里时 horizontalCollision 常常是 false（没有"从外向内撞"这个过程），
        //   而 findOpenDirection() 又会在 1.6 格外探到空间、返回非 null，
        //   于是 stuck 一直是 0、脱困永不触发、noPhysics 一直 false，
        //   而 noPhysics=false 恰恰让碰撞解算每 tick 把实体猛推出去
        //   （实测 dy 达到 +17~+26 格/tick）—— 骑手按 Ctrl 下降完全被淹没。
        // 所以判据改成：位置塞不下就计数，不再附加别的条件。
        boolean noRoom = !hasRoomFor(dragon, dragon.position());
        if (blocked || noRoom) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
        }
        this.embedded = noRoom;

        // ★ 卡住就强制重算路径。
        //   这条才是"撞墙后不会重新找路线"的正解：原版只在 goal 需要新路径时才 createPath，
        //   Mob 里没有"撞墙就重算"的调用，所以龙会一直朝同一个路径点推、直到当前路径走完。
        //   而原版 recomputePath() 自带 20 tick 节流（超出就只置一个没人消费的标记），
        //   光调它没用 —— 所以我们的 DragonFlyingNavigation 把节流去掉了。
        //
        //   ★ 真机补充："遇到死角（凹陷处）不会回头（或者回头概率小）"。
        //   原因是重算出来的候选点经常还是同一个（凹陷的几何没变），于是无限重复
        //   "重算 → 还是那个点 → 撞" 。所以这里加一条兜底：如果连着 REPATH_GIVE_UP 次
        //   重算都指向同一个路径点，就强制"往回退"一段时间（见 retreatTicks），
        //   先脱离死角再重新规划 —— 这比原地重算有用得多。
        if (this.stuckTicks == REROUTE_AFTER) {
            this.repathAttempts++;
            BlockPos node = this.currentPathNode(dragon);
            if (node != null && node.equals(this.lastRepathNode)) {
                // 重算之后目标点没变 —— 说明这条路真的走不通
                if (this.repathAttempts >= REPATH_GIVE_UP) {
                    this.retreatTicks = RETREAT_TICKS;
                    this.repathAttempts = 0;
                    if (DragonDebug.RIDE) {
                        Dragon_golems.LOGGER.info("[fly] 同一个路径点重算 {} 次仍不通 → 强制回退 {} tick",
                                REPATH_GIVE_UP, RETREAT_TICKS);
                    }
                }
            } else {
                this.repathAttempts = 1;
                this.lastRepathNode = node;
            }
            dragon.getNavigation().recomputePath();
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[fly] 卡住 {} tick → 强制重算路径（第 {} 次）", this.stuckTicks,
                        this.repathAttempts);
            }
        } else if (this.stuckTicks > REROUTE_AFTER && this.stuckTicks % REROUTE_EVERY == 0) {
            // 还是出不去就持续重算（每次重算都会按"当前所在位置"重新规划）
            dragon.getNavigation().recomputePath();
        }

        if (this.rescueTicks >= 0) {
            // 脱困中：只要"当前位置塞得下"就结束（不依赖还在不在撞墙）
            this.rescueTicks++;
            if (this.unstickClear(dragon) || this.rescueTicks > RESCUE_MAX) {
                this.endRescue(dragon);
            }
        } else if (!this.freeFlight
                && this.stuckTicks >= STUCK_LIMIT
                && noRoom) {
            // 一直撞墙/一直塞不下 → 是"嵌在方块里"而不是"前方有障碍"
            this.rescueTicks = 0;
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[fly] 自动脱困：stuck={} pos={}", this.stuckTicks, dragon.position());
            }
        }

        // noPhysics 的唯一写入口：自由飞行 / 自动脱困 / 正嵌在方块里。
        //
        // ★ 第三条是这次真机踩出来的必需品：嵌在方块里时如果 noPhysics 为 false，
        //   碰撞解算会每个 tick 把实体猛推出去（实测 dy 达到 +17~+26 格/tick），
        //   骑手的高度输入被彻底淹没 —— "骑着在矿洞里一直往上飞、Ctrl 压不下来"。
        //   这三条都是"穿过方块"，所以统一在这里表达，不要另开写入口。
        dragon.noPhysics = this.freeFlight || this.rescueTicks >= 0 || noRoom;
    }

    /** 现在是不是"嵌在方块里"（供诊断/别的模块查询）。 */
    public boolean isEmbedded() {
        return this.embedded;
    }

    /** 脱困结束：撤掉 no-clip，清计数（否则会立刻又触发一次）。 */
    private void endRescue(DragonGolemEntity dragon) {
        if (DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[fly] 脱困结束：ticks={} pos={}", this.rescueTicks, dragon.position());
        }
        this.rescueTicks = -1;
        this.stuckTicks = 0;
        // 只撤到"自由飞行"这一档：如果它其实还嵌在方块里，noPhysics 必须留着 ——
        // 否则下一 tick 的 handleStuck 会立刻因为 noRoom 又打开，来回抖动。
        // 真正该关掉的时刻是"它真的出来了"（那时 noRoom 为假，handleStuck 会关）。
        dragon.noPhysics = this.freeFlight || !hasRoomFor(dragon, dragon.position());
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
     * 把速度往那个方向偏。
     *
     * <p><b>★ 为什么竖直分量要"爬一段、平飞一段"交替</b>（真机踩到的问题）：
     * 最早无条件给正竖直分量，于是龙在洞穴里被埋住时会<b>一直往上爬</b>，
     * 钻进天花板、嵌进石头里，而且永远不出那个洞。
     * 现在按 {@link #RISE_PHASE} / {@link #LEVEL_PHASE} 交替：
     * 先爬 2 秒（脱离当前这层障碍），再平飞 2 秒（去找横向的出口）。
     * 两者都保留"往开阔处偏"的方向修正，所以既不会原地磨，也不会一直顶天花板。
     *
     * @param want 目标水平速度（y 分量忽略）
     * @return 修正后的速度；没有障碍时原样返回 {@code want}
     */
    public Vec3 avoidance(DragonGolemEntity dragon, Vec3 want) {
        // 强制回退中：故意朝"背离当前路径点"的方向走，先把死角甩开再重新规划。
        // 这一步必须在 horizontalCollision 判断之前 —— 回退期间它可能已经不撞墙了
        // （正在往回飞），但仍需要走完这段脱离动作。
        if (this.retreatTicks > 0) {
            this.retreatTicks--;
            Vec3 node = this.pathNodeVec(dragon);
            Vec3 away = node == null
                    ? want.scale(-1.0D)
                    : new Vec3(dragon.getX() - node.x, 0.0D, dragon.getZ() - node.z);
            if (away.horizontalDistance() < 1.0E-4D) {
                away = want.scale(-1.0D);
            }
            away = away.normalize().scale(Math.max(want.horizontalDistance(), 0.2D));
            if (DragonDebug.RIDE && this.retreatTicks == RETREAT_TICKS - 1) {
                Dragon_golems.LOGGER.info("[fly] 回退中：朝 {} 方向脱离死角", away);
            }
            return new Vec3(away.x, 0.0D, away.z);
        }
        if (!dragon.horizontalCollision) {
            return want;
        }
        double stuckRatio = Mth.clamp(this.stuckTicks / (double) STUCK_LIMIT, 0.0D, 1.0D);
        // 交替相位：爬一段、平飞一段
        boolean risePhase = (this.stuckTicks / RISE_PHASE) % 2 == 0;

        // ★ 首选：顺着寻路给的路径点走。
        //   真机反馈"往一根柱子上撞、绕不过去" —— 原因是避障只在自己的小扇面里挑方向，
        //   完全没用上"寻路已经算好的一条绕行路线"。只要路径还有下一个节点，
        //   那个节点的方向就是最可靠的"往哪绕"，比扇面碰运气准得多。
        Vec3 pathDir = this.pathDirection(dragon);
        Vec3 open = pathDir != null ? pathDir : findOpenDirection(dragon);
        if (open == null) {
            // 四周都堵死：往上顶（这是唯一可能出去的方向），但保留一点原方向避免完全停住
            double vy = risePhase ? 0.15D + 0.25D * stuckRatio : 0.06D;
            return new Vec3(want.x * 0.3D, vy, want.z * 0.3D);
        }
        // 把"想去哪"和"该往哪绕"混一混：优先绕行方向，但保留一点原方向免得完全丢掉目标
        double mix = pathDir != null ? 0.2D : 0.35D;
        Vec3 blended = open.add(want.normalize().scale(mix));
        double len = blended.horizontalDistance();
        if (len < 1.0E-4D) {
            return want;
        }
        double speed = want.horizontalDistance();
        // 爬升相位给正的竖直分量；平飞相位给 0，把高度交给寻路/悬停
        double vy = risePhase ? 0.12D + 0.30D * stuckRatio : 0.0D;
        return new Vec3(blended.x / len * speed, vy, blended.z / len * speed);
    }

    /**
     * 寻路当前路径点的方向（单位向量，水平归一化）；没有路径就返回 null。
     *
     * <p>只取水平分量：竖直方向由 {@link #RISE_PHASE} 那套交替逻辑管，
     * 否则"路径点在头顶"会让龙一直往上钻。
     */
    @Nullable
    private Vec3 pathDirection(DragonGolemEntity dragon) {
        Vec3 node = this.pathNodeVec(dragon);
        if (node == null) {
            return null;
        }
        Vec3 flat = new Vec3(node.x - dragon.getX(), 0.0D, node.z - dragon.getZ());
        if (flat.horizontalDistance() < 0.5D) {
            // 路径点就在脚下：说明这一步没有横向指引，交回扇面探测
            return null;
        }
        return flat.normalize();
    }

    /** 当前路径点（世界坐标）或 null。 */
    @Nullable
    private Vec3 pathNodeVec(DragonGolemEntity dragon) {
        if (!(dragon.getNavigation() instanceof DragonFlyingNavigation nav)) {
            return null;
        }
        return nav.currentPathNode();
    }

    /** 当前路径点所在的方块坐标（用来判断"重算之后目标换没换"）或 null。 */
    @Nullable
    private BlockPos currentPathNode(DragonGolemEntity dragon) {
        Vec3 node = this.pathNodeVec(dragon);
        return node == null ? null : BlockPos.containing(node);
    }

    /** 避障时"往上爬"持续的 tick 数（40 = 2 秒）。 */
    private static final int RISE_PHASE = 40;
    /** 避障时"平飞找横向出口"持续的 tick 数（40 = 2 秒）。 */
    private static final int LEVEL_PHASE = 40;

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
        // 扇面比原来密得多：真机反馈"往柱子上撞、绕不过去"就是因为原来 7 个方向
        // 在大体型的判定箱下全部探到障碍，于是只能往上顶。
        // 现在加了"斜上偏左右"和"纯左右"，绕柱子的成功率明显提高。
        double[][] fan = {
                {0.0D, 1.0D},    // 正上
                {0.6D, 0.8D},    // 斜上前
                {-0.6D, 0.8D},   // 斜上后
                {0.8D, 0.6D},    // 更偏前的斜上
                {-0.8D, 0.6D},   // 更偏后的斜上
                {1.0D, 0.0D},    // 正前
                {-1.0D, 0.0D},   // 正后
                {0.7D, 0.2D},    // 前偏左（几乎水平，带一点上）
                {-0.7D, 0.2D},   // 前偏右
                {0.4D, 0.0D},    // 左前方 45°
                {-0.4D, 0.0D},   // 右前方 45°
                {0.2D, 0.0D},    // 几乎正侧向
                {-0.2D, 0.0D},   // 另一侧
        };
        // 探测距离也比原来远：柱子常有 3~5 格粗，只探 4 格会在"贴着柱子"时全判为堵死。
        for (double dist : new double[]{1.6D, 2.6D, 4.0D, 6.0D, 9.0D}) {
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
