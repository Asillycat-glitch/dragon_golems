package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 末地水晶治疗：<b>每 30 秒判定一次、判定通过后持续回血</b>。
 *
 * <h2>机制（用户定义）</h2>
 * <ol>
 *   <li><b>判定周期 {@link #CHECK_INTERVAL} = 30 秒</b>：若这只龙<b>受伤了</b>、
 *       且它 {@link #RADIUS} 格内有存活的末地水晶，判定通过 → 本轮可以回血。</li>
 *   <li><b>回血</b>：判定通过后，只要水晶还在、龙还没满血，就按原版末影龙的节律
 *       <b>持续</b>回血（每 {@link #HEAL_INTERVAL} = 10 tick 回 {@link #HEAL_AMOUNT} = 1 点）。</li>
 *   <li><b>不叠加</b>：同一只龙旁边放几块水晶都一样快（一块就够治）；多放的意义是覆盖更多龙。</li>
 *   <li>水晶<b>不被消耗</b>，没有额度上限；30 秒那道判定就是全部的闸门。</li>
 * </ol>
 *
 * <h2>原版末影龙是怎么做的（javap 查的字节码，不是印象）</h2>
 * <pre>
 * EnderDragon.checkCrystals():
 *   if (nearestCrystal != null &amp;&amp; !removed &amp;&amp; tickCount % 10 == 0 &amp;&amp; health &lt; maxHealth)
 *       setHealth(health + 1);
 *   if (random.nextInt(10) == 0)
 *       nearestCrystal = 最近的 level.getEntitiesOfClass(EndCrystal.class, bb.inflate(32.0));
 * </pre>
 * 我们沿用它的"10 tick / 1 点 / 32 格"，差别只在多了那道 30 秒的判定闸门。
 *
 * <h2>★ 一个被真机暴露出来的设计缺陷（已修）</h2>
 * 第一版是"判定成功后把计时器归零"。于是龙满血那一刻判定失败
 * （满血不算"受伤"，所以判定不过），计时器却已经因为上一轮成功而接近阈值 ——
 * <b>结果：龙满血后再被打伤，要白等将近 30 秒才开始回血</b>。
 * 玩家的原话是"傀儡似乎会断掉回血（满血后需要 30s 后再回血？）"，就是这个。
 *
 * <p>现在改成：<b>计时器只在判定成功时清零</b>。满血时的判定失败<b>不</b>清零，
 * 所以时钟一直在走 —— 龙一受伤就会被立刻接管（"上次判定已经过去 30 秒了"这个条件早已满足），
 * 既保留了"30 秒最多开局一次"的限制，又没有任何人工等待。
 */
public final class DragonCrystalHeal {

    /** 判定周期（tick）。600 tick = 30 秒。 */
    private static final int CHECK_INTERVAL = 600;
    /** 回血间隔（tick）。和原版一致：10 tick = 0.5 秒。 */
    private static final int HEAL_INTERVAL = 10;
    /** 每次回多少血。和原版一致：1 点 = 半颗心。 */
    private static final float HEAL_AMOUNT = 1.0F;
    /** 搜索半径（格）。和原版一致：以判定箱为中心向外扩 32 格。 */
    private static final double RADIUS = 32.0D;

    /**
     * 距离上一次"判定成功"过了多少 tick。
     *
     * <p>不复用 {@code dragon.tickCount}：600 和 10 的公倍数会让"判定刚通过"和"该回血"
     * 之间产生不该有的相位关系。也不在判定失败时清零，原因见类注释里的缺陷说明。
     */
    private int ticksSinceCheck = CHECK_INTERVAL;
    /** 本轮判定是否通过（通过 = 允许回血）。 */
    private boolean active;
    /** 最近一次判定看到的水晶数量（日志/展示用）。 */
    private int crystalCount;

    /** 本轮判定是否通过。 */
    public boolean isActive() {
        return this.active;
    }

    /** 最近一次判定看到的水晶数量。 */
    public int crystalCount() {
        return this.crystalCount;
    }

    /**
     * 每 tick 调一次（服务端）。
     *
     * @return 本 tick 用来回血的水晶（{@code null} = 本 tick 不回血）。调用方负责
     * <b>加血 + 生成光效</b> —— "什么时候能回、用哪块水晶"留在这里，
     * "怎么表现"留在实体/客户端，职责清楚。
     */
    @Nullable
    public EndCrystal tick(DragonGolemEntity dragon) {
        if (dragon.level().isClientSide()) {
            return null;
        }
        // 时钟一直在走：满血时不清零（见类注释的缺陷说明）
        if (this.ticksSinceCheck < CHECK_INTERVAL) {
            this.ticksSinceCheck++;
        }

        // ---- 判定：上次判定成功之后满 30 秒才做下一次 ----
        if (this.ticksSinceCheck >= CHECK_INTERVAL) {
            // check() 成功时会把 ticksSinceCheck 清零；失败时留着（时钟继续走）
            this.check(dragon);
        }

        // ---- 判定没过：整轮不回 ----
        if (!this.active) {
            return null;
        }
        // ---- 回血节律：每 10 tick 一点，且不满血 ----
        if (this.ticksSinceCheck % HEAL_INTERVAL != 0) {
            return null;
        }
        if (dragon.getHealth() >= dragon.getMaxHealth()) {
            return null;
        }
        return this.findLiveCrystal(dragon);
    }

    /**
     * 判定：<b>这只龙受伤了、且它 32 格内有存活的末地水晶</b>才算通过。
     *
     * <p>成功时把计时器清零（下一次判定要再等 30 秒）；失败时<b>不动</b>它 ——
     * 这正是上面说的缺陷修复点。
     */
    private void check(DragonGolemEntity dragon) {
        if (dragon.getHealth() >= dragon.getMaxHealth()) {
            this.deactivate("龙是满血");
            return;
        }
        int alive = this.countLiveCrystals(dragon);
        if (alive == 0) {
            this.deactivate(RADIUS + " 格内没有存活的水晶");
            return;
        }
        this.active = true;
        this.crystalCount = alive;
        this.ticksSinceCheck = 0;
        if (DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[heal] 判定通过：{} 格内有 {} 块水晶，本轮持续回血", RADIUS, alive);
        }
    }

    private void deactivate(String why) {
        if (this.active && DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[heal] 本轮回血结束（{}）", why);
        }
        this.active = false;
        this.crystalCount = 0;
    }

    /** 找一块存活的水晶（用来发光效）；没有就返回 null。 */
    @Nullable
    private EndCrystal findLiveCrystal(DragonGolemEntity dragon) {
        List<EndCrystal> found = dragon.level().getEntitiesOfClass(EndCrystal.class,
                dragon.getBoundingBox().inflate(RADIUS));
        for (EndCrystal crystal : found) {
            if (!crystal.isRemoved() && crystal.isAlive()) {
                return crystal;
            }
        }
        // 轮途中水晶被拆掉了：立刻停手（否则"打掉水晶"这个反制要等最长 30 秒才生效）
        this.deactivate("水晶已不在");
        return null;
    }

    /** 数一下范围内有几块存活的水晶。 */
    private int countLiveCrystals(DragonGolemEntity dragon) {
        List<EndCrystal> found = dragon.level().getEntitiesOfClass(EndCrystal.class,
                dragon.getBoundingBox().inflate(RADIUS));
        int alive = 0;
        for (EndCrystal crystal : found) {
            if (!crystal.isRemoved() && crystal.isAlive()) {
                alive++;
            }
        }
        return alive;
    }
}
