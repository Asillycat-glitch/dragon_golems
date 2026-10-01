package a_silly_cat.dragom_golems.client;

import a_silly_cat.dragom_golems.dragon.DragonGolemEntity;
import dev.xkmc.modulargolems.content.client.overlay.GolemStatusOverlay;
import dev.xkmc.modulargolems.content.core.GolemOverlayControl;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 悬浮查看傀儡时显示的装备小图标：照抄本家犬型傀儡那两个槽（头部 / 身体）。
 * 和 {@link DragonGolemScreenControl} 一样，槽名/贴图必须跟装备界面保持一致。
 */
@OnlyIn(Dist.CLIENT)
public class DragonGolemOverlayControl extends GolemOverlayControl<DragonGolemEntity> {

    public DragonGolemOverlayControl(DragonGolemEntity golem) {
        super(golem);
    }

    @Override
    public void renderImage(GolemStatusOverlay.GolemEquipmentTooltip tooltip, Font font, int x, int y,
                            GuiGraphics graphics) {
        tooltip.renderSlot(graphics, x, y, this.golem.getItemBySlot(EquipmentSlot.HEAD), "altas_helmet");
        tooltip.renderSlot(graphics, x, y + 18, this.golem.getItemBySlot(EquipmentSlot.CHEST),
                "slotbg_dog_armor");
    }

    @Override
    public int getHeight() {
        return 38;
    }

    @Override
    public int getWidth(Font font) {
        return 18;
    }
}
