package ru.tabatabata

import android.content.Context
import android.media.*
import android.os.*

class Cues(context: Context, private val onFocusLoss: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val pool =
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    private val sounds =
        mapOf(
            Stage.WORK to pool.load(context, R.raw.work, 1),
            Stage.REST to pool.load(context, R.raw.rest, 1),
            Stage.BETWEEN to pool.load(context, R.raw.rest, 1),
            Stage.PREPARE to pool.load(context, R.raw.prepare, 1),
            Stage.COOL to pool.load(context, R.raw.rest, 1),
        )
    private val finish = pool.load(context, R.raw.finish, 1)
    private val tick = pool.load(context, R.raw.tick, 1)
    private val handler = Handler(Looper.getMainLooper())
    private val releaseFocus = Runnable { audio.abandonAudioFocusRequest(focus) }
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            .setOnAudioFocusChangeListener {
                if (
                    it == AudioManager.AUDIOFOCUS_LOSS ||
                        it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                )
                    onFocusLoss()
            }
            .build()
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var pending: Triple<Int, Options, Boolean>? = null
    private var pendingAt = 0L

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) {
                loaded.add(id)
                pending?.let {
                    if (it.first == id && SystemClock.elapsedRealtime() - pendingAt < 250) {
                        pending = null
                        playId(id, it.second, it.third)
                    }
                }
            }
        }
    }

    private var stream = 0

    fun play(stage: Stage?, o: Options, countdown: Boolean = false) {
        handler.removeCallbacks(releaseFocus)
        if (
            o.duckMusic &&
                o.volume > 0 &&
                audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        )
            return
        if (stream != 0) pool.stop(stream)
        val id = if (countdown) tick else if (stage == null) finish else sounds.getValue(stage)
        if (o.volume > 0) {
            if (loaded.contains(id)) playId(id, o, countdown)
            else {
                pending = Triple(id, o, countdown)
                pendingAt = SystemClock.elapsedRealtime()
            }
        }
        if (o.vibrate && !countdown)
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    if (stage == null) 300 else 90,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
            )
        if (o.duckMusic) handler.postDelayed(releaseFocus, 500)
    }

    private fun playId(id: Int, o: Options, countdown: Boolean) {
        if (stream != 0) pool.stop(stream)
        stream = pool.play(id, o.volume, o.volume, 1, 0, 1f)
    }

    fun cancel() {
        pending = null
        handler.removeCallbacks(releaseFocus)
        if (stream != 0) pool.stop(stream)
        vibrator.cancel()
        audio.abandonAudioFocusRequest(focus)
    }

    fun close() {
        cancel()
        pool.release()
    }
}
