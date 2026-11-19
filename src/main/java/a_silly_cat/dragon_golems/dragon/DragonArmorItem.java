package a_silly_cat.dragon_golems.dragon;

import com.google.common.collect.ImmutableMultimap;
import dev.xkmc.modulargolems.content.item.equipments.GolemEquipmentItem;
import dev.xkmc.modulargolems.content.item.equipments.GolemModelItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 一件龙铠：龙头 / 龙身 / 四肢 / 龙尾 × 铁 / 钻石 / 下界合金。
 *
 * <p>本家的 {@link GolemEquipmentItem} 已经处理好了"这个物品属于哪个 {@link EquipmentSlot}、
 * 对哪些实体生效（{@link #isFor}）、属性怎么报"这三件事；这里按本家金属傀儡那套
 * <b>多护甲</b>的写法再实现 {@link GolemModelItem}：
 * <ul>
 *   <li>类型供应商指向龙的实体类型（{@link DragonGolemItems#ENTITY}）；</li>
 *   <li>按槽位塞护甲/韧性/击退抗性；</li>
 *   <li>{@link #getModelPath()}：这件甲要画护甲模型里的哪几链甲片
 *       （{@link DragonArmorModel} 里用 {@code GolemModelPath} 注册）；</li>
 *   <li>{@link #getModelTexture(LivingEntity)}：这件甲自己的 512×512 图集。</li>
 * </ul>
 *
 * <p>在现有四个甲槽内，加一件新甲 = 新物品 + 新 path，渲染层不用改代码；
 * 但 `DragonArmorLayer` 目前只认 {@code DragonArmorItem}，新增槽位或第三方 `GolemModelItem`
 * 仍要改渲染层。
 *
 * <p>槽位约定：龙头 = HEAD、龙身 = CHEST、四肢 = LEGS、龙尾 = FEET——和
 * {@link DragonGolemMenuControl#fillMenu()} 里注册的四个甲槽一一对应。
 */
public class DragonArmorItem extends GolemEquipmentItem implements GolemModelItem {

    /** 四件甲各自的护甲模型路径（注册在 {@code DragonArmorModel} 的静态块里）。 */
    public static final ResourceLocation PATH_HEAD = new ResourceLocation("dragon_golems", "dragon_armor/head");
    public static final ResourceLocation PATH_BODY = new ResourceLocation("dragon_golems", "dragon_armor/body");
    public static final ResourceLocation PATH_LIMBS = new ResourceLocation("dragon_golems", "dragon_armor/limbs");
    public static final ResourceLocation PATH_TAIL = new ResourceLocation("dragon_golems", "dragon_armor/tail");

    private final DragonArmorMaterial material;
    private final ResourceLocation armorTexture;
    private final ResourceLocation modelPath;

    public DragonArmorItem(Properties properties, EquipmentSlot slot, DragonArmorMaterial material,
                           ResourceLocation modelPath) {
        super(properties, slot, () -> DragonGolemItems.ENTITY.get(),
                builder -> addArmorModifiers(builder, slot, material));
        this.material = material;
        this.armorTexture = material.texture();
        this.modelPath = modelPath;
    }

    @Override
    public ResourceLocation getModelPath() {
        return this.modelPath;
    }

    @Override
    public ResourceLocation getModelTexture(LivingEntity entity) {
        return this.armorTexture;
    }

    public DragonArmorMaterial material() {
        return this.material;
    }

    /** 渲染层用的整龙图集（随物品的材质走，不随槽位走）。 */
    public ResourceLocation armorTexture() {
        return this.armorTexture;
    }

    private static void addArmorModifiers(ImmutableMultimap.Builder<Attribute, AttributeModifier> builder,
                                          EquipmentSlot slot, DragonArmorMaterial material) {
        double armor = material.armorFor(slot);
        if (armor > 0.0D) {
            builder.put(Attributes.ARMOR, modifier(slot, "armor", armor, AttributeModifier.Operation.ADDITION));
        }
        if (material.toughness() > 0.0D) {
            builder.put(Attributes.ARMOR_TOUGHNESS,
                    modifier(slot, "toughness", material.toughness(), AttributeModifier.Operation.ADDITION));
        }
        if (material.knockbackResistance() > 0.0D) {
            builder.put(Attributes.KNOCKBACK_RESISTANCE,
                    modifier(slot, "knockback", material.knockbackResistance(), AttributeModifier.Operation.ADDITION));
        }
    }

    private static AttributeModifier modifier(EquipmentSlot slot, String kind, double amount,
                                              AttributeModifier.Operation operation) {
        // 注意：GolemEquipmentItem 里有一个同名的 protected static 字段 UUID，
        // 会遮住 java.util.UUID 的导入，所以这里必须写全限定名。
        java.util.UUID id = java.util.UUID.nameUUIDFromBytes(
                ("dragon_golems:armor:" + kind + ":" + slot.getName()).getBytes(StandardCharsets.UTF_8));
        return new AttributeModifier(id, "dragon_golems.armor." + kind, amount, operation);
    }
}
