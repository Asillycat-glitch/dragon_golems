package a_silly_cat.dragon_golems.dragon;

import com.tterrag.registrate.util.entry.EntityEntry;
import a_silly_cat.dragon_golems.client.DragonGolemOverlayControl;
import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import dev.xkmc.modulargolems.content.core.GolemMenuControl;
import dev.xkmc.modulargolems.content.core.GolemOverlayControl;
import dev.xkmc.modulargolems.content.core.GolemType;
import dev.xkmc.modulargolems.content.core.ModelProvider;
import dev.xkmc.modulargolems.content.menu.equipment.EquipmentsMenu;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * 龙傀儡的种类定义。
 *
 * <p>升级槽数量：基类 {@code GolemType.getUpgradeSlots()} 返回"部件枚举的数量"——我们有 5 个部件，
 * 所以默认正好是 5 槽（和以前一样）。现在改成读配置 {@code golem.upgradeSlots}，
 * 和本家三种傀儡的 {@code largeGolemSlot / humanoidGolemSlot / dogGolemSlot} 同级
 * （本家那三个键只对本家三种类型生效，龙没有对应的键，所以以前"想调也调不了"）。
 *
 * <p>其余行为保持默认：升级能不能装还受 {@code UpgradeItem.fitsOn(GolemType)} 约束
 * （例如"坐骑升级"由我们的 {@code RideUpgradeMixin} 放行）。
 */
public class DragonGolemType extends GolemType<DragonGolemEntity, DragonGolemPartType> {

    /**
     * @param entry 实体注册项
     * @param model 客户端模型提供者（只会在客户端被调用，服务器上不会被实例化）
     */
    public DragonGolemType(EntityEntry<DragonGolemEntity> entry,
                           Supplier<ModelProvider<DragonGolemEntity, DragonGolemPartType>> model) {
        super(entry, DragonGolemPartType::values, DragonGolemPartType.BODY, model);
    }

    /** 升级槽数量改读我们自己的配置（见 {@link DragonGolemConfig#upgradeSlots()}）。 */
    @Override
    public int getUpgradeSlots() {
        return DragonGolemConfig.upgradeSlots();
    }

    @Override
    public GolemMenuControl<DragonGolemEntity> menuControl(EquipmentsMenu menu, DragonGolemEntity golem) {
        return new DragonGolemMenuControl(menu, golem);
    }

    @Override
    public Supplier<Supplier<GolemOverlayControl<DragonGolemEntity>>> overlayControl(DragonGolemEntity golem) {
        // 双层 Supplier：外层延迟到客户端再取值，跟本家 Metal/DogGolemType 的写法一致。
        return () -> () -> new DragonGolemOverlayControl(golem);
    }

    @Override
    public ItemStack getMenuIcon(DragonGolemEntity golem) {
        return DragonGolemItems.BODY.asStack();
    }
}
