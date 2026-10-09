package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
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

    /**
     * 额外拾取半径（格）：<b>让"看着龙头/翅膀点下去"真的能选中这条龙</b>。
     *
     * <p>原版 {@code Entity.getPickRadius()} 默认 0，选中范围就等于碰撞箱本身；而龙的子箱
     * 是照着模型摆的立方体（头 2.4、翼 5.0…），离得稍远就"点不着" —— 回收手杖、徒手回收、
     * 开装备界面这些操作全都要先被准星选中，所以这个半径直接决定"这条龙好不好点"。
     *
     * <p>数值见 {@code DragonGolemConfig.pickRadius()}（默认 1.0）。
     * <b>注意本体那边的半径被刻意压小</b>（{@code bodyPickRadius}）：{@code ProjectileUtil} 里
     * "射线起点落在箱子里"这一支是不看同车关系的，本体判定箱一旦被吹得够高，就会把龙背上
     * 那位乘客自己的准星重新拽回自己骑着的龙身上。详见 {@code DragonGolemEntity.positionRider}。
     */
    @Override
    public float getPickRadius() {
        return (float) DragonGolemConfig.pickRadius();
    }

    /**
     * <b>子碰撞箱算在龙本体那一辆"车"里。</b>
     *
     * <p>这条是"泰坦升级会阻挡坐在其上的玩家"的正面修复。Forge 的多部件实体是<b>独立实体</b>：
     * {@code Entity.getRootVehicle()} 沿着 {@code isPassenger()} 往上爬，而子箱自己永远不会是乘客，
     * 于是它返回的是<b>它自己</b> —— 和龙本体不是同一个 root vehicle。而原版/Forge 到处都用
     * "root vehicle 相同"来判断"这是不是我自己（或我的坐骑）"：
     * <ul>
     *   <li>{@code ProjectileUtil.getEntityHitResult}（准星选中、{@code GameRenderer.pick}）：
     *       同 root vehicle 的候选<b>不会抢走准星</b>（除非 {@code canRiderInteract()}）；</li>
     *   <li>{@code Entity.skipAttackInteraction} / {@code push} / 移动碰撞：同 root vehicle 一律互免；</li>
     *   <li>我们自己的 {@code DragonRiderKeys} 瞄准过滤、{@code DragonRetrieveHandler} 也是这么写的。</li>
     * </ul>
     * 修好之前，骑着龙的人<b>平视前方时准星会先命中自己这条龙的头部/躯干子箱</b>：泰坦体型
     * （{@code getScale()=4}）时躯干箱有 9.6 格高、把整个座位包在里面，准星直接锁在
     * 离自己 0 格的箱子上 —— 表现就是"坐在龙上什么都点不了/打不了"（右键交互、左键攻击、
     * 放方块全被自己的龙吃掉）。普通体型下人的眼睛刚好在箱子上面一点点，所以只有大体型会犯。
     *
     * <p>本家的犬坐骑没有这个问题，因为它<b>根本没有子碰撞箱</b>：整只狗就是一个实体，
     * 原版那套"root vehicle 相同就跳过"的规则天然生效。龙要拿到同样的行为，就得让子箱
     * 如实回答"我属于本体"。
     *
     * <p>注意 {@code ProjectileUtil} 里还有一支：<b>射线起点落在箱子里</b>时是无条件选中的
     * （不看 root vehicle）。所以光有这一条还不够 —— 座位本身也必须待在"自己所有判定箱"
     * 的外面，那一条由 {@code DragonGolemEntity.positionRider} 的座椅净空负责。
     */
    @Override
    public Entity getRootVehicle() {
        DragonGolemEntity parent = this.getParent();
        return parent == null ? this : parent.getRootVehicle();
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
