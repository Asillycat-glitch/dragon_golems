package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 龙的索敌：<b>优先挑"打得着"的敌人</b>。
 *
 * <h2>为什么要换掉本家的 {@code Golem3DTargetGoal}</h2>
 * 本家那套是"就近优先"（{@code TargetingConditions} 按距离打分，`mustSee=false`、`mustReach=false`），
 * 对走到脸上打的地面傀儡够用；但这条龙是<b>远程炮艇</b>：它的龙息会被方块整个挡掉，
 * 所以"最近的那个敌人"经常是<b>隔着墙的</b>——它就会对着墙喷满一整轮，而墙外真正能打的敌人没人管。
 *
 * <p>（"能不能看见"这件事本家也帮不上忙：{@code AbstractGolemEntity.hasLineOfSight} 在带
 * {@code SEE_THROUGH} 时直接返回 true，穿墙也算看见。所以这里用
 * {@link DragonGolemEntity#hasClearShotTo} 的真实方块射线。）
 *
 * <h2>打分</h2>
 * <pre>
 * 分数 = −距离 + 射界通畅(40) + 正在攻击主人(30) + 当前目标(25)
 * </pre>
 * <ul>
 *   <li><b>射界 +40</b>：直接对应"优先索敌不被方块阻挡的敌人"。距离项是格数，所以 40 分相当于
 *       "愿意多飞 40 格去打一个打得着的敌人"，而不是贴着一个隔着墙的敌人空转。</li>
 *   <li><b>攻击主人 +30</b>：保护职责优先于"顺手打近的"。</li>
 *   <li><b>当前目标 +25</b>：滞回。没有这一项，两个分数接近的目标会让它每 5 tick 换一次目标，
 *       龙息永远打不满一轮。</li>
 * </ul>
 *
 * <p>"该不该打"的规则<b>完全复用本家</b>（{@link AbstractGolemEntity#predicateTarget} +
 * {@link Mob#canAttack}），这里只负责"在能打的里面挑哪个"——所以阵营、模式、愤怒、
 * 主人关系、和平模式那些判定一条都不会丢。
 */
public class DragonTargetGoal extends NearestAttackableTargetGoal<LivingEntity> {

    /** 射界通畅的加分：见 {@code skills.targetClearShotBonus}（默认 40 ≈ 愿意多飞 40 格）。 */
    private static double clearShotBonus() {
        return DragonGolemConfig.targetClearShotBonus();
    }
    /** 正在攻击主人的目标加分。 */
    private static final double ATTACKING_OWNER_BONUS = 30.0D;
    /** 当前目标的滞回加分（新目标要明显更好才换）。 */
    private static final double CURRENT_TARGET_BONUS = 25.0D;
    /** 搜索半径的竖直放大：龙是飞行单位，目标可能在头顶/脚下。 */
    private static final double VERTICAL_SEARCH_SCALE = 1.0D;

    private final DragonGolemEntity dragon;

    public DragonTargetGoal(DragonGolemEntity dragon, int interval) {
        // mustSee=false / mustReach=false：射界与可达性由我们自己的 block 射线与寻路负责
        super(dragon, LivingEntity.class, interval, false, false, dragon::predicateTarget);
        this.dragon = dragon;
    }

    /**
     * 自己扫一遍候选并打分（1.20.1 的签名是 {@code protected void findTarget()}，结果写进 {@code target} 字段）。
     *
     * <p>原版流程是"扫盒子 → {@code TargetingConditions} 打分 → 取最高的"，但它的分数只反映距离；
     * 搜索节流（{@code randomInterval} 的概率）在 {@code canUse()} 里，这里不用重复。
     */
    @Override
    protected void findTarget() {
        this.target = this.pickBest();
    }

    /** 按 {@link #score} 挑最高分的候选；"该不该打"的规则复用本家。 */
    @Nullable
    private LivingEntity pickBest() {
        double follow = this.getFollowDistance();
        AABB area = this.getTargetSearchArea(follow);
        List<LivingEntity> found = this.mob.level().getEntitiesOfClass(LivingEntity.class, area);
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (LivingEntity candidate : found) {
            if (candidate == this.mob || !candidate.isAlive() || candidate.isSpectator()) {
                continue;
            }
            // 本家规则：该不该打
            if (!this.dragon.predicateTarget(candidate) || !this.dragon.canAttack(candidate)) {
                continue;
            }
            double score = this.score(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private double score(LivingEntity candidate) {
        double score = -this.dragon.distanceTo(candidate);
        if (this.dragon.hasClearShotTo(candidate)) {
            score += clearShotBonus();
        }
        LivingEntity owner = this.dragon.getOwner();
        if (owner != null && candidate instanceof Mob mob && mob.getTarget() == owner) {
            score += ATTACKING_OWNER_BONUS;
        }
        if (candidate == this.dragon.getTarget()) {
            score += CURRENT_TARGET_BONUS;
        }
        return score;
    }

    /**
     * 搜索区域：<b>三维</b>（原版是 {@code inflate(r, 4, r)}）。
     *
     * <p>龙在离地 10 格（泰坦 20 格）的空中，目标经常在它正下方二三十格 ——
     * 竖直只用 ±4 会让它"看得见的敌人都搜不到"。
     */
    @Override
    protected AABB getTargetSearchArea(double followRange) {
        double vertical = followRange * VERTICAL_SEARCH_SCALE;
        return this.mob.getBoundingBox().inflate(followRange, vertical, followRange);
    }
}
