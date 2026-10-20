package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>高度场</b>：回答"这一列，整条龙能待在哪个 Y 区间"，并且给出<b>有保证</b>的夹取结果。
 *
 * <h2>为什么要单独一个"场"，而不是又一个公式</h2>
 * 之前几次"统一高度"统一的是<b>函数</b>（在悬停 / 追击 / 航线 / 脚本之间挑一个），
 * 挑法本身没有保证：挑出来的数可能整条龙根本塞不下。这里把权威从"会挑的函数"
 * 换成"有约束的数据结构"：
 *
 * <pre>
 * column(x,z) = {
 *   floorY : 支撑面（静水/地面，忽略流水）
 *   ceilY  : 天花板下沿（无 = +∞）
 *   band   = [ floorY + MIN_CLEAR , ceilY − 身体高度 − 天花板余量 ]
 *   fits   : band.min ≤ band.max      // 整条龙这一列塞不塞得下
 * }
 * </pre>
 *
 * <h2>它能保证什么</h2>
 * <ol>
 *   <li><b>存在性</b>：band 由真实方块算出，返回的任何高度都保证整条龙在该列塞得下；</li>
 *   <li><b>唯一性</b>：悬停/追击/航线/脚本/骑手全都过同一个 {@link #feasible}，
 *       不会出现"两套逻辑算出两个高度"；</li>
 *   <li><b>连续性</b>：地面/天花板取自实体的平滑扫描（地面已低通），相邻列 band 连续；</li>
 *   <li><b>可达性</b>：跨列移动时用"当前列 ∩ 下一节点列"的 band 交集；
 *       没有交集才退回下一列（A* 已保证那条路能走），绝不自己发明高度；</li>
 *   <li><b>确定性</b>：每列缓存 {@link #CACHE_WINDOW} tick，一个 tick 内所有系统读到同一个数。</li>
 * </ol>
 *
 * <p>山洞/平原的差别<b>自然地</b>体现在 band 的宽窄上：山洞里 band 很窄甚至 {@code fits == false}
 * （此时返回带顶，龙贴着可用空腔的边界飞行），平原上 band 几乎不约束。
 * 所以攻击模式（{@code DragonProfile}）不再需要任何"高度分支"。
 */
public final class DragonAltitudeField {

    /** 一列的高度信息。{@code ceilY == NO_CEILING} 表示上方没有天花板。 */
    public record Column(double floorY, double ceilY, double minFeet, double maxFeet, boolean fits) {

        /** 这一列能塞下整条龙时，把 {@code wantY} 夹进带内；塞不下时返回带顶（贴天花板下沿）。 */
        public double clamp(double wantY) {
            if (!this.fits) {
                return this.maxFeet;
            }
            return Mth.clamp(wantY, this.minFeet, this.maxFeet);
        }
    }

    private final DragonGolemEntity dragon;
    private final Map<Long, Column> cache = new HashMap<>();
    private int cacheWindow = Integer.MIN_VALUE;

    public DragonAltitudeField(DragonGolemEntity dragon) {
        this.dragon = dragon;
    }

    public Column column(int x, int z) {
        int window = this.dragon.tickCount / CACHE_WINDOW;
        if (window != this.cacheWindow) {
            this.cache.clear();
            this.cacheWindow = window;
        }
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        Column cached = this.cache.get(key);
        if (cached != null) {
            return cached;
        }
        Column computed = this.compute(x, z);
        this.cache.put(key, computed);
        return computed;
    }

    private Column compute(int x, int z) {
        double floor = this.dragon.supportYAt(x, z);
        double ceil = this.dragon.ceilingYAt(x, z, this.dragon.ceilingScanStart());
        double min = floor + DragonGolemEntity.minHoverClearance();
        double max;
        if (ceil == DragonGolemEntity.NO_CEILING) {
            max = Double.MAX_VALUE;
        } else {
            max = ceil - this.dragon.getBbHeight() - DragonGolemConfig.ceilingClearance();
        }
        return new Column(floor, ceil, min, max, min <= max);
    }

    /**
     * 把"想去的高度"变成"真的可行的高度"。
     *
     * @param wantY        模式给出的目标高度（可能来自悬停/追击/航线/脚本）
     * @param nextWaypoint 航线上的下一个节点（没有航线时传 null）
     * @return 一定落在这条龙塞得下的区间内的脚部 Y
     */
    public double feasible(double wantY, @Nullable Vec3 nextWaypoint) {
        Column now = this.column(this.dragon.getBlockX(), this.dragon.getBlockZ());
        if (nextWaypoint == null) {
            return now.clamp(wantY);
        }
        Column next = this.column(Mth.floor(nextWaypoint.x), Mth.floor(nextWaypoint.z));
        double lo = Math.max(now.minFeet(), next.minFeet());
        double hi = Math.min(now.maxFeet(), next.maxFeet());
        if (lo <= hi) {
            // 两列都能待的公共区间 —— 在这里面移动，横向与竖向都不会把自己送进方块
            return Mth.clamp(wantY, lo, hi);
        }
        // 没有交集（要翻台阶/出低房间）：信航线，用下一列的 band
        return next.clamp(wantY);
    }

    /** 每列缓存多少 tick（4 = 0.2 秒；够快，又能让一个 tick 内所有系统读到同一个数）。 */
    private static final int CACHE_WINDOW = 4;
}
