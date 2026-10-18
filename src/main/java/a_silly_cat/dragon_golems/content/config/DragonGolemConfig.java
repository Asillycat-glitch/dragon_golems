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

    /**
     * <b>AI 俯冲前要不要检查竖直空间。</b>默认 <b>true</b>（要检查）。
     *
     * <h2>为什么</h2>
     * 俯冲的航线高度是 {@code 目标脚下地形 + entryAlt()}（1 倍 12 格、泰坦 22 格），
     * 而它<b>原来没有任何天花板约束</b>。在"平台 30 格、天花板 40 格"的地牢房间里，
     * 入场点在 {@code 42 / 52} 格 —— <b>在天花板外面</b>。于是龙每一轮俯冲都在命令自己
     * 往天花板里爬；而竖直碰撞既不是 {@code horizontalCollision} 也没让它嵌进方块，
     * 所以"卡住 / 穿墙"的三条判定一条都不响，它只能等 {@code LINEUP} 的 160 tick 超时，
     * 同时被围住集火。
     *
     * <p>现在两条一起生效：
     * <ul>
     *   <li>航线端点按<b>该点所在坐标</b>的天花板夹住（{@code ceilingYAt}）+ 留出模型下垂的余量；</li>
     *   <li>竖直空间低于 {@code diveMinRoom()}（1 倍约 5.5 格、泰坦约 10 格）时，AI 干脆不抽俯冲，
     *       这一轮交给龙息 / 龙弹 / 音爆（这三招不需要爬升）。</li>
     * </ul>
     *
     * <p>骑手指令（按 R 的冲锋）<b>不受这条影响</b>：那是玩家明确要的，航线被压平后照打。
     * 关掉这个开关 = 旧行为（只剩天花板夹取，AI 仍会尝试够不着的高入场点）。
     */
    public static boolean diveRoomCheck() {
        return bool(COMMON.diveRoomCheck, true);
    }

    /**
     * <b>索敌时"射界通畅"值多少分。</b>默认 <b>40</b>（0 = 关掉加权，退回纯距离优先）。
     *
     * <p>龙的龙息会被方块整个挡掉，而本家那套索敌是"就近优先"——"最近的那个"经常隔着墙，
     * 它就对着墙喷满一整轮。分数里加这一项之后，一个"打得着但远 30 格"的敌人会排在
     * "隔着墙但就在 5 格"的敌人前面。
     *
     * <p>距离项是格数（{@code −距离}），所以 40 分 ≈ "愿意多飞 40 格去打一个打得着的敌人"。
     * 调大 = 更执着于"打得着"，调小 = 更执着于"打得近"。
     */
    public static double targetClearShotBonus() {
        return Math.max(0.0D, num(COMMON.targetClearShotBonus, 40.0D));
    }

    // ---- 飞行寻路（B 档：A* 出航路点，我们只负责按航路点飞）----

    /**
     * 是否启用"真寻路"：给目标点算一条 A* 路线，然后<b>只把路线的下一个节点当作转向目标</b>，
     * 速度与高度仍然由原来的 goal 控制。
     *
     * <p>为什么要这样：龙原先的移动是"朝目标点直接推速度"，而目标点（随机游走点 / 主人的 XZ）
     * <b>不做任何校验</b> —— 主人在墙后、目标点在墙里，龙就会一直朝那个点拱，卡住了靠扇面探测和
     * no-clip 救援硬挤（"老是隔着墙找目的地"）。开着这个开关之后，绕墙由原版 A*
     * （{@code FlyNodeEvaluator}，实心方块是 {@code BLOCKED}、节点直接排除）负责，
     * 龙照旧按自己的速度与悬停高度飞。
     *
     * <p>关掉就退回旧行为（直奔目标 + 硬挤），用于对照排查。
     */
    public static boolean pathEnabled() {
        return bool(COMMON.pathEnabled, true);
    }

    /** 每隔多少 tick 重算一次路线（目标移动超过 2 格也会立刻重算）。 */
    public static int pathRepathTicks() {
        return Math.max(2, (int) num(COMMON.pathRepathTicks, 20.0D));
    }

    /**
     * 寻路时是否考虑"这条龙很宽"。
     *
     * <p>原版 {@code FlyNodeEvaluator} 只按方块格判定，<b>完全不吃实体尺寸</b>（2.6 格宽的龙
     * 照样会被规划进 1 格缝，然后一路蹭墙）。开着这个开关会给它加一层体型余量：
     * 节点处按龙的身体盒子采样，塞不下就判 {@code BLOCKED}。
     */
    public static boolean pathBodyClearance() {
        return bool(COMMON.pathBodyClearance, true);
    }

    /** 连续算不出路线这么多 tick 之后，目标判定为"不可达"（游走 goal 会因此换一个点）。 */
    public static int pathGiveUpTicks() {
        return Math.max(10, (int) num(COMMON.pathGiveUpTicks, 40.0D));
    }

    /**
     * 航线高度与悬停高度的"防微抖死区"（格）。默认 1。
     *
     * <h2>竖直方向现在归寻路</h2>
     * A* 的节点带空地判定（评估器按身体盒子逐节点采样），所以<b>路线本身就是"哪儿能飞、飞多高"</b>。
     * 现在龙在航线上时竖直目标 = 航路点 Y（跟着路线爬升/下降），走完路线才回悬停高度。
     * 这个死区只用来吸掉"航路点高度和悬停高度几乎相同"时的来回微抖。
     *
     * <p>调大 = 更倾向保持悬停高度（更像"定高巡航"）；调小 = 更严格地贴着航线的起伏。
     */
    public static double pathVerticalDeadband() {
        return Math.max(0.1D, num(COMMON.pathVerticalDeadband, 1.0D));
    }

    // ---- 旋转（转向速度与压弯）----

    /** 基础转向速度（度/tick）：慢速时的下限，速度上来后按 {@code turnRadius} 一起放大。 */
    public static float turnRateBase() {
        return (float) num(COMMON.turnRateBase, 22.0D);
    }

    /** 玩家驾驶时的转向下限（度/tick）：和 AI 分开调，避免"鼠标一抖机头就抽"。 */
    public static float riderTurnRate() {
        return (float) num(COMMON.riderTurnRate, 10.0D);
    }

    /** 转弯半径（格）：{@code ω = v / r}，调小 = 更灵活，调大 = 更沉。 */
    public static double turnRadius() {
        return Math.max(0.5D, num(COMMON.turnRadius, 3.4D));
    }

    /**
     * 体型对转速的惩罚：{@code 转速 ÷ (1 + 惩罚 × (体型倍率 − 1))}。
     *
     * <p>0 = 不惩罚（泰坦和 1 倍一样灵活，转弯像雨刷）；0.35 时 4 倍泰坦约 ÷2，
     * 大体型转起来更"重"、更像一条巨龙。默认 0 保持原手感。
     */
    public static double turnSizePenalty() {
        return num(COMMON.turnSizePenalty, 0.0D);
    }

    /** 侧倾上限（度）：转弯时最多压弯多少。 */
    public static float maxRoll() {
        return (float) num(COMMON.maxRoll, 35.0D);
    }

    /** 每 1° 转速折成多少度侧倾（转弯越急压得越狠）。 */
    public static float rollPerDegree() {
        return (float) num(COMMON.rollPerDegree, 1.5D);
    }

    /**
     * 头顶天花板与身体之间留多少余量（格）。默认 0.25。
     *
     * <p>悬停上限 = {@code 天花板方块 Y − 身体高度 − 这个余量}。原来只减了"一格"，
     * 完全没扣身体高度，于是 1 倍体型头顶穿进天花板 1.2 格、泰坦穿 7.8 格（真机截图确认）。
     * 模型还有翅膀之类高出判定箱的部分，所以留一点余量更稳。
     */
    public static double ceilingClearance() {
        return Math.max(0.0D, num(COMMON.ceilingClearance, 0.25D));
    }

    /**
     * 巡航轨迹的平滑系数（每 tick 朝"期望速度"插值多少）。
     *
     * <p>巡航是"永不刹停"的：到点立刻换下一个采样点，速度方向可能整个反过去。
     * 1.0 = 不平滑（旧行为：位移瞬间反向、模型还在慢慢转 → "到了目的地顿一下再往回蹭"）；
     * 0.25 ≈ 十几 tick 完成掉头，模型来得及跟着转；太小则像在冰上滑。
     */
    public static double trajectoryLerp() {
        return Mth.clamp(num(COMMON.trajectoryLerp, 0.25D), 0.05D, 1.0D);
    }

    // ---- 悬停参照系（支撑面 / 液体）----

    /**
     * <b>流水（瀑布、水渠、岩浆瀑布）能不能当"支撑面"。</b>默认 <b>false</b>（不能）。
     *
     * <h2>为什么要这样</h2>
     * 悬停高度是"脚下的支撑面 + 悬停高度"，而 {@code scanFloor} 撞到液体时会往上找
     * <b>整根液柱的顶面</b>（{@code liquidTopY}）。流水柱在竖直方向上可以很长：
     * 龙一旦飞进瀑布，支撑面就成了"瀑布顶"，于是它<b>每 tick 往上挪一格</b>；
     * 从瀑布边缘蹭出去时支撑面又瞬间掉回地面 —— 悬停目标在两者之间反复跳，
     * 表现就是"穿过瀑布时抽搐"。关掉这条之后：
     * <ul>
     *   <li>流水一律忽略，继续往下找真正的地面 / <b>静水</b>液面（湖泊与水池都是静水方块，照旧悬停其上方）；</li>
     *   <li>泡在静水里时照旧以静水液面为准（"自己爬出水池"这条能力不受影响）。</li>
     * </ul>
     * 设 true = 旧行为（流水也算支撑面，会顺着瀑布往上爬）。
     */
    public static boolean flowingFluidIsFloor() {
        return bool(COMMON.flowingFluidIsFloor, false);
    }

    /**
     * <b>水流 / 岩浆流能不能推动龙。</b>默认 <b>false</b>（推不动）。
     *
     * <p>龙是"悬停 + 每 tick 由 goal 直接写速度"的单位，原版的水流推力只会和它自己的
     * 速度控制互相打架（瀑布里尤其明显：竖直方向被水流往下拽、控制端在往上抬）。
     * 关掉之后它仍然会正常"落进水里"（原版的浮力与游泳逻辑不看这个开关），
     * 只是不再被水流推着走。设 true = 原版行为。
     */
    public static boolean pushedByFluid() {
        return bool(COMMON.pushedByFluid, false);
    }

    // ---- 傀儡本体 ----

    /**
     * <b>龙的升级槽数量</b>（默认 5，可 0~100）。
     *
     * <h2>为什么要单独做一个键</h2>
     * 本家三种傀儡各有自己的配置项（{@code largeGolemSlot = 4}、{@code humanoidGolemSlot = 3}、
     * {@code dogGolemSlot = 2}），而 {@code GolemType.getUpgradeSlots()} 的<b>基类默认值</b>是
     * "部件枚举的数量"——我们刚好有 5 个部件，所以龙一直是 5 槽，而且<b>没法调</b>。
     * 这条把它变成和本家三个键同级的配置项（{@link #upgradeSlots()} → {@code DragonGolemType} 的覆盖）。
     */
    public static int upgradeSlots() {
        return Mth.clamp((int) num(COMMON.upgradeSlots, 5.0D), 0, 100);
    }

    // ---- 幽灵（穿墙）状态 ----

    /**
     * <b>战斗中允许自动进入幽灵（穿墙）状态吗。</b>默认 <b>false</b>（不允许）。
     *
     * <h2>为什么</h2>
     * 幽灵态是"穿过方块"，而<b>龙息与大部分技能会被方块挡住</b> —— 也就是穿墙时战斗无效。
     * 这个结果本身是对的（穿墙就该付出代价），但前提是"<b>战斗开始前就已经在里面</b>"。
     * 如果允许战斗中进入，就会出现最糟的组合：龙卡在墙里 / 追不到人，于是自动穿墙，
     * 穿墙之后又打不到任何傀儡 —— <b>整整一场战斗都无效</b>。
     * 所以：自动幽灵（自动脱困 / 累计撞墙的常驻幽灵）只在<b>非战斗</b>时进入；
     * 战斗开始后，已经开着的常驻幽灵会被挂起（"嵌在方块里"的物理安全那条不受影响，见下）。
     *
     * <p>玩家手动按 <b>H</b> 的自由飞行<b>不受这条限制</b> —— 那是玩家明确要的，代价自负。
     * "嵌在方块里"那条 no-clip 也不受限制：那是<b>物理安全</b>（不开会被碰撞解算每 tick 弹飞十几格），
     * 不是战术穿墙。
     */
    public static boolean ghostDuringCombat() {
        return bool(COMMON.ghostDuringCombat, false);
    }

    /** "战斗中"的记忆窗口（tick）：最近这么久内有目标 / 打过 / 被打过都算战斗中。100 = 5 秒。 */
    public static int ghostCombatMemoryTicks() {
        return Math.max(20, (int) num(COMMON.ghostCombatMemoryTicks, 100.0D));
    }

    // ---- 诊断日志 ----

    /**
     * <b>移动 / 寻路的诊断日志</b>（默认关）。打开后每 {@link #flightLogInterval()} tick 打一行：
     *
     * <pre>
     * [move] #tick goal=... pos=... | steer=... onRoute=... | vert: target=...(来源) hover=... floor=...
     *        ceil=... maxFeet=... | vel=... | hColl=... vColl=... noPhys=... rescue=... stuck=... | goals=...
     * [path] recompute goal=(x,z) goalY=... reach=... → nodes=... canReach=true 路线前几节点=[(x,y,z) ...]
     * [move] 正在上升但头顶被挡：...
     * </pre>
     *
     * <p>为什么要有它：龙的移动分三层（goal 给目标点 → 寻路算 3D 路线 → 竖直/水平速度），
     * 出问题时从外面只能看到"它在乱飞"。这三行把"<b>目标点是什么 / 路线算了什么 / 竖直目标来自哪</b>"
     * 全部摊开，尤其第三行专门盯"想往上飞但天花板顶着"这种反馈。
     */
    public static boolean flightLog() {
        return bool(COMMON.flightLog, false);
    }

    /** 诊断日志的间隔（tick）：默认 10（0.5 秒）。 */
    public static int flightLogInterval() {
        return Math.max(1, (int) num(COMMON.flightLogInterval, 10.0D));
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

        public final ForgeConfigSpec.BooleanValue diveRoomCheck;

        public final ForgeConfigSpec.DoubleValue targetClearShotBonus;

        public final ForgeConfigSpec.BooleanValue pathEnabled;
        public final ForgeConfigSpec.DoubleValue pathRepathTicks;
        public final ForgeConfigSpec.BooleanValue pathBodyClearance;
        public final ForgeConfigSpec.DoubleValue pathGiveUpTicks;
        public final ForgeConfigSpec.DoubleValue pathVerticalDeadband;

        public final ForgeConfigSpec.DoubleValue turnRateBase;
        public final ForgeConfigSpec.DoubleValue riderTurnRate;
        public final ForgeConfigSpec.DoubleValue turnRadius;
        public final ForgeConfigSpec.DoubleValue turnSizePenalty;
        public final ForgeConfigSpec.DoubleValue maxRoll;
        public final ForgeConfigSpec.DoubleValue rollPerDegree;
        public final ForgeConfigSpec.DoubleValue trajectoryLerp;
        public final ForgeConfigSpec.DoubleValue ceilingClearance;

        public final ForgeConfigSpec.BooleanValue flowingFluidIsFloor;
        public final ForgeConfigSpec.BooleanValue pushedByFluid;

        public final ForgeConfigSpec.BooleanValue ghostDuringCombat;
        public final ForgeConfigSpec.DoubleValue ghostCombatMemoryTicks;

        public final ForgeConfigSpec.BooleanValue flightLog;
        public final ForgeConfigSpec.DoubleValue flightLogInterval;

        public final ForgeConfigSpec.DoubleValue upgradeSlots;

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
            this.diveRoomCheck = builder
                    .comment("AI 俯冲前检查竖直空间（室内/低天花板）",
                            "俯冲入场高度 = 目标脚下地形 + 12~22 格，原本没有天花板约束 —— 40 格天花板的房间里",
                            "它会一直往天花板里爬（竖直碰撞不触发任何『卡住』判定），白等 LINEUP 超时 + 挨打",
                            "开着：航线按端点坐标的天花板夹住，空间不足时这一轮改用龙息/龙弹/音爆",
                            "骑手指令（按 R）不受影响；关掉 = 旧行为")
                    .define("diveRoomCheck", true);
            this.targetClearShotBonus = builder
                    .comment("索敌时『射界通畅』值多少分（距离项是 −格数，所以 40 ≈ 愿意多飞 40 格去打看得见的敌人）",
                            "0 = 退回纯距离优先（本家行为）；调大更执着于『打得着』，调小更执着于『打得近』")
                    .defineInRange("targetClearShotBonus", 40.0D, 0.0D, 200.0D);
            builder.pop();

            builder.comment("飞行寻路（B 档：A* 出航路点，龙只负责按航路点飞）").push("pathfinding");
            this.pathEnabled = builder
                    .comment("给目标点算 A* 路线、按路线节点转向（关掉退回『直奔目标 + 卡住硬挤』）")
                    .define("enabled", true);
            this.pathRepathTicks = builder
                    .comment("每隔多少 tick 重算一次路线（目标移动超过 2 格会立刻重算）")
                    .defineInRange("repathTicks", 20.0D, 2.0D, 200.0D);
            this.pathBodyClearance = builder
                    .comment("寻路时是否考虑龙的体型（原版飞行评估器只看方块格，会把 2.6 格宽的龙规划进 1 格缝）")
                    .define("bodyClearance", true);
            this.pathGiveUpTicks = builder
                    .comment("连续算不出路线这么多 tick 就认定目标不可达（游走 goal 会换一个点）")
                    .defineInRange("giveUpTicks", 40.0D, 10.0D, 400.0D);
            this.pathVerticalDeadband = builder
                    .comment("航线高度与悬停高度的防微抖死区（格）",
                            "竖直方向现在归寻路：在航线上时竖直目标 = 航路点 Y（跟着路线爬升/下降），走完路线才回悬停高度",
                            "这个死区只吸掉『两者几乎相同』时的来回微抖；调大更像定高巡航，调小更贴航线起伏")
                    .defineInRange("verticalDeadband", 1.0D, 0.1D, 10.0D);
            builder.pop();

            builder.comment("旋转（转向速度与压弯）").push("flight");
            this.turnRateBase = builder
                    .comment("基础转向速度（度/tick）：慢速时的下限；18 原值、22 ≈ 0.8 秒掉头 180°")
                    .defineInRange("turnRateBase", 22.0D, 2.0D, 180.0D);
            this.riderTurnRate = builder
                    .comment("玩家驾驶时的转向下限（度/tick）：调大 = 更跟手，调小 = 更沉")
                    .defineInRange("riderTurnRate", 10.0D, 2.0D, 180.0D);
            this.turnRadius = builder
                    .comment("转弯半径（格）：ω = v / r。调小更灵活、调大更沉")
                    .defineInRange("turnRadius", 3.4D, 0.5D, 40.0D);
            this.turnSizePenalty = builder
                    .comment("体型对转速的惩罚：转速 ÷ (1 + 惩罚 × (体型倍率 − 1))",
                            "0 = 泰坦和 1 倍一样灵活；0.35 时 4 倍泰坦约 ÷2")
                    .defineInRange("turnSizePenalty", 0.0D, 0.0D, 2.0D);
            this.maxRoll = builder
                    .comment("侧倾上限（度）：转弯时最多压弯多少（28 原值）")
                    .defineInRange("maxRoll", 35.0D, 0.0D, 80.0D);
            this.rollPerDegree = builder
                    .comment("每 1° 转速折成多少度侧倾（1.2 原值）")
                    .defineInRange("rollPerDegree", 1.5D, 0.0D, 4.0D);
            this.trajectoryLerp = builder
                    .comment("巡航轨迹的平滑系数：每 tick 朝期望速度插值多少",
                            "1.0 = 不平滑（到点瞬间反向 → 顿一下再往回蹭）；0.25 ≈ 十几 tick 完成掉头")
                    .defineInRange("trajectoryLerp", 0.25D, 0.05D, 1.0D);
            this.ceilingClearance = builder
                    .comment("头顶天花板与身体之间留多少余量（格）",
                            "悬停上限 = 天花板方块 Y − 身体高度 − 这个值；调大 = 更贴地、更安全，调小 = 更高",
                            "0.25 是默认；模型翅膀比判定箱高出一点，别设太小")
                    .defineInRange("ceilingClearance", 0.25D, 0.0D, 2.0D);
            builder.pop();

            builder.comment("悬停参照系（支撑面 / 液体）").push("hover");
            this.flowingFluidIsFloor = builder
                    .comment("流水（瀑布/水渠/岩浆瀑布）算不算支撑面",
                            "false（默认）：忽略流水、继续往下找地面或静水液面 —— 修掉『飞进瀑布时顺着瀑布往上爬 + 抽搐』",
                            "true：旧行为（流水也算支撑面）")
                    .define("flowingFluidIsFloor", false);
            this.pushedByFluid = builder
                    .comment("水流/岩浆流能不能推动龙",
                            "false（默认）：推不动 —— 否则水流推力和龙自己的速度控制互相打架（瀑布里最明显）",
                            "true：原版行为（浮力与游泳不受这个开关影响）")
                    .define("pushedByFluid", false);
            builder.pop();

            builder.comment("幽灵（穿墙）状态").push("ghost");
            this.ghostDuringCombat = builder
                    .comment("战斗中允许自动进入幽灵（穿墙）状态吗",
                            "false（默认）：不许 —— 穿墙时龙息/技能会被方块挡住，战斗中进去等于整场战斗无效",
                            "  自动脱困与『累计撞墙的常驻幽灵』都受这条约束；已开着的常驻幽灵在战斗开始时被挂起",
                            "  不受影响：玩家按 H 的手动自由飞行、以及『正嵌在方块里』的物理安全 no-clip",
                            "true：旧行为（战斗中也会自动穿墙）")
                    .define("duringCombat", false);
            this.ghostCombatMemoryTicks = builder
                    .comment("『战斗中』的记忆窗口（tick）：最近这么久内有目标/打过/被打过都算战斗中（100 = 5 秒）")
                    .defineInRange("combatMemoryTicks", 100.0D, 20.0D, 1200.0D);
            builder.pop();

            builder.comment("傀儡本体").push("golem");
            this.upgradeSlots = builder
                    .comment("龙的升级槽数量（默认 5 = 部件数量；本家对照：大型 4 / 人形 3 / 犬型 2）",
                            "0 = 完全不能装升级；上限 100")
                    .defineInRange("upgradeSlots", 5.0D, 0.0D, 100.0D);
            builder.pop();

            builder.comment("诊断日志").push("debug");
            this.flightLog = builder
                    .comment("移动/寻路诊断日志（默认关）",
                            "打开后每 flightLogInterval tick 打一行 [move]：目标点 / 航路点 / 竖直目标来源 / 速度 / 碰撞",
                            "每次重算路线打一行 [path]：goal、goalY、节点数、canReach、路线前几个节点",
                            "另外『正在上升但头顶被挡』会单独 WARN 一行（限流 1 秒一次）")
                    .define("flightLog", false);
            this.flightLogInterval = builder
                    .comment("诊断日志间隔（tick）：10 = 0.5 秒一行")
                    .defineInRange("flightLogInterval", 10.0D, 1.0D, 200.0D);
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
