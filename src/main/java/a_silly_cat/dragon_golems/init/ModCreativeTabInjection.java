package a_silly_cat.dragon_golems.init;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonGolemItems;
import dev.xkmc.modulargolems.content.config.GolemMaterialConfig;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把龙部件和龙傀儡补进本家的「傀儡装配 - 傀儡」页（{@code modulargolems:golems}）。
 *
 * <p>本家那一页的内容是它自己逐个物品挂上去的（{@code GolemPart#fillItemCategory}：裸体 + 每种材料，
 * 一个物品一段），我们注册的物品不在里面。这里补上同一形状的段落：
 * <b>一段一个部件 = 裸体 + 可用材料变体</b>，材料用 {@link ModCreativeTabs#usableMaterials} 过滤，
 * 所以不会出现"拿得出来、铁砧上铸不上"的条目，也不会被几十个灰色兜底部件刷屏。
 *
 * <p><b>为什么用事件而不是像 tinkers_golem 那样在注册链上挂本家的 tab key</b>：TG 的做法是
 * {@code .transform(e -> e.tab(GolemItems.GOLEMS.getKey(), x -> e.getEntry().fillItemCategory(x)))}
 * —— 借本家的 {@code ResourceKey<CreativeModeTab>} 加上本家部件的填充逻辑。那样也能用，但要把本家
 * tab 的 key 抄进物品注册代码，而且 {@code fillItemCategory} 是{@code getAllMaterials()} 全铺、
 * 没有任何可用性过滤（TG 那 30 多个"铸不上"的变体就是这么进来的）。
 * 用 {@link BuildCreativeModeTabContentsEvent}（MOD 总线）效果一样，注册链保持干净，过滤也好写。
 * 代价只有一个：只能<b>追加</b>，龙的那几段会排在本家自己那几百条后面。
 *
 * <p><b>不想要龙的东西出现在本家页里</b>：删掉这个类即可，我们自己的创造栏不受影响。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModCreativeTabInjection {

    /** 本家「傀儡」页的 key。用字面量而不是 {@code GolemItems.GOLEMS.getKey()}，免得提前触发本家那个类的静态初始化。 */
    private static final ResourceLocation GOLEMS_TAB = new ResourceLocation("modulargolems", "golems");

    @SubscribeEvent
    public static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().location().equals(GOLEMS_TAB)) return;

        for (var entry : ModCreativeTabs.parts()) {
            GolemPart<?, ?> part = entry.get();
            event.accept(new ItemStack(part));
            for (ResourceLocation material : ModCreativeTabs.usableMaterials(m -> GolemMaterialConfig.mayApply(part, m))) {
                event.accept(GolemPart.setMaterial(new ItemStack(part), material));
            }
        }

        var holder = DragonGolemItems.HOLDER.get();
        for (ResourceLocation material : ModCreativeTabs.usableMaterials(m -> GolemMaterialConfig.mayApply(holder, m))) {
            event.accept(holder.withUniformMaterial(material));
        }
    }

    private ModCreativeTabInjection() {
    }
}
