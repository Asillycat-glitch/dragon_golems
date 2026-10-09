package a_silly_cat.dragon_golems.content.config;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.lang.reflect.Field;

/**
 * <b>内置版的 AttributeFix（只放宽"最大生命"这一条上限），默认开。</b>
 *
 * <h2>为什么需要它</h2>
 * 本 mod 的部件系数合计是 HEALTH 2.0（本家金属傀儡是 1.0），所以幽匿身体的龙裸装就有
 * <b>1000 血，已经是原版 1024 上限的 97.7%</b>；再挂一级体型升级（+20%/级）或坐骑升级（+20%）
 * 就会被原版 {@code RangedAttribute.sanitizeValue} <b>静默夹掉</b> —— 材料与升级白铸，
 * 而且不留任何日志（本 mod 另有一行 {@code attributes.logOverflow} 提示）。
 * 想不改原版上限（整合包另有安排）就把 {@code attributes.maxHealthCapFix} 设成 false。
 *
 * <h2>为什么只放宽最大生命</h2>
 * 因为只有这一条会真的被夹：幽匿龙裸装 1000 血就是 1024 上限的 97.7%，加一级升级就溢出。
 * <ul>
 *   <li><b>护甲</b>上限 30 放宽也没有实际收益：原版 {@code CombatRules} 内部就把护甲减伤封在 80%
 *       （约 20~30 点护甲之后不再增加减伤）；</li>
 *   <li>其余属性（攻击、攻速、移速、飞行速度、各种 golem_*）实测都只用到上限的百分之几。</li>
 * </ul>
 * 本 mod 的所有伤害/击退代码都按"抗性只用来削、削到 0 为止"写
 * （{@code Math.max(0, 1 - 抗性)}，和原版 {@code LivingEntity#knockback} 里
 * {@code if (!(strength <= 0))} 那道守卫同一个语义），所以放宽上限不会引出任何反向效果。
 *
 * <h2>为什么用反射而不是 mixin</h2>
 * 注入原版 {@code RangedAttribute} 属于"注入原版类"，需要 mixin 的 annotation processor +
 * refmap 才能在生产环境重映射到 SRG 名（本仓现有三个 mixin 全部只注入本家类，正是为了绕开这件事），
 * 而 {@code build.gradle} 里没有那套基础设施。反射只需要在运行时改一个 double 字段，
 * 开发环境（{@code maxValue}）与生产环境（{@code f_22308_}）两个名字都试一遍即可，
 * 并且<b>改完会回读校验</b>，猜错也只是打一行警告、什么都不发生。
 *
 * <p>与真正的 AttributeFix 同时安装不冲突：这里只做"<b>只升不降</b>"（已经是更大的上限就不动它）。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DragonAttributeFix {

    /** 开发环境（official 映射）下的字段名。 */
    private static final String FIELD_OFFICIAL = "maxValue";
    /** 生产环境（SRG）下的字段名。 */
    private static final String FIELD_SRG = "f_22308_";

    private DragonAttributeFix() {
    }

    /**
     * 配置刚读进来时应用一次（{@code ModConfigEvent} 是唯一能保证"配置已经可读"的时机；
     * 在 {@code @Mod} 构造函数里读配置会抛"config not loaded"）。
     */
    @SubscribeEvent
    public static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == DragonGolemConfig.COMMON_SPEC) {
            apply();
        }
    }

    /** 兜底：配置文件本来就存在、且事件顺序有变时也能应用上。 */
    @SubscribeEvent
    public static void onSetup(FMLCommonSetupEvent event) {
        apply();
    }

    /** 把最大生命的上限抬到配置值（默认压根不做，除非 {@code attributes.maxHealthCapFix = true}）。 */
    public static void apply() {
        if (!DragonGolemConfig.maxHealthCapFix()) {
            return;
        }
        if (!(Attributes.MAX_HEALTH instanceof RangedAttribute health)) {
            return;
        }
        double target = DragonGolemConfig.maxHealthCap();
        if (health.getMaxValue() >= target) {
            // 已经够大（比如装了 AttributeFix，或者别的 mod 抬过了）→ 不动
            return;
        }
        for (String name : new String[]{FIELD_OFFICIAL, FIELD_SRG}) {
            try {
                Field field = RangedAttribute.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(health, target);
                if (health.getMaxValue() >= target) {
                    Dragon_golems.LOGGER.info("[attr] 最大生命上限已放宽到 {}（内置属性修复，字段 {}）", target, name);
                    return;
                }
            } catch (Throwable ignored) {
                // 换个名字再试；两个都不行就在下面打一行警告
            }
        }
        Dragon_golems.LOGGER.warn("[attr] 内置属性修复没能生效：找不到 RangedAttribute 的上限字段，"
                + "请改用 AttributeFix 模组，或把 attributes.maxHealthCapFix 关掉");
    }
}
