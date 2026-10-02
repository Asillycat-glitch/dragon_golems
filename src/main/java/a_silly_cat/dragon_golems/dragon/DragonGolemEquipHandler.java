package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import dev.xkmc.modulargolems.events.event.GolemEquipItemEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BannerItem;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 龙的装备槽"收什么"的判定。
 *
 * <p><b>为什么必须我们自己接管：</b>本家决定一件物品能进哪个槽的是
 * {@code GolemEventListeners.onEquip(GolemEquipItemEvent)}，而它里面只有
 * <b>人形 / 大型金属 / 犬型</b>三种傀儡的分支（2.7.3 字节码逐条 {@code instanceof} 确认过），
 * <b>没有兜底</b>。谁没被那个事件设过槽位，{@code GolemMenuControl.getSlotForItem} 最后就
 * {@code return Set.of();} —— 于是
 * {@link DragonGolemMenuControl} 注册的那两格（{@code isValid(HEAD)} / {@code isValid(CHEST)}）
 * 谓词恒为 false，<b>连头盔和旗帜都放不进去</b>。
 *
 * <p>现在只放<b>旗帜 → 头部槽</b>，和本家对那三种傀儡的做法一致
 * （{@code BannerItem → EquipmentSlot.HEAD}）。护甲等有设计之后再往这里加分支。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DragonGolemEquipHandler {

    @SubscribeEvent
    public static void onGolemEquip(GolemEquipItemEvent event) {
        if (!(event.getEntity() instanceof DragonGolemEntity)) {
            return;
        }
        if (event.getStack().getItem() instanceof BannerItem) {
            // 1 个就够（旗帜不叠），给头部槽
            event.setSlot(1, EquipmentSlot.HEAD);
        }
    }

    private DragonGolemEquipHandler() {
    }
}
