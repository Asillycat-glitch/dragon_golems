package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import dev.xkmc.l2library.base.menu.base.MenuLayoutConfig;
import dev.xkmc.modulargolems.content.core.GolemMenuControl;
import dev.xkmc.modulargolems.content.core.GolemScreenControl;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 龙装备界面的绘制：<b>左列四件龙甲 + 右列紧贴的旗帜/皮肤/巡逻手册</b>，共七格。
 *
 * <p>布局直接用本家 {@code equipments} 布局的 comp 名，和大型傀儡同一套坐标：
 * <pre>
 *   head  (80,17)   left_shoulder (98,17)   ← 旗帜
 *   chest (80,35)   left_hand     (98,35)   ← 皮肤
 *   legs  (80,53)   arrow_1       (98,53)   ← 巡逻手册
 *   feet  (80,71)
 * </pre>
 * 右列特意用 x=98 那组 comp（不是 x=116 的 banner/skin/route）：那组和中心列隔着一整列，
 * 界面上会多出一条空档；x=98 和 x=80 只隔 18 像素，两列是紧贴的。
 * 槽名必须和 {@link a_silly_cat.dragon_golems.dragon.DragonGolemMenuControl#fillMenu()} 一致，
 * 否则底板/底图会画到别的坐标上（{@code MenuLayoutConfig} 是按 comp 名取坐标的）。
 *
 * <p>两种空槽图标的画法照抄本家：
 * <ul>
 *   <li>{@code altas_*}（原版护甲轮廓）：偏移 (0, 0)；</li>
 *   <li>{@code slotbg_*}（本家画的槽底图）：偏移 (-1, -1)，因为那张图是 18×18、比 16×16 的格子大一圈。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class DragonGolemScreenControl extends GolemScreenControl<DragonGolemEntity> {

    public DragonGolemScreenControl(GolemMenuControl<DragonGolemEntity> ctrl) {
        super(ctrl);
    }

    @Override
    public void render(MenuLayoutConfig.ScreenRenderer renderer, GuiGraphics graphics, float partialTick) {
        // 左列：四件龙甲（龙头 / 龙身 / 四肢 / 龙尾）——用大傀儡的中心列。
        drawSlot(renderer, graphics, "head", "altas_helmet");
        drawSlot(renderer, graphics, "chest", "altas_chestplate");
        drawSlot(renderer, graphics, "legs", "altas_leggings");
        drawSlot(renderer, graphics, "feet", "altas_boots");
        // 右列紧贴（x=98）：旗帜（披风来源）/ 皮肤 / 巡逻手册。
        // 用 left_shoulder/left_hand/arrow_1 三个 comp，和中心列只隔 18 像素，不再出现中间空档。
        drawSlot(renderer, graphics, "left_shoulder", "slotbg_banner");
        drawSlot(renderer, graphics, "left_hand", "slotbg_skin");
        drawSlot(renderer, graphics, "arrow_1", "slotbg_route");
    }

    /** 先铺 18×18 的槽底板，空着的时候再叠一张"这里该放什么"的底图。 */
    private void drawSlot(MenuLayoutConfig.ScreenRenderer renderer, GuiGraphics graphics,
                          String slot, String emptyIcon) {
        renderer.draw(graphics, slot, "slot", -1, -1);
        if (!menu.isSlotEmpty(slot)) {
            return;
        }
        boolean atlasIcon = emptyIcon.startsWith("altas_");
        renderer.draw(graphics, slot, emptyIcon, atlasIcon ? 0 : -1, atlasIcon ? 0 : -1);
    }
}
