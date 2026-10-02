package a_silly_cat.dragon_golems.init;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import a_silly_cat.dragon_golems.dragon.DragonGolemItems;
import a_silly_cat.dragon_golems.dragon.DragonGolemPartType;
import com.tterrag.registrate.util.entry.ItemEntry;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * 创造栏（我们自己的那一页）。
 *
 * <p>图标用身体部件 —— 它在装备界面里也是菜单图标。
 *
 * <p><b>为什么只列五种材料的变体</b>：部件的贴图是按
 * {@code dragon_golems:textures/entity/dragon/<材料路径>.png} 取的，没画对应材料就退回
 * {@code base.png}。我们只画了铜 / 金 / 铁 / 下界合金 / 幽匿这五张，所以别的材料（本家之后新加的、
 * 或者别的 mod 塞进 {@code GolemMaterialConfig} 的）铸上去全是同一张灰色兜底图 ——
 * 那既不是"能用的内容"，也没法从图标上看出来是什么。这里就只列这五种，
 * 少列的东西在铁砧上照样能用，只是不在创造栏里摆出来。
 *
 * <p>另外：<b>不要</b>再往本家的「傀儡装配 - 傀儡」页里塞东西。试过一版（挂
 * {@code BuildCreativeModeTabContentsEvent} 把 {@code getAllMaterials()} 全盖上），
 * 结果那一页被几十个一模一样的灰部件刷屏 —— 那个页面的物品名单是本家自己逐个显式挂的，
 * 我们加进去既控不住数量也控不住贴图，所以龙的东西一律留在自己这一页。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Dragon_golems.MODID);

    /** 铸得进龙部件、而且我们画了贴图的五种材料。顺序就是创造栏里的排列顺序。 */
    private static final List<ResourceLocation> SUPPORTED_MATERIALS = List.of(
            new ResourceLocation("modulargolems", "copper"),
            new ResourceLocation("modulargolems", "gold"),
            new ResourceLocation("modulargolems", "iron"),
            new ResourceLocation("modulargolems", "netherite"),
            new ResourceLocation("modulargolems", "sculk"));

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register(
            Dragon_golems.MODID,
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + Dragon_golems.MODID))
                    .icon(() -> new ItemStack(DragonGolemItems.BODY.get()))
                    .displayItems((parameters, output) -> {
                        // 胚料
                        output.accept(DragonGolemItems.TEMPLATE.get());
                        // 五个部件：裸的本体
                        for (ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> part : parts()) {
                            output.accept(new ItemStack(part.get()));
                        }
                        // 五个部件 × 五种材料
                        for (ResourceLocation material : SUPPORTED_MATERIALS) {
                            for (ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> part : parts()) {
                                output.accept(GolemPart.setMaterial(new ItemStack(part.get()), material));
                            }
                        }
                        // 成品：裸的本体 + 五种材料各一条纯部件龙
                        output.accept(DragonGolemItems.HOLDER.get());
                        for (ResourceLocation material : SUPPORTED_MATERIALS) {
                            output.accept(DragonGolemItems.HOLDER.get().withUniformMaterial(material));
                        }
                    })
                    .build());

    /** 五个部件，顺序 = {@link DragonGolemPartType} 的枚举顺序。 */
    private static List<ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>>> parts() {
        return List.of(DragonGolemItems.WINGS, DragonGolemItems.HEAD, DragonGolemItems.BODY,
                DragonGolemItems.TAIL, DragonGolemItems.LEG);
    }

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        CREATIVE_TABS.register(modEventBus);
    }
}
