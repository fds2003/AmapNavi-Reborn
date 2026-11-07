package tech.touka.amapnavi.cluster

interface ClusterTransport {
    fun sendTbt(frame: TbtFrame): Boolean
    fun sendSpeed(frame: SpeedFrame): Boolean
    fun sendEst(frame: EstFrame): Boolean
    fun sendText(frame: TextFrame): Boolean

    /** ADAS 电子地平线：transact 14。默认不支持，由 HSAE transport 覆盖。 */
    fun sendAdas(payload: ByteArray, type: Int): Boolean = false

    fun probe(): ProbeResult
    fun close() {}
}

sealed class ProbeResult {
    object Ok : ProbeResult()
    object ServiceMissing : ProbeResult()
    object PermissionDenied : ProbeResult()
    data class Error(val message: String) : ProbeResult()
}
