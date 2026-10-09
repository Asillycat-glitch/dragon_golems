package a_silly_cat.dragon_golems;

import a_silly_cat.dragon_golems.content.config.DragonBodyConfig;
import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import a_silly_cat.dragon_golems.dragon.DragonDebug;
import a_silly_cat.dragon_golems.dragon.DragonGolemItems;
import a_silly_cat.dragon_golems.init.ModCreativeTabs;
import a_silly_cat.dragon_golems.network.DragonNetwork;
import dev.xkmc.l2library.serial.config.ConfigTypeEntry;
import dev.xkmc.modulargolems.init.ModularGolems;
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

    /**
     * "身体主题表"的类型登记：数据包 {@code data/<命名空间>/modulargolems_config/dragon_bodies/*.json}。
     *
     * <p><b>故意挂在<a href="https://github.com">本家</a>的 {@link ModularGolems#HANDLER} 上</b>，而不是自己新开一条：
     * <ul>
     *   <li>数据包目录因此和本家材料配置（{@code .../modulargolems_config/materials/}）完全同构，
     *       整合包作者不用学第二套目录；</li>
     *   <li>"重载 + 同步到客户端"是那条通道现成的能力（{@code onDatapackSync}），
     *       我们省掉一整条网络通道和一次"客户端表没同步"的坑。</li>
     * </ul>
     */
    public static final ConfigTypeEntry<DragonBodyConfig> BODIES =
            new ConfigTypeEntry<>(ModularGolems.HANDLER, "dragon_bodies", DragonBodyConfig.class);

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
        // TOML 配置：config/l2_configs/dragon_golems-common.toml（和傀儡装配的配置同一个目录）。
        // 必须在这里注册：registerConfig 认的是"当前正在构造的 mod 容器"。
        DragonGolemConfig.init();
        // 网络通道：骑手的技能键（R/G/V）必须自己发包 —— 原版只把"跳跃/潜行"两个按键状态
        // 同步到服务端，其余按键服务端看不见。两端都要注册：客户端发包、服务端收包。
        DragonNetwork.register();
        // 数据包重载后打一行身体主题表的合并结果（排查用，见 DragonDebug.CONFIG）。
        ModularGolems.HANDLER.addAfterReloadListener(Dragon_golems::logBodies);
    }

    /** 重载完打一行"表里到底读到了什么"，整合包作者加完 json 就能在日志里确认。 */
    private static void logBodies() {
        if (!DragonDebug.CONFIG) {
            return;
        }
        DragonBodyConfig cfg = DragonBodyConfig.get();
        LOGGER.info("[dragon_bodies] 合并完成，共 {} 条：{}",
                cfg == null ? 0 : cfg.bodies.size(), cfg == null ? "（表不可用）" : cfg.describe());
    }
}
