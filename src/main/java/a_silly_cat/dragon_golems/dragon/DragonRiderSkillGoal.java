package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.network.DragonSkillPacket;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 骑手按键触发的技能：R 冲锋、G 龙息、V 龙弹（幽匿身体换成音爆）。
 *
 * <p><b>和 AI 那三个 goal 是分开的两套</b>，理由有三个：
 * <ol>
 *   <li>AI 的重心是"追着索敌到的目标打"，这里根本没有目标——装坐骑升级的龙带 {@code PASSIVE}，
 *       {@code setTarget} 会被本家挡掉，"有目标才开火"那一整套在这里用不上；</li>
 *   <li>AI 的冷却挂在 {@code pendingSkill} / {@code diveCooldown} 上，和"这一轮抽中了谁"绑死；
 *       玩家按键是即时意图，不该去搅那套调度（否则按一下 G 会把 AI 的大招 CD 也吃掉一颗）；</li>
 *   <li>混在一起就必须到处判断"这个目标到底是玩家给的还是索敌来的"，很容易写出
 *       "骑着龙它还自己俯冲"这类 bug。</li>
 * </ol>
 * 共用的只有<b>表现与伤害结算</b>那几层：{@code dragon.breathDamage} / {@code applyBreathEffects} /
 * {@code breathParticle} / {@code mouthPosition}，以及冲锋直接复用
 * {@link DragonDiveGoal}（那是作者已经实测过的一套航线 + 撞击结算）。
 *
 * <p>优先级放在 2（和俯冲/音爆并列的前排），并且<b>比 AI 的远程（3）高</b>：
 * 玩家按键时必须能抢到 MOVE 通道，否则开着 AI 的靠拢 goal 会把机头拽走。
 */
public class DragonRiderSkillGoal extends Goal {

    /** 龙息前摇（tick）：嘴里聚粒子、机头对准，然后把锥体点着。 */
    private static final int CHARGE_TICKS = 12;
    /** 龙息持续（tick）：和 AI 版一致（60 tick = 3 秒）。 */
    private static final int BREATH_TICKS = 60;
    /** 龙弹瞄准（tick）：机头转到准星上再发射。 */
    private static final int ROCKET_AIM_TICKS = 8;
    /** 收尾（tick）：动作结束后的硬直，给模型一点时间把姿态收回去。 */
    private static final int RECOVER_TICKS = 8;

    /** 锥体长度（1 倍体型基准；再乘 {@code combatGeoScale()}）。和 AI 版一致。 */
    private static final double CONE_LENGTH = 30.0D;
    /** 锥体半角（度）。 */
    private static final double CONE_HALF_ANGLE = 16.0D;
    /** 每一跳的伤害倍率（基准攻击力的倍数）与击退。和 AI 版一致。 */
    private static final float BREATH_MULT = 0.35F;
    private static final double BREATH_KNOCKBACK = 0.12D;
    /** 同一个目标两次受伤之间的最短间隔（tick）：不能每 tick 都打，否则无敌帧和数值都会失控。 */
    private static final int HIT_COOLDOWN = 10;
    /** 喷流粒子采样数（每条 tick）。 */
    private static final int STREAM_SAMPLES = 22;
    /** 瞄准转向速度（度/tick）：比驾驶时的机头转向快，按键放技能时"甩头"要跟手。 */
    private static final float AIM_TURN = 10.0F;

    /** 音爆（幽匿身体）的伤害倍率：比龙息高一档，因为它只有一发、而且要 3 秒冷却。 */
    private static final float SONIC_MULT = 1.0F;
    private static final double SONIC_KNOCKBACK_H = 2.5D;
    private static final double SONIC_KNOCKBACK_V = 0.5D;
    private static final double SONIC_HIT_INFLATE = 1.0D;

    private final DragonGolemEntity dragon;
    /** 本轮的技能序号与瞄准点（开始时从实体上取一份快照，中途不再变）。 */
    private int skill = -1;
    @Nullable
    private Vec3 aim;
    private int phaseTicks;
    /** 龙息期间"下一个能再受伤的 tick"，按目标 id 记，避免每 tick 重复结算。 */
    private final Map<Integer, Integer> nextHit = new HashMap<>();

