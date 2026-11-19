package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.client.DragonGolemScreenControl;
import dev.xkmc.modulargolems.content.core.GolemScreenControl;
import dev.xkmc.modulargolems.content.entity.common.SweepGolemMenuControl;
import dev.xkmc.modulargolems.content.menu.equipment.EquipmentsMenu;
import dev.xkmc.modulargolems.init.data.MGTagGen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BannerItem;

import java.util.Optional;

/**
 * 龙的装备界面控制：<b>七格</b>——左列四件龙甲（龙头 / 龙身 / 四肢 / 龙尾），右列旗帜 / 皮肤 / 巡逻手册。
 *
 * <p><b>排列</b>：四件龙甲用本家中心列 {@code head(80,17) / chest(80,35) / legs(80,53) / feet(80,71)}，
 * 右列用 <b>x=98</b> 那组 comp {@code left_shoulder(98,17) / left_hand(98,35) / arrow_1(98,53)}，
 * 两列只隔 18 像素、是紧贴的。
 * （不要用 x=116 的 {@code banner/skin/route}：那组和中心列隔着一整列，界面上会多出一条空档。）
 * 这些名字都是 l2library 装备布局
 * （本家 {@code data/modulargolems/l2library_config/menu_layout/equipments.json}）里现成的
 * comp 名，坐标和空槽底图都由那份 json 决定，{@code DragonGolemScreenControl} 只负责把
 * "slot" 底板和底图贴上去。名字写错 = 槽不会出现在界面上，但也不会崩。
 *
 * <p><b>龙甲的槽位约定</b>：龙头 = {@code HEAD}、龙身 = {@code CHEST}、四肢 = {@code LEGS}、龙尾 = {@code FEET}。
 * 龙甲物品实现 {@code IGolemEquipmentItem} 时，{@code getSlot()} 按这个返回即可；
 * 本家 {@code GolemEventListeners.onEquip} 只给大型/人形/犬型写了
 * {@code IGolemEquipmentItem.isFor(...)} 分支，龙由 {@link DragonGolemEquipHandler} 补上同一条。
 *
 * <p><b>存储也必须对</b>：新版装备菜单的每个槽其实是
 * {@code GolemInvManager.View} 里的一个 {@code ItemWrapper}——
 * {@code getWrapperOfHand(HEAD/CHEST/LEGS/FEET)} / {@code getBannerSlot()} / {@code getSkinSlot()} /
 * {@code getRouteSlot()}。{@code addSlot} 的第二个参数就是这个包装器；
 * 传 {@code ItemWrapper.EMPTY} 的话槽看起来能开，但放进去的东西不落地（等于吞物品）。
 *
 * <p><b>各槽收什么</b>：
 * <ul>
 *   <li>{@code head}/{@code chest}/{@code legs}/{@code feet}：走
 *       {@link #isValid(EquipmentSlot, net.minecraft.world.item.ItemStack)}，
 *       也就是"装备事件把物品判成 HEAD/CHEST/LEGS/FEET"的龙甲；当前 12 件龙甲已注册；</li>
 *   <li>{@code left_shoulder}（布局位置，语义是旗帜）：任何 {@link BannerItem}
 *       （披风的来源，和本家犬型/大型傀儡的旗帜槽一致）；</li>
 *   <li>{@code left_hand}（语义是皮肤）：{@code modulargolems:curio/skin} 标签里的傀儡皮肤；</li>
 *   <li>{@code arrow_1}（语义是巡逻手册）：{@code modulargolems:curio/path} 标签里的巡逻手册。</li>
 * </ul>
 *
 * <p>{@code addSlot} 的第三个参数是"允许 Shift 快速移入"，第四个 int 是快速移动的优先级
 * （本家给 banner/skin 用 1）。
 */
public class DragonGolemMenuControl extends SweepGolemMenuControl<DragonGolemEntity> {

    public DragonGolemMenuControl(EquipmentsMenu menu, DragonGolemEntity golem) {
        super(menu, golem);
    }

    @Override
    public void fillMenu() {
        // 四件龙甲：龙头 / 龙身 / 四肢 / 龙尾，放在中心列
        // head(80,17) / chest(80,35) / legs(80,53) / feet(80,71)。
        // 语义约定：龙头 = HEAD、龙身 = CHEST、四肢 = LEGS、龙尾 = FEET
        // （龙甲物品的 getSlot() 按这个返回）。
        addSlot("head", () -> golem.getWrapperOfHand(EquipmentSlot.HEAD), true,
                stack -> isValid(EquipmentSlot.HEAD, stack));
        addSlot("chest", () -> golem.getWrapperOfHand(EquipmentSlot.CHEST), true,
                stack -> isValid(EquipmentSlot.CHEST, stack));
        addSlot("legs", () -> golem.getWrapperOfHand(EquipmentSlot.LEGS), true,
                stack -> isValid(EquipmentSlot.LEGS, stack));
        addSlot("feet", () -> golem.getWrapperOfHand(EquipmentSlot.FEET), true,
                stack -> isValid(EquipmentSlot.FEET, stack));
        // 右边紧贴的一列（x=98）：旗帜（披风来源）/ 皮肤 / 巡逻手册。
        // 注意：这里必须用布局里 x=98 那组 comp 名（left_shoulder / left_hand / arrow_1），
        // 不能用 x=116 的 banner/skin/route——那组和中心列隔着一整列，界面上会看到一条空档。
        addSlot("left_shoulder", golem::getBannerSlot, true,
                stack -> stack.getItem() instanceof BannerItem, 1);
        addSlot("left_hand", golem::getSkinSlot, true, stack -> stack.is(MGTagGen.CURIO_SKIN), 1);
        addSlot("arrow_1", golem::getRouteSlot, true, stack -> stack.is(MGTagGen.CURIO_PATH));
    }

    /**
     * 已废弃的上游接口：2.7.5 的 {@code EquipmentsMenu} 按 {@code slotNames} 顺序走快速移动，
     * 不再读这个数组，整包只有 {@code GolemMenuControl} 自己声明了它。
     * 保留返回犬型两格只是历史兜底；实际槽位由 {@link #fillMenu()} 注册的七个槽决定。
     */
    @Override
    public EquipmentSlot[] getSlotDefinition() {
        return EquipmentsMenu.DOG_SLOTS;
    }

    @Override
    public Optional<GolemScreenControl<DragonGolemEntity>> getScreenProvider() {
        return Optional.of(new DragonGolemScreenControl(this));
    }
}
