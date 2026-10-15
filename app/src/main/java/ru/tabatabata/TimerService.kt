package ru.tabatabata

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class TimerService : Service() {
    companion object {
        private val mutable = MutableStateFlow<Snapshot?>(null)
        val state = mutable.asStateFlow()

        fun clearResult() {
            if (mutable.value?.status in listOf(Status.COMPLETED, Status.STOPPED))
                mutable.value = null
        }

        fun command(context: Context, action: String, plan: Plan? = null) {
            val intent = Intent(context, TimerService::class.java).setAction(action)
            if (plan != null) intent.putExtra("plan", plan.json().toString())
            if (action == "start" || action == "recover") context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: TimerEngine? = null
    private lateinit var cues: Cues
    private var options = Options()
    private val optionsReady = CompletableDeferred<Unit>()
    private var wake: PowerManager.WakeLock? = null
    private var ticker: Job? = null
    private var lastIndex = -1
    private var lastSecond = -1L
    private var lastNotification = -1L
    private var lastSaved = -1L
    private var lastStatus: Status? = null
    private var startedAt = 0L
    private var activeMs = 0L
    private var pauses = 0
    private var skips = 0
    private val prefs by lazy { getSharedPreferences("session", MODE_PRIVATE) }
    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                pause()
            }
        }

    override fun onCreate() {
        super.onCreate()
        cues = Cues(this) { pause() }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        "workout",
                        getString(R.string.workout_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply { setSound(null, null) }
            )
        registerReceiver(
            noisy,
            IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        scope.launch {
            Store(this@TimerService).options.collect {
                options = it
                if (!optionsReady.isCompleted) optionsReady.complete(Unit)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val now = SystemClock.elapsedRealtime()
        when (intent?.action) {
            "start" -> {
                if (engine == null) {
                    val plan =
                        runCatching { JSONObject(intent.getStringExtra("plan")!!).plan() }
                            .getOrNull() ?: return START_NOT_STICKY
                    engine = TimerEngine(plan, now)
                    startedAt = now
                    activeMs = 0
                    pauses = 0
                    skips = 0
                    Telemetry.event(
                        "workout_started",
                        mapOf(
                            "session_id" to engine!!.sessionId,
                            "type" to "simple",
                            "duration_bucket" to durationBucket(plan.durationMs()),
                        ),
                    )
                    lastIndex = -1
                    foreground()
                    acquire()
                    startTicker()
                }
            }
            "recover" -> {
                if (engine == null) {
                    val o =
                        runCatching { JSONObject(prefs.getString("active", null)!!) }.getOrNull()
                            ?: run {
                                stopSelf()
                                return START_NOT_STICKY
                            }
                    val plan =
                        runCatching { o.getJSONObject("plan").plan() }.getOrNull()
                            ?: run {
                                stopSelf()
                                return START_NOT_STICKY
                            }
                    engine =
                        TimerEngine(
                                plan,
                                now,
                                o.optString("sessionId", java.util.UUID.randomUUID().toString()),
                            )
                            .apply {
                                restore(
                                    o.getInt("index"),
                                    o.getLong("remaining"),
                                    o.optLong("activeMs", 0),
                                    o.optJSONArray("completedWork")?.let { a ->
                                        (0 until a.length()).map { a.getInt(it) }.toSet()
                                    } ?: emptySet(),
                                    o.optBoolean("statsAvailable", false),
                                )
                            }
                    Telemetry.event("session_recovered", mapOf("reason" to "interrupted_unknown"))
                    foreground()
                    publish()
                    startTicker()
                }
            }
            "pause" -> pause()
            "resume" -> {
                if (engine?.snapshot(now)?.status in listOf(Status.PAUSED, Status.INTERRUPTED))
                    startedAt = now
                engine?.resume(now)
                acquire()
                lastIndex = engine?.snapshot(now)?.index ?: -1
                publish()
            }
            "next",
            "previous" -> {
                skips++
                cues.cancel()
                engine?.skip(if (intent.action == "next") 1 else -1, now)
                lastIndex = -1
                lastSecond = -1
                publish()
            }
            "stop" -> {
                engine?.stop(now)
                publish()
                finish(false)
            }
        }
        if (engine == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun foreground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun acquire() {
        if (engine?.snapshot(SystemClock.elapsedRealtime())?.status != Status.RUNNING) return
        if (wake?.isHeld == true) return
        wake =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabataBata:Workout")
                .apply {
                    setReferenceCounted(false)
                    acquire(
                        (engine!!.snapshot(SystemClock.elapsedRealtime()).totalRemaining + 5000)
                            .coerceAtMost(86405000)
                    )
                }
    }

    private fun release() {
        if (wake?.isHeld == true) wake?.release()
        wake = null
    }

    private fun pause() {
        if (engine?.snapshot(SystemClock.elapsedRealtime())?.status == Status.RUNNING) {
            activeMs += SystemClock.elapsedRealtime() - startedAt
            pauses++
        }
        engine?.pause(SystemClock.elapsedRealtime())
        cues.cancel()
        release()
        publish()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker =
            scope.launch {
                optionsReady.await()
                while (isActive && engine != null) {
                    publish()
                    delay(50)
                }
            }
    }

    private fun publish() {
        val s = engine?.snapshot(SystemClock.elapsedRealtime()) ?: return
        mutable.value = s
        val second = (s.remaining + 999) / 1000
        if (s.status == Status.RUNNING) {
            if (s.index != lastIndex) {
                lastIndex = s.index
                lastSecond = second
                cues.play(s.interval?.stage, options)
            } else if (
                options.countdown &&
                    (s.interval?.duration ?: 0) >= 6000 &&
                    second in 1..3 &&
                    second != lastSecond
            )
                cues.play(s.interval?.stage, options, true)
            lastSecond = second
        }
        if (s.status == Status.COMPLETED) {
            cues.play(null, options)
            finish(true)
            return
        }
        if (s.status == Status.STOPPED) return
        val now = SystemClock.elapsedRealtime()
        val changed = s.status != lastStatus
        if (now - lastSaved >= 1000 || changed) {
            prefs
                .edit()
                .putString(
                    "active",
                    JSONObject()
                        .put("plan", s.plan.json())
                        .put("index", s.index)
                        .put("remaining", s.remaining)
                        .put("sessionId", s.sessionId)
                        .put("activeMs", s.activeMs)
                        .put("completedWork", org.json.JSONArray(s.completedWork.toList()))
                        .put("statsAvailable", s.statsAvailable)
                        .toString(),
                )
                .apply()
            lastSaved = now
        }
        if (now - lastNotification >= 1000 || changed) {
            getSystemService(NotificationManager::class.java).notify(1, notification())
            lastNotification = now
        }
        lastStatus = s.status
    }

    private fun finish(completed: Boolean) {
        val e = engine
        if (e != null) {
            val s = mutable.value
            val elapsed = s?.activeMs ?: 0L
            Telemetry.event(
                "workout_finished",
                mapOf(
                    "session_id" to e.sessionId,
                    "result" to if (completed) "completed" else "stopped",
                    "active_seconds" to elapsed / 1000,
                    "pauses" to pauses,
                    "skips" to skips,
                ),
            )
        }
        prefs.edit().remove("active").apply()
        release()
        ticker?.cancel()
        engine = null
        if (!completed) cues.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (completed)
            scope.launch {
                delay(450)
                if (engine == null) stopSelf()
            }
        else stopSelf()
    }

    private fun notification(): Notification {
        val s = engine?.snapshot(SystemClock.elapsedRealtime())
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        fun action(name: String) =
            PendingIntent.getService(
                this,
                name.hashCode(),
                Intent(this, TimerService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val running = s?.status == Status.RUNNING
        return Notification.Builder(this, "workout")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                "${getString(stageString(s?.interval?.stage))} · ${formatTime(s?.remaining ?: 0)}"
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                        null,
                        getString(if (running) R.string.pause else R.string.resume),
                        action(if (running) "pause" else "resume"),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(null, getString(R.string.stop), action("stop")).build()
            )
            .build()
    }

    override fun onDestroy() {
        ticker?.cancel()
        release()
        cues.close()
        unregisterReceiver(noisy)
        scope.cancel()
        super.onDestroy()
    }
}

fun stageString(stage: Stage?): Int =
    when (stage) {
        Stage.PREPARE -> R.string.prepare
        Stage.WORK -> R.string.work
        Stage.REST -> R.string.rest
        Stage.BETWEEN -> R.string.between
        Stage.COOL -> R.string.cool
        null -> R.string.completed
    }

fun formatTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

fun durationBucket(ms: Long): String =
    when {
        ms < 60000 -> "under_1m"
        ms < 300000 -> "1_5m"
        ms < 1800000 -> "5_30m"
        else -> "30m_plus"
    }
