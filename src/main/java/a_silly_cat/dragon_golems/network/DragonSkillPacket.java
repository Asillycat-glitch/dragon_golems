package a_silly_cat.dragon_golems.network;

import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import dev.xkmc.l2serial.network.SimplePacketBase;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：骑手按下了某个技能键，并给出了<b>瞄准点</b>。
 *
 * <p><b>为什么瞄准点要客户端算：</b>服务端拿不到玩家的准星。而龙的技能必须知道"往哪儿打"——
 * 装坐骑升级的龙带 {@code GolemFlags.PASSIVE}，本家的 {@code canAttackType} 会因此返回 false，
 * 于是 {@code setTarget} 全部被挡、{@code getTarget()} 永远是 null，AI 那套"有目标才开火"
 * 在驾驶时完全用不上。所以这里由客户端沿着准星算出一个世界坐标（打到实体就是实体身上，
 * 打到方块就是方块表面，都没打到就是射程尽头），发过来当"命令瞄准点"。
 *
 * <p><b>服务端要重新校验</b>（不能信客户端）：见 {@link DragonGolemEntity#onRiderCommand}。
 * 这里只负责把三个数搬过去，不做任何判定。
 */
public class DragonSkillPacket extends SimplePacketBase {

    /** 冲锋（俯冲）。 */
    public static final int SKILL_DIVE = 0;
    /** 龙息锥。 */
    public static final int SKILL_BREATH = 1;
    /** 龙弹；身体是幽匿的龙自动换成音爆（和 AI 的槽位规则一致）。 */
    public static final int SKILL_BLAST = 2;
    /** 技能序号上限，服务端校验用。 */
    public static final int SKILL_MAX = SKILL_BLAST;

    /** 技能序号，取值见上面几个常量。 */
    public int skill;
    public double x;
    public double y;
    public double z;

    /** 反序列化用（Forge 的 {@code Function<FriendlyByteBuf, MSG>} 解码器要求有它）。 */
    public DragonSkillPacket() {
    }

    public DragonSkillPacket(int skill, double x, double y, double z) {
        this.skill = skill;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.skill);
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
    }

    /** 解码构造器（由 {@link DragonNetwork} 注册）。 */
    public DragonSkillPacket(FriendlyByteBuf buf) {
        this.skill = buf.readVarInt();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
    }

    @Override
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // 注意：数据包线程不能碰世界，必须 enqueueWork 丢回主线程
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (player.getVehicle() instanceof DragonGolemEntity dragon) {
                dragon.onRiderCommand(player, this.skill, this.x, this.y, this.z);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
