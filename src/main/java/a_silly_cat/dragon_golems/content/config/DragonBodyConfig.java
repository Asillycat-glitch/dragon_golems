package a_silly_cat.dragon_golems.content.config;

import a_silly_cat.dragon_golems.Dragon_golems;
import dev.xkmc.l2library.serial.config.BaseConfig;
import dev.xkmc.l2library.serial.config.CollectType;
import dev.xkmc.l2library.serial.config.ConfigCollect;
import dev.xkmc.l2serial.serialization.SerialClass;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 龙傀儡身体主题表（数据包 {@code modulargolems_config/dragon_bodies}）的合并结果。
 *
 * <p>这就是"整合包作者想加自己的龙"的入口：键是材料 id，值是 {@link DragonBodyEntry}。
 * 类型登记在 {@code Dragon_golems.BODIES}，挂在<b>本家的</b>
 * {@code ModularGolems.HANDLER} 上，所以重载与客户端同步都直接复用本家那条通道。
 *
 * <p>归属用 {@link CollectType#MAP_OVERWRITE}（和本家 {@code ingredients} 同档）：
 * 加新材料是"各加各的"，只有同一个材料 id 被两个数据包同时定义时才互相覆盖。
 */
@SerialClass
public class DragonBodyConfig extends BaseConfig {

    @ConfigCollect(CollectType.MAP_OVERWRITE)
    @SerialClass.SerialField
    public HashMap<ResourceLocation, DragonBodyEntry> bodies = new HashMap<>();

    /** 合并后的表；数据包还没重载完（或整条读失败）时返回 null，调用方走 Java 侧兜底。 */
    public static DragonBodyConfig get() {
        try {
            return Dragon_golems.BODIES.getMerged();
        } catch (Exception e) {
            Dragon_golems.LOGGER.warn("[dragon_bodies] 取合并表失败，本次按内置兜底处理：{}", e.toString());
            return null;
        }
    }

    /** 这个材料有没有专门的条目；没有返回 null（由调用方继续往下匹配）。 */
    public DragonBodyEntry entry(ResourceLocation material) {
        return this.bodies == null ? null : this.bodies.get(material);
    }

    /** 所有材料都没匹配上时用哪份：{@code dragon_golems:default} → Java 侧兜底。 */
    public DragonBodyEntry fallback() {
        DragonBodyEntry def = entry(DragonBodyEntry.DEFAULT_KEY);
        return def == null ? DragonBodyEntry.FALLBACK : def;
    }

    /** 调试用：把合并后的表打成一行（键 + 匹配范围 + 粒子），便于实机确认数据包生效。 */
    public String describe() {
        if (this.bodies == null || this.bodies.isEmpty()) {
            return "（空表，全部走 Java 侧兜底）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ResourceLocation, DragonBodyEntry> e : this.bodies.entrySet()) {
            DragonBodyEntry v = e.getValue();
            sb.append(e.getKey()).append('[').append(v.matchesAnyPart() ? "any" : "body")
                    .append(", ").append(v.breathParticle)
                    .append(", ").append(v.damageType()).append("] ");
        }
        return sb.toString().trim();
    }
}
