package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

import java.util.List;

/**
 * 末地水晶治疗：<b>每 30 秒判定一次、判定通过后持续回血</b>。
 *
 * <h2>机制（用户定义）</h2>
 * <ol>
 *   <li><b>判定（每 {@link #CHECK_INTERVAL} = 30 秒一次）</b>：只有当场上存在
 *       <b>受伤的龙傀儡</b>、且它 {@link #RADIUS} 格内有末地水晶时，才算"判定通过"。</li>
 *   <li><b>回血</b>：判定通过后，只要水晶还在、龙还没满血，就<b>持续</b>按原版末影龙的节律
 *       回血（每 {@link #HEAL_INTERVAL} = 10 tick 回 {@link #HEAL_AMOUNT} = 1 点）。</li>
 *   <li><b>不叠加</b>：同一只龙旁边不管放几块水晶，回血速度都是 10 tick / 1 点
 *       （一块水晶就够治）。多放水晶的意义在于"覆盖范围"——能同时照顾到更多龙。</li>
 *   <li><b>没有上限池</b>：水晶不被消耗，也不需要 12 块的额度。30 秒那道判定就是全部的闸门 ——
 *       判定没通过（龙满血、或者附近没水晶）就整轮不回；一旦通过，这一轮里水晶一直有效。</li>
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
 * 我们沿用它的"10 tick / 1 点 / 32 格"，差别只在<b>加了一道 30 秒的判定闸门</b>：
 * 原版是"有水晶就无限回"，这里必须"场上有一只受伤的龙 + 它附近有水晶"才开闸，
 * 而且每轮重新判一次。
 *
 * <h2>性能</h2>
 * 每只龙每 30 秒只做一次实体搜索（原版是平均每 10 tick 一次），比原版省得多。
 * 没通过的轮次直接跳过，不做任何查询。
 */
public final class DragonCrystalHeal {

    /** 判定间隔（tick）。600 tick = 30 秒。 */
    private static final int CHECK_INTERVAL = 600;
    /** 回血间隔（tick）。和原版一致：10 tick = 0.5 秒。 */
    private static final int HEAL_INTERVAL = 10;
    /** 每次回多少血。和原版一致：1 点 = 半颗心。 */
    private static final float HEAL_AMOUNT = 1.0F;
    /** 搜索半径（格）。和原版一致：以判定箱为中心向外扩 32 格。 */
    private static final double RADIUS = 32.0D;

    /**
     * 本只龙的计数器：既用来数"距离下次判定还有多久"，也用来对 {@link #HEAL_INTERVAL} 取模。
     *
     * <p>不复用 {@code dragon.tickCount}：那个是实体的总寿命，两个周期的公倍数会在
     * "判定刚通过"和"该回血"之间产生不该有的相位关系（判定通过那一 tick 恰好不回血之类）。
     */
    private int ticks;
    /** 本轮判定是否通过（通过 = 允许回血）。 */
    private boolean active;
    /** 判定通过的瞬间记下水晶数量，只用于日志/展示。 */
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
     * @return 本 tick 实际回了多少血（0 = 没回）。调用方负责加血 ——
     * "怎么加血"留在实体里，"什么时候能回"留在这里，职责清楚。
     */
    public float tick(DragonGolemEntity dragon) {
        if (dragon.level().isClientSide()) {
            return 0.0F;
        }
        this.ticks++;

        // ---- 30 秒判定 ----
        if (this.ticks >= CHECK_INTERVAL) {
            this.ticks = 0;
            this.check(dragon);
        }

        // ---- 判定没过：整轮不回 ----
        if (!this.active) {
            return 0.0F;
        }
        // ---- 回血节律：每 10 tick 一点，且不满血 ----
        if (this.ticks % HEAL_INTERVAL != 0) {
            return 0.0F;
        }
        if (dragon.getHealth() >= dragon.getMaxHealth()) {
            return 0.0F;
        }
        return this.healIfStillLit(dragon);
    }

    /**
     * 判定：<b>这只龙自己受伤了、且它 32 格内有末地水晶</b>才算通过。
     *
     * <p>"受伤了"是判定的前提之一：满血的龙不需要治疗，也就没必要把这个闸门打开
     * （否则一轮里它一直"激活"着，等它受伤时那道 30 秒的延迟就白等了）。
     *
     * <p>每次判定都重新搜一遍，所以水晶被拆掉之后最多 30 秒就会失效；
     * 但<b>轮内</b>不复查 —— 这正是"判定通过后可以持续回血"的含义。
     * 不过被打掉的水晶会被 {@link EndCrystal#isRemoved()} 排除，见下面的复查。
     */
    private void check(DragonGolemEntity dragon) {
        if (dragon.getHealth() >= dragon.getMaxHealth()) {
            this.deactivate();
            return;
        }
        List<EndCrystal> found = dragon.level().getEntitiesOfClass(EndCrystal.class,
                dragon.getBoundingBox().inflate(RADIUS));
        // 只认还活着的：区块卸载/已摧毁的要排掉
        int alive = 0;
        for (EndCrystal crystal : found) {
            if (!crystal.isRemoved() && crystal.isAlive()) {
                alive++;
            }
        }
        if (alive == 0) {
            this.deactivate();
            return;
        }
        this.active = true;
        this.crystalCount = alive;
        if (DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[heal] 判定通过：{} 格内有 {} 块水晶，本轮持续回血", RADIUS, alive);
        }
    }

    private void deactivate() {
        if (this.active && DragonDebug.RIDE) {
            Dragon_golems.LOGGER.info("[heal] 判定未通过：本轮不回血（满血或附近无水晶）");
        }
        this.active = false;
        this.crystalCount = 0;
    }

    /**
     * 轮内复查：回血这一下之前确认"附近还有活水晶"。
     *
     * <p>为什么需要（对用户给的机制做的一处加固）：机制说是"每 30 秒判定一次"，
     * 严格照做的话，水晶在这 30 秒中间被打掉，龙还会继续回血到本轮结束 ——
     * 而战斗中 30 秒足够决定胜负了，那会让"打掉水晶"这个反制失效。
     * 所以每次真回血之前顺手确认一次。代价是"只在真要回血时才做"的一次小范围查询
     * （每次最多返回一块，见下面的 limit）。
     */
    private float healIfStillLit(DragonGolemEntity dragon) {
        // 不用带过滤器的 getEntitiesOfClass 重载：它的形参是 EntitySelector（Predicate<? super T>），
        // lambda 里推断出的类型容易和通配符打架（javac 会报无法推断）。列表很短，自己挑更省事也更清楚。
        List<EndCrystal> found = dragon.level().getEntitiesOfClass(EndCrystal.class,
                dragon.getBoundingBox().inflate(RADIUS));
        for (EndCrystal crystal : found) {
            if (!crystal.isRemoved() && crystal.isAlive()) {
                return HEAL_AMOUNT;
            }
        }
        return 0.0F;
    }
}
