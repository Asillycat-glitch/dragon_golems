package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.client.DragonGolemFireballRenderer;
import a_silly_cat.dragon_golems.client.DragonGolemModel;
import a_silly_cat.dragon_golems.client.DragonGolemRenderer;
import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import com.tterrag.registrate.util.entry.EntityEntry;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.entry.RegistryEntry;
import dev.xkmc.l2library.base.L2Registrate;
import dev.xkmc.modulargolems.content.core.GolemType;
import dev.xkmc.modulargolems.content.item.golem.GolemHolder;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import dev.xkmc.modulargolems.init.data.MGTagGen;
import dev.xkmc.modulargolems.init.registrate.GolemTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;

/**
 * 傀儡龙的全部注册点：实体、傀儡种类、五个部件物品、成品、胚料、龙铠。
 *
 * <p>这里用的是 Registrate（本家也是走 Registrate），原因是 {@code GolemType} 的构造函数必须拿到
 * 一个 {@code EntityEntry}，普通 {@code DeferredRegister} 给不出来。
 *
 * <p>铁砧消耗量写在 {@link GolemPart} 构造函数的第 4 个参数（count）里：五个部件统一
 * <b>默认 64（一组）</b>（`craft.partMaterialCost` 可调 9~64），也就是默认铸满一整只龙要 5 × 64 = 320 个材料。
 * 这个数字同时决定 JEI 铁砧页上
 * 写的材料数量，并参与本家的"重铸基数"（= 五个部件消耗之和）。
 */
public final class DragonGolemItems {

    /** 龙专用的 Registrate 实例（构造时会自动挂到 mod 事件总线）。 */
    public static final L2Registrate REG = new L2Registrate(Dragon_golems.MODID);

