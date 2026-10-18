package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 龙的<b>飞行执行层</b>：<b>每 tick 唯一的速度出口</b>。
 *
 * <h2>为什么要把它抽出来</h2>
 * 之前"谁能写 {@code deltaMovement}"散落在实体、四个 goal、飞行辅助里，于是每修一个 bug
 * 就多一条否决规则，而这些规则彼此不知道对方存在 —— 表现就是抽搐、穿墙、"想往上飞"。
 * 这个类把执行层收成一条流水线：
 *
 * <pre>
 * goal（只表达意图） ──► DragonFlightController（选模式 → 算期望速度 → 安全层 → 写一次）
 *                                 │
 *                                 └─► Entity.move()（原版物理）
 * </pre>
 *
 * <h2>模式优先级（写死在一处，不再分散）</h2>
 * <ol>
 *   <li>{@link Mode#RIDER_DIVE} 骑手冲锋：航线接管，连玩家输入都让位；</li>
 *   <li>{@link Mode#RIDER} 骑手驾驶：由 {@code DragonRiderControl} 在别处写，这里不动；</li>
 *   <li>{@link Mode#ESCAPE} 重合脱出：{@code noPhysics} 只是物理安全，方向必须由这里给；</li>
 *   <li>{@link Mode#SCRIPT} 技能航线（俯冲/拉升）；</li>
 *   <li>{@link Mode#RESCUE} 自动脱困；</li>
 *   <li>{@link Mode#HOVER} 悬停（没人管的时候维持高度）。</li>
 * </ol>
 *
 * <h2>S1：唯一的安全层</h2>
 * 所有模式算出来的期望速度都过 {@link #applySafety(Vec3)}：
 * <b>把竖直分量夹进飞行高度带</b>（{@link DragonGolemEntity#flightBand()}），
 * 带外上方绝不许继续上升、带外下方绝不许继续下沉。
 * 于是"顶着天花板往上飞"在结构上不可能，不需要再给每个模式单独打补丁。
 *
 * <p>这一版（S0+S1）只做"搬家 + 收口"，动作细节与原来逐行一致；
 * 后续 S2 会把"俯冲超出高度带"从"夹平"改成"降级成浅俯冲"。
 */
public final class DragonFlightController {

    /** 本 tick 由谁接管速度。 */
    public enum Mode {
        RIDER_DIVE("骑手冲锋"),
        RIDER("骑手驾驶"),
        ESCAPE("重合脱出"),
        SCRIPT("技能航线"),
        RESCUE("自动脱困"),
        HOVER("悬停");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private final DragonGolemEntity dragon;

    /** 上一次 tick 选中的模式（诊断用）。 */
    private Mode mode = Mode.HOVER;

    /** 骑手冲锋的"上一 tick 位置"，只用于诊断日志。 */
    private Vec3 riderDivePrevPos = Vec3.ZERO;

    public DragonFlightController(DragonGolemEntity dragon) {
        this.dragon = dragon;
    }

    public Mode mode() {
        return this.mode;
    }

    /**
     * 每 tick 调一次（服务端 {@code aiStep}，{@code super.aiStep()} 之后）。
     *
     * <p>必须在 {@code super.aiStep()} 之后：那时 {@code moveControl.tick()} 与原版骑乘管线
     * 已经把竖直速度清过一遍，我们再写才是"最终值"。
     */
    public void tick(@Nullable Player rider) {
        Vec3 script = this.dragon.scriptVelocity();
        if (rider != null && this.dragon.isRiderOrderedDive() && script != null) {
            this.mode = Mode.RIDER_DIVE;
            this.tickRiderDive(script);
            return;
        }
        if (rider != null) {
            // 骑手驾驶：速度由 DragonRiderControl/travelRidden 写，这里只让出控制权
            this.mode = Mode.RIDER;
            this.dragon.setKeepAim(false);
            return;
        }
        if (this.dragon.flightAssist().isEmbedded()) {
            this.mode = Mode.ESCAPE;
            this.applySafety(this.dragon.flightAssist().embeddedEscape(this.dragon));
            this.dragon.clearScriptVelocity();
            return;
        }
        if (script != null) {
            this.mode = Mode.SCRIPT;
            this.applySafety(script);
            this.dragon.clearScriptVelocity();
            return;
        }
        if (this.dragon.flightAssist().isRescuing()) {
            this.mode = Mode.RESCUE;
            Vec3 escape = this.dragon.flightAssist().rescueVelocity(this.dragon);
            if (escape != null) {
                this.applySafety(escape);
            } else {
                this.dragon.setNoGravity(true);
                this.dragon.fallDistance = 0.0F;
            }
            return;
        }
        this.mode = Mode.HOVER;
        this.dragon.setKeepAim(false);
        Double hoverY = this.dragon.hoverYSpeed();
        if (hoverY == null) {
            return;      // 已经在目标高度附近：什么都不写（和原 applyHover 的早退一致）
        }
        Vec3 current = this.dragon.getDeltaMovement();
        this.applySafety(new Vec3(current.x, hoverY, current.z));
    }

    /** 骑手冲锋：航线接管 + 原有的诊断/朝向处理。 */
    private void tickRiderDive(Vec3 vel) {
        this.dragon.setNoGravity(true);
        this.dragon.setDeltaMovement(vel);
        this.dragon.fallDistance = 0.0F;
        if (DragonDebug.RIDE && this.dragon.tickCount % 10 == 0) {
            Dragon_golems.LOGGER.info("[dive] 骑手冲锋速度 vel={} prevPos={} nowPos={} noPhysics={} hColl={}",
                    vel, this.riderDivePrevPos, this.dragon.position(),
                    this.dragon.noPhysics, this.dragon.horizontalCollision);
        }
        this.riderDivePrevPos = this.dragon.position();
        this.faceIfNotAiming(vel);
        this.dragon.clearScriptVelocity();
    }

    /**
     * <b>唯一的安全层</b>：把期望速度夹进飞行高度带，然后写一次。
     *
     * <ul>
     *   <li>{@code 下一 tick 的 Y > 带顶} → 竖直分量压成 ≤ 0（不许再上升）；
     *       这一条同时替代了以前散落的"天花板否决""重合期间不许上升"两处补丁；</li>
     *   <li>{@code 下一 tick 的 Y < 带底} → 竖直分量压成 ≥ 0（不许继续沉下去）。</li>
     * </ul>
     * 水平分量原样保留（绕墙由寻路/避障负责，不在这里重复判断）。
     */
    private void applySafety(Vec3 desired) {
        DragonGolemEntity.FlightBand band = this.dragon.flightBand();
        double y = desired.y;
        double next = this.dragon.getY() + y;
        if (next > band.maxFeet()) {
            y = Math.min(y, 0.0D);
        }
        if (next < band.minFeet()) {
            y = Math.max(y, 0.0D);
        }
        Vec3 v = new Vec3(desired.x, y, desired.z);
        this.dragon.setNoGravity(true);
        this.dragon.setDeltaMovement(v);
        this.dragon.fallDistance = 0.0F;
        this.faceIfNotAiming(v);
    }

    /** 开火中（{@code keepAim}）朝向由 goal 的 aimAt 管；否则朝运动方向。 */
    private void faceIfNotAiming(Vec3 vel) {
        if (this.dragon.keepAim()) {
            this.dragon.setKeepAim(false);
        } else {
            this.dragon.faceMovement(vel);
        }
    }
}
