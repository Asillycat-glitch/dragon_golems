package a_silly_cat.dragon_golems.dragon;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * 龙铠的三档材质：铁 / 钻石 / 下界合金。
 *
 * <p><b>数值对齐本家“大型傀儡甲”三档，不是原版护甲值</b>（原版铁套是 2/6/5/2，
 * 这里刻意不用）：铁 8/10/6/4、钻石 11/14/8/6、下界合金 14/18/10/10；
 * 每件都带本家同档的韧性，下界合金另带击退抗性。
 * 四件分别用 HEAD / CHEST / LEGS / FEET 当载体槽（龙头 / 龙身 / 四肢 / 龙尾）。
 *
 * <p>装甲贴图是 512×512 的整龙图集，一张图集对应一档材质；
 * 四件混穿时每件各自绑自己的图集（{@link #texture()}），互不影响。
 */
public enum DragonArmorMaterial {

    // 数值直接对齐本家"大型傀儡甲"的三档（2.7.5+3）：
    //   roman_guard（铁）    头 8 / 胸 10 / 腿 6 / 靴 4，韧性 4
    //   wind_spirit（钻石）  头 11 / 胸 14 / 腿 8 / 靴 6，韧性 6
    //   barbaric_vanguard（下界合金）头 14 / 胸 18 / 腿 10 / 靴 10，韧性 8 + 击退抗性 0.2
    // 龙的四件按"头甲←头盔、身甲←胸甲、四肢←护腿、尾甲←靴子"取对应值。
    // 注意：这里的护甲/韧性/击退抗性都是**每件**的数值，四件合计是单件的四倍。
    IRON("iron", 8.0D, 10.0D, 6.0D, 4.0D, 4.0D, 0.0D),
    DIAMOND("diamond", 11.0D, 14.0D, 8.0D, 6.0D, 6.0D, 0.0D),
    NETHERITE("netherite", 14.0D, 18.0D, 10.0D, 10.0D, 8.0D, 0.2D);

    private final String id;
    private final double headArmor;
    private final double bodyArmor;
    private final double limbsArmor;
    private final double tailArmor;
    private final double toughness;
    private final double knockbackResistance;

    DragonArmorMaterial(String id, double headArmor, double bodyArmor, double limbsArmor, double tailArmor,
                        double toughness, double knockbackResistance) {
        this.id = id;
        this.headArmor = headArmor;
        this.bodyArmor = bodyArmor;
        this.limbsArmor = limbsArmor;
        this.tailArmor = tailArmor;
        this.toughness = toughness;
        this.knockbackResistance = knockbackResistance;
    }

    /** 哪件甲用哪个槽：龙头 HEAD、龙身 CHEST、四肢 LEGS、龙尾 FEET。 */
    public double armorFor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> this.headArmor;
            case CHEST -> this.bodyArmor;
            case LEGS -> this.limbsArmor;
            case FEET -> this.tailArmor;
            default -> 0.0D;
        };
    }

    public double toughness() {
        return this.toughness;
    }

    public double knockbackResistance() {
        return this.knockbackResistance;
    }

    /** 整龙图集：{@code textures/entity/dragon/armor/<材质>.png}。 */
    public ResourceLocation texture() {
        return new ResourceLocation("dragon_golems", "textures/entity/dragon/armor/" + this.id + ".png");
    }
}
