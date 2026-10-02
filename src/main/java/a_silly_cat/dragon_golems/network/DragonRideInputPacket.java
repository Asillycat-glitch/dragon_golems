package a_silly_cat.dragon_golems.network;

import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import dev.xkmc.l2serial.network.SimplePacketBase;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：骑手的<b>升降键状态</b>（按住 / 松开）。
 *
 * <p><b>为什么升降也要发包：</b>原版 {@code Player.wantsToStopRiding()} 直接返回
 * {@code isShiftKeyDown()}，而它在 {@code Player.baseTick} 里一旦为真就 {@code stopRiding()}
 * ——也就是说 <b>Shift 是"下车键"，不能拿来做下降</b>（我第一版就是这么写的，
 * 表现是"按 Shift 想下降，人却掉下去了"）。而 Ctrl 这类键服务端根本看不见，所以只能自己发。
 *
 * <p>上升键仍然用原版的跳跃键（服务端能同步），这里一起带过来是为了让服务端只有一个
 * 输入来源、逻辑简单：两个 bit 的状态一变就发一包，按住期间不会每 tick 刷包。
 */
public class DragonRideInputPacket extends SimplePacketBase {

    /** bit0 = 上升（跳跃键），bit1 = 下降（本 mod 的下降键）。 */
    public int flags;

    /** 反序列化用。 */
    public DragonRideInputPacket() {
    }

    public DragonRideInputPacket(int flags) {
        this.flags = flags;
    }

    public DragonRideInputPacket(FriendlyByteBuf buf) {
        this.flags = buf.readByte();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeByte(this.flags);
    }

    @Override
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (player.getVehicle() instanceof DragonGolemEntity dragon) {
                dragon.onRiderInput(player, this.flags);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
