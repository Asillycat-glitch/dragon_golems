package a_silly_cat.dragon_golems.compat.jei;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonGolemItems;
import a_silly_cat.dragon_golems.dragon.DragonGolemPartType;
import dev.xkmc.modulargolems.content.config.GolemMaterialConfig;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 适配。
 *
 * <p>先说清楚哪些是<b>本家白送的</b>（核过 2.7.3 的实现，所以这里不重复造）：
 * <ul>
 *   <li><b>5 条切石机配方</b>：原版切石机分类，JEI 自己就会列出来。</li>
 *   <li><b>装配配方</b>：{@code modulargolems:golem_assemble} 的 Java 类在 l2library 里是
 *       {@code AbstractShapedRecipe extends ShapedRecipe}，配方类型就是 {@code minecraft:crafting}，
 *       所以它落在 JEI 的<b>工作台</b>分类里；本家还按配方类（{@code GolemAssembleRecipe.class}）注册了
 *       {@code GolemAssemblyExtension}，会自动把部件格展开成"每种材料一件"、跟着你点选的材料联动输出。
 *       换句话说，我们的炉子在 JEI 里的表现和本家自己的傀儡完全一致，不需要另写分类。</li>
 *   <li><b>铁砧页</b>：本家 {@code GolemJEIPlugin} 是遍历 {@code GolemPart.LIST}（全局列表，我们的 5 个部件
 *       在构造时就进表了）× {@code GolemMaterialConfig.getAllMaterials()}（合并了所有数据包，含我们的 5 种材料）
 *       生成的，"部件 + 材料块 → 带材料的部件"这些页面同样自动出现，连铁砧需求数量都对
 *       （本家用 {@code GolemPart.count} 生成材料数量）。</li>
 *   <li><b>同名不同材料算不同条目</b>：本家给 {@code GolemPart.LIST} 全体注册了 subtype 解释器。</li>
 * </ul>
 *
 * <p>所以这个插件只补本家没有的那部分：龙自己的说明页（铁砧要喂多少、胚料怎么切、
 * 成品怎么合、放出来什么行为）。
 *
 * <p>关于 subtype：本家给 {@code GolemPart} 注册了按材料区分的 subtype 解释器，只登记"裸部件"的话
 * 页面不会出现在"已经铸进材料的部件"上，所以这里把每个部件的所有材料变体一起登记。
 */
@JeiPlugin
public class DragonGolemsJeiPlugin implements IModPlugin {

    public static final ResourceLocation UID = new ResourceLocation(Dragon_golems.MODID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addItemStackInfo(new ItemStack(DragonGolemItems.TEMPLATE.get()),
                Component.translatable("jei.dragon_golems.template.title"),
                Component.translatable("jei.dragon_golems.template.desc"),
                Component.translatable("jei.dragon_golems.template.source"));

        for (DragonGolemPartType part : DragonGolemPartType.values()) {
            GolemPart<?, DragonGolemPartType> item = part.toItem();
            registration.addItemStackInfo(variants(item),
                    Component.translatable("jei.dragon_golems.part.title"),
                    Component.translatable("jei.dragon_golems.part.howto", item.count),
                    weight(part),
                    Component.translatable("jei.dragon_golems.part.formula"),
                    Component.translatable("jei.dragon_golems.part.limit"));
        }

        registration.addItemStackInfo(new ItemStack(DragonGolemItems.HOLDER.get()),
                Component.translatable("jei.dragon_golems.holder.title"),
                Component.translatable("jei.dragon_golems.holder.desc"),
                Component.translatable("jei.dragon_golems.holder.slot"),
                Component.translatable("jei.dragon_golems.holder.hover"),
                Component.translatable("jei.dragon_golems.holder.ride"));
    }

    /** 裸部件 + 每一种材料一个变体，保证页面在任何一个具体部件上都能翻出来。 */
    private static List<ItemStack> variants(GolemPart<?, DragonGolemPartType> item) {
        List<ItemStack> list = new ArrayList<>();
        list.add(new ItemStack(item));
        for (ResourceLocation mat : GolemMaterialConfig.get().getAllMaterials()) {
            list.add(GolemPart.setMaterial(new ItemStack(item), mat));
        }
        return list;
    }

    /**
     * 部件权重，只是把 {@code data/dragon_golems/modulargolems_config/parts/dragon.json} 的
     * filters 抄成给玩家看的一行字——<b>那份 json 才是唯一数据源</b>，改数值时两边要一起改。
     */
    private static Component weight(DragonGolemPartType part) {
        return switch (part) {
            case WINGS -> Component.translatable("jei.dragon_golems.part.weight.wings");
            case HEAD -> Component.translatable("jei.dragon_golems.part.weight.head");
            case BODY -> Component.translatable("jei.dragon_golems.part.weight.body");
            case TAIL -> Component.translatable("jei.dragon_golems.part.weight.tail");
            case LEG -> Component.translatable("jei.dragon_golems.part.weight.leg");
        };
    }
}
