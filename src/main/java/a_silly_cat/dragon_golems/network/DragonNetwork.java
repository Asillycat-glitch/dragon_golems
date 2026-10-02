package a_silly_cat.dragon_golems.network;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 本 mod 的网络通道。目前只有一个包：骑手按下技能键（{@link DragonSkillPacket}）。
 *
 * <p>这是"加自定义按键包"不可避免的成本：原版只把<b>跳跃</b>和<b>潜行</b>两个按键状态同步到服务端
 * （升降键用的就是这两个），R / G / V 这类键在服务端根本看不见，必须自己发包。
 *
 * <p>通道用 {@code ChannelBuilder}（Forge 47 的新写法，{@code NetworkRegistry.newSimpleChannel}
 * 那套已经废弃）。协议版本写死 {@code "1"}：客户端和服务端不一致时 Forge 只会在日志里提醒，
 * 不会把游戏搞崩——所以以后改包体记得同步加这个号。
 */
public final class DragonNetwork {

    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(Dragon_golems.id("main"))
            .networkProtocolVersion(() -> VERSION)
            .simpleChannel();

    private DragonNetwork() {
    }

    /** 注册包。由 {@code Dragon_golems} 的构造函数调用（两端都要，服务端要收包）。 */
    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(DragonSkillPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DragonSkillPacket::write)
                .decoder(DragonSkillPacket::new)
                .consumerMainThread(DragonSkillPacket::handle)
                .add();
    }
}
