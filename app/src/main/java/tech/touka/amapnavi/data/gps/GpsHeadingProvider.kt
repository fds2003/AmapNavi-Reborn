package tech.touka.amapnavi.data.gps

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.touka.amapnavi.core.model.NaviState

/**
 * 车机 GPS 航向源（纯车载传感器，不依赖 C3，也不依赖高德导航）。
 *
 * 背景：高德车机版只在导航时通过 10001 广播下发 `CAR_DIRECTION`；
 * 纯地图态（未开始导航）只发定位帧 12205，其中 `EXTRA_GEOLOCATION` 为整数 0，
 * 不含经纬度与航向。而仪表罗盘需要"开机即有东南西北"。
 *
 * 车机 GPS 一直在运行（系统应用与高德自身都在请求 ACCURACY_FINE gps），
 * 因此用 GPS bearing 作为兜底航向：
 * - 有速度（≥1.5 m/s）时 bearing 才可信，取整归一化到 0..359；
 * - 静止/低速时保持上一次有效朝向，指针不跳；
 * - 从未取到有效航向时为 -1（无效），由上层判断是否进入罗盘模式。
 */
class GpsHeadingProvider(private val context: Context) {

    companion object {
        /** 低于该速度时 GPS 航向不可信（米/秒）。 */
        private const val MIN_SPEED_FOR_BEARING_MPS = 1.5f
        private const val UPDATE_INTERVAL_MS = 1_000L
    }

    private val _headingFlow = MutableStateFlow(-1)

    /** 当前有效航向（0..359），无有效值时为 -1。 */
    val headingFlow: StateFlow<Int> = _headingFlow

    private var listener: LocationListener? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location?) {
                val loc = location ?: return
                if (loc.hasSpeed() && loc.speed >= MIN_SPEED_FOR_BEARING_MPS && loc.hasBearing()) {
                    val deg = ((loc.bearing.toInt() % 360) + 360) % 360
                    _headingFlow.value = deg
                }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String?) {}
            override fun onProviderDisabled(provider: String?) {}
        }
        listener = l
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, l)
        } catch (_: Throwable) {
            // 无 GPS provider 或权限被拒：静默降级（罗盘仅在导航时出现）
        }
    }

    fun stop() {
        if (!started) return
        started = false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        try {
            listener?.let { lm?.removeUpdates(it) }
        } catch (_: Throwable) {
            // 引擎已停止时忽略
        }
        listener = null
    }
}

/**
 * 航向兜底合并：高德下发的航向优先（导航时经过地图匹配，更准），
 * 高德缺省时用 GPS 航向；两者都无效则保持 -1（晶格仪表不进入罗盘模式）。
 *
 * 纯函数，便于 JVM 单测。
 */
fun NaviState.withFallbackHeading(gpsHeading: Int): NaviState {
    if (carDirection in 0..360) return this
    if (gpsHeading !in 0..360) return this
    return copy(carDirection = gpsHeading)
}
