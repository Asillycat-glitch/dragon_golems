package a_silly_cat.dragon_golems.content.config;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 巨龙傀儡的 TOML 配置（{@code config/l2_configs/dragon_golems-common.toml}）。
 *
 * <h2>为什么放在 {@code l2_configs/} 里</h2>
 * 傀儡装配自己就是这么放的：本家 {@code MGConfig.register} 与 l2library 的
 * {@code L2LibraryConfig.register} 都是同一段手写代码 ——
 * <pre>
 * String path = "l2_configs/" + mod.getModId() + "-" + type.extension() + ".toml";
 * ModLoadingContext.get().registerConfig(type, spec, path);
 * </pre>
 * l2library <b>没有</b>提供任何配置 API（{@code l2_configs} 这个字符串在整个库里只出现那一次），
 * 所以这里照抄同一段，文件名就是 {@code l2_configs/dragon_golems-common.toml}，
 * 和 {@code modulargolems-common.toml}、{@code l2library-common.toml} 并排。
 *
 * <p><b>必须在 {@code @Mod} 构造函数里调 {@link #init()}</b>：{@code registerConfig} 用的是
 * {@code ModLoadingContext.get().getActiveContainer()}（当前正在构造的那个 mod），
 * 放到 {@code FMLCommonSetupEvent} 里注册就会挂到别人的容器上、文件也会被命名成别人的 modid。
 *
 * <p>注意这里和 {@link DragonBodyConfig} 是<b>两套不同的东西</b>：那个是数据包
 * （{@code data/<ns>/modulargolems_config/dragon_bodies/*.json}，管"这条龙长什么样"），
 * 这个是本地配置文件（管"这条龙怎么打"）。整合包作者改怪物表现走数据包，改数值开关走这里。
 *
 * <p><b>读值的姿势</b>：配置文件是在 mod 构造阶段之后才真正读进内存的，任何在"配置就绪之前"
 * 被问到的值都会抛异常。所以这里所有取值都走 {@link #bool} / {@link #num} 这两个兜底包装
 * （取不到就用 Java 侧默认值），调用方永远不需要自己 try/catch。
 */
public final class DragonGolemConfig {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON_SPEC = pair.getRight();
        COMMON = pair.getLeft();
    }

    private DragonGolemConfig() {
    }

    /** 由 {@link Dragon_golems} 的构造函数调用（见类注释：必须在那里，不能更晚）。 */
    public static void init() {
        ModContainer mod = ModLoadingContext.get().getActiveContainer();
        String path = "l2_configs/" + mod.getModId() + "-" + ModConfig.Type.COMMON.extension() + ".toml";
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC, path);
        Dragon_golems.LOGGER.info("[config] 配置文件：config/{}（和傀儡装配的配置放在同一个 l2_configs 目录）", path);
    }

    // ---- 龙息递减（问题：一次龙息总伤害过高）----

    /**
     * 同一次龙息里，对同一个目标的后续伤害是否递减。
     *
     * <p>一次龙息是 60 tick、每 {@code HIT_COOLDOWN}(10) tick 结算一次，所以同一个目标会被打 6 跳。
     * 六跳都按满伤害算的话，单体总伤害约 {@code 0.35 × 6 = 2.1} 倍攻击力 —— 比一次俯冲撞击还高，
     * 而且它是<b>全程无冷却</b>的常态输出（俯冲 30 秒、龙弹/音爆 15 秒）。开了递减之后
     * 一次龙息的单体总量降到约 1.05 倍攻击力（默认 step 0.25 / 下限 0.25），群体覆盖不受影响。
     */
    public static boolean breathDiminishing() {
        return bool(COMMON.breathDiminishing, true);
    }

    /** 每多命中一次，伤害倍率下降多少（默认 0.25 = 第二跳 75%、第三跳 50%…）。 */
    public static double breathDiminishingStep() {
        return num(COMMON.breathDiminishingStep, 0.25D);
    }

    /** 递减的下限倍率（默认 0.25）：再怎么挨打也不会低到 0，免得出现"喷了不掉血"。 */
    public static double breathDiminishingMin() {
        return num(COMMON.breathDiminishingMin, 0.25D);
    }

    // ---- 坐骑升级能不能装在龙身上（问题：骑乘升级被本家挡在门外）----

    /**
     * 本家的「坐骑升级」（{@code modulargolems:mount_upgrade}）能不能装到龙身上。
     *
     * <p>本家 {@code RideUpgrade.fitsOn} 写死了 {@code type == GolemTypes.TYPE_DOG}（只认狗），
     * 所以升级台 / 铁砧都会把它从龙的可用列表里剔掉。装上去之后龙会带上 {@code GolemFlags.PASSIVE}
     * （不主动攻击、不被 mob 当目标），但<b>驾驶完全不受影响</b>：龙自己的
     * {@code canRideWith} / {@code DragonRideHandler} / 骑手指令都不看这个 flag，只有
     * "AI 自己索敌开火"会被关掉 —— 玩家按 R/G/V 时那几秒另有 {@code riderCombat} 开关放行。
     */
    public static boolean mountUpgradeForDragon() {
        return bool(COMMON.mountUpgradeForDragon, true);
    }

    // ---- 选定 / 拾取（问题：选定框太小，回收和选择不方便）----

    /**
     * 头部 / 脖颈 / 躯干 / 尾巴 / 双翼这些<b>子碰撞箱</b>的额外拾取半径（格，默认 1.0）。
     *
     * <p>它只影响"能不能被准星选中 / 回收手杖能不能点到"，<b>完全不动碰撞体积</b>。
     * 默认值会让座椅净空把乘客抬高约 0.25 格（躯干箱被吹高之后，眼睛需要跟着让位），
     * 属于看不出来的量级；想要"一个像素都不动"就把它调到 0.5 以下。
     */
    public static double pickRadius() {
        return num(COMMON.pickRadius, 1.0D);
    }

    /**
     * 本体那一个判定箱的额外拾取半径（格，默认 0.5）。
     *
     * <p>刻意比子箱小：乘客的座位就在本体判定箱上方一点，这个半径会把判定箱往上"吹"，
     * 而原版 {@code ProjectileUtil} 对"射线起点落在某个箱子里"这一支是<b>不看同车关系</b>的
     * （见 {@code DragonGolemEntity.positionRider} 里座椅净空的说明）——
     * 吹得太高会把骑着龙的人自己的准星重新拽回自己的龙身上。座椅净空会自动跟着这两个值抬高，
     * 所以调大不会出 bug，只是会让龙背上的人坐得更高。
     */
    public static double bodyPickRadius() {
        return num(COMMON.bodyPickRadius, 0.5D);
    }

    // ---- 技能升级的挂载点（问题：技能升级对龙基本没有效果）----

    /**
     * 是否保留本家（含 compat 材料）挂上来的那一批远程攻击 goal。
     *
     * <p>龙的三个攻击 goal 是自己写的，而本家那批 {@code *AttackGoal}（炽焰喷射、死光、音波炮…）
     * 全是照"站在地上的傀儡"写的：出手点在身体中心、射界是固定数字（不随体型缩放）、
     * 而且伤害走原版 {@code hurt}，会把目标重新顶进无敌帧 —— 所以默认<b>整批摘掉</b>。
     * 打开这个开关就原样放回来，代价是上面那几条都会一起回来（还会和龙自己的招式同台）。
     *
     * <p>注意这条只影响"<b>靠 goal 出手</b>"的技能；靠伤害事件生效的那些升级
     * （药水效果 / 目标加成 / 穿甲 / 吸血 / 击杀特效…）走的是
     * {@code DragonGolemEntity.dealSkillDamage} 那条统一通道，<b>不需要</b>这个开关。
     */
    public static boolean nativeSkillGoals() {
        return bool(COMMON.nativeSkillGoals, false);
    }

    /**
     * 俯冲撞击时，要不要顺带触发"<b>只有近战才会触发</b>"的那一族升级。
     *
     * <p>本家的地震 / 跳劈（{@code EarthquakeHelper}，来自巨兽、猫灾、Mowzie 那些材料的 modifier）
     * 是<b>唯一</b>挂在近战管线上的技能：只有 {@code GolemMeleeGoal} 的跳劈会问
     * {@code EarthquakeHelper.findInstance}。龙没有近战攻击，所以这一族升级在龙身上原本是死的；
     * 打开这个开关之后，<b>俯冲（撞上去那一下）就是龙的"近战"</b> —— 撞击命中的那一刻按同一套
     * 冷却与射程判定触发一次地震。
     *
     * <p>本家自己的规则照旧遵守：带着乘客的傀儡不会触发地震（`findInstance` 的第一道闸门），
     * 被动（装坐骑升级）的龙也不会。骑手按 R 的冲锋走的是同一个 goal，所以一样会触发。
     */
    public static boolean diveTriggersMelee() {
        return bool(COMMON.diveTriggersMelee, true);
    }

    // ---- 属性（问题：属性快要顶到原版上限）----

    /**
     * 属性超过原版上限时，在刷新属性的那一刻打一行日志。
     *
     * <p>原版 {@code RangedAttribute.sanitizeValue} 是<b>静默</b>夹取的：超过上限的部分直接丢掉，
     * 不报错、不留痕，"这条龙为什么少了 300 血"完全看不出来。开着它至少能在日志里看到
     * "哪个属性、基础值多少、上限多少"。
     */
    public static boolean logAttributeOverflow() {
        return bool(COMMON.logAttributeOverflow, true);
    }

    /**
     * 内置版的 AttributeFix：把<b>最大生命</b>的原版上限抬到 {@link #maxHealthCap()}，<b>默认开</b>。
     *
     * <p>开着的原因很直接：龙的部件系数合计 HEALTH 2.0（本家金属傀儡是 1.0），幽匿身体的龙裸装就有
     * <b>1000 血 = 原版 1024 上限的 97.7%</b>，再挂一级体型升级（+20%/级）或坐骑升级（+20%）
     * 就会被原版 {@code RangedAttribute.sanitizeValue} <b>静默夹掉</b> —— 材料与升级白铸，
     * 而且不留任何日志（本 mod 已经补了 `attributes.logOverflow` 那一行提示）。
     * 效果等价于 AttributeFix 对最大生命那一条。
     *
     * <p>只放宽最大生命：护甲上限放宽没有实际收益（原版 {@code CombatRules} 内部就把护甲减伤封在 80%，
     * 超过约 20~30 点护甲不再增加减伤），其余属性（攻击、攻速、移速、飞行速度、各种 golem_*）
     * 实测都只用到上限的百分之几 —— 只有最大生命这条会真的被夹。
     *
     * <p>与真正的 AttributeFix 同时安装不冲突：这里只做"只升不降"。
     * 不想改原版上限（比如整合包另有安排）就把它设成 false，此时超上限的部分依旧会被静默夹掉。
     */
    public static boolean maxHealthCapFix() {
        return bool(COMMON.maxHealthCapFix, true);
    }

    /** 内置属性修复时，最大生命的上限抬到多少（默认 10 万，够任何整合包用）。 */
    public static double maxHealthCap() {
        return num(COMMON.maxHealthCap, 100000.0D);
    }

    // ---- 兜底读取 ----

    private static boolean bool(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (Throwable e) {
            // 配置还没加载（mod 构造阶段 / 数据包重载中的极端时序）→ 用 Java 侧默认值
            return fallback;
        }
    }

    private static double num(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (Throwable e) {
            return fallback;
        }
    }

    public static class Common {

        public final ForgeConfigSpec.BooleanValue breathDiminishing;
        public final ForgeConfigSpec.DoubleValue breathDiminishingStep;
        public final ForgeConfigSpec.DoubleValue breathDiminishingMin;

        public final ForgeConfigSpec.BooleanValue mountUpgradeForDragon;

        public final ForgeConfigSpec.DoubleValue pickRadius;
        public final ForgeConfigSpec.DoubleValue bodyPickRadius;

        public final ForgeConfigSpec.BooleanValue nativeSkillGoals;
        public final ForgeConfigSpec.BooleanValue diveTriggersMelee;

        public final ForgeConfigSpec.BooleanValue logAttributeOverflow;
        public final ForgeConfigSpec.BooleanValue maxHealthCapFix;
        public final ForgeConfigSpec.DoubleValue maxHealthCap;

        Common(ForgeConfigSpec.Builder builder) {
            builder.comment("龙息（唯一没有冷却的常态攻击）").push("breath");
            this.breathDiminishing = builder
                    .comment("同一次龙息里，对同一个目标的后续命中是否递减伤害",
                            "一次龙息 60 tick、每 10 tick 结算一次 = 单体 6 跳；不开递减时单体总量约 2.1 倍攻击力")
                    .define("diminishing", true);
            this.breathDiminishingStep = builder
                    .comment("每多命中一次，伤害倍率下降多少（0.25 = 第二跳 75%、第三跳 50%…）")
                    .defineInRange("diminishingStep", 0.25D, 0.0D, 1.0D);
            this.breathDiminishingMin = builder
                    .comment("递减的下限倍率（0.25 = 再怎么挨打也保留 25% 伤害）")
                    .defineInRange("diminishingMin", 0.25D, 0.0D, 1.0D);
            builder.pop();

            builder.comment("骑乘").push("mount");
            this.mountUpgradeForDragon = builder
                    .comment("本家的坐骑升级（modulargolems:mount_upgrade）能否装到龙身上",
                            "本家 fitsOn 只认狗型；开关只影响『能不能装』，装上后的驾驶/骑手指令照常可用")
                    .define("mountUpgradeOnDragon", true);
            builder.pop();

            builder.comment("选定 / 拾取（准星选中、回收手杖、徒手回收都吃这一项）").push("selection");
            this.pickRadius = builder
                    .comment("头/颈/躯干/尾/双翼这些子碰撞箱的额外拾取半径（格）")
                    .defineInRange("pickRadius", 1.0D, 0.0D, 8.0D);
            this.bodyPickRadius = builder
                    .comment("本体判定箱的额外拾取半径（格）",
                            "比子箱小是故意的：吹得太高会把龙背上那位乘客的准星重新拽回自己的龙身上",
                            "（座椅净空会自动跟着它抬高，所以调大不会出 bug，只是人坐得更高）")
                    .defineInRange("bodyPickRadius", 0.5D, 0.0D, 8.0D);
            builder.pop();

            builder.comment("技能升级").push("skills");
            this.nativeSkillGoals = builder
                    .comment("保留本家/compat 挂上来的那批远程攻击 goal（*AttackGoal）",
                            "默认关：那批 goal 是按『站在地上的傀儡』写的（身体中心出手、固定射界、会顶掉无敌帧）",
                            "靠伤害事件生效的升级（药水/加成/穿甲/吸血/击杀特效）走 dealSkillDamage 统一通道，不需要这个开关")
                    .define("nativeSkillGoals", false);
            this.diveTriggersMelee = builder
                    .comment("俯冲撞击时触发『只有近战才会触发』的那一族升级（本家 EarthquakeHelper：地震/跳劈）",
                            "龙没有近战攻击，这一族升级原本在龙身上是死的；开着就是『俯冲 = 龙的近战』",
                            "本家规则照旧：带乘客的傀儡、被动（装坐骑升级）的龙都不会触发")
                    .define("diveTriggersMelee", true);
            builder.pop();

            builder.comment("属性").push("attributes");
            this.logAttributeOverflow = builder
                    .comment("属性基础值超过原版上限时在日志里打一行（原版是静默夹取的，超了完全看不出来）")
                    .define("logOverflow", true);
            this.maxHealthCapFix = builder
                    .comment("内置版的 AttributeFix：把最大生命的原版上限（1024）抬到 maxHealthCap",
                            "默认开 —— 幽匿龙裸装 1000 血已经是 1024 的 97.7%，任何 +20% 生命的升级都会被静默夹掉",
                            "只放宽最大生命：护甲上限放宽没有实际收益（原版内部封顶 80% 减伤），其余属性离上限都很远",
                            "装了真正的 AttributeFix 也不冲突（只升不降）；不想动原版上限就设 false")
                    .define("maxHealthCapFix", true);
            this.maxHealthCap = builder
                    .comment("内置属性修复时最大生命的上限值")
                    .defineInRange("maxHealthCap", 100000.0D, 1024.0D, 100000000.0D);
            builder.pop();
        }
    }
}
