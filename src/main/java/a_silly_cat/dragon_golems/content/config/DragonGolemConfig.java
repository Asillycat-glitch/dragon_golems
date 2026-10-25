package a_silly_cat.dragon_golems.content.config;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.util.Mth;
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
        return bool(COMMON.breathDiminishing, false);
    }

    /**
     * <b>龙息基值倍率</b>（默认 1.0，0.25~8）。
     *
     * <p>护甲高的整合包里，每跳固定伤害会被护甲吃掉很大一块（"伤害质量急剧下降"），
     * 靠这个倍率直接补，不需要改代码重新打包。
     */
    public static double breathDamageMultiplier() {
        return Mth.clamp(num(COMMON.breathDamageMultiplier, 1.0D), 0.25D, 8.0D);
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
     * 实际生效值还会被自动削一次：罩着乘客的箱子不许把乘客的眼睛包进拾取箱
     * （泰坦体型时那一块会变成负数），否则骑手的准星会被自己的坐骑吃掉 ——
     * 详见 {@code DragonGolemEntity.pickRadiusFor}。座椅位置本身<b>只由模型决定</b>，不受这里影响。
     */
    public static double pickRadius() {
        return num(COMMON.pickRadius, 1.0D);
    }

    /**
     * 本体那一个判定箱的额外拾取半径（格，默认 0.5）。
     *
     * <p>刻意比子箱小：本体判定箱就在座位下面，而原版
     * {@code ProjectileUtil.getEntityHitResult} 对"射线起点落在箱子里"这一支是
     * <b>不看同车关系</b>的，吹得太高容易把骑手的准星拽回自己的龙身上。
     * 真的调大了也不会出 bug：{@code pickRadiusFor} 会自动把这一块收到不包住眼睛为止。
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


    /**
     * <b>龙的升级槽数量</b>（默认 5 = 5 个部件各占一槽，和本家默认一致）。
     *
     * <p>本家的做法是"每种傀儡类型各自读一个配置"（{@code largeGolemSlot} / {@code humanoidGolemSlot} /
     * {@code dogGolemSlot}，见 {@code MetalGolemType#getUpgradeSlots}）。龙是自定义类型，
     * 没有对应的本家键，所以这里给它自己一个。
     */
    public static double upgradeSlots() {
        return Mth.clamp(num(COMMON.upgradeSlots, 5.0D), 0.0D, 100.0D);
    }

    /** 内置属性修复时，最大生命的上限抬到多少（默认 10 万，够任何整合包用）。 */
    public static double maxHealthCap() {
        return num(COMMON.maxHealthCap, 100000.0D);
    }

    // ---- 洞穴模式（在 0.4 上追加，不动已有逻辑）----

    /** 洞穴模式总开关（默认开）。关掉 = 恢复到 0.4 的纯开阔地行为。 */
    public static boolean caveMode() {
        return bool(COMMON.caveEnabled, true);
    }

    /**
     * 头顶可用空间小于这个值就算"洞穴"（格，含身体高度之外还要的余量）。
     * 默认 14：0.4 的绕圈半径对 1 倍体型约 22 格，天花板低于 14 格时绕圈必然贴顶。
     */
    public static double caveCeilingRoom() {
        return Math.max(0.0D, num(COMMON.caveCeilingRoom, 14.0D));
    }

    /**
     * 水平净空小于 {@code orbitRadius() × 这个系数} 也算"洞穴"。默认 0.9。
     * 用中位数（8 方向各探一次），所以贴着一面墙不会误判。
     */
    public static double caveSideRoomFactor() {
        return Math.max(0.0D, num(COMMON.caveSideRoomFactor, 0.9D));
    }

    /** 洞穴"引导式喷息"（默认开）：命中不依赖喷流几何，只看射程 + 同层。 */
    public static boolean caveGuidedDamage() {
        // 本版强制走几何判定（喷流打到才有伤害）：旧配置文件里 guidedDamage 可能还是 true。
        return false;
    }

    /** 引导喷息的射程（格，默认 24）。 */
    public static double caveGuideRange() {
        return Mth.clamp(num(COMMON.caveGuideRange, 24.0D), 4.0D, 48.0D);
    }

    /** 引导喷息允许的高度差（Y，默认 8）。 */
    public static double caveGuideYTolerance() {
        return Mth.clamp(num(COMMON.caveGuideYTolerance, 8.0D), 1.0D, 32.0D);
    }

    /** 洞穴闪避（默认开）：投射物飞来 / 刚挨打时做一次侧向脱离。 */
    public static boolean caveDodge() {
        return bool(COMMON.caveDodge, true);
    }

    /**
     * 洞穴里是否允许 AI 走"幽灵模式"（{@code noPhysics}，直接穿墙；默认开）。
     *
     * <p>只在<b>没有骑手</b>且 {@code DragonGolemEntity.inCave()} 判定成立时生效。洞里 2.6 格宽的判定箱
     * 物理上就塞不进矿道；与其让它贴着墙磨、反复触发脱困，不如明确允许穿墙，
     * 由 {@code DragonGolemEntity.caveStandoff(...)} 保证它仍然停在敌人的近战范围之外。
     *
     * <p>关掉之后一切交回 {@code DragonFlightAssist} 的自动脱困 / 常驻幽灵。
     */
    public static boolean caveGhost() {
        return bool(COMMON.caveGhost, true);
    }

    /**
     * 洞穴里脚底离<b>岩浆液面</b>至少留多少格（默认 3.0）。
     *
     * <p>水体只用默认的 1.5 格兜底；岩浆要更宽，因为龙的模型比判定箱更宽，
     * 贴着液面飞时翅膀/尾巴会"蹭"进液面（不掉血但很难看）。
     */
    public static double caveLavaClearance() {
        return Mth.clamp(num(COMMON.caveLavaClearance, 3.0D), 0.0D, 16.0D);
    }

    /** 天花板余量（默认 1.5 格）：允许探头到天花板之上一点，但不允许飞出房间。 */
    public static double caveCeilingSlack() {
        return Mth.clamp(num(COMMON.caveCeilingSlack, 1.5D), 0.0D, 6.0D);
    }

    /** 喷息时保持在目标上方多少格（默认 4.0）：地面近战够不到这个高度。 */
    public static double caveAttackHeight() {
        return Mth.clamp(num(COMMON.caveAttackHeight, 4.0D), 0.0D, 16.0D);
    }



    /** 站位倍率（默认 1.0）：调大 = 站得更远。 */
    public static double caveStandoffFactor() {
        return Mth.clamp(num(COMMON.caveStandoffFactor, 1.0D), 0.5D, 3.0D);
    }




    /** 洞穴里俯冲改"冲刺"（默认开：不做高度前摇，直接朝目标方向冲）。 */
    public static boolean caveDash() {
        // 冲刺分支暂不启用（洞穴里改成"追杀 + 站位"；旧配置文件里可能是 true）
        return false;
    }

    /** 洞穴里是否禁止抽俯冲（默认开：大弧度动作在狭小场地只会贴墙）。 */
    public static boolean caveNoDive() {
        return bool(COMMON.caveNoDive, true);
    }

    /** 洞穴里喷息是否站桩（默认开：不绕圈、不压弯，只动头）。 */
    public static boolean caveHoldBreath() {
        return bool(COMMON.caveHoldBreath, true);
    }

    /**
     * <b>绕圈喷息时的倾斜角（度）</b>，默认 20。0 = 不压弯。
     *
     * <p>脚本圆是"先有轨迹、再叠姿态"：轨迹决定位置，这个角度只决定机身往圆心内倾多少；
     * 正负由绕行方向自动决定（顺时针/逆时针都往圆心倾）。
     */
    public static double breathOrbitBank() {
        return num(COMMON.breathOrbitBank, 20.0D);
    }

    /**
     * <b>平原龙息转圈的线速度</b>（格/tick，基准值，会再乘速度倍率）。默认 0.55。
     *
     * <p>这是动画参数：角速度 = 线速度 / 半径。调大转得更快、观感更"急"。
     */
    public static double breathOrbitSpeed() {
        return Mth.clamp(num(COMMON.breathOrbitSpeed, 0.55D), 0.05D, 3.0D);
    }

    // ---- 寻路（0.4 追加：体型感知 + 自算航点）----

    /**
     * 寻路总开关（默认开）。关掉退回"直飞目标"。
     *
     * <p>★ 这一条<b>不再是硬编码 false</b>。以前的 A* 会在矿道里把主线程卡死，
     * 所以被临时强制关闭；现在每一层都有性能护栏：
     * <ul>
     *   <li>直飞路径（身体盒 + 液体都干净）时<b>根本不跑 A*</b>，见 {@code DragonPathFollower.steer}；</li>
     *   <li>幽灵模式（{@code noPhysics}）直接穿墙飞，也不跑 A*；</li>
     *   <li>单次搜索节点数、单 tick 全服搜索次数、单次耗时超限后的冷却都封了顶。</li>
     * </ul>
     * 真出问题时在 TOML 里把 {@code pathfinding.enabled} 设成 false 即可退回直飞。
     */
    public static boolean pathEnabled() {
        return bool(COMMON.pathEnabled, true);
    }

    /** 每隔多少 tick 重算路线（默认 20 = 每秒最多一次）。 */
    public static int pathRepathTicks() {
        return Math.max(20, (int) num(COMMON.pathRepathTicks, 20.0D));
    }

    /** 寻路节点是否按"整条龙的身体盒子"判定（默认开）。 */
    public static boolean pathBodyClearance() {
        return bool(COMMON.pathBodyClearance, true);
    }

    /** 寻路节点给身体留的额外余量（格，默认 0.6）。 */
    public static double pathBodyMargin() {
        return Mth.clamp(num(COMMON.pathBodyMargin, 0.60D), 0.0D, 2.0D);
    }

    /**
     * 寻路节点与岩浆之间额外留的余量（格，默认 0.6）。
     *
     * <p>岩浆没有碰撞箱，旧版评估器只检查"有没有碰撞形状"，于是会把航路点放在
     * 岩浆湖边缘一格的位置 —— 判定箱过得去，但龙的模型（翅膀/尾巴比判定箱宽）
     * 会蹭进液面。这个余量同时用在节点评估和路线拉直上。
     */
    public static double pathLavaMargin() {
        return Mth.clamp(num(COMMON.pathLavaMargin, 0.60D), 0.0D, 4.0D);
    }

    /** 路线拉直的前瞻节点数（默认 6，0 = 关闭）。 */
    public static int pathSmoothingLookahead() {
        return (int) Mth.clamp(num(COMMON.pathSmoothingLookahead, 6.0D), 0.0D, 24.0D);
    }

    /** 移动/寻路诊断日志（默认关）。 */
    public static boolean flightLog() {
        return bool(COMMON.flightLog, false);
    }

    /** 航线诊断日志（默认开：排查"不绕柱子 / 判定能过其实进不去"时必看）。 */
    public static boolean pathLog() {
        return bool(COMMON.pathLog, true);
    }

    /** 液体判定：流水（瀑布/水渠）算不算支撑面（默认 false = 忽略，只认静水）。 */
    public static boolean flowingFluidIsFloor() {
        return bool(COMMON.flowingFluidIsFloor, false);
    }

    // ---- 末地水晶护盾（0.4 追加）----

    /** 是否免疫"末地水晶自爆"（只针对 EndCrystal 直接来源，不是所有爆炸）。 */
    public static boolean crystalExplosionImmune() {
        return bool(COMMON.crystalExplosionImmune, false);
    }

    /** 水晶护盾总开关（默认开）。关掉后附近水晶只回血，不再加护甲/韧性。 */
    public static boolean crystalGuard() {
        return bool(COMMON.crystalGuard, true);
    }

    /** 每块存活水晶提供多少护甲（默认 4）。 */
    public static double crystalArmorPerCrystal() {
        return Mth.clamp(num(COMMON.crystalArmorPerCrystal, 4.0D), 0.0D, 30.0D);
    }

    /** 水晶护盾的护甲上限（默认 16）。 */
    public static double crystalArmorCap() {
        return Mth.clamp(num(COMMON.crystalArmorCap, 16.0D), 0.0D, 60.0D);
    }

    /** 每块存活水晶提供多少护甲韧性（默认 2）。 */
    public static double crystalToughnessPerCrystal() {
        return Mth.clamp(num(COMMON.crystalToughnessPerCrystal, 2.0D), 0.0D, 20.0D);
    }

    /** 水晶护盾的韧性上限（默认 8）。 */
    public static double crystalToughnessCap() {
        return Mth.clamp(num(COMMON.crystalToughnessCap, 8.0D), 0.0D, 40.0D);
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
        public final ForgeConfigSpec.DoubleValue breathDamageMultiplier;
        public final ForgeConfigSpec.DoubleValue breathDiminishingStep;
        public final ForgeConfigSpec.DoubleValue breathDiminishingMin;

        public final ForgeConfigSpec.BooleanValue mountUpgradeForDragon;

        public final ForgeConfigSpec.DoubleValue pickRadius;
        public final ForgeConfigSpec.DoubleValue bodyPickRadius;

        public final ForgeConfigSpec.BooleanValue nativeSkillGoals;
        public final ForgeConfigSpec.BooleanValue diveTriggersMelee;

        public final ForgeConfigSpec.DoubleValue upgradeSlots;
        public final ForgeConfigSpec.BooleanValue logAttributeOverflow;
        public final ForgeConfigSpec.BooleanValue maxHealthCapFix;
        public final ForgeConfigSpec.DoubleValue maxHealthCap;

        public final ForgeConfigSpec.BooleanValue caveEnabled;
        public final ForgeConfigSpec.DoubleValue caveCeilingRoom;
        public final ForgeConfigSpec.DoubleValue caveSideRoomFactor;
        public final ForgeConfigSpec.BooleanValue caveNoDive;
        public final ForgeConfigSpec.BooleanValue caveDash;
        public final ForgeConfigSpec.BooleanValue caveGuidedDamage;
        public final ForgeConfigSpec.DoubleValue caveGuideRange;
        public final ForgeConfigSpec.DoubleValue caveGuideYTolerance;
        public final ForgeConfigSpec.BooleanValue caveDodge;
        public final ForgeConfigSpec.BooleanValue caveGhost;
        public final ForgeConfigSpec.DoubleValue caveLavaClearance;
        public final ForgeConfigSpec.DoubleValue caveCeilingSlack;
        public final ForgeConfigSpec.DoubleValue caveAttackHeight;
        public final ForgeConfigSpec.DoubleValue caveStandoffFactor;
        public final ForgeConfigSpec.BooleanValue caveHoldBreath;

        public final ForgeConfigSpec.BooleanValue crystalGuard;
        public final ForgeConfigSpec.BooleanValue crystalExplosionImmune;
        public final ForgeConfigSpec.DoubleValue crystalArmorPerCrystal;
        public final ForgeConfigSpec.DoubleValue crystalArmorCap;
        public final ForgeConfigSpec.DoubleValue crystalToughnessPerCrystal;
        public final ForgeConfigSpec.DoubleValue crystalToughnessCap;

        public final ForgeConfigSpec.DoubleValue breathOrbitBank;
        public final ForgeConfigSpec.BooleanValue pathEnabled;
        public final ForgeConfigSpec.DoubleValue pathRepathTicks;
        public final ForgeConfigSpec.BooleanValue pathBodyClearance;
        public final ForgeConfigSpec.DoubleValue pathBodyMargin;
        public final ForgeConfigSpec.DoubleValue pathLavaMargin;
        public final ForgeConfigSpec.DoubleValue pathSmoothingLookahead;
        public final ForgeConfigSpec.BooleanValue flightLog;
        public final ForgeConfigSpec.BooleanValue pathLog;
        public final ForgeConfigSpec.BooleanValue flowingFluidIsFloor;
        public final ForgeConfigSpec.DoubleValue breathOrbitSpeed;

        Common(ForgeConfigSpec.Builder builder) {
            this.flowingFluidIsFloor = builder
                    .comment("流水（瀑布/水渠）算不算支撑面（默认 false = 忽略，只认静水）",
                            "开着时龙会顺着瀑布往上爬 —— 真机反馈『冲不过瀑布 / 在瀑布里抽搐』",
                            "注意：流动岩浆永远算支撑面（岩浆不能像流水一样忽略）")
                    .define("flowingFluidIsFloor", false);
            builder.comment("末地水晶：治疗光束 + 护盾（附近每块存活水晶给龙加护甲/韧性）").push("crystal");
            this.crystalExplosionImmune = builder
                    .comment("免疫末地水晶自爆（默认 false，和原版末影龙一致：水晶自爆会对龙造成伤害）",
                            "只针对 EndCrystal 作为直接来源的爆炸；TNT/苦力怕/火球仍然正常结算，",
                            "打开后也不会和爆炸免疫/海绵升级冲突，但水晶会变成纯增益")
                    .define("explosionImmune", false);
            this.crystalGuard = builder
                    .comment("水晶护盾总开关（默认 true）",
                            "附近每有一块存活水晶，给龙加 crystal.armorPerCrystal 点护甲、crystal.toughnessPerCrystal 点韧性；",
                            "水晶被拆掉后 1 秒内自动回落到原值")
                    .define("guard", true);
            this.crystalArmorPerCrystal = builder
                    .comment("每块水晶提供的护甲（默认 4）")
                    .defineInRange("armorPerCrystal", 4.0D, 0.0D, 30.0D);
            this.crystalArmorCap = builder
                    .comment("水晶护盾的护甲上限（默认 16）")
                    .defineInRange("armorCap", 16.0D, 0.0D, 60.0D);
            this.crystalToughnessPerCrystal = builder
                    .comment("每块水晶提供的护甲韧性（默认 2）")
                    .defineInRange("toughnessPerCrystal", 2.0D, 0.0D, 20.0D);
            this.crystalToughnessCap = builder
                    .comment("水晶护盾的韧性上限（默认 8）")
                    .defineInRange("toughnessCap", 8.0D, 0.0D, 40.0D);
            builder.pop();
            builder.comment("龙息（唯一没有冷却的常态攻击）").push("breath");
            this.breathDiminishing = builder
                    .comment("同一次龙息里，对同一个目标的后续命中是否递减伤害（默认 false）",
                            "默认关：护甲高的整合包里递减会让总伤害低到没意义；需要时再打开",
                            "开着时一次龙息单体总量约 1.05 倍攻击力；关着时约 2.1 倍")
                    .define("diminishing", false);
            this.breathDamageMultiplier = builder
                    .comment("龙息基值倍率（默认 1.0，0.25~8）",
                            "护甲会把每跳伤害吃掉很多，用这个直接补，不需要改代码")
                    .defineInRange("damageMultiplier", 1.0D, 0.25D, 8.0D);
            this.breathDiminishingStep = builder
                    .comment("每多命中一次，伤害倍率下降多少（0.25 = 第二跳 75%、第三跳 50%…）")
                    .defineInRange("diminishingStep", 0.25D, 0.0D, 1.0D);
            this.breathDiminishingMin = builder
                    .comment("递减的下限倍率（0.25 = 再怎么挨打也保留 25% 伤害）")
                    .defineInRange("diminishingMin", 0.25D, 0.0D, 1.0D);
            this.breathOrbitBank = builder
                    .comment("绕圈喷息时的倾斜角（度，默认 20）；0 = 不压弯",
                            "脚本圆决定位置，这个角度只决定机身往圆心内倾多少；正负由绕行方向自动决定")
                    .defineInRange("orbitBank", 20.0D, 0.0D, 60.0D);
            this.breathOrbitSpeed = builder
                    .comment("平原龙息转圈的线速度（格/tick，基准值，再乘速度倍率），默认 0.55",
                            "角速度 = 线速度 / 绕圈半径；调大转得更急")
                    .defineInRange("orbitSpeed", 0.55D, 0.05D, 3.0D);
            builder.pop();

            builder.comment("骑乘").push("mount");
            this.mountUpgradeForDragon = builder
                    .comment("本家的坐骑升级（modulargolems:mount_upgrade）能否装到龙身上",
                            "本家 fitsOn 只认狗型；开关只影响『能不能装』，装上后的驾驶/骑手指令照常可用")
                    .define("mountUpgradeOnDragon", true);
            builder.pop();

            builder.comment("选定 / 拾取（准星选中、回收手杖、徒手回收都吃这一项）").push("selection");
            this.pickRadius = builder
                    .comment("头/颈/躯干/尾/双翼这些子碰撞箱的额外拾取半径（格）",
                            "只影响准星选中与回收手杖，不动碰撞体积；罩着乘客的那一块会自动收缩")
                    .defineInRange("pickRadius", 1.0D, 0.0D, 8.0D);
            this.bodyPickRadius = builder
                    .comment("本体判定箱的额外拾取半径（格）",
                            "比子箱小是故意的：本体判定箱就在座位下面（罩着乘客的那一块会自动收缩，不会挡住骑手）")
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
            this.upgradeSlots = builder
                    .comment("龙的升级槽数量（默认 5 = 每个部件一槽）",
                            "和本家的 largeGolemSlot / humanoidGolemSlot / dogGolemSlot 是同一类东西")
                    .defineInRange("upgradeSlots", 5.0D, 0.0D, 100.0D);

            this.maxHealthCap = builder
                    .comment("内置属性修复时最大生命的上限值")
                    .defineInRange("maxHealthCap", 100000.0D, 1024.0D, 100000000.0D);
            builder.pop();

            builder.comment("寻路（体型感知 + 自算航点）").push("pathfinding");
            this.pathEnabled = builder
                    .comment("寻路总开关（默认 true）；关掉退回『直飞目标』",
                            "直飞路径干净时不会跑 A*；幽灵模式也不跑 A*；单 tick 搜索次数与节点数都有硬上限")
                    .define("enabled", true);
            this.pathRepathTicks = builder
                    .comment("每隔多少 tick 重算路线（默认 20 = 每秒最多一次；代码会把小于 20 的值夹到 20）")
                    .defineInRange("repathTicks", 20.0D, 4.0D, 200.0D);
            this.pathBodyClearance = builder
                    .comment("寻路节点是否按『整条龙的身体盒子』判定（默认 true）",
                            "原版飞行评估器只看方块格、不看实体尺寸，2.6 格宽的龙会被规划进 1 格缝")
                    .define("bodyClearance", true);
            this.pathBodyMargin = builder
                    .comment("寻路节点给身体留的额外余量（格，默认 0.6）")
                    .defineInRange("bodyMargin", 0.60D, 0.0D, 2.0D);
            this.pathLavaMargin = builder
                    .comment("寻路节点与岩浆额外保持的距离（格，默认 0.6）",
                            "岩浆没有碰撞箱，只按身体盒判定会贴着液面走；这个余量让航点离岩浆更远")
                    .defineInRange("lavaMargin", 0.60D, 0.0D, 4.0D);
            this.pathSmoothingLookahead = builder
                    .comment("路线拉直的前瞻节点数（默认 6；0 = 关闭）")
                    .defineInRange("smoothingLookahead", 6.0D, 0.0D, 24.0D);
            builder.pop();

            builder.comment("诊断").push("debug");
            this.flightLog = builder
                    .comment("移动/寻路诊断日志（默认 false）")
                    .define("flightLog", false);
            this.pathLog = builder
                    .comment("航线诊断日志 [path]（默认 true：排查『不绕柱子 / 判定能过其实进不去』必看）")
                    .define("pathLog", true);
            builder.pop();
            builder.comment("洞穴模式（狭小场地：废弃工厂 / 矿洞 / 室内）").push("cave");
            this.caveEnabled = builder
                    .comment("洞穴模式总开关（默认开）",
                            "关掉 = 完全恢复 0.4 的开阔地行为（绕圈喷息、照常抽俯冲）")
                    .define("enabled", true);
            this.caveCeilingRoom = builder
                    .comment("头顶可用空间小于这个值就算洞穴（格）",
                            "0.4 的绕圈半径 1 倍体型约 22 格；天花板低于 14 格时绕圈必然贴顶")
                    .defineInRange("ceilingRoom", 14.0D, 0.0D, 128.0D);
            this.caveSideRoomFactor = builder
                    .comment("水平净空小于『绕圈半径 × 这个系数』也算洞穴（默认 0.9）",
                            "净空取 8 个方向『整条龙塞得下』的最远距离的中位数，贴一面墙不会误判")
                    .defineInRange("sideRoomFactor", 0.9D, 0.0D, 4.0D);
            this.caveNoDive = builder
                    .comment("洞穴里不抽俯冲（默认开）")
                    .define("noDive", true);
            this.caveDash = builder
                    .comment("洞穴里俯冲改成『冲刺』（默认开）",
                            "不做『飞到目标后方 16 格 + 爬 12 格高』的前摇，直接从当前位置朝目标方向冲")
                    .define("caveDash", false);
            this.caveGuidedDamage = builder
                    .comment("洞穴『引导式喷息』（默认 true）",
                            "洞里塞不下/穿模都是必然，所以命中不再依赖喷流几何：只要射程内 + 同层，每 10 tick 结算一跳")
                    .define("guidedDamage", false);
            this.caveGuideRange = builder
                    .comment("引导喷息的射程（格，默认 24）")
                    .defineInRange("guideRange", 24.0D, 4.0D, 48.0D);
            this.caveGuideYTolerance = builder
                    .comment("引导喷息允许的高度差（默认 8）")
                    .defineInRange("guideYTolerance", 8.0D, 1.0D, 32.0D);
            this.caveAttackHeight = builder
                    .comment("喷息时保持在目标上方多少格（默认 4.0）",
                            "地面近战够不到这个高度；它先在下方爬升，再开火")
                    .defineInRange("attackHeight", 4.0D, 0.0D, 16.0D);
            this.caveCeilingSlack = builder
                    .comment("天花板余量（默认 1.5 格）",
                            "幽灵模式允许探头到天花板之上一点；调大 = 更容易被近战够到但不会飞出房间")
                    .defineInRange("ceilingSlack", 1.5D, 0.0D, 6.0D);
            this.caveStandoffFactor = builder
                    .comment("站位倍率（默认 1.0，越大站得越远）")
                    .defineInRange("standoffFactor", 1.0D, 0.5D, 3.0D);
            this.caveDodge = builder
                    .comment("洞穴闪避（默认 true）",
                            "投射物朝它飞来、或刚挨近战 → 侧向脱离 8 tick；只挑『整条龙塞得下』的方向，塞不下就不闪")
                    .define("dodge", true);
            this.caveGhost = builder
                    .comment("洞穴里允许 AI 走『幽灵模式』（noPhysics，直接穿墙；默认 true）",
                            "只在没有骑手、且洞穴判定成立时生效；幽灵状态下移动不再走 A*，也不会因为『没路线』站桩",
                            "关掉则交回飞行辅助的自动脱困 / 常驻幽灵")
                    .define("ghost", true);
            this.caveLavaClearance = builder
                    .comment("洞穴战斗时脚底离岩浆液面至少留多少格（默认 3.0）",
                            "水体只保留 1.5 格兜底；岩浆要更宽，避免模型翅膀/尾巴蹭进液面")
                    .defineInRange("lavaClearance", 3.0D, 0.0D, 16.0D);
            this.caveHoldBreath = builder
                    .comment("洞穴里喷息站桩（不绕圈、不压弯，只动头；默认开）")
                    .define("holdBreath", true);
            builder.pop();
        }
    }
}
