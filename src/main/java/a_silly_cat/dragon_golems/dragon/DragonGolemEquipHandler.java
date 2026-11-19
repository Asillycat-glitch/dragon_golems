package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import dev.xkmc.modulargolems.content.item.equipments.IGolemEquipmentItem;
import dev.xkmc.modulargolems.events.event.GolemEquipItemEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 龙的"这件装备该进哪个 {@code EquipmentSlot}"判定。
 *
 * <p><b>为什么还要这一个类：</b>本家 {@code GolemEventListeners.onEquip} 对
 * {@code IGolemEquipmentItem} 的分支是按傀儡类型写的——大型 / 人形 / 犬型各一段
 * {@code instanceof}，<b>没有兜底</b>。龙的 {@code DragonGolemEntity} 不在任何一段里，
 * 所以哪怕有一个 {@code isFor(龙的实体类型)} 的龙甲，装备事件也不会把它的
 * {@code getSlot()}（HEAD / CHEST / LEGS / FEET）报给菜单，{@link DragonGolemMenuControl} 里
 * {@code isValid(EquipmentSlot.X, stack)} 就会一直是 false。
 *
 * <p>这里只补"本家对别的傀儡会做、但对龙没做"的那一条：物品是
 * {@link IGolemEquipmentItem} 且 {@code isFor(龙的实体类型)} 为真时，按它自己的
 * {@code getSlot()} 报上去。旗帜/皮肤/巡逻手册三个槽不经过这里——它们在
 * {@code DragonGolemMenuControl.fillMenu()} 里是用物品/标签直接判的。
 *
 * <p>龙甲四件的槽位约定：<b>龙头 = HEAD、龙身 = CHEST、四肢 = LEGS、龙尾 = FEET</b>。
 * 12 件龙甲（`DragonArmorItem`）都已注册，并实现 {@code IGolemEquipmentItem} /
 * {@code isFor(龙)}，所以对应的槽会自动接受它们。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DragonGolemEquipHandler {

    @SubscribeEvent
    public static void onGolemEquip(GolemEquipItemEvent event) {
        if (!(event.getEntity() instanceof DragonGolemEntity dragon)) {
            return;
        }
        ItemStack stack = event.getStack();
        if (stack.getItem() instanceof IGolemEquipmentItem item && item.isFor(dragon.getType())) {
            event.setSlot(1, item.getSlot());
        }
    }

    private DragonGolemEquipHandler() {
    }
}
