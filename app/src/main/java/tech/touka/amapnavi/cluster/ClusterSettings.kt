package tech.touka.amapnavi.cluster

interface ClusterSettings {
    val enabled: Boolean
    val tbtEnabled: Boolean
    val speedEnabled: Boolean
    val estEnabled: Boolean
    val textEnabled: Boolean
    val tlEnabled: Boolean
    val etaMode: Int
    /** 环岛/收费站/匝道等未标定图标是否发候选码，默认关闭。 */
    val experimentalIcons: Boolean get() = false

    /**
     * ADAS 电子地平线（transact 14）实时数据通道，默认关闭。
     * 开启后 Position(458) 由 NaviState 真实数据填充，100ms 周期发送。
     */
    val adasEnabled: Boolean get() = false
}
