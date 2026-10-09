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

    /**
     * 打开后，每次 {@code updateAttributes} 都打一行 {@code [render]}：<b>服务端 / 客户端各自的
     * modifier 表内容</b>。
     *
     * <p>用来排查"第三方视觉升级（mgdp 的倒立 / 前后翻转 / 大风车 / 后空翻）在龙身上没效果"：
     * 那些升级的判据是 {@code entity.getModifiers().containsKey(mgdp 的那条)}，而客户端这张表是
     * {@code writeSpawnData / readSpawnData} → {@code updateAttributes} 填出来的，
     * <b>不走</b> {@code SynchedEntityData}。两行日志一比就知道是"客户端表里没有"还是"表里有但画不出来"。
     *
     * <p><b>定位完请改回 {@code false}</b>。
     */
    public static final boolean RENDER = true;

    /**
     * 打开后，数据包每次重载完都会打一行 {@code [dragon_bodies]}：合并后的身体主题表里有哪几条。
     *
     * <p>整合包作者加完 {@code modulargolems_config/dragon_bodies/*.json} 之后，看这行就知道
     * 自己的条目有没有被读进来、有没有被别的包顶掉。
     */
    public static final boolean CONFIG = true;

    private DragonDebug() {
    }
}