    public DragonRiderSkillGoal(DragonGolemEntity dragon) {
        this.dragon = dragon;
        // 抢 MOVE + LOOK：技能期间由这里控制机头和移动
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.dragon.hasRiderOrder()) {
            return false;
        }
        if (this.dragon.getControllingPassenger() == null) {
            // 指令还没执行人就下去了：直接作废，别让它自己飞出去
            this.dragon.clearRiderOrder();
            return false;
        }
        return this.dragon.riderAim() != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.dragon.getControllingPassenger() == null) {
            // 人下去了就立刻收手（不是让它跑完）
            return false;
        }
        return this.phaseTicks < this.totalTicks();
    }

    @Override
    public void start() {
        this.skill = this.dragon.riderOrderedSkill();
        this.aim = this.dragon.riderAim();
        this.phaseTicks = 0;
        this.nextHit.clear();
        if (this.skill == DragonSkillPacket.SKILL_DIVE) {
            // 冲锋不由这里执行：{@code DragonGolemEntity.onRiderCommand} 已经把这一轮排进了
            // DragonDiveGoal（那套航线 + 撞击结算已经实测过）。这里只负责让开 MOVE 通道，
            // 所以给自己一 tick 就收工（see totalTicks）。
            this.dragon.clearRiderOrder();
            return;
        }
        this.dragon.setAggressive(true);
        if (this.skill == DragonSkillPacket.SKILL_BREATH) {
            // 开一轮龙息：递减计数在这一轮之内有效（同一次喷息对同一个目标越打越轻）
            this.dragon.beginBreathVolley();
            this.dragon.playSound(SoundEvents.ENDER_DRAGON_GROWL, 1.2F, 0.7F);
        }
    }

    @Override
    public void stop() {
        this.dragon.endBreathVolley();
        this.dragon.clearRiderOrder();
        this.dragon.setDiveVelocity(null);
        this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_NONE);
        this.dragon.setAggressive(false);
        this.nextHit.clear();
        this.aim = null;
        this.skill = -1;
    }

    @Override
    public void tick() {
        if (this.aim == null) {
            return;
        }
        this.phaseTicks++;
        // 机头一直咬住瞄准点（前摇/喷息/瞄准三个阶段都要）
        this.dragon.aimAt(this.aim.x, this.aim.z, AIM_TURN);
        this.holdHover();

        if (this.skill == DragonSkillPacket.SKILL_BREATH) {
            this.tickBreath();
        } else if (this.skill == DragonSkillPacket.SKILL_BLAST) {
            this.tickBlast();
        }
    }

    /** 技能总共持续多久（决定 goal 什么时候收工）。 */
    private int totalTicks() {
        if (this.skill == DragonSkillPacket.SKILL_BREATH) {
            return CHARGE_TICKS + BREATH_TICKS + RECOVER_TICKS;
        }
        if (this.skill == DragonSkillPacket.SKILL_BLAST) {
            return ROCKET_AIM_TICKS + RECOVER_TICKS;
        }
        // 冲锋不在这里跑（交给 DragonDiveGoal）：给自己留一 tick 就走完，避免占着 MOVE 不放
        return 1;
    }

    // ---- 龙息 ----

    private void tickBreath() {
        if (this.phaseTicks <= CHARGE_TICKS) {
            // 前摇：只聚粒子、不结算
            this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_BREATH);
            this.spawnMouthParticles(0.4D + 0.6D * (1.0F - this.phaseTicks / (float) CHARGE_TICKS));
            return;
        }
        if (this.phaseTicks == CHARGE_TICKS + 1) {
            this.dragon.playSound(SoundEvents.ENDER_DRAGON_SHOOT, 1.5F, 0.6F);
        }
        Vec3 origin = this.dragon.mouthPosition();
        Vec3 dir = this.direction(origin);
        double reach = this.breathReach(origin, dir);
        this.damageCone(origin, dir, reach);
        this.spawnBreathStream(origin, dir, reach);
    }

    // ---- 龙弹 / 音爆 ----

    private void tickBlast() {
        if (this.phaseTicks < ROCKET_AIM_TICKS) {
            // 瞄准：低头 + 嘴里聚粒子（和 AI 版的前摇表现一致）
            this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_BREATH);
            this.spawnMouthParticles(0.5D);
            return;
        }
        if (this.phaseTicks == ROCKET_AIM_TICKS) {
            Vec3 origin = this.dragon.mouthPosition();
            Vec3 dir = this.direction(origin);
            if (this.dragon.isSonicBody()) {
                // 音爆：一发瞬时的线状穿透（幽匿身体专属，和 AI 的音爆槽位规则一致）
                this.fireSonic(origin, dir);
            } else {
                this.fireRocket(origin, dir);
            }
        }
    }

    private void fireRocket(Vec3 origin, Vec3 dir) {
        DragonGolemFireball ball = new DragonGolemFireball(this.dragon.level(), this.dragon, origin, dir);
        this.dragon.level().addFreshEntity(ball);
        this.dragon.playSound(SoundEvents.ENDER_DRAGON_SHOOT, 1.5F, 0.8F);
    }

    /**
     * 音爆：沿瞄准方向打一条穿透线。
     *
     * <p>粒子、采样方式和 AI 版一样（每格一颗 {@code SONIC_BOOM}），但判定只取<b>线上最近的一个</b>
     * 目标：AI 那发是 15 秒一次的大招，骑手版本只有 3 秒冷却，整条线全打会明显超模。
     *
     * <p>伤害仍走 {@code dragon.breathDamage}（幽匿身体的龙会自动用我们自己的
     * {@code dragon_sonic} 伤害类型，那条在 {@code bypasses_cooldown} 里、并且会把目标原本的
     * 无敌帧原样放回去），所以<b>不会把目标的无敌帧顶掉</b>、不会影响旁边地面傀儡的拳头。
     */
    private void fireSonic(Vec3 origin, Vec3 dir) {
        if (!(this.dragon.level() instanceof ServerLevel level)) {
            return;
        }
        int len = Math.max(1, Mth.floor(this.dragon.sonicBeamLength()));

        // 光束粒子：每格一颗（和回响炮、本 mod 的 AI 音爆同一套观感）
        for (int i = 1; i < len; i++) {
            Vec3 at = origin.add(dir.scale(i));
            level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        level.playSound(null, this.dragon.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.HOSTILE, 3.0F, 1.0F);

        // 线状判定：沿用 AI 版那套"每格采样点落进谁的外扩盒里"（比射线相交更宽容，和回响炮一致）
        AABB area = new AABB(origin, origin.add(dir.scale(len)));
        LivingEntity best = null;
        double bestAlong = Double.MAX_VALUE;
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, area.inflate(SONIC_HIT_INFLATE))) {
            if (!this.canHit(victim)) {
                continue;
            }
            AABB box = victim.getBoundingBox().inflate(SONIC_HIT_INFLATE);
            double along = -1.0D;
            for (int i = 0; i <= len; i++) {
                if (box.contains(origin.add(dir.scale(i)))) {
                    along = i;
                    break;
                }
            }
            if (along >= 0.0D && along < bestAlong) {
                bestAlong = along;
                best = victim;
            }
        }
        if (best == null) {
            return;
        }
        if (this.dragon.breathDamage(best, SONIC_MULT, 0.0D)) {
            this.dragon.applyBreathEffects(best);
        }
        // 击退（照 AI 版：水平 2.5、垂直 0.5，再按目标的击退抗性削；抗性拉满 = 完全不推）
        double resist = Math.max(0.0D, 1.0D - best.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        best.push(dir.x * SONIC_KNOCKBACK_H * resist, dir.y * SONIC_KNOCKBACK_V * resist,
                dir.z * SONIC_KNOCKBACK_H * resist);
        best.hasImpulse = true;
    }

    // ---- 共用 ----

    /** 从嘴指向瞄准点的单位向量。瞄准点几乎和嘴重合时退回"机头水平方向"，避免 NaN。 */
    private Vec3 direction(Vec3 origin) {
        assert this.aim != null;
        Vec3 to = this.aim.subtract(origin);
        if (to.lengthSqr() < 1.0E-4D) {
            double yaw = Math.toRadians(this.dragon.getYRot());
            return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        }
        return to.normalize();
    }

    /** 站住不动：技能期间不要悬停那套接管竖直速度。 */
    private void holdHover() {
        this.dragon.setDiveVelocity(new Vec3(0.0D, 0.0D, 0.0D));
    }

    private double coneLength() {
        return CONE_LENGTH * this.dragon.combatGeoScale();
    }

    /** 从嘴沿瞄准轴打一条射线，返回"喷流实际能打多远"（被方块挡住就到此为止）。 */
    private double breathReach(Vec3 origin, Vec3 dir) {
        double cone = this.coneLength();
        Vec3 end = origin.add(dir.scale(cone));
        BlockHitResult hit = this.dragon.level().clip(new ClipContext(origin, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.dragon));
        return hit.getType() == HitResult.Type.MISS ? cone : origin.distanceTo(hit.getLocation());
    }

    /** 锥体伤害：和 AI 版同一套判定（沿轴距离 + 侧向偏移 + 各自的无敌间隔）。 */
    private void damageCone(Vec3 origin, Vec3 dir, double reach) {
        Vec3 end = origin.add(dir.scale(Math.min(this.coneLength(), reach + 2.0D)));
        AABB box = new AABB(origin, end).inflate(2.0D);
        for (LivingEntity victim : this.dragon.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (!this.canHit(victim)) {
                continue;
            }
            Vec3 to = victim.getBoundingBox().getCenter().subtract(origin);
            double along = to.dot(dir);
            if (along < 0.0D || along > reach + victim.getBbWidth() * 0.5D) {
                continue;
            }
            double lateral = Math.sqrt(Math.max(0.0D, to.lengthSqr() - along * along));
            double limit = Math.tan(Math.toRadians(CONE_HALF_ANGLE)) * along
                    + victim.getBbWidth() * 0.5D + 0.5D;
            if (lateral > limit) {
                continue;
            }
            Integer next = this.nextHit.get(victim.getId());
            if (next != null && this.dragon.tickCount < next) {
                continue;
            }
            this.nextHit.put(victim.getId(), this.dragon.tickCount + HIT_COOLDOWN);
            if (this.dragon.breathDamage(victim, BREATH_MULT, BREATH_KNOCKBACK)) {
                this.dragon.applyBreathEffects(victim);
            }
        }
    }

    /** 能不能打这个目标：自己、乘客、友军、旁观者都排除。 */
    private boolean canHit(LivingEntity victim) {
        if (victim == this.dragon || !victim.isAlive() || victim.isSpectator()) {
            return false;
        }
        if (this.dragon.hasPassenger(victim) || victim.isPassengerOfSameVehicle(this.dragon)) {
            return false;
        }
        // 龙的 predicateTarget 会看配置卡的敌我过滤；再叠一次 canAttack（材料/升级的免伤等）核实
        return this.dragon.predicateTarget(victim);
    }

    private void spawnMouthParticles(double radius) {
        if (!(this.dragon.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 mouth = this.dragon.mouthPosition();
        level.sendParticles(this.dragon.breathParticle(), mouth.x, mouth.y, mouth.z, 2,
                radius, radius, radius, 0.0D);
    }

    /** 喷流表现：一条从嘴射向瞄准点的粒子流 + 落点一小团，和 AI 版同源。 */
    private void spawnBreathStream(Vec3 origin, Vec3 dir, double reach) {
        if (!(this.dragon.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = dir.cross(up);
        if (right.lengthSqr() < 1.0E-4D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        }
        right = right.normalize();
        Vec3 realUp = right.cross(dir).normalize();
        ParticleOptions particle = this.dragon.breathParticle();
        for (int i = 0; i < STREAM_SAMPLES; i++) {
            double t = this.dragon.getRandom().nextDouble();
            double spread = (0.10D + 0.30D * t) * (1.0D + t);
            double a = this.dragon.getRandom().nextDouble() * Math.PI * 2.0D;
            double r = spread * Math.sqrt(this.dragon.getRandom().nextDouble());
            Vec3 at = origin.add(dir.scale(t * reach))
                    .add(right.scale(Math.cos(a) * r))
                    .add(realUp.scale(Math.sin(a) * r));
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        // 落点溅射
        Vec3 hit = origin.add(dir.scale(reach));
        level.sendParticles(this.dragon.breathCloudParticle(), hit.x, hit.y, hit.z, 6,
                0.6D, 0.6D, 0.6D, 0.02D);
    }
}
