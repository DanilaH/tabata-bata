package ru.tabatabata

import android.content.Context
import android.media.*
import android.os.*
import kotlinx.coroutines.flow.asStateFlow

class Cues(private val context: Context, private val onFocusLoss: () -> Unit) {
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
    private data class CueSet(val prepare: Int, val work: Int, val rest: Int, val finish: Int, val tick: Int, val halfway: Int)
    private val banks: Map<String, CueSet>
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
                ) {
                    cancel()
                    onFocusLoss()
                }
            }
            .build()
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var pending: Triple<Int, Options, Boolean>? = null
    private var pendingAt = 0L

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (closed) return@setOnLoadCompleteListener
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
            fun cue(id: Int) = pool.load(context, id, 1)
        banks = mapOf(
            "electronic" to CueSet(cue(R.raw.prepare), cue(R.raw.work), cue(R.raw.rest), cue(R.raw.finish), cue(R.raw.tick), cue(R.raw.halfway)),
            "beep" to CueSet(cue(R.raw.beep_prepare), cue(R.raw.beep_work), cue(R.raw.beep_rest), cue(R.raw.beep_finish), cue(R.raw.beep_tick), cue(R.raw.beep_halfway)),
            "bell" to CueSet(cue(R.raw.bell_prepare), cue(R.raw.bell_work), cue(R.raw.bell_rest), cue(R.raw.bell_finish), cue(R.raw.bell_tick), cue(R.raw.bell_halfway)),
        )
    }

    private val voiceMutable = kotlinx.coroutines.flow.MutableStateFlow("loading")
    val voiceState = voiceMutable.asStateFlow()
    private var tts: android.speech.tts.TextToSpeech? = null
    private var utterance: String? = null
    private var speechFallback: (() -> Unit)? = null
    private var closed = false
    private val speechTimeout = Runnable {
        val fallback = speechFallback
        voiceMutable.value = "unavailable"
        utterance = null
        speechFallback = null
        tts?.stop()
        fallback?.invoke()
    }

    init {
        tts =
            android.speech.tts.TextToSpeech(context) { status ->
                handler.post {
                    if (!closed) {
                        val locale =
                            if (context.resources.configuration.locales[0].language == "ru")
                                java.util.Locale.forLanguageTag("ru-RU")
                            else java.util.Locale.ENGLISH
                        val voice =
                            if (status == android.speech.tts.TextToSpeech.SUCCESS)
                                tts?.voices
                                    ?.filter {
                                        it.locale.language == locale.language &&
                                            !it.isNetworkConnectionRequired &&
                                            it.features?.contains("notInstalled") != true
                                    }
                                    ?.sortedBy { it.name }
                                    ?.firstOrNull()
                            else null
                        voiceMutable.value =
                            if (
                                voice != null &&
                                    tts?.setVoice(voice) == android.speech.tts.TextToSpeech.SUCCESS
                            )
                                "ready"
                            else "unavailable"
                        tts?.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        tts?.setOnUtteranceProgressListener(
                            object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(id: String?) {}

                                override fun onDone(id: String?) {
                                    handler.post { finishSpeech(id, false) }
                                }

                                @Deprecated("Deprecated in Java")
                                override fun onError(id: String?) {
                                    handler.post { finishSpeech(id, true) }
                                }
                            }
                        )
                    }
                }
            }
    }

    private fun finishSpeech(id: String?, failed: Boolean) {
        if (id == null || id != utterance) return
        val fallback = speechFallback
        utterance = null
        speechFallback = null
        handler.removeCallbacks(speechTimeout)
        if (failed) {
            voiceMutable.value = "unavailable"
            fallback?.invoke()
        } else audio.abandonAudioFocusRequest(focus)
    }

    private var stream = 0

    fun play(stage: Stage?, o: Options, countdown: Boolean = false, lastRound: Boolean = false,
        round: Int = 0, totalRounds: Int = 0, halfway: Boolean = false, allowVoice: Boolean = true) {
        // The countdown takes priority over speech when an interval is short.
        if (closed) return
        cancel()
        if (o.vibrate) vibrate(stage, countdown, halfway)
        if (!countdown && !halfway && allowVoice && o.voice && o.volume > 0 && voiceMutable.value == "ready") {
            if (
                o.duckMusic &&
                    audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            )
                return
            val id = java.util.UUID.randomUUID().toString()
            utterance = id
            speechFallback = { play(stage, o.copy(voice = false), lastRound = lastRound) }
            val text = if (o.voiceRounds && stage == Stage.WORK && round > 0 && totalRounds > 0)
                context.getString(R.string.voice_round, round, totalRounds)
            else context.getString(if (stage == null) R.string.voice_finish
                else if (lastRound) R.string.voice_last_round else stageString(stage))
            val params =
                Bundle().apply {
                    putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, o.volume)
                }
            val result = tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, id)
            if (result == android.speech.tts.TextToSpeech.SUCCESS) {
                handler.postDelayed(speechTimeout, 4000)
                return
            }
            utterance = null
            speechFallback = null
        }
        if (
            o.duckMusic &&
                o.volume > 0 &&
                audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        )
            return
        if (stream != 0) pool.stop(stream)
        val bank = banks[o.soundProfile] ?: banks.getValue("electronic")
        val id = when {
            countdown -> bank.tick
            halfway -> bank.halfway
            stage == null -> bank.finish
            stage == Stage.PREPARE -> bank.prepare
            stage == Stage.WORK -> bank.work
            else -> bank.rest
        }
        if (o.volume > 0) {
            if (loaded.contains(id)) playId(id, o, countdown)
            else {
                pending = Triple(id, o, countdown)
                pendingAt = SystemClock.elapsedRealtime()
            }
        }
        if (o.duckMusic) handler.postDelayed(releaseFocus, if (countdown) 250 else 1100)
    }

    private fun vibrate(stage: Stage?, countdown: Boolean, halfway: Boolean) {
        val timings = when {
            halfway -> longArrayOf(0, 80)
            countdown -> longArrayOf(0, 120)
            stage == Stage.WORK -> longArrayOf(0, 450)
            stage == null -> longArrayOf(0, 300, 120, 500)
            else -> longArrayOf(0, 250)
        }
        // Waveforms also work on motors without amplitude control.
        vibrator.vibrate(VibrationEffect.createWaveform(timings,
            IntArray(timings.size) { if (it % 2 == 0) 0 else 255 }, -1),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    }

    private fun playId(id: Int, o: Options, countdown: Boolean) {
        if (stream != 0) pool.stop(stream)
        val gain = o.volume * if (countdown) .55f else 1f
        stream = pool.play(id, gain, gain, 1, 0, 1f)
    }

    fun cancel() {
        utterance = null
        speechFallback = null
        handler.removeCallbacks(speechTimeout)
        tts?.stop()
        pending = null
        handler.removeCallbacks(releaseFocus)
        if (stream != 0) pool.stop(stream)
        vibrator.cancel()
        audio.abandonAudioFocusRequest(focus)
    }

    fun close() {
        closed = true
        cancel()
        tts?.shutdown()
        pool.setOnLoadCompleteListener(null)
        pool.release()
    }
}
