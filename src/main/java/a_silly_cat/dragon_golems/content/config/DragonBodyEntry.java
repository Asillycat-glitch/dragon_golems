package a_silly_cat.dragon_golems.content.config;

import a_silly_cat.dragon_golems.Dragon_golems;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import dev.xkmc.l2serial.serialization.SerialClass;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条龙的"身体主题"配置：龙息粒子、残云粒子、伤害类型、命中效果、贴图。
 *
 * <p><b>数据包位置</b>：{@code data/<命名空间>/modulargolems_config/dragon_bodies/<文件名>.json}
 * —— 和本家的材料配置（{@code .../modulargolems_config/materials/}）同一个目录体系，
 * 键是<b>材料 id</b>。整合包想加自己的龙，只要在这个目录里丢一个 json：
 *
 * <pre>{@code
 * {
 *   "bodies": {
 *     "mymod:crystal": {
 *       "texture": "mypack:textures/entity/dragon/crystal.png",
 *       "breathParticle": "minecraft:end_rod",
 *       "cloudParticle": "minecraft:dust{color:[0.4,0.8,1.0],scale:1.5}",
 *       "breathDamageType": "mymod:crystal_breath",
 *       "fireSeconds": 0,
 *       "effects": [{ "effect": "minecraft:levitation", "duration": 60, "amplifier": 0 }]
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>匹配规则</b>（见 {@code DragonGolemEntity.bodyEntry()}）：身体部件那份材料的条目优先
 * → 其它部件里写了 {@code "match": "any"} 的条目 → {@code dragon_golems:default} → Java 侧兜底
 * （{@link #FALLBACK}，也就是"其它材料"的老行为）。
 *
 * <p><b>没写的字段</b>用这里的默认值，而默认值就是原来"其它材料"那一档的行为
 * （普通火焰 / 龙息紫云 / {@code dragon_breath} / 着火 3 秒 / 无附加效果），
 * 所以一个只写了两三个字段的条目也能直接跑。
 *
 * <p>归属用 {@code MAP_OVERWRITE}（和本家 {@code ingredients} 同档）：<b>加新材料不会顶掉别人</b>，
 * 只有两个数据包给<b>同一个材料 id</b> 写条目时才互相覆盖（那本来就是冲突）。
 */
@SerialClass
public class DragonBodyEntry {

    /** 所有没列出的材料都落到这一条（数据包可覆盖它来统一改"其它材料"的表现）。 */
    public static final ResourceLocation DEFAULT_KEY = Dragon_golems.id("default");

    /** 连数据包都读不到时的 Java 侧兜底：正好等于改造前"其它材料"的行为。 */
    public static final DragonBodyEntry FALLBACK = new DragonBodyEntry();

    /** {@code "body"}（默认：只看身体部件）或 {@code "any"}（身上任意部件用了这个材料就算）。 */
    @SerialClass.SerialField
    public String match = "body";

    /** 覆盖贴图（完整 ResourceLocation）。留空 = 按 {@code dragon_golems:textures/entity/dragon/<材料路径>.png} 找。 */
    @SerialClass.SerialField
    public ResourceLocation texture;

    /**
     * 龙息粒子。
     *
     * <p>可以写粒子 id（{@code "minecraft:flame"}），也可以写带参数的（{@code "minecraft:dust{color:[1,0,0],scale:1}"}）
     * —— 走 {@link ParticleTypes#CODEC}，所以原版所有带参数的粒子都支持。写错只会回退 + 警告，不会崩。
     */
    @SerialClass.SerialField
    public String breathParticle = "minecraft:flame";

    /** 龙弹尾迹 / 落点残云的粒子（同样是 id 或带参数的形式）。 */
    @SerialClass.SerialField
    public String cloudParticle = "minecraft:dragon_breath";

    /** 龙息 / 龙弹的伤害类型（默认我们那条吃护甲吃附魔的龙息）。 */
    @SerialClass.SerialField
    public ResourceLocation breathDamageType = Dragon_golems.id("dragon_breath");

    /** 命中后点燃的秒数（0 = 不点火）。 */
    @SerialClass.SerialField
    public int fireSeconds = 3;

    /** 命中后附加的药水效果。 */
    @SerialClass.SerialField
    public List<EffectEntry> effects = new ArrayList<>();

    // ---- 下面都是解析缓存，不参与序列化（没标 @SerialField，l2serial 会跳过）----
    private transient ParticleOptions breathParticleCache;
    private transient ParticleOptions cloudParticleCache;

    /** 这条条目是不是"任意部件都算"。 */
    public boolean matchesAnyPart() {
        return "any".equalsIgnoreCase(this.match);
    }

    public ParticleOptions breathParticle() {
        if (this.breathParticleCache == null) {
            this.breathParticleCache = parseParticle(this.breathParticle, ParticleTypes.FLAME, "breathParticle");
        }
        return this.breathParticleCache;
    }

    public ParticleOptions cloudParticle() {
        if (this.cloudParticleCache == null) {
            this.cloudParticleCache = parseParticle(this.cloudParticle, ParticleTypes.DRAGON_BREATH, "cloudParticle");
        }
        return this.cloudParticleCache;
    }

    /** 伤害类型 id（数据包写成 null 时回退到我们的龙息）。 */
    public ResourceLocation damageType() {
        return this.breathDamageType == null ? Dragon_golems.id("dragon_breath") : this.breathDamageType;
    }

    /** 把粒子字符串解析成 {@link ParticleOptions}；空/不存在/写错都回退，最多打一行警告。 */
    private static ParticleOptions parseParticle(String raw, ParticleOptions fallback, String field) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        // 注意：ParticleTypes.CODEC 是 Codec<ParticleOptions>（抽象的 ParticleType 本身不实现
        // ParticleOptions，是它那些具体子类实现的），所以这里直接按 ParticleOptions 收。
        ParticleOptions type;
        try {
            type = ParticleTypes.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(raw)).result().orElse(null);
        } catch (Exception e) {
            Dragon_golems.LOGGER.warn("[dragon_bodies] {} 的粒子 '{}' 解析异常，回退到 {}：{}",
                    field, raw, fallback, e.toString());
            return fallback;
        }
        if (type == null) {
            Dragon_golems.LOGGER.warn("[dragon_bodies] {} 的粒子 '{}' 不存在，回退到 {}", field, raw, fallback);
            return fallback;
        }
        return type;
    }

    /** 命中后附加的一条药水效果。 */
    @SerialClass
    public static class EffectEntry {

        @SerialClass.SerialField
        public ResourceLocation effect;

        /** 时长（tick）。 */
        @SerialClass.SerialField
        public int duration = 100;

        @SerialClass.SerialField
        public int amplifier = 0;
    }
}
