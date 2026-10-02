package a_silly_cat.dragon_golems.dragon;

import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * "让背上的傀儡用龙的索敌结果"的公开接口。
 *
 * <p><b>为什么需要它：</b>本家傀儡的索敌是各自独立的（{@code Golem3DTargetGoal} 走
 * {@code TargetManager}），而龙有 FOLLOW_RANGE 35、比傀儡远得多。傀儡骑在龙背上时，
 * 最合理的行为是"<b>龙发现谁、傀儡就打谁</b>"——龙负责索敌，傀儡负责开火。
 *
 * <p><b>为什么单独一个文件、而不是嵌在 {@code DragonGolemEntity} 里：</b>那是个泛型类
 * （{@code SweepGolemEntity<DragonGolemEntity, DragonGolemPartType>}），把接口嵌进去会形成
 * "接口引用外层类的泛型参数"的循环继承，javac 直接报 {@code cyclic inheritance}。
 * 顶层接口还顺带更好用：别的 mod 只要 {@code instanceof DragonTargetSource} 就能接线，
 * 不需要依赖本 mod 的具体实体类。
 *
 * <p><b>接线示例（傀儡骑手侧）：</b>
 * <pre>{@code
 * Entity vehicle = golem.getVehicle();
 * if (vehicle instanceof DragonTargetSource src) {
 *     LivingEntity shared = src.dragonCurrentTarget();
 *     if (shared != null && golem.canAttack(shared)) {
 *         golem.setTargetRaw(shared);      // 本家自己的"绕开索敌权限"入口
 *     }
 * }
 * }</pre>
 */
public interface DragonTargetSource {

    /**
     * 龙当前的索敌目标；没有则 null。
     *
     * <p>顺序：骑手指定的（命令手杖写的 {@code forcedTarget}）→ 本家目标槽。
     */
    @Nullable
    LivingEntity dragonCurrentTarget();

    /**
     * 让龙锁定某个目标（走本家的 {@code setTargetRaw}，绕开 {@code PASSIVE} 的索敌限制）。
     *
     * <p>用途：傀儡乘客想打谁、让龙也一起打，避免"傀儡在打、龙却在看别处"。
     */
    void dragonForceTarget(@Nullable LivingEntity target);
}
