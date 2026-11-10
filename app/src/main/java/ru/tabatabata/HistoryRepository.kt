package ru.tabatabata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.time.ZoneId

internal fun WorkoutRecord.json(): JSONObject = JSONObject()
    .put("sessionId", sessionId).put("plan", plan.json()).put("name", name)
    .put("accent", accent).put("mark", mark).put("startedAt", startedAt)
    .put("finishedAt", finishedAt).put("completed", completed).put("activeMs", activeMs)
    .put("completedRounds", completedRounds).put("statsAvailable", statsAvailable)

internal fun JSONObject.workoutRecord() = WorkoutRecord(
    getString("sessionId"), getJSONObject("plan").plan(), getString("name"),
    optString("accent", "blue").takeIf { it in presetAccents } ?: "blue",
    optInt("mark", 0).takeIf { it in 0..3 } ?: 0,
    optLong("startedAt", 0).takeIf { it > 0 }, getLong("finishedAt"), getBoolean("completed"),
    getLong("activeMs"), getInt("completedRounds"), getBoolean("statsAvailable"),
).also { it.validate() }

internal data class HistoryPage(val records: List<WorkoutRecord>, val total: Int,
    val week: WeekSummary, val window: WeekWindow)

/** Durable terminal outbox + primary key prevent loss/duplicates across process death. */
internal class HistoryRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    companion object {
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
        private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var helper: HistoryDatabase? = null
        private fun changed() { revision.value += 1 }
    }
    private fun db(): SQLiteDatabase {
        if (helper == null) helper = HistoryDatabase(app)
        return helper!!.writableDatabase
    }

    // One small synchronous terminal commit, never on the timer tick path. The old active
    // checkpoint and the pending result change together; DB work happens on the IO worker.
    fun enqueue(record: WorkoutRecord) = synchronized(lock) {
        record.validate()
        check(prefs.edit().putString("history:${record.sessionId}", record.json().toString())
            .remove("active").commit())
        changed()
    }

    fun flushInBackground() { worker.launch { runCatching { synchronized(lock) { flush() } } } }
    fun refresh() = synchronized(lock) { changed() }

    private fun flush() {
        val pending = prefs.all.filterKeys { it.startsWith("history:") }
        if (pending.isEmpty()) return
        val database = db()
        database.beginTransaction()
        try {
            pending.forEach { (_, raw) -> insert(database, JSONObject(raw as String).workoutRecord()) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        // A kill before this commit replays INSERT OR IGNORE with the same session id.
        check(prefs.edit().apply { pending.keys.forEach { remove(it) } }.commit())
    }

    private fun insert(database: SQLiteDatabase, r: WorkoutRecord) {
        val values = ContentValues().apply {
            put("session_id", r.sessionId); put("finished_at", r.finishedAt)
            put("completed", if (r.completed) 1 else 0); put("active_ms", r.activeMs)
            put("stats_available", if (r.statsAvailable) 1 else 0)
            put("record", r.json().toString())
        }
        check(database.insertWithOnConflict("workouts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L ||
            database.rawQuery("SELECT 1 FROM workouts WHERE session_id = ?", arrayOf(r.sessionId)).use { it.moveToFirst() })
    }

    suspend fun load(limit: Int = 50, now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()): HistoryPage = withContext(Dispatchers.IO) {
        synchronized(lock) {
            flush()
            val database = db()
            val window = currentWeek(now, zone)
            val records = database.rawQuery(
                "SELECT record FROM workouts ORDER BY finished_at DESC, session_id DESC LIMIT ?",
                arrayOf(limit.coerceAtLeast(1).toString()),
            ).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0)).workoutRecord()) } }
            val total = database.rawQuery("SELECT COUNT(*) FROM workouts", null).use { it.moveToFirst(); it.getInt(0) }
            val week = database.rawQuery("""SELECT COALESCE(SUM(completed),0),
                COALESCE(SUM(1-completed),0),
                COALESCE(SUM(CASE WHEN stats_available=1 THEN active_ms ELSE 0 END),0),
                COALESCE(SUM(1-stats_available),0) FROM workouts
                WHERE finished_at >= ? AND finished_at < ?""",
                arrayOf(window.start.toString(), window.end.toString())).use {
                it.moveToFirst(); WeekSummary(it.getInt(0), it.getInt(1), it.getLong(2), it.getInt(3))
            }
            HistoryPage(records, total, week, window)
        }
    }

    suspend fun reviewExperience(session: String, zone: ZoneId = ZoneId.systemDefault()): ReviewExperience =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                flush()
                val database = db()
                val current = database.rawQuery("SELECT record FROM workouts WHERE session_id = ?", arrayOf(session))
                    .use { if (it.moveToFirst()) JSONObject(it.getString(0)).workoutRecord() else null }
                if (current == null || !current.completed || !current.statsAvailable || current.completedRounds == 0)
                    return@synchronized ReviewExperience()
                var sessions = 0
                var active = 0L
                val days = mutableSetOf<java.time.LocalDate>()
                database.rawQuery("SELECT record FROM workouts WHERE completed=1 AND stats_available=1 ORDER BY finished_at DESC", null).use { c ->
                    while (c.moveToNext()) {
                        val r = JSONObject(c.getString(0)).workoutRecord()
                        if (r.completedRounds == 0) continue
                        sessions = (sessions + 1).coerceAtMost(3)
                        active += r.activeMs.coerceAtMost(600_000 - active)
                        if (days.size < 2) days.add(java.time.Instant.ofEpochMilli(r.finishedAt).atZone(zone).toLocalDate())
                        if (sessions >= 3 && days.size >= 2 && active >= 600_000) break
                    }
                }
                ReviewExperience(sessions, days.size, active, true)
            }
        }

    fun observe(limit: Int): Flow<Result<HistoryPage>> = revision.map {
        runCatching { load(limit) }
    }.flowOn(Dispatchers.IO)

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            // Clearing also removes queued terminal records, so they cannot reappear later.
            check(prefs.edit().apply { prefs.all.keys.filter { it.startsWith("history:") }.forEach { remove(it) } }.commit())
            db().delete("workouts", null, null)
            changed()
        }
    }
}

private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "workout-history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE workouts (session_id TEXT PRIMARY KEY NOT NULL,
            finished_at INTEGER NOT NULL, completed INTEGER NOT NULL, active_ms INTEGER NOT NULL,
            stats_available INTEGER NOT NULL, record TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX workouts_finished ON workouts(finished_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported history schema upgrade")
    }
}
