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
    private var finishJob: Job? = null
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
                    finishJob?.cancel()
                    cues.cancel()
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
                    val restored = runCatching {
                        val o = JSONObject(prefs.getString("active", null)!!)
                        TimerEngine(o.getJSONObject("plan").plan(), now,
                            o.optString("sessionId", java.util.UUID.randomUUID().toString()))
                            .apply {
                                restore(o.getInt("index"), o.getLong("remaining"),
                                    o.optLong("activeMs", 0),
                                    o.optJSONArray("completedWork")?.let { a ->
                                        (0 until a.length()).map { a.getInt(it) }.toSet()
                                    } ?: emptySet(),
                                    o.optBoolean("statsAvailable", false),
                                    o.optLong("currentDuration", 0).takeIf { it > 0 })
                            }
                    }.getOrNull()
                    if (restored == null) {
                        prefs.edit().remove("active").apply()
                        android.widget.Toast.makeText(this, R.string.recovery_error,
                            android.widget.Toast.LENGTH_LONG).show()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    finishJob?.cancel()
                    cues.cancel()
                    engine = restored
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
     …6767 tokens truncated…edium,
                    modifier = Modifier.hiddenWhen((s.interval?.set ?: 0) == 0),
                )
        }
        val next = stringResource(R.string.next)
        TextButton(
            onClick = { TimerService.command(context, "next") },
            enabled = !locked,
            colors = ButtonDefaults.textButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .4f)),
            modifier = Modifier.semantics { contentDescription = next },
        ) {
            Text(stringResource(R.string.stage_short), style = MaterialTheme.typography.labelSmall)
            Text("›", fontSize = 28.sp)
        }
    }
    Text(
        "${stringResource(R.string.remaining)} ${formatTime(s.totalRemaining)}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun StageQueue(s: Snapshot, modifier: Modifier) {
    val stages = remember(s.plan) { s.plan.stages() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        val next = stages.drop(s.index + 1).take(3)
        repeat(3) { position ->
            val interval = next.getOrNull(position)
            val it = interval ?: Interval(Stage.WORK, 0, 0, 0)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).hiddenWhen(interval == null),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(stageString(it.stage)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(formatTime(it.duration), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Reserve layout space without drawing or exposing a hidden accessibility target. */
private fun Modifier.hiddenWhen(hidden: Boolean): Modifier = if (!hidden) this else
    clearAndSetSemantics {}.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {}
    }
