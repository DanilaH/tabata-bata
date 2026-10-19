package ru.tabatabata

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("tabata")

data class Preset(val id: String = UUID.randomUUID().toString(), val name: String, val plan: Plan)

data class Options(
    val volume: Float = .8f,
    val vibrate: Boolean = true,
    val countdown: Boolean = true,
    val keepScreen: Boolean = true,
    val duckMusic: Boolean = false,
    val theme: String = "system",
    val showStages: Boolean = false,
    val voice: Boolean = false,
    val showReadyModes: Boolean = true,
)

fun Plan.json(): JSONObject =
    JSONObject()
        .put("prepare", prepare)
        .put("work", work)
        .put("rest", rest)
        .put("reps", reps)
        .put("sets", sets)
        .put("between", between)
        .put("cool", cool)
        .put("lastRest", lastRest)

fun JSONObject.plan(): Plan =
    Plan(
            getInt("prepare"),
            getInt("work"),
            getInt("rest"),
            getInt("reps"),
            getInt("sets"),
            getInt("between"),
            getInt("cool"),
            getBoolean("lastRest"),
        )
        .also { it.validate() }

enum class PresetWrite { SAVED, LIMIT, DAMAGED }
data class RemovedPreset(val preset: Preset, val index: Int)

class Store(private val context: Context) {
    private val presetsKey = stringPreferencesKey("presets")
    val lastPlan =
        context.dataStore.data.map {
            it[stringPreferencesKey("lastPlan")]?.let { value ->
                runCatching { JSONObject(value).plan() }.getOrNull()
            } ?: Plan()
        }

    suspend fun saveLastPlan(plan: Plan) {
        context.dataStore.edit { it[stringPreferencesKey("lastPlan")] = plan.json().toString() }
    }

    val lastPresetId = context.dataStore.data.map { it[stringPreferencesKey("lastPresetId")] }

    suspend fun saveLastPreset(id: String) {
        context.dataStore.edit { it[stringPreferencesKey("lastPresetId")] = id }
    }

    // Legacy oversized lists remain readable and exportable; writes cannot grow them.
    val presets = context.dataStore.data.map { readStored(it[presetsKey] ?: "[]") }
    val damagedPresets = context.dataStore.data.map {
        runCatching { parsePresets(it[presetsKey] ?: "[]", false) }.isFailure
    }
    private fun readStored(raw: String): List<Preset> =
        runCatching { parsePresets(raw, false) }.getOrElse {
            // Keep the raw value untouched; expose valid entries for export.
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { parsePresets(JSONArray().put(arr.get(i)).toString(), false).single() }.getOrNull()
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }
    val options =
        context.dataStore.data.map {
            Options(
                it[floatPreferencesKey("volume")] ?: .8f,
                it[booleanPreferencesKey("vibrate")] ?: true,
                it[booleanPreferencesKey("countdown")] ?: true,
                it[booleanPreferencesKey("keepScreen")] ?: true,
                it[booleanPreferencesKey("duckMusic")] ?: false,
                it[stringPreferencesKey("theme")]?.takeIf { value ->
                    value in listOf("system", "light", "dark")
                } ?: "system",
                it[booleanPreferencesKey("showStages")] ?: false,
                it[booleanPreferencesKey("voice")] ?: false,
                it[booleanPreferencesKey("showReadyModes")] ?: true,
            )
        }

    suspend fun saveOptions(o: Options) {
        context.dataStore.edit {
            it[floatPreferencesKey("volume")] = o.volume
            it[booleanPreferencesKey("vibrate")] = o.vibrate
            it[booleanPreferencesKey("countdown")] = o.countdown
            it[booleanPreferencesKey("keepScreen")] = o.keepScreen
            it[booleanPreferencesKey("duckMusic")] = o.duckMusic
            it[stringPreferencesKey("theme")] = o.theme
            it[booleanPreferencesKey("showStages")] = o.showStages
            it[booleanPreferencesKey("voice")] = o.voice
            it[booleanPreferencesKey("showReadyModes")] = o.showReadyModes
        }
    }

    private suspend fun mutate(transform: (List<Preset>) -> List<Preset>): PresetWrite {
        var result = PresetWrite.SAVED
        context.dataStore.edit { prefs ->
            val current = runCatching { parsePresets(prefs[presetsKey] ?: "[]", false) }.getOrNull()
            if (current == null) {
                result = PresetWrite.DAMAGED
            } else {
                val next = transform(current)
                if (next.size > 500 && next.size > current.size) result = PresetWrite.LIMIT
                else {
                    require(next.map { it.id }.distinct().size == next.size)
                    next.forEach { require(it.name.length in 1..100); it.plan.validate() }
                    prefs[presetsKey] = encode(next).toString()
                }
            }
        }
        return result
    }

    suspend fun savePresets(items: List<Preset>) = mutate { items }

    suspend fun updatePreset(p: Preset): PresetWrite {
        val result = mutate { current ->
            val items = current.toMutableList()
            val i = items.indexOfFirst { it.id == p.id }
            if (i < 0) items.add(p) else items[i] = p
            items
        }
        if (result == PresetWrite.SAVED) Telemetry.event("preset_saved", mapOf("type" to "simple"))
        return result
    }

    suspend fun addPresets(items: List<Preset>) = mutate { it + items }

    suspend fun remove(id: String): RemovedPreset? {
        var removed: RemovedPreset? = null
        mutate { current ->
            val index = current.indexOfFirst { it.id == id }
            if (index >= 0) removed = RemovedPreset(current[index], index)
            current.filterNot { it.id == id }
        }
        return removed
    }

    suspend fun restore(removed: RemovedPreset) = mutate { current ->
        if (current.any { it.id == removed.preset.id }) current
        else current.toMutableList().apply {
            add(removed.index.coerceIn(0, size), removed.preset)
        }
    }

    suspend fun move(id: String, delta: Int) = mutate { current ->
        current.toMutableList().apply {
            val from = indexOfFirst { it.id == id }
            val to = from + delta
            if (from >= 0 && to in indices) add(to, removeAt(from))
        }
    }

    fun encode(items: List<Preset>): JSONArray =
        JSONArray().apply {
            items.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("plan", it.plan.json()))
            }
        }

    fun export(items: List<Preset>): String =
        JSONObject().put("schema", 1).put("presets", encode(items)).toString(2)

    fun import(text: String): List<Preset> {
        require(text.length <= 1_000_000)
        val obj = JSONObject(text)
        require(obj.getInt("schema") == 1)
        val list = parsePresets(obj.getJSONArray("presets").toString())
        require(list.size <= 500)
        return list.map { it.copy(id = UUID.randomUUID().toString()) }
    }

    fun parsePresets(text: String, enforceLimit: Boolean = true): List<Preset> {
        val arr = JSONArray(text)
        if (enforceLimit) require(arr.length() <= 500)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Preset(
                o.getString("id").also { id -> require(id.isNotBlank()) },
                o.getString("name").also { n -> require(n.length in 1..100) },
                o.getJSONObject("plan").plan(),
            )
        }.also { items -> require(items.map { it.id }.distinct().size == items.size) }
    }
}
