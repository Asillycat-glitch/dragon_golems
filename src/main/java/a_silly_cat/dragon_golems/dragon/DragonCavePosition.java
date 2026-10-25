package a_silly_cat.dragon_golems.dragon;

import java.util.List;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>洞穴站位</b>：挑一个"近战打不到"的位置待着，而不是挨打之后才闪。
 *
 * <h2>什么叫"近战打不到"</h2>
 * 洞穴里绝大多数威胁是地面近战。对它们来说，一个位置"安全"只需要满足一条：
 * <ul>
 *   <li><b>高度差</b>：龙脚底 ≥ 敌人最高点 + 2.5 格（原版近战不能跳打到这个高度）；或</li>
 *   <li><b>水平距离</b>：≥ 6 格（走出近战攻击范围）。</li>
 * </ul>
 * 两条满足其一即可。高度优先 —— 它比"跑远"更稳定，也不会把自己挤到墙角。
 *
 * <h2>为什么不寻路</h2>
 * 洞穴常驻幽灵（{@link DragonGolemEntity#noPhysics}），本来就不吃碰撞、不需要绕路。
 * 这里只在当前位置周围做<b>环状采样</b>（8 方向 × 3 距离 + 上下偏移），
 * 用 {@link DragonFlightAssist#hasRoomFor} 剔掉"塞不下"的点，挑分数最高的当锚点，
 * 然后<b>直接朝它飘过去</b>。没有 A*、没有碰撞求解、没有路径跟随。
 */
public final class DragonCavePosition {

    /** 安全高度差（格）：龙脚底比敌人头顶高这么多，近战就打不到。 */
    private static final double SAFE_HEIGHT = 2.5D;
    /** 安全水平距离（格）。 */
    private static final double SAFE_DISTANCE = 6.0D;
    /** 多近的敌人才算威胁（格）。 */
    private static final double THREAT_RANGE = 14.0D;
    /** 锚点重算间隔（tick）。 */
    private static final int RECALC_TICKS = 20;
    /** 朝锚点飘的速度（格/tick）。 */
    private static final double MOVE_SPEED = 0.32D;
    /** 到锚点这么近就算"到位"，之后只做微幅环绕。 */
    private static final double ARRIVE = 0.8D;

    private final DragonGolemEntity dragon;
    private Vec3 anchor;
    private int recalc = 1;
    private double orbitAngle;
    private int lastHurtTick = Integer.MIN_VALUE / 2;

    public DragonCavePosition(DragonGolemEntity dragon) {
        this.dragon = dragon;
    }

    /** 挨打时记一笔：让下一次重算"更想换位"（诊断/权重用）。 */
    public void onHurt() {
        this.lastHurtTick = this.dragon.tickCount;
        this.recalc = Math.min(this.recalc, 2);
    }

    /** 当前锚点（诊断用）。 */
    public Vec3 anchor() {
        return this.anchor;
    }

    /**
     * 每 tick 调一次。
     *
     * @param vertical 竖直速度（悬停/高度控制仍归实体）
     * @return 这一 tick 应该施加的移动速度；不需要移动时返回 {@code null}
     */
    public Vec3 tick(boolean enabled) {
        if (!enabled) {
            this.anchor = null;
            return null;
        }
        // 幽灵模式：不需要"塞得下"的安全位（能穿墙），移动交给追击/站位距离控制。
        if (this.dragon.noPhysics) {
            this.anchor = null;
            return null;
        }
        if (--this.recalc <= 0) {
            this.recalc = RECALC_TICKS;
            this.anchor = this.pickSafeSpot();
        }
        if (this.anchor == null) {
            return null;
        }
        Vec3 to = this.anchor.subtract(this.dragon.position());
        double dist = Math.sqrt(to.x * to.x + to.z * to.z);
        // 竖直也朝锚点靠（安全位常常在高处：-0.2~+0.2 的温和升降，不做爬升动作）
        double vertical = Math.max(-0.2D, Math.min(0.2D, to.y * 0.10D));
        if (dist <= ARRIVE) {
            // 到位：围着锚点做很慢的微幅环绕，避免"钉死一个点"
            this.orbitAngle += 0.02D;
            double px = this.anchor.x + Math.cos(this.orbitAngle) * 0.6D;
            double pz = this.anchor.z + Math.sin(this.orbitAngle) * 0.6D;
            double dx = px - this.dragon.getX();
            double dz = pz - this.dragon.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 0.05D) {
                return new Vec3(0.0D, vertical, 0.0D);
            }
            double speed = Math.min(0.08D, d * 0.5D);
            return new Vec3(dx / d * speed, vertical, dz / d * speed);
        }
        double speed = Math.min(MOVE_SPEED, dist * 0.35D);
        return new Vec3(to.x / dist * speed, vertical, to.z / dist * speed);
    }

    /**
     * 环状采样挑锚点：当前点 + 8 方向 × (3/5/7 格) + 上下偏移 (0/+2/+4/-2)。
     *
     * <p>评分 = 到最近威胁的距离 × 2 + 高度优势 × 3 - 塞不下的惩罚。
     * 高度优势按"比该敌人高多少"算，超过 {@link #SAFE_HEIGHT} 之后不再加分（再多没用）。
     */
    private Vec3 pickSafeSpot() {
        List<LivingEntity> threats = this.scanThreats();
        // 当前点本身就安全：不动（省事、稳定）
        if (this.isSafe(this.dragon.position(), threats)) {
            return this.dragon.position();
        }
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3 base = this.dragon.position();
        double[] radii = {3.0D, 5.0D, 7.0D};
        double[] heights = {0.0D, 2.0D, 4.0D, -2.0D};
        for (double radius : radii) {
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2.0D * i / 8.0D;
                for (double h : heights) {
                    Vec3 candidate = new Vec3(base.x + Math.cos(angle) * radius,
                            base.y + h, base.z + Math.sin(angle) * radius);
                    if (!DragonFlightAssist.hasRoomFor(this.dragon, candidate)) {
                        continue;
                    }
                    // 别把锚点选在岩浆/水面上：避障扇面会优先绕开液体，但这里才是"常驻点"
                    if (!DragonFlightAssist.isLiquidFree(this.dragon, candidate, 0.35D)) {
                        continue;
                    }
                    double score = this.score(candidate, threats);
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        if (best == null) {
            return base; // 周围全是墙：保持原地（幽灵模式仍然能打）
        }
        this.orbitAngle = 0.0D;
        return best;
    }

    private double score(Vec3 pos, List<LivingEntity> threats) {
        if (threats.isEmpty()) {
            return 0.0D;
        }
        double nearest = Double.MAX_VALUE;
        double heightBonus = 0.0D;
        for (LivingEntity threat : threats) {
            double horizontal = Math.hypot(pos.x - threat.getX(), pos.z - threat.getZ());
            nearest = Math.min(nearest, horizontal);
            double above = pos.y - (threat.getY() + threat.getBbHeight());
            if (above >= SAFE_HEIGHT) {
                heightBonus = Math.max(heightBonus, 1.0D);
            } else {
                heightBonus = Math.max(heightBonus, Math.max(0.0D, above / SAFE_HEIGHT) * 0.5D);
            }
        }
        return nearest * 2.0D + heightBonus * 3.0D;
    }

    private boolean isSafe(Vec3 pos, List<LivingEntity> threats) {
        for (LivingEntity threat : threats) {
            double horizontal = Math.hypot(pos.x - threat.getX(), pos.z - threat.getZ());
            double above = pos.y - (threat.getY() + threat.getBbHeight());
            if (horizontal >= SAFE_DISTANCE || above >= SAFE_HEIGHT) {
                return true;
            }
        }
        return threats.isEmpty();
    }

    /** 附近"能打到我、且是落地近战"的敌人。飞行怪不算（高度优势对它们无效）。 */
    private List<LivingEntity> scanThreats() {
        AABB box = this.dragon.getBoundingBox().inflate(THREAT_RANGE);
        return this.dragon.level().getEntitiesOfClass(LivingEntity.class, box, e ->
                e != this.dragon && e.isAlive() && !e.isSpectator()
                        && this.dragon.canAttack(e)
                        && (e instanceof Enemy || e instanceof Mob)
                        && !e.isNoGravity());
    }
}
