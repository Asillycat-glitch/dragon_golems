package a_silly_cat.dragon_golems.mixin;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonGolemItems;
import dev.xkmc.modulargolems.content.config.GolemMaterialConfig;
import dev.xkmc.modulargolems.content.item.golem.GolemPart;
import dev.xkmc.modulargolems.init.ModularGolems;
import dev.xkmc.modulargolems.init.data.MGTagGen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 兼容修复：让"复制式清单"不再把龙部件排除在外。
 *
 * <h2>问题</h2>
 * 本家的 {@code GolemMaterialConfig.mayApply(part, material)} 只有两条分支：
 * <ul>
 *   <li>该材料有显式 {@code partLimitation} 清单 → 只认清单里列的部件；</li>
 *   <li>没有清单（或清单为空）→ 回退到 {@code modulargolems:generic_parts} 物品标签。</li>
 * </ul>
 * 而本家给附属提供的 datagen 辅助方法 {@code supportsDefaultAnd(parts, ids...)} 会把清单写成
 * "8 个基础部件 + 我的新部件" 的<b>快照</b>。附属只要用它给 {@code modulargolems:copper} 之类的
 * 基础材料键写一行，这份快照就把"通用部件"从<b>可扩展的标签</b>退化成了<b>固定的 8 个物品</b>，
 * 于是任何后加的通用部件（我们的 5 个龙部件就在 {@code modulargolems:generic_parts} 里）
 * 都会被那一刀切掉：装了那个附属，铜/金/铁/下界合金就铸不上龙部件了。
 *
 * <p>另外，这个字段的 collect 类型是 {@code MAP_OVERWRITE}（见 {@code @ConfigCollect}），
 * 同一个材料键在多个数据包里只有一份能活下来，而活下来的是哪一份由
 * {@code BaseConfigType.configs}（一个以文件路径为键的 HashMap）的迭代顺序决定，
 * 与数据包优先级无关。所以"我们自己也写一份清单"这种反击既不可靠、又会反过来把别人挤掉。
 *
 * <h2>修法</h2>
 * 只做一件事：某种材料对龙部件被排除了，但<b>它的清单里含全部 8 个本家基础部件</b>
 * （也就是 {@code generic_parts} 标签的本家成员）时，说明写这份清单的人意图是
 * "通用部件都能用 + 我新加的部件"，而不是"刻意收窄到这几个部件"。
 * 龙部件同样是 {@code generic_parts} 的成员，因此这种清单应当一并放行。
 *
 * <p>刻意收窄的清单（例如只允许金属傀儡身体的那种）保持原样，尊重对方的设计；
 * 没有清单的材料走本家原有的标签回退逻辑，这里不插手。
 * 效果上等价于"没装那个附属时的行为"——只是让别人的声明不再影响我们的部件。
 *
 * <p>注意：这里<b>不</b>给龙部件做黑白名单（暂时仅用于测试兼容修复本身）。
 * 将来若想精确控制"龙部件能吃什么材料"，在这个方法里加一层判断即可，
 * 不需要写任何 {@code partLimitation}，也就不会和任何附属抢材料键。
 */
@Mixin(GolemMaterialConfig.class)
public class GolemMaterialConfigMixin {

	/**
	 * {@code require = 0}：这里是"兼容修复"，不是功能本体。万一以后本家换掉了这个方法签名，
	 * 我们要的是"修复悄悄失效 + 日志里留一行警告"，而不是让所有玩家启动就崩。
	 * 本地测试想让它响，把 0 改回 1 即可。
	 */
	@Inject(method = "mayApply(Ldev/xkmc/modulargolems/content/item/golem/GolemPart;Lnet/minecraft/resources/ResourceLocation;)Z",
			at = @At("HEAD"), cancellable = true, require = 0)
	private static void dragonGolems$respectCopiedGenericList(GolemPart<?, ?> part, ResourceLocation mat,
															  CallbackInfoReturnable<Boolean> cir) {
		if (!isDragonPart(part)) return;                          // 只管我们自己的部件
		var limit = GolemMaterialConfig.get().partLimitation.get(mat);
		if (limit == null || limit.isEmpty()) return;             // 没清单：本家原本就走标签回退
		if (isCopiedGenericList(limit)) {
			Dragon_golems.LOGGER.debug("复制式部件清单按通用部件放行：{} ← {}", part.getDescriptionId(), mat);
			cir.setReturnValue(true);                              // ← 修复点
		}
	}

	/** 是否是我们的 5 个龙部件之一。运行时才取 Registrate 条目，避免提前触发注册。 */
	private static boolean isDragonPart(GolemPart<?, ?> part) {
		Item item = part.getDefaultInstance().getItem();
		return item == DragonGolemItems.WINGS.get()
				|| item == DragonGolemItems.HEAD.get()
				|| item == DragonGolemItems.BODY.get()
				|| item == DragonGolemItems.TAIL.get()
				|| item == DragonGolemItems.LEG.get();
	}

	/**
	 * 清单里是否含 {@code modulargolems:generic_parts} 标签中<b>全部</b>本家部件。
	 * 只数本家命名空间的成员，这样别的附属往同一个标签里加自己的部件不会影响判定。
	 */
	private static boolean isCopiedGenericList(Set<Item> limit) {
		int base = 0;
		for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(MGTagGen.GENERIC_PARTS)) {
			Item item = holder.value();
			if (!item.builtInRegistryHolder().key().location().getNamespace().equals(ModularGolems.MODID))
				continue;
			base++;
			if (!limit.contains(item)) return false;
		}
		return base > 0;  // 标签读不到（例如缺数据包）时不要放行，保持原逻辑
	}

}
