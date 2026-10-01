package a_silly_cat.dragom_golems.dragon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.entity.PartEntity;

/**
 * 龙的子碰撞箱，照抄原版末影龙 {@code EnderDragonPart} 那套做法：
 * 本体只保留"身体重心那一块"，头（含脖颈）、尾巴、双翼各自是一个 PartEntity。
 *
 * <p>Forge 的 {@code ServerLevel$EntityCallbacks} 会在本体被 tracking 时自动把
 * {@link #getParent()} 的 {@code getParts()} 注册进世界的实体表，所以客户端准星能选中、
 * 服务端也能按 id 找到它；id 由本体覆写的 {@code setId} 按"本体 id + i + 1"分配（Forge 的 MC-158205 修复写法）。
 *
 * <p>受伤和交互都转发给本体：所以打翅膀等于打龙，用手杖/打开装备界面也一样（射到子箱也会交给本体处理）。
 *
 * <p><b>构造时给的宽高是"1 倍体型"的基准</b>，实际判定箱每次都会乘上本体的体型倍率
 * （{@link DragonGolemEntity#bodyScale()}）—— 本体的判定箱和模型都被本家 {@code getScale()} 缩放，
 * 子箱不跟就会在体型升级后缩成身体中心的一小团。见 {@link #getDimensions} 与 {@link #refreshForScale}。
 */
public class DragonGolemPartEntity extends PartEntity<DragonGolemEntity> {

    private final String name;
    /** <b>1 倍体型</b>下的尺寸；实际判定箱 = 这个值 × 当前的体型倍率。 */
    private final float baseWidth;
    private final float baseHeight;
    /** 上一次算尺寸用的体型倍率，只用来判断"要不要重算"，不参与几何。 */
    private float lastScale = Float.NaN;

    public DragonGolemPartEntity(DragonGolemEntity parent, String name, float width, float height) {
        super(parent);
        this.name = name;
        this.baseWidth = width;
        this.baseHeight = height;
        this.refreshDimensions();
    }

    /** 注意不能叫 getName()：{@code Entity.getName()} 返回的是 Component，签名冲突。 */
    public String getPartName() {
        return this.name;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    /** 允许被准星选中（玩家能打到翅膀/头/尾巴）。 */
    @Override
    public boolean isPickable() {
        return !this.getParent().isRemoved();
    }

    /** 伤害转发给本体：材料减伤、升级效果、仇恨等全部照常走本体那条管线。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return this.getParent().hurt(source, amount);
    }

    /** 交互（手杖 / 装备界面 / 收回）也转发给本体，否则点到翅膀会"没反应"。 */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return this.getParent().interact(player, hand);
    }

    /** 命令选择器 / 射线命中时把子箱视同本体。 */
    @Override
    public boolean is(Entity other) {
        return this == other || this.getParent() == other;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }

    /**
     * 判定箱尺寸 = 1 倍尺寸 × 本体的体型倍率。
     *
     * <p>为什么必须跟着体型走：模型的缩放走本家 {@code AbstractGolemRenderer.scale}（乘 {@code getScale()}），
     * 本体的判定箱也乘同一个值，唯独子箱原来写死不动。装上傀儡地牢的泰坦升级（+300% 体型 = 4 倍）之后，
     * 表现就是"白框变大了、9 个绿框还缩在身体中心"，龙头、脖子、尾巴、翅膀全都没有判定。
     *
     * <p>用 {@code scalable} 而不是 {@code fixed}：只有 scalable 的 {@code EntityDimensions.scale()} 才真的生效。
     */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float scale = this.parentScale();
        this.lastScale = scale;
        return EntityDimensions.scalable(this.baseWidth * scale, this.baseHeight * scale);
    }

    /**
     * 体型变了就重算一次判定箱。
     *
     * <p>本体那边由本家 {@code AbstractGolemEntity.checkSize()}（每 10 tick + 属性刷新时）刷，
     * 子箱是独立实体、没人替它刷，所以每 tick 摆位之前由本体调一次这里。
     */
    public void refreshForScale(float scale) {
        if (this.lastScale != scale) {
            this.refreshDimensions();
        }
    }

    /** 当前体型倍率。本家 {@code getScale()} 在材质还没同步 / 假实体时返回 1，正是我们想要的兜底。 */
    private float parentScale() {
        DragonGolemEntity parent = this.getParent();
        return parent == null ? 1.0F : (float) parent.bodyScale();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
