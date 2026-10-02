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
 * <p><b>这里必须用 {@code NetworkRegistry.newSimpleChannel} 这条老 API，不能手搓
 * {@code ChannelBuilder}。</b>这条是拿真实崩溃换来的教训：
 *
 * <pre>
 * java.lang.NullPointerException: Cannot invoke "java.util.function.Predicate.test(Object)"
 *         because "this.serverAcceptedVersions" is null
 *   at NetworkInstance.tryClientVersionOnServer(NetworkInstance.java:81)
 *   at NetworkRegistry.buildChannelVersionsForListPing(NetworkRegistry.java:176)
 *   at ServerStatusPing.&lt;init&gt;(ServerStatusPing.java:105)
 * </pre>
 *
 * {@code ChannelBuilder} 只设 {@code networkProtocolVersion} 是不够的 ——
 * 它<b>不会</b>替你填 {@code clientAcceptedVersions} / {@code serverAcceptedVersions}，
 * 两个字段留 null 的话，服务端每次构造 <b>server-list ping</b>（多人游戏列表、局域网广播都会走）
 * 就在 {@code buildChannelVersionsForListPing} 里 NPE，直接把整个服务端 tick 循环打崩。
 * 而 {@code newSimpleChannel} 内部会把这两个判定器设成
 * {@code ACCEPTVANILLA + ACCEPTVERSION}（也就是"同版本或原版都能连"），不会踩这个坑。
 *
 * <p>协议版本写死 {@code "1"}：客户端和服务端不一致时只会在日志里提醒，不会把游戏搞崩
 * —— 以后改包体记得同步加这个号。
 */
public final class DragonNetwork {

    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            Dragon_golems.id("main"), () -> VERSION, VERSION::equals, VERSION::equals);

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
