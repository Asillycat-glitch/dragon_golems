package a_silly_cat.dragom_golems.init;

import a_silly_cat.dragom_golems.Dragom_golems;
import a_silly_cat.dragom_golems.dragon.DragonGolemEntity;
import a_silly_cat.dragom_golems.dragon.DragonGolemItems;
import a_silly_cat.dragom_golems.dragon.DragonGolemPartType;
import com.tterrag.registrate.util.entry.ItemEntry;
import dev.xkmc.modulargolems.content.config.GolemMaterialConfig;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import dev.xkmc.modulargolems.init.registrate.GolemItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * 创造栏。
 *
 * <p><b>我们自己那一页</b>（{@link #TAB}）：龙的东西摆在原版分类之外单开一页，图标用身体部件
 * （它在装备界面里也是菜单图标）。
 *
 * <p><b>本家那一页</b>（{@link #onBuildTabContents}）：把我们的五个部件和成品也塞进本家的
 * 「傀儡装配 - 傀儡」（{@code modulargolems:golems}）。本家那页是<b>逐个物品显式挂上去</b>的
 * （{@code GolemItems} 里每个物品都调一次 {@code .tab(GOLEMS.getKey(), fillItemCategory)}），
 * 我们自己注册的物品不在那张名单里，所以默认不会出现 —— 这里用 Forge 的
 * {@code BuildCreativeModeTabContentsEvent} 补上，效果和本家一致：
 * <b>每个部件都是"本体 + 每种材料一份"，成品是"每种材料一条纯部件龙"</b>。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Dragom_golems.MODID);

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register(
            Dragom_golems.MODID,
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + Dragom_golems.MODID))
                    .icon(() -> new ItemStack(DragonGolemItems.BODY.get()))
                    .displayItems((parameters, output) -> {
                        // 傀儡龙：胚料 + 五个部件 + 成品。
                        output.accept(DragonGolemItems.TEMPLATE.get());
                        output.accept(DragonGolemItems.WINGS.get());
                        output.accept(DragonGolemItems.HEAD.get());
                        output.accept(DragonGolemItems.BODY.get());
                        output.accept(DragonGolemItems.TAIL.get());
                        output.accept(DragonGolemItems.LEG.get());
                        output.accept(DragonGolemItems.HOLDER.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        CREATIVE_TABS.register(modEventBus);
        // BuildCreativeModeTabContentsEvent 是 mod 总线事件，用 addListener 而不是 @SubscribeEvent
        modEventBus.addListener(ModCreativeTabs::onBuildTabContents);
    }

    /**
     * 往本家的「傀儡装配 - 傀儡」页里追加我们的东西。
     *
     * <p>做法和本家 {@code GolemPart#fillItemCategory} / {@code GolemHolder#fillItemCategory} 一样，
     * 只是这里是往一个<b>别人的</b>标签页里加，所以不能改本家那张显式名单，只能挂这个事件。
     *
     * <p>判断用 {@code getTabKey()} 而不是标签页实例：注册表和标签页的构建顺序无关，
     * 用 key 比较不会踩到"对面还没建出来"的坑。
     */
    private static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(GolemItems.GOLEMS.getKey())) {
            return;
        }
        List<ResourceLocation> materials = GolemMaterialConfig.get().getAllMaterials();
        List<ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>>> parts = List.of(
                DragonGolemItems.WINGS, DragonGolemItems.HEAD, DragonGolemItems.BODY,
                DragonGolemItems.TAIL, DragonGolemItems.LEG);
        // 部件：本体（没装材料）一份，再每种材料一份
        for (ItemEntry<GolemPart<DragonGolemEntity, DragonGolemPartType>> entry : parts) {
            event.accept(new ItemStack(entry.get()));
            for (ResourceLocation material : materials) {
                event.accept(GolemPart.setMaterial(new ItemStack(entry.get()), material));
            }
        }
        // 成品：每种材料一条"纯部件龙"
        for (ResourceLocation material : materials) {
            event.accept(DragonGolemItems.HOLDER.get().withUniformMaterial(material));
        }
    }
}
