package a_silly_cat.dragon_golems.dragon;

/**
 * 排查用的诊断日志开关。
 *
 * <p>骑乘那套（按键 → 包 → 服务端执行）出问题时，"按了没反应"有太多种可能：
 * 包没发出去、服务端没收到、收到了但被校验挡掉、或者收到了但那段逻辑就没跑。
 * 光看代码推不出来，所以留这个开关：打开后客户端发包、服务端收包、骑乘 tick
 * 都会各打一行带 {@code [ride]} 前缀的日志，直接看日志就能定位断在哪一环。
 *
 * <p><b>定位完请改回 {@code false}</b>——正式版不该刷这些日志。
 */
public final class DragonDebug {

    /** 打开后输出 {@code [ride]} 系列日志。 */
    public static final boolean RIDE = true;

    private DragonDebug() {
    }
}
