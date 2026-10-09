package a_silly_cat.dragon_golems.mixin;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.humanoid.HumanoidGolemEntity;
import dev.xkmc.modulargolems.content.entity.metalgolem.MetalGolemEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 傀儡乘客上龙：给本家 {@code checkRide} 里"只认狗"的那道判断补上"龙"。
 *
 * <h2>为什么需要它</h2>
 * 入口是<b>本家的成品</b>：{@code GolemHolder.interactLivingEntity} —— 拿装好傀儡的成品右键龙，
 * 本家会在龙的位置召唤它，并对它调 {@code checkRide(龙)}。而本家两个覆写都只认狗：
 * <pre>
 * MetalGolemEntity   : if (target instanceof DogGolemEntity dog &amp;&amp; dog.getBbWidth() &gt; getBbWidth()) startRiding(target);
 * HumanoidGolemEntity: if (target instanceof DogGolemEntity || target instanceof AbstractHorse) startRiding(target);
 * </pre>
 * 于是"傀儡上龙当炮台"这条路在本家那边是断的。上游 mgdp 的 {@code GolemRideMixin} 在这里
 * {@code ci.cancel()} 并**无条件** {@code startRiding(任何目标)}；我们做得更窄：
 * <b>只在目标是龙时</b>才接管，其余情况（狗、马）留给本家原逻辑。
 *
 * <p>上背动作走 {@link DragonGolemEntity#rideAsPassenger}，座位规则（玩家只坐第一个位子、
 * 傀儡最多 3 个乘客位）由它统一把关；坐不下就不 cancel，让本家原逻辑照旧跑一遍（它也会失败）。
 *
 * <p>{@code require = 0}：与 {@code GolemMaterialConfigMixin} 同样的理由 —— 这是"多一个入口"，
 * 不是功能本体。以后本家改了这两个类，我们要的是"入口悄悄失效 + 日志留一行"，而不是启动崩。
 */
@Mixin({MetalGolemEntity.class, HumanoidGolemEntity.class})
public class GolemCheckRideMixin {

	@Inject(method = "checkRide", at = @At("HEAD"), cancellable = true, require = 0)
	private void dragonGolems$rideDragon(LivingEntity target, CallbackInfo ci) {
		if (!(target instanceof DragonGolemEntity dragon)) {
			return;
		}
		AbstractGolemEntity<?, ?> self = (AbstractGolemEntity<?, ?>) (Object) this;
		if (dragon.rideAsPassenger(self)) {
			Dragon_golems.LOGGER.debug("傀儡乘客上龙：{} 骑上 {}", self.getType(), dragon.getType());
			ci.cancel();
		}
	}

}
