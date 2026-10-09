package a_silly_cat.dragon_golems.dragon;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 龙的"回位传送"，用来替换本家的 {@code TeleportToOwnerGoal}。
 *
 * <p>本家那套的判定是：{@code golem.distanceToSqr(getTargetPos()) >= maxWanderRadius²}
 * （默认 30），且 <b>是三维距离</b>。龙按空中炮艇设计常挂在离地 10 格，竖直分量直接吃掉一大截阈值，
 * 玩家横向走二十几格就会被传送回来——所以这里改成"水平、垂直分别判定"，并且动作中不传送。
 *
 * <p>落点沿用本家的思路：在主人附近随机试若干次，找到与当前判定箱不碰撞的位置才传送，
 * 并且避开主人脚下若干格（免得糊在脸上）。
 *
 * <p><b>阈值和搜索范围都要乘龙的体型倍率</b>（{@link DragonGolemEntity#bodyScale()}）：
 * 48 格对 1 倍体型是"主人跑远了"，对泰坦体型（4 倍、身体一百多格长）却还不到半个身位；
 * 而落点搜索原来是死写的 ±3 格 —— 泰坦体型判定箱本身就有十几格宽，20 次尝试会全部因为
 * {@code noCollision} 失败，龙就<b>永远回不来</b>了，而且日志里一个字都不会有。
 */
public class DragonFollowTeleportGoal extends Goal {

    /** 水平超过这个距离才回位（再乘体型倍率，但有上限）。 */
    private static final double MAX_HORIZONTAL = 48.0D;
    /** 垂直差超过这个才回位（被打上天 / 被留在悬崖下；再乘体型倍率，但有上限）。 */
    private static final double MAX_VERTICAL = 24.0D;
    /**
     * 回位距离的<b>上限</b>：不管体型多大，都不许在更远的地方待着。
     *
     * <p><b>为什么必须有：</b>本家傀儡收回杖的"潜行右键单体"是<b>射线
     * {@code retrieveDistance}（默认 64 格）</b>，而"右键群收"只捞 {@code retrieveRange}（默认 20 格）内的。
     * 而上面那两个阈值原本是 × 体型倍率的 —— 泰坦体型（地牢 +300% = 4 倍）下水平阈值会涨到
     * <b>192 格</b>：龙在 100 格外还"没超限"，手杖射线够不到，表现就是<b>收不回来</b>。
     *
     * <p>上限 48 / 32 把最坏情况的三维距离压在 58 格以内，保证它永远在射线射程里；
     * 同时又大于泰坦的环绕半径（约 37 格），所以打架时不会被一直往主人身边拉。
     * 想更宽松就把这两个上限往上加 —— 但别超过本家配置里的 {@code retrieveDistance}。
     */
    private static final double MAX_HORIZONTAL_CAP = 48.0D;
    private static final double MAX_VERTICAL_CAP = 32.0D;
    /** 落点搜索的基础半径（格，再乘体型倍率）：水平 ±3、垂直 ±1。 */
    private static final int SEARCH_RADIUS = 3;
    private static final int SEARCH_VERTICAL = 1;
    /** 基础尝试次数（再按体型倍率放大：体型越大，需要的搜索体积越大）。 */
    private static final int TRIES = 20;
    /** 落点至少离主人水平这么远（格，再乘体型倍率），免得整条龙糊在主人脸上。 */
    private static final double KEEP_AWAY = 2.0D;
    /**
     * 一次搜索全失败之后，隔这么久再试（tick）。
     *
     * <p>没有这个冷却的话：{@code canUse()} 每 tick 都返回 true → {@code start()} 里做几十次
     * 体积检测 → 找不到落点 → 下一 tick 再来一遍，一直空转到主人走回来为止。
     * 体型越大每次检测越贵，这个空转就越明显。
     */
    private static final int FAIL_RETRY = 40;

    private final DragonGolemEntity dragon;
    /** 搜索失败后的冷却到期 tick；在此之前不再尝试。 */
    private int nextTryTick;

    public DragonFollowTeleportGoal(DragonGolemEntity dragon) {
        this.dragon = dragon;
        // 传送不需要抢移动/朝向，避免和寻路、悬停打架
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.dragon.tickCount < this.nextTryTick) {
            return false;
        }
        if (!this.dragon.isMovable() || this.dragon.isLeashed()) {
            return false;
        }
        if (this.dragon.getControllingPassenger() instanceof Player) {
            return false;
        }
        // ★ 骑手冲锋（按 R）期间绝不回位：那几秒龙"离开主人"正是设计要求的。
        //   必须用这个明确的标志，不能只靠下面那条 DIVE_PHASE 判断 ——
        //   真机日志（16:51 那版）里就是它漏过去了：
        //     start(): pos=(216.34,-49.25,-127.62)
        //     finish(): phase=LINEUP phaseTicks=28 target=null   ← 28 tick 内龙一动没动
        //     下一次 start(): pos=(254.86,-48.65,-118.92)        ← 突然瞬移 38 格
        //   也就是回位传送把正在冲锋的龙拽了回来，顺手 setTarget(null) 毁掉了航线目标。
        if (this.dragon.isRiderOrderedDive()) {
            return false;
        }
        // ★ 任何"已经排进调度但还没飞完"的技能期间都不回位。
        //   真机症状（泰坦体型）："总是在执行俯冲但完不成 —— 总是冲出传送范围被拽回来"。
        //   原因是俯冲的入口点在<b>目标后方</b>（passBack = 4 × 体型；泰坦体型下最多 64 格），
        //   体型越大、speedScale 越大，LINEUP 那一段越长 —— 而传送阈值只按 bodyScale 放大、
        //   还有 MAX_HORIZONTAL_CAP 封顶（见下），于是"还没飞到入口就被判定离主人太远 → 拽回"，
        //   拽回后重掷又抽中俯冲，无限循环。
        //   判据用 pendingSkill（排队中）或 skillInProgress（已开打）：两者任一都说明
        //   "这一轮攻击正在占用它"，此时回位等于把攻击打断。
        //   注意 pendingSkill 由 tickSkillRoll 管、skillInProgress 由 onSkillStarted 管，
        //   所以"掷出来却一直没执行"的超时清空仍然会把这两个标志放下（见 tickSkillRoll 的看门狗）。
        if (this.dragon.pendingSkill() != null || this.dragon.isSkillInProgress()) {
            return false;
        }
        // 俯冲/冲刺动作中也不回位（AI 自发的俯冲走这条：那时 DIVE_PHASE 已经离开 NONE）
        if (this.dragon.getDivePhase() != DragonGolemEntity.DIVE_PHASE_NONE) {
            return false;
        }
        double scale = this.dragon.bodyScale();
        // 阈值随体型放大，但压在上限之内（见 MAX_HORIZONTAL_CAP）：不然泰坦体型下龙要跑到
        // 192 格外才回位，本家傀儡收回杖的射线（默认 64 格）根本够不到，就是"收不回来"。
        double maxH = Math.min(MAX_HORIZONTAL * scale, MAX_HORIZONTAL_CAP);
        double maxV = Math.min(MAX_VERTICAL * scale, MAX_VERTICAL_CAP);
        // 自由行动时"家"是原点，不是主人 —— 否则追怪离开一会儿就被拽回主人身边，
        // 而玩家明明把它放在这里站岗（见 DragonGolemEntity.freeWanderOrigin 的说明）。
        Vec3 target = this.dragon.isFreeWander()
                ? this.dragon.freeWanderOrigin()
                : this.dragon.getTargetPos();
        double dx = target.x - this.dragon.getX();
        double dy = target.y - this.dragon.getY();
        double dz = target.z - this.dragon.getZ();
        return Math.sqrt(dx * dx + dz * dz) > maxH
                || Math.abs(dy) > maxV
                || this.dragon.getY() < this.dragon.level().getMinBuildHeight() - 32;
    }

    /** 一次性动作。 */
    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        this.teleportNearOwner();
    }

    private void teleportNearOwner() {
        // 和 canUse() 里的判据保持一致：自由行动时回的是原点，不是主人。
        Vec3 target = this.dragon.isFreeWander()
                ? this.dragon.freeWanderOrigin()
                : this.dragon.getTargetPos();
        BlockPos pos = BlockPos.containing(target);
        if (pos.getY() < this.dragon.level().getMinBuildHeight() - 32) {
            return;
        }
        double scale = this.dragon.bodyScale();
        // 搜索体积跟着体型走：判定箱宽高都是 bodyScale() 倍，搜索范围不跟着放大的话，
        // 大体型的龙在主人附近根本找不到一个"不与判定箱碰撞"的落点（原来死写 ±3 格）。
        int radius = Math.max(SEARCH_RADIUS, Mth.ceil(SEARCH_RADIUS * scale));
        int vRadius = Math.max(SEARCH_VERTICAL, Mth.ceil(SEARCH_VERTICAL * scale));
        int tries = Math.max(TRIES, Mth.ceil(TRIES * scale));
        double keepAway = KEEP_AWAY * scale;
        // ★ 落点优先选"不沾液体"的（别把龙传进湖里 / 岩浆里）。
        //   主人真站在水里或岩浆湖边时可能一个干的落点都抽不到，那就用第一次抽到的湿落点兜底 ——
        //   "回得来"比"落点完美"重要，而且悬停逻辑会把它在液面之上重新摆好
        //   （见 DragonGolemEntity.localFloorY 的说明）。
        Vec3 wetSpot = null;
        for (int i = 0; i < tries; i++) {
            int x = pos.getX() + this.randomIntInclusive(-radius, radius);
            int y = pos.getY() + this.randomIntInclusive(-vRadius, vRadius);
            int z = pos.getZ() + this.randomIntInclusive(-radius, radius);
            if (Math.abs((double) x - target.x()) < keepAway
                    && Math.abs((double) z - target.z()) < keepAway) {
                continue;
            }
            Vec3 spot = new Vec3(x + 0.5D, y, z + 0.5D);
            double toX = spot.x - this.dragon.getX();
            double toY = spot.y - this.dragon.getY();
            double toZ = spot.z - this.dragon.getZ();
            // 只挑不与当前判定箱碰撞的落点（飞行单位，不需要找"可站立"方块）
            if (!this.dragon.level().noCollision(this.dragon, this.dragon.getBoundingBox().move(toX, toY, toZ))) {
                continue;
            }
            if (!DragonFlightAssist.isLiquidFree(this.dragon, spot)) {
                if (wetSpot == null) {
                    wetSpot = spot;
                }
                continue;
            }
            this.landAt(spot);
            return;
        }
        if (wetSpot != null) {
            this.landAt(wetSpot);
            return;
        }
        // 这么多次都没找到（主人缩在洞里、被方块埋着）→ 记一次冷却，过 2 秒再试，
        // 别每 tick 都重做几十次体积检测。
        this.nextTryTick = this.dragon.tickCount + FAIL_RETRY;
    }

    /** 真的落下去：挪位置 + 停掉正在走的航线（但别顺手清掉攻击目标）。 */
    private void landAt(Vec3 spot) {
        this.dragon.moveTo(spot.x, spot.y, spot.z, this.dragon.getYRot(), this.dragon.getXRot());
        this.dragon.getNavigation().stop();
        // 传送会打断正在飞的航线，别顺手把它的目标也清了
        // （canUse 里已经挡了骑手冲锋，这里是第二道保险）
        if (!this.dragon.isRiderOrderedDive()) {
            this.dragon.setTarget(null);
        }
        this.nextTryTick = 0;
    }

    private int randomIntInclusive(int min, int max) {
        return this.dragon.getRandom().nextInt(max - min + 1) + min;
    }
}
