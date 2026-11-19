package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端注册点：龙铠模型的 {@code LayerDefinition}。
 *
 * <p>必须在这里注册，{@code EntityModelSet.bakeLayer(DragonArmorModel.LAYER)} 才能拿到网格；
 * 渲染层本身由 {@code DragonGolemRenderer} 的构造函数挂上去。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DragonArmorClient {

    @SubscribeEvent
    public static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(DragonArmorModel.LAYER, DragonArmorModel::createBodyLayer);
    }

    private DragonArmorClient() {
    }
}