    /**
     * 实体。体积给得偏大，飞行怪走 MISC 分类（本家三个傀儡也都是 MISC）。
     *
     * <p><b>载客只收一个玩家</b>：龙的定位是"一个人开的空中炮艇"，
     * 见 {@code DragonGolemEntity.canAddPassenger} 与 docs/MOUNT.md 第七节。
     * 因为座位被限制成"只能一个玩家"，这里就<b>没有</b>照本家狗那条"乘客宽度之和 <= getBbWidth()"
     * 的公式去加宽判定箱：判定箱保持"身体重心那一块"（外加头/颈/躯干/尾/翼一共 15 个子箱），
     * 不然水平方向要白占 5 格、树林和矿洞里到处蹭方块。
     *
     * <p>万一以后要放宽到"多个乘客 / 傀儡也能上"，才需要回头看这一段宽度预算：
     * 一个金属傀儡（{@code sized(1.4F, 2.7F)}、自身 getScale 为 1）宽 1.4，三个就是 4.2，
     * 而 {@code getBbWidth() = 这里的宽度 × getScale()}，
     * {@code getScale() = GOLEM_SIZE / 默认 GOLEM_SIZE}（见 DragonGolemEntity.createAttributes），
     * 所以基准 5 时这个 2.6 就是实际宽度；想靠 GOLEM_SIZE 放大到 4.2 得要 1.62 倍
     * （= GOLEM_SIZE 8.1），而体型升级最高只到 6.0，走不通。
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

    // 铁砧消耗（GolemPart 构造函数的第 4 个参数）：五个部件统一读 `craft.partMaterialCost`
    // （默认 64，范围 9~64；五个部件合计 = 5 × 此值）。
    // 注意这个数字还参与本家的"重铸基数"（= 五个部件消耗之和），也决定 JEI 铁砧页上
    // 写的材料数量。配置在物品注册时求值，改完要重启游戏。
    // 这里用 lambda 而不是静态常量：Registrate 的 item factory 在 RegisterEvent 时才执行，
    // 那时 config 已经加载；不要提前在静态初始化里取值。

    /** 双翼：装配表第一行，铁砧消耗由 `craft.partMaterialCost` 决定（默认 64）。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> WINGS =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_wings",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.WINGS, DragonGolemConfig.partMaterialCost()))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 头部（含脖颈）：装配表第二行左侧，铁砧消耗由 `craft.partMaterialCost` 决定（默认 64）。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> HEAD =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_head",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.HEAD, DragonGolemConfig.partMaterialCost()))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 身体（重心那一块）：装配表正中，铁砧消耗由 `craft.partMaterialCost` 决定（默认 64）。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> BODY =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_body",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.BODY, DragonGolemConfig.partMaterialCost()))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 尾巴：装配表第二行右侧，铁砧消耗由 `craft.partMaterialCost` 决定（默认 64）。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> TAIL =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_tail",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.TAIL, DragonGolemConfig.partMaterialCost()))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 四肢：装配表第三行，铁砧消耗由 `craft.partMaterialCost` 决定（默认 64）。 */
    public static final ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> LEG =
            REG.<GolemPart<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_limb",
                            p -> new GolemPart<>(p, TYPE, DragonGolemPartType.LEG, DragonGolemConfig.partMaterialCost()))
                    .properties(p -> p.stacksTo(16))
                    .register();

    /** 成品：把五个部件摆成装配表合成它。 */
    public static final ItemEntry<GolemHolder<DragonGolemEntity, DragonGolemPartType>> HOLDER =
            REG.<GolemHolder<DragonGolemEntity, DragonGolemPartType>>item("dragon_golem_holder",
                            p -> new GolemHolder<>(p, TYPE))
                    .register();

    /** 大傀儡胚料：切石机切成上面五个部件；图标暂借 `modulargolems:metal_golem_template`。 */
    public static final ItemEntry<Item> TEMPLATE =
            REG.item("dragon_golem_template", Item::new).register();

    // ---- 龙铠：四件 × 三档材质（铁 / 钻石 / 下界合金）----
    // 槽位约定：龙头 HEAD、龙身 CHEST、四肢 LEGS、龙尾 FEET，和 DragonGolemMenuControl 的四个甲槽对应。
    // 贴图：物品图标 textures/item/dragon_armor_<件>_<材质>.png；
    // 穿在龙身上的 512×512 整龙图集在 textures/entity/dragon/armor/<材质>.png。

    private static ItemEntry<DragonArmorItem> armor(String id, EquipmentSlot slot, DragonArmorMaterial material,
                                                   ResourceLocation modelPath) {
        // 龙甲是装备，不叠放：和本家大型傀儡甲一致（它们的注册里也是 stacksTo(1)）。
        // 之前漏了这一行，默认堆到 64。
        return REG.<DragonArmorItem>item(id,
                p -> new DragonArmorItem(p.stacksTo(1), slot, material, modelPath)).register();
    }

    public static final ItemEntry<DragonArmorItem> ARMOR_HEAD_IRON =
            armor("dragon_armor_head_iron", EquipmentSlot.HEAD, DragonArmorMaterial.IRON,
                    DragonArmorItem.PATH_HEAD);
    public static final ItemEntry<DragonArmorItem> ARMOR_HEAD_DIAMOND =
            armor("dragon_armor_head_diamond", EquipmentSlot.HEAD, DragonArmorMaterial.DIAMOND,
                    DragonArmorItem.PATH_HEAD);
    public static final ItemEntry<DragonArmorItem> ARMOR_HEAD_NETHERITE =
            armor("dragon_armor_head_netherite", EquipmentSlot.HEAD, DragonArmorMaterial.NETHERITE,
                    DragonArmorItem.PATH_HEAD);

    public static final ItemEntry<DragonArmorItem> ARMOR_BODY_IRON =
            armor("dragon_armor_body_iron", EquipmentSlot.CHEST, DragonArmorMaterial.IRON,
                    DragonArmorItem.PATH_BODY);
    public static final ItemEntry<DragonArmorItem> ARMOR_BODY_DIAMOND =
            armor("dragon_armor_body_diamond", EquipmentSlot.CHEST, DragonArmorMaterial.DIAMOND,
                    DragonArmorItem.PATH_BODY);
    public static final ItemEntry<DragonArmorItem> ARMOR_BODY_NETHERITE =
            armor("dragon_armor_body_netherite", EquipmentSlot.CHEST, DragonArmorMaterial.NETHERITE,
                    DragonArmorItem.PATH_BODY);

    public static final ItemEntry<DragonArmorItem> ARMOR_LIMBS_IRON =
            armor("dragon_armor_limbs_iron", EquipmentSlot.LEGS, DragonArmorMaterial.IRON,
                    DragonArmorItem.PATH_LIMBS);
    public static final ItemEntry<DragonArmorItem> ARMOR_LIMBS_DIAMOND =
            armor("dragon_armor_limbs_diamond", EquipmentSlot.LEGS, DragonArmorMaterial.DIAMOND,
                    DragonArmorItem.PATH_LIMBS);
    public static final ItemEntry<DragonArmorItem> ARMOR_LIMBS_NETHERITE =
            armor("dragon_armor_limbs_netherite", EquipmentSlot.LEGS, DragonArmorMaterial.NETHERITE,
                    DragonArmorItem.PATH_LIMBS);

    public static final ItemEntry<DragonArmorItem> ARMOR_TAIL_IRON =
            armor("dragon_armor_tail_iron", EquipmentSlot.FEET, DragonArmorMaterial.IRON,
                    DragonArmorItem.PATH_TAIL);
    public static final ItemEntry<DragonArmorItem> ARMOR_TAIL_DIAMOND =
            armor("dragon_armor_tail_diamond", EquipmentSlot.FEET, DragonArmorMaterial.DIAMOND,
                    DragonArmorItem.PATH_TAIL);
    public static final ItemEntry<DragonArmorItem> ARMOR_TAIL_NETHERITE =
            armor("dragon_armor_tail_netherite", EquipmentSlot.FEET, DragonArmorMaterial.NETHERITE,
                    DragonArmorItem.PATH_TAIL);

    /** 创造栏用：四件 × 三档材质，顺序 = 头/身/四肢/尾，每件铁→钻石→下界合金。 */
    public static final java.util.List<ItemEntry<DragonArmorItem>> ARMOR_ITEMS = java.util.List.of(
            ARMOR_HEAD_IRON, ARMOR_HEAD_DIAMOND, ARMOR_HEAD_NETHERITE,
            ARMOR_BODY_IRON, ARMOR_BODY_DIAMOND, ARMOR_BODY_NETHERITE,
            ARMOR_LIMBS_IRON, ARMOR_LIMBS_DIAMOND, ARMOR_LIMBS_NETHERITE,
            ARMOR_TAIL_IRON, ARMOR_TAIL_DIAMOND, ARMOR_TAIL_NETHERITE);

    private DragonGolemItems() {
    }

    /** 主动触发类初始化；实际注册由 Registrate 挂的事件完成。 */
    public static void register() {
        // 空实现即可：调用静态方法会初始化本类。
    }
}
