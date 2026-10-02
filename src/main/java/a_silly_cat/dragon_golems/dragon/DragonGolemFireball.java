package a_silly_cat.dragon_golems.dragon;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 龙弹：方案 C 里"远距离"那一档。
 *
 * <p>继承原版末影龙火球（{@link DragonFireball}）是为了白拿整套飞行手感：{@code AbstractHurtingProjectile}
 * 每 tick 把自己的加速度 {@code xPower/yPower/zPower} 叠到速度上、再乘 0.95 阻尼，所以会"越飞越快"直到
 * 终速 ≈ 19 × POWER（{@code POWER = 0.10} 时约 2 格/tick，和原版龙火球一致）。
 *
 * <p>但命中的结算<b>整段重写</b>（原版是"6 点固定爆炸 + 强效伤害药水云"，既不吃龙的攻击力、
 * 也不认友军）：
 * <ul>
 *   <li>伤害 = 龙的 {@code ATTACK_DAMAGE} × {@link #DAMAGE_MULT}，走 {@code mob_projectile}
 *       伤害类型（带攻击者 = 龙 → 傀儡装配的药水效果升级照样生效，和俯冲/龙息一致）；</li>
 *   <li>只打 {@code dragon.canAttack()} 通过的目标（不会误伤主人和友军傀儡），也绝不打自己；</li>
 *   <li>顺带套上龙息那套负面效果（材料决定：幽匿 = 黑暗 + 虚弱 I）；</li>
 *   <li>落点留一片只做表现的龙息云（半径 3、5 秒，不带伤害也不带效果，所以不会泡到自己人）。</li>
 * </ul>
 */
public class DragonGolemFireball extends DragonFireball {

    /** 单发威力倍率（相对龙的 ATTACK_DAMAGE）。 */
    public static final float DAMAGE_MULT = 1.2F;
    /** 爆炸波及范围（水平 / 垂直）。 */
    private static final double SPLASH_H = 4.0D;
    private static final double SPLASH_V = 2.5D;
    /** 落点龙息云：半径、持续 tick、结束时收缩到多小。 */
    private static final float CLOUD_RADIUS = 3.0F;
    private static final int CLOUD_DURATION = 100;
    /**
     * 每 tick 加速度。终速 ≈ 19 × POWER，所以 0.10 → 约 2.0 格/tick。
     * 想让它更快/更慢就调这一个数（0.13 → 约 2.5 格/tick）。
     */
    private static final double POWER = 0.10D;
    /** 出膛速度只给终速的 40%，剩下的靠加速度补，看起来是"先慢后快"。 */
    private static final double MUZZLE_FACTOR = 0.4D;

    public DragonGolemFireball(EntityType<? extends DragonGolemFireball> type, Level level) {
        super(type, level);
    }

    /** 从龙的嘴部朝 {@code dir} 发射一颗。 */
    public DragonGolemFireball(Level level, LivingEntity owner, Vec3 origin, Vec3 dir) {
        this(DragonGolemItems.FIREBALL.get(), level);
        Vec3 unit = dir.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : dir.normalize();
        this.setOwner(owner);
        this.setPos(origin.x, origin.y, origin.z);
        this.xPower = unit.x * POWER;
        this.yPower = unit.y * POWER;
        this.zPower = unit.z * POWER;
        this.setDeltaMovement(unit.scale(POWER * 19.0D * MUZZLE_FACTOR));
        // 让模型/朝向一开始就对着飞行方向（原版是靠 ProjectileUtil.rotateTowardsMovement 慢慢转）
        this.setYRot((float) (Math.toDegrees(Math.atan2(unit.x, unit.z))));
        this.setXRot((float) (-Math.toDegrees(Math.asin(unit.y))));
    }

    /** 只认"龙想打的目标"：友军、主人、旁观者、自己都被挡掉。 */
    @Override
    protected boolean canHitEntity(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        if (entity == this.getOwner()) {
            return false;
        }
        if (this.getOwner() instanceof DragonGolemEntity dragon && !dragon.canAttack(living)) {
            return false;
        }
        return super.canHitEntity(entity);
    }

    /**
     * 命中结算。故意<b>不调</b> {@code super.onHit}：原版那一下会生成"固定 6 点 + 强效伤害云"，
     * 既不吃龙的攻击力也会打到友军。Forge 的 {@code ProjectileImpactEvent} 已经由父类 tick 里触发过，
     * 这里不用再补。
     */
    @Override
    protected void onHit(HitResult result) {
        if (this.level().isClientSide) {
            return;
        }
        Vec3 pos = result.getLocation();
        @Nullable DragonGolemEntity dragon =
                this.getOwner() instanceof DragonGolemEntity golem ? golem : null;
        for (LivingEntity victim : this.level().getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(SPLASH_H, SPLASH_V, SPLASH_H))) {
            if (victim == dragon || victim.isSpectator()) {
                continue;
            }
            if (dragon != null) {
                // 只打"龙想打的人"：主人、友军傀儡、被驯服的生物都不吃这一发
                if (!dragon.canAttack(victim) || !dragon.predicateTarget(victim)) {
                    continue;
                }
                dragon.rocketDamage(victim, this, DAMAGE_MULT, 0.5D);
                dragon.applyBreathEffects(victim);
            } else {
                victim.hurt(this.damageSources().thrown(this, this.getOwner()), 6.0F);
            }
        }
        this.spawnCloud(pos);
        this.level().playSound(null, this.blockPosition(), SoundEvents.DRAGON_FIREBALL_EXPLODE,
                SoundSource.HOSTILE, 2.0F, 1.0F);
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1,
                    0.0D, 0.0D, 0.0D, 0.0D);
        }
        this.discard();
    }

    /** 落点留一片"龙息残留"：纯表现（无伤害、无效果），所以不会泡到自己人。 */
    private void spawnCloud(Vec3 pos) {
        AreaEffectCloud cloud = new AreaEffectCloud(this.level(), pos.x, pos.y, pos.z);
        if (this.getOwner() instanceof LivingEntity owner) {
            cloud.setOwner(owner);
        }
        // 云的粒子跟着龙的材料走（见 DragonGolemEntity.breathCloudParticle）：
        // 原来是硬编码的原版紫云，所有材料的龙都长一样。
        ParticleOptions particle = this.getOwner() instanceof DragonGolemEntity dragon
                ? dragon.breathCloudParticle()
                : ParticleTypes.DRAGON_BREATH;
        cloud.setParticle(particle);
        cloud.setRadius(CLOUD_RADIUS);
        cloud.setDuration(CLOUD_DURATION);
        cloud.setRadiusPerTick(-CLOUD_RADIUS / CLOUD_DURATION);
        this.level().addFreshEntity(cloud);
    }
}
