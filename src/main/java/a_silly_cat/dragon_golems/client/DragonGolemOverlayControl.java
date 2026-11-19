package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import dev.xkmc.modulargolems.content.client.overlay.GolemStatusOverlay;
import dev.xkmc.modulargolems.content.core.GolemOverlayControl;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 悬浮查看傀儡（法杖指向它时）显示的装备小图标。
 *
 * <p>和装备界面保持同一套七格，排成 <b>2 列 × 4 行</b>：
 * <pre>
 *   head  (0, 0)   banner (18, 0)
 *   chest (0,18)   skin   (18,18)
 *   legs  (0,36)   route  (18,36)
 *   feet  (0,54)
 * </pre>
 * 左列是四件龙甲，右列是旗帜/皮肤/巡逻手册（比左列少一行，和装备界面一致）；
 * 槽名、存储和 {@link a_silly_cat.dragon_golems.dragon.DragonGolemMenuControl} 一致，
 * 空槽图标也用它同一套（`altas_helmet` / `altas_chestplate` / `altas_leggings` / `altas_boots` /
 * `slotbg_banner` / `slotbg_skin` / `slotbg_route`）。
 */
@OnlyIn(Dist.CLIENT)
public class DragonGolemOverlayControl extends GolemOverlayControl<DragonGolemEntity> {

    public DragonGolemOverlayControl(DragonGolemEntity golem) {
        super(golem);
    }

    @Override
    public void renderImage(GolemStatusOverlay.GolemEquipmentTooltip tooltip, Font font, int x, int y,
                            GuiGraphics graphics) {
        // 左列：四件龙甲（龙头 / 龙身 / 四肢 / 龙尾）
        tooltip.renderSlot(graphics, x, y,
                this.golem.getWrapperOfHand(EquipmentSlot.HEAD).getItem(), "altas_helmet");
        tooltip.renderSlot(graphics, x, y + 18,
                this.golem.getWrapperOfHand(EquipmentSlot.CHEST).getItem(), "altas_chestplate");
        tooltip.renderSlot(graphics, x, y + 36,
                this.golem.getWrapperOfHand(EquipmentSlot.LEGS).getItem(), "altas_leggings");
        tooltip.renderSlot(graphics, x, y + 54,
                this.golem.getWrapperOfHand(EquipmentSlot.FEET).getItem(), "altas_boots");
        // 右列：旗帜 / 皮肤 / 巡逻手册（少一行，和界面上空出来的那一格一致）
        tooltip.renderSlot(graphics, x + 18, y, this.golem.getBannerSlot().getItem(), "slotbg_banner");
        tooltip.renderSlot(graphics, x + 18, y + 18, this.golem.getSkinSlot().getItem(), "slotbg_skin");
        tooltip.renderSlot(graphics, x + 18, y + 36, this.golem.getRouteSlot().getItem(), "slotbg_route");
    }

    @Override
    public int getHeight() {
        return 74;
    }

    @Override
    public int getWidth(Font font) {
        return 36;
    }
}
