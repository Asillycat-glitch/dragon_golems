package a_silly_cat.dragom_golems.dragon;

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

    /** 水平超过这个距离才回位（再乘体型倍率）。 */
    private static final double MAX_HORIZONTAL = 48.0D;
    /** 垂直差超过这个才回位（被打上天 / 被留在悬崖下；再乘体型倍率）。 */
    private static final double MAX_VERTICAL = 24.0D;
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
        // 俯冲/冲刺动作中不回位（以后做龙息时也走这里）
        if (this.dragon.getDivePhase() != DragonGolemEntity.DIVE_PHASE_NONE) {
            return false;
        }
        double scale = this.dragon.bodyScale();
        Vec3 target = this.dragon.getTargetPos();
        double dx = target.x - this.dragon.getX();
        double dy = target.y - this.dragon.getY();
        double dz = target.z - this.dragon.getZ();
        return Math.sqrt(dx * dx + dz * dz) > MAX_HORIZONTAL * scale
                || Math.abs(dy) > MAX_VERTICAL * scale
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
        Vec3 target = this.dragon.getTargetPos();
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
        for (int i = 0; i < tries; i++) {
            int x = pos.getX() + this.randomIntInclusive(-radius, radius);
            int y = pos.getY() + this.randomIntInclusive(-vRadius, vRadius);
            int z = pos.getZ() + this.randomIntInclusive(-radius, radius);
            if (Math.abs((double) x - target.x()) < keepAway
                    && Math.abs((double) z - target.z()) < keepAway) {
                continue;
            }
            double toX = x + 0.5D - this.dragon.getX();
            double toY = y - this.dragon.getY();
            double toZ = z + 0.5D - this.dragon.getZ();
            // 只挑不与当前判定箱碰撞的落点（飞行单位，不需要找"可站立"方块）
            if (!this.dragon.level().noCollision(this.dragon, this.dragon.getBoundingBox().move(toX, toY, toZ))) {
                continue;
            }
            this.dragon.moveTo(x + 0.5D, y, z + 0.5D, this.dragon.getYRot(), this.dragon.getXRot());
            this.dragon.getNavigation().stop();
            this.dragon.setTarget(null);
            this.nextTryTick = 0;
            return;
        }
        // 这么多次都没找到（主人缩在洞里、被方块埋着）→ 记一次冷却，过 2 秒再试，
        // 别每 tick 都重做几十次体积检测。
        this.nextTryTick = this.dragon.tickCount + FAIL_RETRY;
    }

    private int randomIntInclusive(int min, int max) {
        return this.dragon.getRandom().nextInt(max - min + 1) + min;
    }
}
