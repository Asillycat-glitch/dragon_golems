package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;

/**
 * 带<b>体型余量</b>的飞行节点评估器。
 *
 * <h2>为什么需要它</h2>
 * 原版 {@code FlyNodeEvaluator} 判定"这个节点能不能过"只看<b>方块格</b>：
 * {@code mob.getPathfindingMalus(方块类型) >= 0} 就可走，而实心方块是 {@code BLOCKED}（malus −1）
 * 会被排除。也就是说：<b>它不会规划穿墙路线</b>，但也<b>完全不看实体多大</b> ——
 * {@code NodeEvaluator} 里那两个按体型算出来的 {@code entityWidth/entityHeight}
 * 在飞行这一支里没参与判定。于是 2.6 格宽（泰坦 10 格宽）的龙会被规划进"1 格缝"，
 * 一路蹭墙、然后触发卡住救援（表现就是"贴着墙磨/莫名穿墙"）。
 *
 * <h2>做法</h2>
 * 在 {@code super} 判定为可走之后，再按<b>龙自己的身体盒子</b>在节点处采样：
 * 四个角柱 + 中柱，各取"脚底 / 半高 / 头顶"三个高度（共 15 个采样点），
 * 任何一个点落进有碰撞箱的方块 → 这个节点判 {@code BLOCKED}。
 *
 * <p>采样宽度/高度各封顶 {@link #MAX_SAMPLE}：泰坦体型下身体盒子有十几格宽，
 * 逐方块遍历会把这个评估器变成性能黑洞，而"比 8 格还宽的缝"本来也不该指望能钻过去。
 *
 * <p>采样异常（区域外的区块等）一律按"能过"处理 —— 这是寻路的辅助信息，
 * 绝不能因为检查本身把整条寻路搞崩。
 */
public class DragonFlyNodeEvaluator extends FlyNodeEvaluator {

    /** 采样用的身体尺寸上限（格）：超过就按这个值采样，避免大体型把开销拉爆。 */
    private static final double MAX_SAMPLE = 8.0D;
    /** 贴边余量（格）：正好卡着边界的缝不算缝。 */
    private static final double EDGE = 0.2D;
    /** 采样高度（相对节点底部，格）。 */
    /**
     * 采样高度（相对身体高度的比例）：{@code 1.0} 必须包含 —— 那是"身体顶端"，
     * 少了它就会出现"头顶 0.46 格没检查"的盲区（见 {@link #bodyFits} 的说明）。
     */
    private static final double[] SAMPLE_HEIGHTS = {0.15D, 0.5D, 0.85D, 1.0D};

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
        BlockPathTypes base = super.getBlockPathType(level, x, y, z, mob);
        if (base == BlockPathTypes.BLOCKED || !DragonGolemConfig.pathBodyClearance()) {
            return base;
        }
        return this.bodyFits(level, x, y, z, mob) ? base : BlockPathTypes.BLOCKED;
    }

    /**
     * 身体盒子在节点处塞不塞得下。
     *
     * <p><b>★ 采样必须覆盖到身体顶端</b>（曾经漏掉最后 0.15 格 → 差约 0.46 格的盲区）：
     * 旧版高度是 {@code min(bbHeight, 8) - 0.15}，最高采样点只有 {@code 0.85 × (2.2 - 0.15) = 1.74}，
     * 而 1 倍体型的身体高 2.2 —— <b>头顶那 0.46 格没有任何采样</b>。
     * 于是"脚上方第 2 格就是天花板"的节点会被判成可通行，A* 把航路点放在天花板里半格，
     * 龙照着航线往上飞 → 撞进去变 embedded → 脱出逻辑把它压下来 → 一不重合航线又指回去 → 来回抖。
     * 现在采样到 {@code 1.0 × bbHeight}（身体顶端），并且去掉那个 -0.15。
     */
    private boolean bodyFits(BlockGetter level, int x, int y, int z, Mob mob) {
        // ★ 除了身体尺寸，再向外放宽 pathBodyMargin：让路线自己离墙/梁远一点
        //   （真机里"贴着结构蹭过去然后嵌进去"多数是因为按精确尺寸判成'刚好塞得下'）。
        double margin = DragonGolemConfig.pathBodyMargin();
        double half = Math.min(mob.getBbWidth(), MAX_SAMPLE) * 0.5D + margin - EDGE;
        double height = Math.min(mob.getBbHeight(), MAX_SAMPLE) + margin;
        if (half <= 0.0D || height <= 0.0D) {
            return true;
        }
        double cx = x + 0.5D;
        double cz = z + 0.5D;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                for (double h : SAMPLE_HEIGHTS) {
                    if (solidAt(level, cx + sx * half, y + h * height, cz + sz * half)) {
                        return false;
                    }
                }
            }
        }
        // 中柱也要查：正对着一堵墙时，四个角可能恰好都在空气里
        for (double h : SAMPLE_HEIGHTS) {
            if (solidAt(level, cx, y + h * height, cz)) {
                return false;
            }
        }
        return true;
    }

    private static boolean solidAt(BlockGetter level, double px, double py, double pz) {
        try {
            BlockPos pos = BlockPos.containing(px, py, pz);
            BlockState state = level.getBlockState(pos);
            return !state.getCollisionShape(level, pos).isEmpty();
        } catch (Throwable t) {
            // 区域外（PathNavigationRegion 只覆盖被加载的区块）或任何异常 → 当作能过
            return false;
        }
    }
}
