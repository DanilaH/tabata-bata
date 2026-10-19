package ru.tabatabata

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import org.json.JSONObject

internal fun parseDuration(value: String): Int? {
    if (value.all(Char::isDigit)) return value.toIntOrNull()
    val parts = value.split(':')
    if (parts.size != 2 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    val minutes = parts[0].toIntOrNull() ?: return null
    val seconds = parts[1].toIntOrNull() ?: return null
    if (minutes > 60 || seconds > 59) return null
    return minutes * 60 + seconds
}

internal fun durationInput(seconds: Int): String =
    "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)

/** Raw strings preserve incomplete/invalid input too. Saved modes are never changed here. */
internal class EditorDrafts(context: Context) {
    private val prefs = context.getSharedPreferences("editor_drafts", Context.MODE_PRIVATE)

    fun read(key: String): String? = prefs.getString(key, null)

    fun write(key: String, raw: String) {
        prefs.edit().putString(key, raw).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun has(key: String) = prefs.contains(key)
}

@Composable
internal fun Editor(
    preset: Preset?,
    initialPlan: Plan? = null,
    onCancel: (() -> Unit)?,
    onSave: (Preset) -> Unit,
    onStart: (Plan) -> Unit,
) {
    val context = LocalContext.current
    val drafts = remember { EditorDrafts(context) }
    val key = preset?.id ?: if (onCancel == null) "quick" else "new"
    val base = preset?.plan ?: initialPlan ?: Plan()
    fun initial(): String =
        JSONObject()
            .put("name", preset?.name ?: "")
            .put(
                "values",
                org.json.JSONArray(
                    listOf(
                            base.prepare,
                            base.work,
                            base.rest,
                            base.reps,
                            base.sets,
                            base.between,
                            base.cool,
                        )
                        .mapIndexed { i, v ->
                            if (i in listOf(3, 4)) v.toString() else durationInput(v)
                        }
                ),
            )
            .put("last", base.lastRest)
            .toString()
    val baseline = remember(key) { initial() }
    var raw by rememberSaveable(key) { mutableStateOf(drafts.read(key) ?: baseline) }
    val obj = remember(raw) { runCatching { JSONObject(raw) }.getOrElse { JSONObject(baseline) } }
    val values = (0..6).map { obj.getJSONArray("values").getString(it) }
    val name = obj.optString("name")
    val last = obj.optBoolean("last")
    val numbers =
        values.mapIndexed { i, v -> if (i in listOf(3, 4)) v.toIntOrNull() else parseDuration(v) }
    val limits = listOf(0..3600, 1..3600, 0..3600, 1..200, 1..20, 0..3600, 0..3600)
    val valid = numbers.mapIndexed { i, n -> n != null && n in limits[i] }
    val plan =
        if (valid.all { it })
            runCatching {
                    Plan(
                            numbers[0]!!,
                            numbers[1]!!,
                            numbers[2]!!,
                            numbers[3]!!,
                            numbers[4]!!,
                            numbers[5]!!,
                            numbers[6]!!,
                            last,
                        )
                        .also { it.validate() }
                }
                .getOrNull()
        else null
    fun change(update: (JSONObject) -> Unit) {
        val next = JSONObject(raw)
        update(next)
        raw = next.toString()
        drafts.write(key, raw)
    }
    var leave by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    fun save() {
        plan?.let {
            onSave(
                Preset(
                    preset?.id ?: java.util.UUID.randomUUID().toString(),
                    name.trim().ifEmpty { context.getString(R.string.default_name) },
                    it,
                )
            )
        }
    }
    fun requestSave() {
        if (preset == null) naming = true else save()
    }
    fun back() {
        if (raw != baseline) leave = true else onCancel?.invoke()
    }
    BackHandler(enabled = onCancel != null) { back() }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onCancel != null)
                TextButton(onClick = { back() }) { Text("← " + stringResource(R.string.saved)) }
            if (preset != null)
                OutlinedTextField(
                    name,
                    { v -> if (v.length <= 100) change { it.put("name", v) } },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            Text(
                stringResource(R.string.time_input_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            val labels =
                listOf(
                    R.string.prepare,
                    R.string.work,
                    R.string.rest,
                    R.string.reps,
                    R.string.sets,
                    R.string.between,
                    R.string.cool,
                )
            for (i in 0..6) {
                if (i == 4)
                    Text(stringResource(R.string.advanced), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp))
                run {
                    val title = stringResource(labels[i])
                    val tint = workoutBackground(when(i) { 1 -> Stage.WORK; 2,5 -> Stage.REST; else -> Stage.PREPARE }, false)
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    BoxWithConstraints(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        val stacked = maxWidth < 390.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
                        Column {
                        if (stacked) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StageMark(i, if (MaterialTheme.colorScheme.background.red < .3f)
                                androidx.compose.ui.graphics.lerp(tint, androidx.compose.ui.graphics.Color.White, .55f) else tint)
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!stacked) Text(title, modifier = Modifier.weight(1f))
                            
                            RepeatButton(
                                symbol = "−", label = "− $title", tint = tint,
                                onStep = {
                                    val v =
                                        ((numbers[i] ?: limits[i].first) - 1).coerceIn(limits[i])
                                    change {
                                        it.getJSONArray("values")
                                            .put(
                                                i,
                                                if (i in listOf(3, 4)) v.toString()
                                                else durationInput(v),
                                            )
                                    }
                                },
                                enabled = numbers[i] != limits[i].first,
                            )
                            OutlinedTextField(
                                values[i],
                                { v ->
                                    if (
                                        v.length <= 6 &&
                                            v.all {
                                                it.isDigit() || (i !in listOf(3, 4) && it == ':')
                                            }
                                    )
                                        change { it.getJSONArray("values").put(i, v) }
                                },
                                textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 26.sp),
                                singleLine = true,
                                isError = !valid[i],
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType =
                                            if (i in listOf(3, 4)) KeyboardType.Number
                                            else KeyboardType.Text
                                    ),
                                modifier =
                                    (if (stacked) Modifier.weight(1f) else Modifier.width(140.dp)).padding(horizontal = 8.dp).semantics { contentDescription = title },
                            )
                            RepeatButton(
                                symbol = "+", label = "+ $title", tint = tint,
                                onStep = {
                                    val v =
                                        ((numbers[i] ?: limits[i].first) + 1).coerceIn(limits[i])
                                    change {
                                        it.getJSONArray("values")
                                            .put(
                                                i,
                                                if (i in listOf(3, 4)) v.toString()
                                                else durationInput(v),
                                            )
                                    }
                                },
                                enabled = numbers[i] != limits[i].last,
                            )
                        }
                        if (!valid[i])
                            Text(
                                if (i in listOf(3, 4))
                                    stringResource(
                                        R.string.count_range,
                                        limits[i].first,
                                        limits[i].last,
                                    )
                                else
                                    stringResource(
                                        R.string.time_range,
                                        durationInput(limits[i].first),
                                        durationInput(limits[i].last),
                                    ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                    }
                    }
                }
            }
            Toggle(R.string.last_rest, last) { v -> change { it.put("last", v) } }
            if (plan != null) {
                TextButton(onClick = { preview = !preview }) {
                    Text(stringResource(R.string.plan_preview) + if (preview) " ▴" else " ▾")
                }
                if (preview)
                    Text(
                        plan.stages().take(8).joinToString(" → ") {
                            "${context.getString(stageString(it.stage))} ${durationInput((it.duration/1000).toInt())}"
                        } + if (plan.stages().size > 8) " …" else ""
                    )
            }
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (plan != null)
                    stringResource(R.string.total) + ": " + formatTime(plan.durationMs())
                else if (valid.all { it }) stringResource(R.string.too_long)
                else stringResource(R.string.fix_fields),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        plan?.let {
                            drafts.write(key, raw)
                            onStart(it)
                        }
                    },
                    enabled = plan != null,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.start))
                }
                OutlinedButton(
                    onClick = { requestSave() },
                    enabled = plan != null,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
    if (leave)
        AlertDialog(
            onDismissRequest = { leave = false },
            title = { Text(stringResource(R.string.unsaved)) },
            text = { Text(stringResource(R.string.unsaved_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        leave = false
                        requestSave()
                    },
                    enabled = plan != null,
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leave = false }) { Text(stringResource(R.string.stay)) }
                    TextButton(
                        onClick = {
                            drafts.clear(key)
                            leave = false
                            onCancel?.invoke()
                        }
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                }
            },
        )
    if (naming)
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.save_mode)) },
            text = {
                OutlinedTextField(
                    name,
                    { v -> if (v.length <= 100) change { it.put("name", v) } },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        naming = false
                        save()
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
}
