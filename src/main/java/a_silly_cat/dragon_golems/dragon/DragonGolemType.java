package a_silly_cat.dragon_golems.dragon;

import com.tterrag.registrate.util.entry.EntityEntry;
import a_silly_cat.dragon_golems.client.DragonGolemOverlayControl;
import dev.xkmc.modulargolems.content.core.GolemMenuControl;
import dev.xkmc.modulargolems.content.core.GolemOverlayControl;
import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import dev.xkmc.modulargolems.content.core.GolemType;
import dev.xkmc.modulargolems.content.core.ModelProvider;
import dev.xkmc.modulargolems.content.menu.equipment.EquipmentsMenu;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * 龙傀儡的种类定义。
 *
 * <p>升级槽保持默认（{@code GolemType.getUpgradeSlots()} 返回部件数量，也就是 5 个），
 * 不额外覆盖：现有升级能装上去，只是有些需要特定 AI 的行为暂时不会触发。
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

    /**
     * 升级槽数量：和本家 {@code MetalGolemType#getUpgradeSlots()} 同一套做法 —— 读配置。
     * 默认 5 = 5 个部件各占一槽（本家基类的默认行为）。
     */
    @Override
    public int getUpgradeSlots() {
        return (int) DragonGolemConfig.upgradeSlots();
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
