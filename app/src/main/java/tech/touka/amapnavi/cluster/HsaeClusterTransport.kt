package tech.touka.amapnavi.cluster

import android.os.IBinder
import android.os.Parcel

/**
 * HSAE 仪表 Binder 实现。
 *
 * 通过反射访问 ServiceManager，避免编译期依赖 hidden API；
 * API 19 / target 22 不受 hidden API 限制。
 */
class HsaeClusterTransport : ClusterTransport {

    companion object {
        private const val NAV_SERVICE = "com.hsae.auto.IBINDER_NAVIGATION"
        private const val VEHICLE_SERVICE = "com.hsae.auto.IBINDER_VEHICLE"
        private const val NAV_DESCRIPTOR = "com.hsae.autosdk.navigation.INavigation"
        private const val VEHICLE_DESCRIPTOR = "com.hsae.autosdk.vehicle.IVehicle"

        private const val TX_TBT = 1
        private const val TX_GET_NAV_STATUS = 7
        private const val TX_SPEED = 5
        private const val TX_EST = 6
        private const val TX_INFO = 46
        private const val TX_ADAS = 14
    }

    @Volatile
    private var nav: IBinder? = null

    @Volatile
    private var vehicle: IBinder? = null

    @Volatile
    private var permissionDenied = false

    @Volatile
    private var lastError: String? = null

    override fun sendTbt(frame: TbtFrame): Boolean =
        transact(navBinder(), NAV_DESCRIPTOR, TX_TBT, { p ->
            HsaeFrameCodec.writeTbt(ParcelSinkAdapter(p), frame)
        }) { nav = null }

    override fun sendSpeed(frame: SpeedFrame): Boolean =
        transact(navBinder(), NAV_DESCRIPTOR, TX_SPEED, { p ->
            HsaeFrameCodec.writeSpeed(ParcelSinkAdapter(p), frame)
        }) { nav = null }

    override fun sendEst(frame: EstFrame): Boolean =
        transact(navBinder(), NAV_DESCRIPTOR, TX_EST, { p ->
            HsaeFrameCodec.writeEst(ParcelSinkAdapter(p), frame)
        }) { nav = null }

    override fun sendText(frame: TextFrame): Boolean =
        transact(vehicleBinder(), VEHICLE_DESCRIPTOR, TX_INFO, { p ->
            HsaeFrameCodec.writeText(ParcelSinkAdapter(p), frame)
        }) { vehicle = null }

    override fun sendAdas(payload: ByteArray, type: Int): Boolean =
        transact(navBinder(), NAV_DESCRIPTOR, TX_ADAS, { p ->
            HsaeFrameCodec.writeAdas(ParcelSinkAdapter(p), payload, type)
        }) { nav = null }

    override fun probe(): ProbeResult {
        val binder = navBinder() ?: return ProbeResult.ServiceMissing
        val ok = transact(binder, NAV_DESCRIPTOR, TX_GET_NAV_STATUS, { }) { nav = null }
        return when {
            ok -> ProbeResult.Ok
            permissionDenied -> ProbeResult.PermissionDenied
            else -> ProbeResult.Error(lastError ?: "unknown")
        }
    }

    override fun close() {
        nav = null
        vehicle = null
    }

    private fun navBinder(): IBinder? {
        val cached = nav
        if (cached != null && cached.isBinderAlive) return cached
        return service(NAV_SERVICE)?.also { nav = it }
    }

    private fun vehicleBinder(): IBinder? {
        val cached = vehicle
        if (cached != null && cached.isBinderAlive) return cached
        return service(VEHICLE_SERVICE)?.also { vehicle = it }
    }

    private fun service(name: String): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        val get = sm.getMethod("getService", String::class.java)
        get.invoke(null, name) as? IBinder
    } catch (t: Throwable) {
        lastError = "service $name: ${t.message}"
        null
    }

    private fun transact(
        binder: IBinder?,
        descriptor: String,
        code: Int,
        write: (Parcel) -> Unit,
        onError: () -> Unit = {}
    ): Boolean {
        if (binder == null || !binder.isBinderAlive) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (e: SecurityException) {
            permissionDenied = true
            lastError = "SecurityException: ${e.message}"
            false
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            onError()
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private class ParcelSinkAdapter(private val parcel: Parcel) : ParcelSink {
        override fun byte(value: Int) = parcel.writeByte(value.toByte())
        override fun int(value: Int) = parcel.writeInt(value)
        override fun string(value: String) = parcel.writeString(value)
        override fun byteArray(value: ByteArray) = parcel.writeByteArray(value)
    }
}
