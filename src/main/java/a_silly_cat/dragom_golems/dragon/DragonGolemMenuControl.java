package a_silly_cat.dragom_golems.dragon;

import a_silly_cat.dragom_golems.client.DragonGolemScreenControl;
import dev.xkmc.modulargolems.content.core.GolemScreenControl;
import dev.xkmc.modulargolems.content.entity.common.SweepGolemMenuControl;
import dev.xkmc.modulargolems.content.menu.equipment.EquipmentsMenu;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.Optional;

/**
 * 龙的装备界面控制。
 *
 * <p>装备栏暂时按<b>犬型</b>来：只有两个护甲位（头部 / 身体），不铺主手、副手、箭矢、副武器那些槽。
 * 这也是本家 {@code DogGolemMenuControl} 的做法，所以龙目前既不能拿武器、也没有远程槽，
 * 等以后确定龙的战斗方式再决定要不要加回来。
 */
public class DragonGolemMenuControl extends SweepGolemMenuControl<DragonGolemEntity> {

    public DragonGolemMenuControl(EquipmentsMenu menu, DragonGolemEntity golem) {
        super(menu, golem);
    }

    /** 两个护甲位：胸口槽收头盔，腿槽收胸甲（沿用本家犬型傀儡的槽名，界面贴图才对得上）。 */
    @Override
    public void fillMenu() {
        this.menu.addSlot("chest", stack -> isValid(EquipmentSlot.HEAD, stack));
        this.menu.addSlot("legs", stack -> isValid(EquipmentSlot.CHEST, stack));
    }

    @Override
    public EquipmentSlot[] getSlotDefinition() {
        return EquipmentsMenu.DOG_SLOTS;
    }

    @Override
    public Optional<GolemScreenControl<DragonGolemEntity>> getScreenProvider() {
        return Optional.of(new DragonGolemScreenControl(this));
    }
}
