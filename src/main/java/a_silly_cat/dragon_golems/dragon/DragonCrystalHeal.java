package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 末地水晶治疗：<b>每 30 秒一轮，每轮最多 12 个可用水晶</b>。
 *
 * <h2>原版是怎么做的（字节码核对过，不是印象）</h2>
 * <pre>
 * // EnderDragon.checkCrystals()
 * if (nearestCrystal != null &amp;&amp; !removed &amp;&amp; tickCount % 10 == 0 &amp;&amp; health &lt; maxHealth)
 *     setHealth(health + 1);                       // 每 10 tick 回 1 点
 * if (random.nextInt(10) == 0)                     // 每 tick 1/10 概率去搜
 *     nearestCrystal = 最近的 level.getEntitiesOfClass(EndCrystal.class, bb.inflate(32.0));
 * </pre>
 * 所以原版是：<b>半径 32 格、每 10 tick 回 1 点、只要附近有一块水晶就无限回</b>。
 *
 * <h2>为什么不能照抄</h2>
 * 照抄的话，基地里放一块水晶的龙就等于无限回血 —— 打不死了。所以这里改成
 * <b>"水晶是消耗品、按轮结算"</b>：
 *
 * <ul>
 *   <li><b>轮（cycle）</b>：每 {@link #CYCLE_TICKS}(600 tick = 30 秒) 结算一次。
 *       结算时搜一遍半径 {@link #RADIUS}(32 格) 内的水晶，<b>最多取
 *       {@link #MAX_CRYSTALS}(12) 个</b>放进"本轮可用池"。</li>
 *   <li><b>回血</b>：池里有水晶时，每 {@link #HEAL_INTERVAL}(10 tick) 回 {@link #HEAL_AMOUNT}(1 点)，
 *       并<b>消耗池里一个</b>。</li>
 *   <li><b>清空后要等下一轮</b>：池空了就不再回血，直到下一个 30 秒结算重新补充。
 *       所以"30 秒内放多个水晶"延长的是一轮里的回血时长（12 个 = 最多 120 tick 的回血），
 *       而不是让它可以无限续。</li>
 * </ul>
 *
 * <p>这样"12 个上限"和"30 秒一轮"共同给了一个明确的总量上限：
 * 每 30 秒最多回 {@code 12 × HEAL_AMOUNT} 点 —— 有压力、但不会变成无敌。
 *
 * <p><b>为什么池要按"消耗"而不是"计时"来扣</b>：按计时扣的话，池里 12 个水晶和 1 个水晶
 * 效果完全一样（都是回满整轮），那"最多 12 个"这条就没有意义了。按消耗扣才能让
 * "多放水晶"真的有收益。
 *
 * <p><b>性能</b>：搜水晶只在每 600 tick 做一次（原版是每 tick 1/10 概率、平均 10 tick 一次），
 * 比原版还省。
 */
public final class DragonCrystalHeal {

    /** 一轮的长度（tick）。600 tick = 30 秒。 */
    private static final int CYCLE_TICKS = 600;
    /** 一轮里最多认几块水晶。 */
    private static final int MAX_CRYSTALS = 12;
    /** 相隔多久回一次血（tick）。和原版一致：10 tick = 0.5 秒。 */
    private static final int HEAL_INTERVAL = 10;
    /** 每次回多少血量。和原版一致：1 点 = 半颗心。 */
    private static final float HEAL_AMOUNT = 1.0F;
    /** 搜索半径（格）。和原版一致：以判定箱为中心向外扩 32 格。 */
    private static final double RADIUS = 32.0D;

    /** 本轮可用的水晶（还未被消耗的）。 */
    private final List<EndCrystal> pool = new ArrayList<>();
    /** 距离下一次"30 秒结算"还有多少 tick。 */
    private int cycleTicks;

    /** 本轮的池子还剩几个（诊断/展示用）。 */
    public int availableCrystals() {
        return this.pool.size();
    }

    /**
     * 每 tick 调一次（服务端）。
     *
     * @return 本 tick 实际回了多少血（0 = 没回）。调用方负责加血 —— 这样"怎么加血"
     * 留在实体里，"什么时候能回"留在这里，职责清楚。
     */
    public float tick(DragonGolemEntity dragon) {
        if (dragon.level().isClientSide()) {
            return 0.0F;
        }
        // ---- 轮结算：每 30 秒重搜一次可用水晶 ----
        if (this.cycleTicks <= 0) {
            this.cycleTicks = CYCLE_TICKS;
            this.refreshPool(dragon);
        } else {
            this.cycleTicks--;
        }

        // ---- 清理失效的水晶（被打掉的、区块卸载的）----
        // 注意：被玩家打爆的水晶 isRemoved() 为真，必须剔除，
        // 否则"摧毁水晶"这个反制手段就失效了。
        if (!this.pool.isEmpty()) {
            Iterator<EndCrystal> it = this.pool.iterator();
            while (it.hasNext()) {
                EndCrystal crystal = it.next();
                if (crystal.isRemoved() || !crystal.isAlive()) {
                    it.remove();
                }
            }
        }

        // ---- 回血：池里有货才回，每次消耗一个 ----
        if (this.pool.isEmpty()) {
            return 0.0F;
        }
        if (dragon.tickCount % HEAL_INTERVAL != 0) {
            return 0.0F;
        }
        if (dragon.getHealth() >= dragon.getMaxHealth()) {
            return 0.0F;
        }
        this.pool.remove(this.pool.size() - 1);
        return HEAL_AMOUNT;
    }

    /**
     * 搜一遍 32 格内的水晶、取最近的至多 {@link #MAX_CRYSTALS} 个放进池子。
     *
     * <p>取"最近的"而不是"随便 12 个"：水晶放远了够不着的时候，近处的应该优先算数。
     */
    private void refreshPool(DragonGolemEntity dragon) {
        this.pool.clear();
        List<EndCrystal> found = dragon.level().getEntitiesOfClass(EndCrystal.class,
                dragon.getBoundingBox().inflate(RADIUS));
        if (found.isEmpty()) {
            return;
        }
        found.sort((a, b) -> Double.compare(a.distanceToSqr(dragon), b.distanceToSqr(dragon)));
        for (EndCrystal crystal : found) {
            if (this.pool.size() >= MAX_CRYSTALS) {
                break;
            }
            if (crystal.isRemoved() || !crystal.isAlive()) {
                continue;
            }
            this.pool.add(crystal);
        }
        if (DragonDebug.RIDE && !this.pool.isEmpty()) {
            Dragon_golems.LOGGER.info("[heal] 新的一轮：可用水晶 {} 个（上限 {}）",
                    this.pool.size(), MAX_CRYSTALS);
        }
    }

    /** 供调试/展示：这个实体的水晶治疗器还有多少 tick 到下一轮。 */
    public int ticksToNextCycle() {
        return this.cycleTicks;
    }
}
