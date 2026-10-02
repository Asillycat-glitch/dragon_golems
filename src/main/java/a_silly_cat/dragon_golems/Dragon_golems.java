package a_silly_cat.dragon_golems;

import a_silly_cat.dragon_golems.dragon.DragonGolemItems;
import a_silly_cat.dragon_golems.init.ModCreativeTabs;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 巨龙傀儡的主类。这个 mod 只有一个内容包（{@code dragon}），主类因此很薄：
 * 触发一次注册、挂上创造栏，其余全部由 {@link DragonGolemItems} 里的 Registrate 完成。
 */
@Mod(Dragon_golems.MODID)
public class Dragon_golems {
    public static final String MODID = "dragon_golems";
    public static final Logger LOGGER = LoggerFactory.getLogger(Dragon_golems.class);

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }

    public Dragon_golems() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        // 傀儡龙：实体 / 傀儡种类 / 五个部件 / 成品 / 胚料。
        // DragonGolemItems 里会 new L2Registrate(MODID)，靠"类被加载"这件事挂事件，
        // 所以必须在 mod 构造阶段调用一次，且要在任何注册事件之前。
        DragonGolemItems.register();
        ModCreativeTabs.register(modEventBus);
    }
}
