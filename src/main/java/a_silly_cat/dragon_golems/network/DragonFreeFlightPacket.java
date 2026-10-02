package a_silly_cat.dragon_golems.network;

import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import dev.xkmc.l2serial.network.SimplePacketBase;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：切换龙傀儡的<b>自由飞行（穿墙）</b>开关。
 *
 * <p>为什么要发到服务端：{@code noPhysics} 决定的是实体移动时要不要做方块碰撞，
 * 而位置由服务端裁定（{@code isControlledByLocalInstance} 之外的情况）——
 * 只在客户端设会让两端的位置算得不一样、表现为抖动或回弹。所以按一下发一包，
 * 服务端设完再让原版的位置同步把它带回客户端。
 *
 * <p>载荷只有"目标状态"一个布尔值（而不是"切换"）：这样丢包或重复投递都不会
 * 让开关状态和玩家的预期相反。服务端仍然要校验"确实是骑着这条龙的人"发的。
 */
public class DragonFreeFlightPacket extends SimplePacketBase {

    /** 想要的状态：true = 开（穿墙），false = 关。 */
    public boolean on;

    /** 反序列化用。 */
    public DragonFreeFlightPacket() {
    }

    public DragonFreeFlightPacket(boolean on) {
        this.on = on;
    }

    public DragonFreeFlightPacket(FriendlyByteBuf buf) {
        this.on = buf.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(this.on);
    }

    @Override
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            // 只认"当前确实骑着这条龙"的人，其它一律忽略（防止伪造包）
            if (player.getVehicle() instanceof DragonGolemEntity dragon) {
                dragon.setFreeFlight(this.on);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
