package a_silly_cat.dragom_golems.dragon;

import a_silly_cat.dragom_golems.Dragom_golems;
import a_silly_cat.dragom_golems.client.DragonGolemFireballRenderer;
import a_silly_cat.dragom_golems.client.DragonGolemModel;
import a_silly_cat.dragom_golems.client.DragonGolemRenderer;
import com.tterrag.registrate.util.entry.EntityEntry;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.entry.RegistryEntry;
import dev.xkmc.l2library.base.L2Registrate;
import dev.xkmc.modulargolems.content.core.GolemType;
import dev.xkmc.modulargolems.content.item.golem.GolemHolder;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import dev.xkmc.modulargolems.init.data.MGTagGen;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;

/**
 * 傀儡龙的全部注册点：实体、傀儡种类、四个部件物品、成品、胚料。
 *
 * <p>这里用的是 Registrate（本家也是走 Registrate），原因是 {@code GolemType} 的构造函数必须拿到
 * 一个 {@code EntityEntry}，普通 {@code DeferredRegister} 给不出来。
 *
 * <p>铁砧消耗量写在 {@link GolemPart} 构造函数的第 4 个参数（count）里：
 * 头 3、身体 6、每只翼 1、四肢 4，一共 15 个铁块。
 */
public final class DragonGolemItems {

    /** 龙专用的 Registrate 实例（构造时会自动挂到 mod 事件总线）。 */
    public static final L2Registrate REG = new L2Registrate(Dragom_golems.MODID);

    /**
     * 实体。体积给得偏大，飞行怪走 MISC 分类（本家三个傀儡也都是 MISC）。
     *
     * <p><b>宽度 4.3 是给"载客"留的预算，不是照着模型定的。</b>本家狗的载客判定是
     * {@code 乘客宽度之和 <= getBbWidth()}，而一个金属傀儡（{@code sized(1.4F, 2.7F)}、
     * 自身 getScale 为 1）宽 1.4 → 三个就是 4.2。链条是
     * {@code getBbWidth() = 这里的宽度 × getScale()}，
     * 而 {@code getScale() = GOLEM_SIZE / 默认 GOLEM_SIZE}（见 DragonGolemEntity.createAttributes），
     * 所以基准 5 时这个 4.3 就是实际宽度；想靠 GOLEM_SIZE 放大到 4.2 得要 1.75 倍
     * （= GOLEM_SIZE 8.75），而体型升级最高只到 6.0，走不通。
     *
     * <p>代价要清楚：判定箱是"X/Z 同宽"的方柱（{@code EntityDimensions} 只有宽和高、没有长），
     * 所以 4.3 意味着它在水平方向要占 5 格左右的空间，树林/矿洞里很容易蹭到方块。
     * 不想让碰撞箱变胖的话，就别用本家的"宽度和"公式，改成按座位数算——见 docs/MOUNT.md。
     *
     * <p>另外它现在<b>还不会真的载客</b>：龙的乘客逻辑（canAddPassenger 等）还没搬进来，
     * 这一档宽度是先把预算准备好。
     */
    public static final EntityEntry<DragonGolemEntity> ENTITY = REG
            .entity("dragon_golem", DragonGolemEntity::new, MobCategory.MISC)
            // 判定箱只留"身体重心那一块"：头（含颈）、尾巴、双翼各自有独立的子碰撞箱
            // （DragonGolemPartEntity，见 DragonGolemEntity#getParts）。尺寸要和模型身体盒子对得上：
            // 模型身体是 24 像素 ≈ 24/16 × MODEL_SCALE 格。
            .properties(b -> b.sized(2.6F, 2.2F).clientTrackingRange(12).updateInterval(2))
            .attributes(DragonGolemEntity::createAttributes)
            .renderer(() -> DragonGolemRenderer::new)
            .tag(MGTagGen.GOLEM_FRIENDLY)
            .register();

    /**
     * 龙弹：远距离（24 格以上）用的炮弹。飞行手感继承原版末影龙火球，
     * 但命中结算整段重写（吃龙的攻击力、只打敌对目标、附带龙息负面效果），见 {@link DragonGolemFireball}。
     *
     * <p>判定箱给 1×1（它的碰撞体只用 {@code getBoundingBox().inflate(...)} 做命中检测，
     * 真正的大小靠渲染时那个 2 倍缩放），追踪范围和刷新率都调小：它飞得快，不需要那么密的同步。
     */
    public static final EntityEntry<DragonGolemFireball> FIREBALL = REG
            // 显式写类型参数：这个实体有两个构造函数，不写的话 Java 推断不出要用哪一个
            .<DragonGolemFireball>entity("dragon_golem_fireball", DragonGolemFireball::new, MobCategory.MISC)
            .properties(b -> b.sized(1.0F, 1.0F).clientTrackingRange(8).updateInterval(1))
            .renderer(() -> DragonGolemFireballRenderer::new)
            .register();

    /** 傀儡种类，注册进 Modular Golems 的共用注册表 {@code modulargolems:golem_type}。 */
    public static final RegistryEntry<DragonGolemType> TYPE = REG
            .<GolemType<?, ?>, DragonGolemType>generic(GolemTypes.TYPES, "dragon_golem",
                    // 双层 lambda：内层的方法引用只会在客户端真正取模型时才会被求值，
                    // 这样服务端不会去加载任何客户端类（本家也是这个写法）。
                    () -> new DragonGolemType(ENTITY, () -> DragonGolemModel::new))
            .defaultLang()
            .register();

    // 铁砧消耗（GolemPart 构造函数的第 4 个参数）：双翼 8、头 12、身 12、尾 8、四肢 8。
    // 注意这个数字还参与本家的"重铸基数"（= 五个部件消耗之和 48）。

    /** 双翼：装配表第一行，铁砧消耗 8。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> WINGS =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_wings",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.WINGS, 8))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 头部（含脖颈）：装配表第二行左侧，铁砧消耗 12。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> HEAD =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_head",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.HEAD, 12))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 身体（重心那一块）：装配表正中，铁砧消耗 12。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> BODY =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_body",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.BODY, 12))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 尾巴：装配表第二行右侧，铁砧消耗 8。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> TAIL =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_tail",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.TAIL, 8))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 四肢：装配表第三行，铁砧消耗 8。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> LEG =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_limb",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.LEG, 8))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 成品：把五个部件摆成装配表合成它。 */
    public static final ItemEntry<GolemHolder<DragonGolemEntity, DragonGolemPartType>> HOLDER =
            REG.<GolemHolder<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_holder",
                            p -> new GolemHolder<>(p, TYPE))
                    .register();

    /** 大傀儡胚料：切石机切成上面五个部件（贴图暂时用本家的傀儡胚料占位）。 */
    public static final ItemEntry<Item> TEMPLATE =
            REG.item("dragon_golem_template", Item::new).register();

    private DragonGolemItems() {
    }

    /** 主动触发类初始化；实际注册由 Registrate 挂的事件完成。 */
    public static void register() {
        // 空实现即可：调用静态方法会初始化本类。
    }
}
