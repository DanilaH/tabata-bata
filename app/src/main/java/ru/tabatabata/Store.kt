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

    val presets = context.dataStore.data.map { parsePresets(it[presetsKey] ?: "[]") }
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
        }
    }

    suspend fun savePresets(items: List<Preset>) {
        context.dataStore.edit { it[presetsKey] = encode(items).toString() }
        Telemetry.event("preset_saved", mapOf("type" to "simple"))
    }

    suspend fun updatePreset(p: Preset) {
        context.dataStore.edit {
            val items = parsePresets(it[presetsKey] ?: "[]").toMutableList()
            val i = items.indexOfFirst { item -> item.id == p.id }
            if (i < 0) items.add(p) else items[i] = p
            it[presetsKey] = encode(items).toString()
        }
        Telemetry.event("preset_saved", mapOf("type" to "simple"))
    }

    suspend fun remove(id: String) {
        context.dataStore.edit {
            it[presetsKey] =
                encode(parsePresets(it[presetsKey] ?: "[]").filterNot { p -> p.id == id })
                    .toString()
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

    fun parsePresets(text: String): List<Preset> {
        val arr = JSONArray(text)
        require(arr.length() <= 500)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Preset(
                o.getString("id"),
                o.getString("name").also { n -> require(n.length in 1..100) },
                o.getJSONObject("plan").plan(),
            )
        }
    }
}
