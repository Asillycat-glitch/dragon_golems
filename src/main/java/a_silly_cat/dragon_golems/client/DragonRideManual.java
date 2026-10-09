package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 骑龙时屏幕左侧的"操作手册"：把按键说明摆在眼前，省得玩家去翻 JEI。
 *
 * <p>只在<b>骑着龙</b>时显示，而且开着界面（{@code mc.screen != null}）或按了 F1（{@code options.hideGui}）
 * 时不画。默认展开，按 J（{@link DragonRiderKeys#MANUAL}）收起 —— 状态只存在内存里，
 * 重开游戏回到默认，不写配置也不写存档。
 *
 * <p>文案全部走 lang（{@code overlay.dragon_golems.ride.*}），所以改字不用重编代码。
 * 每行的内容都对着代码核过，别凭印象写：
 * <ul>
 *   <li>上升/下降用的是原版跳跃键与<b>左 Ctrl</b>（{@link DragonRiderKeys#DESCEND}）——
 *       Shift 是原版下车键，不能拿来做下降；</li>
 *   <li>R = 冲锋（`SKILL_DIVE`，30 秒冷却，<b>必须有目标</b>）；G = 龙息（3 秒喷流）；
 *       V = 龙弹，<b>幽匿身体时改成音爆</b>（{@code DragonRiderSkillGoal.tickBlast} 里那条分支）；
 *       G / V 的冷却都是 60 tick（`RIDER_BLAST_CD`）；</li>
 *   <li>H = 自由飞行（穿墙）开关。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DragonRideManual {

    private static final String TITLE = "overlay.dragon_golems.ride.title";

    /** 手册正文：一条一行，数组顺序就是显示顺序。 */
    private static final String[] LINES = {
            "overlay.dragon_golems.ride.move",
            "overlay.dragon_golems.ride.height",
            "overlay.dragon_golems.ride.free",
            "overlay.dragon_golems.ride.charge",
            "overlay.dragon_golems.ride.breath",
            "overlay.dragon_golems.ride.blast",
            "overlay.dragon_golems.ride.dismount",
            "overlay.dragon_golems.ride.wand",
            "overlay.dragon_golems.ride.toggle",
    };

    /** 会话内的展开状态。 */
    private static boolean expanded = true;

    private DragonRideManual() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!expanded || mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        if (!(mc.player.getVehicle() instanceof DragonGolemEntity)) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        int x = 4;
        int y = 4;
        int line = mc.font.lineHeight + 2;
        int width = mc.font.width(Component.translatable(TITLE));
        for (String key : LINES) {
            width = Math.max(width, mc.font.width(Component.translatable(key)));
        }
        int height = line * (LINES.length + 1) + 2;
        // 半透明底板：不铺的话在亮色天空/雪地上读不清
        g.fill(x - 3, y - 3, x + width + 3, y + height, 0x90000000);
        g.drawString(mc.font, Component.translatable(TITLE), x, y, 0xFFE0C060, true);
        for (int i = 0; i < LINES.length; i++) {
            g.drawString(mc.font, Component.translatable(LINES[i]), x, y + line * (i + 1), 0xFFE0E0E0, true);
        }
    }

    /**
     * 自己消费 MANUAL 的点击，不塞进 {@link DragonRiderKeys} 的技能处理里 ——
     * 那边有一堆"没骑龙就 return"的早退，开关不该受影响。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (DragonRiderKeys.MANUAL.consumeClick()) {
            expanded = !expanded;
        }
    }
}
