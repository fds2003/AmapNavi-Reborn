package tech.touka.amapnavi.cluster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tech.touka.amapnavi.core.model.NaviState
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 仪表运行时：100ms 调度 + 协程收集 NaviState。
 *
 * 所有 Binder 调用都发生在单线程 cluster-tick 上。
 */
class ClusterRuntime(
    private val transport: ClusterTransport,
    private val settings: ClusterSettings
) {

    private val publisher = ClusterPublisher(transport, settings)
    private val adasPublisher = AdasHorizonPublisher(transport, settings)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cluster-tick").apply { isDaemon = true }
    }

    private var collectJob: Job? = null
    private var started = false

    fun start(scope: CoroutineScope, flow: StateFlow<NaviState>) {
        if (started) return
        started = true
        scheduler.scheduleAtFixedRate({
            try {
                publisher.tick()
                adasPublisher.tick()
            } catch (_: Throwable) {
                // 单次 tick 异常不得终止调度
            }
        }, 0L, 100L, TimeUnit.MILLISECONDS)

        collectJob = scope.launch {
            flow.collect {
                publisher.onNaviState(it)
                adasPublisher.onNaviState(it)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        collectJob?.cancel()
        collectJob = null
        try {
            // 交还必须在 cluster-tick 线程执行，避免主线程 Binder 调用
            scheduler.submit { publisher.release() }.get(300L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // 调度器已停止或超时：放弃交还，不阻塞调用方
        }
        scheduler.shutdownNow()
        transport.close()
    }

    fun status(): StateFlow<ClusterStatus> = publisher.status

    fun sendTestFrame() {
        publisher.sendTestFrame()
    }

    /** 回放一帧 ADAS Position（type 458），用于实车验证 transact 14 通道。 */
    fun sendAdasTestFrame(): Boolean {
        val payload = AdasPayloadCodec.buildPosition(PositionFields())
        return transport.sendAdas(payload, AdasPayloadCodec.TYPE_POSITION)
    }

    /** 回放一帧 ADAS Meta（type 459）前导帧，用于实车验证 transact 14 通道。 */
    fun sendAdasMetaTestFrame(): Boolean {
        val payload = AdasPayloadCodec.buildMeta(MetaFields())
        return transport.sendAdas(payload, AdasPayloadCodec.TYPE_META)
    }

    /** 标定用：直接发送指定 displayType 的 TBT 帧（1.5km）。 */
    fun sendCalibrationFrame(displayType: Int, meters: Int = 1_500): Boolean {
        val d = ClusterMapper.formatDistance(meters)
        val frame = TbtFrame(displayType, 0, d.distVal, d.scale, d.frac)
        return publisher.sendRawTbt(frame)
    }

    fun probe(): ProbeResult = transport.probe()
}
