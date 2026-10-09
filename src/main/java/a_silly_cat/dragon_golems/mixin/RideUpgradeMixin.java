package a_silly_cat.dragon_golems.mixin;

import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import a_silly_cat.dragon_golems.dragon.DragonGolemItems;
import dev.xkmc.modulargolems.content.core.GolemType;
import dev.xkmc.modulargolems.content.modifier.ride.RideUpgrade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让本家的「坐骑升级」能装到龙身上。
 *
 * <h2>为什么需要它</h2>
 * {@code RideUpgrade} 是整个傀儡装配里<b>唯一</b>一个覆写了 {@code fitsOn} 的 modifier，而且写死成：
 * <pre>
 * public boolean fitsOn(GolemType&lt;?, ?&gt; type) { return type == GolemTypes.TYPE_DOG.get(); }
 * </pre>
 * 这一个是"能不能装"的唯一闸门，升级台（{@code GolemUpgradeItemHandler}）和铁砧
 * （{@code CraftEventListeners.appendUpgrade}）都读它。于是玩家在装配界面里<b>根本看不到</b>
 * 这条升级能装在龙上 —— 而龙其实早就被我们接上了完整的载客管线
 * （{@code DragonGolemEntity.canAddPassenger/positionRider/getControllingPassenger} +
 * {@code DragonRideHandler} + 骑手指令 R/G/V），"龙能骑、偏偏装不上坐骑升级"就是这里断的。
 *
 * <p>装在龙身上会带来 {@code GolemFlags.PASSIVE}（本家 {@code canAttackType} 直接返回
 * {@code !PASSIVE}）：<b>AI 不再自己索敌开火，也不会被 mob 当目标</b>，这正是"坐骑"该有的样子；
 * 但玩家驾驶 / 骑手指令完全不受影响 —— 那几条路都不看这个 flag，而且玩家按键的那几秒
 * {@code DragonGolemEntity.riderCombat} 会临时放行攻击判定（见 {@code canAttackType}）。
 *
 * <p>开关是 {@code DragonGolemConfig.mountUpgradeForDragon()}（默认开），关掉就退回本家行为。
 *
 * <p>{@code require = 0}：和这个包里另外两个 mixin 同一个理由 —— 这是"多开一扇门"，
 * 不是功能本体。以后本家改了 {@code RideUpgrade}（比如自己支持了别的傀儡类型）时，
 * 我们要的是"这扇门悄悄失效"，而不是启动崩。
 */
@Mixin(RideUpgrade.class)
public class RideUpgradeMixin {

	@Inject(method = "fitsOn", at = @At("HEAD"), cancellable = true, require = 0)
	private void dragonGolems$fitsOnDragon(GolemType<?, ?> type, CallbackInfoReturnable<Boolean> cir) {
		if (type == DragonGolemItems.TYPE.get() && DragonGolemConfig.mountUpgradeForDragon()) {
			cir.setReturnValue(true);
		}
	}

}
