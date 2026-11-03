package tech.touka.amapnavi.ui.sound

import android.content.Context
import android.media.AudioManager
import android.media.SoundPool
import tech.touka.amapnavi.core.event.SoundEvent
import java.util.Collections

/**
 * 多通道低延迟音效反馈引擎。
 *
 * 全面采用 SoundPool 内存预加载（替代高延迟的 MediaPlayer），8 大核心音效 <10ms 瞬时发声。
 */
class SoundFeedbackEngine(context: Context) {

    companion object {
        private const val MAX_STREAMS = 4
    }

    private val appContext: Context = context.applicationContext

    @Suppress("DEPRECATION")
    private val soundPool: SoundPool = SoundPool(MAX_STREAMS, AudioManager.STREAM_MUSIC, 0)

    private val soundIds = HashMap<String, Int>()
    private val loadedIds = Collections.synchronizedSet(HashSet<Int>())

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedIds.add(sampleId)
            }
        }
        SoundEvent.values().forEach { preload(it) }
    }

    private fun preload(event: SoundEvent) {
        val resourceName = event.fileName.removeSuffix(".wav")
        val resId = appContext.resources.getIdentifier(resourceName, "raw", appContext.packageName)
        if (resId != 0) {
            soundIds[event.fileName] = soundPool.load(appContext, resId, 1)
        }
    }

    fun play(event: SoundEvent) {
        val soundId = soundIds[event.fileName] ?: return
        if (!loadedIds.contains(soundId)) return
        soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
    }

    fun release() {
        soundPool.release()
    }
}
