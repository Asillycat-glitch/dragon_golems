package a_silly_cat.dragom_golems.client;

import a_silly_cat.dragom_golems.dragon.DragonGolemEntity;
import dev.xkmc.l2library.base.menu.base.MenuLayoutConfig;
import dev.xkmc.modulargolems.content.core.GolemMenuControl;
import dev.xkmc.modulargolems.content.core.GolemScreenControl;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 龙装备界面的绘制：照抄本家犬型傀儡那两个槽（头部 / 身体）的画法，
 * 槽名必须和 {@link a_silly_cat.dragom_golems.dragon.DragonGolemMenuControl#fillMenu()} 里一致，
 * 否则背景框和实际槽位对不上。
 */
@OnlyIn(Dist.CLIENT)
public class DragonGolemScreenControl extends GolemScreenControl<DragonGolemEntity> {

    public DragonGolemScreenControl(GolemMenuControl<DragonGolemEntity> ctrl) {
        super(ctrl);
    }

    @Override
    public void render(MenuLayoutConfig.ScreenRenderer renderer, GuiGraphics graphics, float partialTick) {
        renderer.draw(graphics, "chest", "slot", -1, -1);
        renderer.draw(graphics, "legs", "slot", -1, -1);
        if (this.menu.getAsPredSlot("chest", 0, 0).getItem().isEmpty()) {
            renderer.draw(graphics, "chest", "altas_helmet", 0, 0);
        }
        if (this.menu.getAsPredSlot("legs", 0, 0).getItem().isEmpty()) {
            renderer.draw(graphics, "legs", "slotbg_dog_armor", -1, -1);
        }
    }
}
