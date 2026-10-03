package a_silly_cat.dragon_golems.init;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import a_silly_cat.dragon_golems.dragon.DragonGolemItems;
import a_silly_cat.dragon_golems.dragon.DragonGolemPartType;
import com.tterrag.registrate.util.entry.ItemEntry;
import dev.xkmc.modulargolems.content.config.GolemMaterialConfig;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import dev.xkmc.modulargolems.init.data.MGTagGen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 创造栏（我们自己的那一页）。
 *
 * <p>图标用身体部件 —— 它在装备界面里也是菜单图标。
 *
 * <p><b>排列方式：按部件分段</b>（每段 = 裸体 + 该部件的可用材料变体，然后才是下一个部件）。
 * 本家「傀儡装配 - 傀儡」页就是这种分段：它的 {@code GolemPart#fillItemCategory} 是<b>按物品</b>
 * 调用的，所以每个物品贡献一段材料变体。早先这里是按材料分段（同一种材料的五个部件挨在一起），
 * 翻起来既不像本家，也不方便对照"这个部件都有哪些材料"。
 *
 * <p><b>只摆真的能用的</b>：{@link #usableMaterials(Predicate)} 用的是和本家 JEI 插件同一套判断
 * （材料有铸造物品、不在 {@code modulargolems:special_crafting_material} 里、{@code mayApply} 认可）。
 * 创造栏本身<b>不做</b>任何可用性检查（本家的 {@code fillItemCategory} 就是把
 * {@code getAllMaterials()} 全铺一遍），所以"拿得出来、铁砧上却铸不上"的条目要靠我们自己挡掉。
 *
 * <p><b>为什么只列这五种材料</b>：部件的贴图是按
 * {@code dragon_golems:textures/entity/dragon/<材料路径>.png} 取的，没画对应材料就退回
 * {@code base.png}。我们只画了铜 / 金 / 铁 / 下界合金 / 幽匿这五张，所以别的材料（本家之后新加的、
 * 或者别的 mod 塞进 {@code GolemMaterialConfig} 的）铸上去全是同一张灰色兜底图 ——
 * 那既不是"能用的内容"，也没法从图标上看出来是什么。少列的东西在铁砧上照样能用，只是不在创造栏里摆出来。
 *
 * <p><b>本家那一页</b>：龙部件与成品的对应段落由 {@link ModCreativeTabInjection} 补上去，
 * 分段与过滤都和这里一致。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Dragon_golems.MODID);

    /** 铸得进龙部件、而且我们画了贴图的五种材料。段内顺序就是创造栏里的排列顺序。 */
    static final List<ResourceLocation> SUPPORTED_MATERIALS = List.of(
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
                        // 五个部件，一段一个部件：裸体 + 可用材料变体
                        for (ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> entry : parts()) {
                            GolemPart<DragonGolemEntity, DragonGolemPartType> part = entry.get();
                            output.accept(new ItemStack(part));
                            for (ResourceLocation material : usableMaterials(m -> GolemMaterialConfig.mayApply(part, m))) {
                                output.accept(GolemPart.setMaterial(new ItemStack(part), material));
                            }
                        }
                        // 成品：裸体 + 每种可用材料一支"五个部件同材料"的龙
                        output.accept(DragonGolemItems.HOLDER.get());
                        var holder = DragonGolemItems.HOLDER.get();
                        for (ResourceLocation material : usableMaterials(m -> GolemMaterialConfig.mayApply(holder, m))) {
                            output.accept(holder.withUniformMaterial(material));
                        }
                    })
                    .build());

    /** 五个部件，顺序 = {@link DragonGolemPartType} 的枚举顺序。 */
    static List<ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>>> parts() {
        return List.of(DragonGolemItems.WINGS, DragonGolemItems.HEAD, DragonGolemItems.BODY,
                DragonGolemItems.TAIL, DragonGolemItems.LEG);
    }

    /**
     * {@link #SUPPORTED_MATERIALS} 里"这个部件真的能用"的那些。
     *
     * <p>三条过滤和本家 JEI 插件生成铁砧页时用的是同一套：
     * <ol>
     *   <li>材料有铸造物品（{@code getAllMaterials()} 只在生产环境剔除空 ingredient）；</li>
     *   <li>铸造物品不在 {@code modulargolems:special_crafting_material} 里 —— 进了那个标签的材料
     *       在铁砧上直接不给结果，摆出来就是骗人；</li>
     *   <li>{@code GolemMaterialConfig#mayApply} 认可这一对"部件 × 材料"（龙部件与材料键的判定）。</li>
     * </ol>
     */
    static List<ResourceLocation> usableMaterials(Predicate<ResourceLocation> applies) {
        GolemMaterialConfig config = GolemMaterialConfig.get();
        List<ResourceLocation> ans = new ArrayList<>();
        for (ResourceLocation material : SUPPORTED_MATERIALS) {
            Ingredient ingredient = config.getCraftIngredient(material);
            if (ingredient.isEmpty()) continue;
            boolean special = false;
            for (ItemStack stack : ingredient.getItems()) {
                if (stack.is(MGTagGen.SPECIAL_CRAFT)) {
                    special = true;
                    break;
                }
            }
            if (special) continue;
            if (!applies.test(material)) continue;
            ans.add(material);
        }
        return ans;
    }

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        CREATIVE_TABS.register(modEventBus);
    }
}
