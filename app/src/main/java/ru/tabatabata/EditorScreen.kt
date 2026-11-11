package ru.tabatabata

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

/** Reject damaged containers while retaining incomplete user input inside valid fields. */
internal fun decodeEditorDraft(raw: String, fallback: String): JSONObject = runCatching {
    JSONObject(raw).also { obj ->
        require(obj.getString("name").length <= 100)
        obj.getBoolean("last")
        val fields = obj.getJSONArray("values")
        require(fields.length() == 7)
        repeat(7) { require(fields.getString(it).length <= 6) }
    }
}.getOrElse { JSONObject(fallback) }

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
    onStart: (Preset) -> Unit,
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
            .put("accent", preset?.accent ?: "blue").put("mark", preset?.mark ?: 0)
            .toString()
    val baseline = remember(key) { initial() }
    var raw by rememberSaveable(key) { mutableStateOf(decodeEditorDraft(drafts.read(key) ?: baseline, baseline).toString()) }
    val obj = remember(raw) { decodeEditorDraft(raw, baseline) }
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
        val next = JSONObject(obj.toString())
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
                    obj.optString("accent", preset?.accent ?: "blue").takeIf { a -> a in presetAccents } ?: "blue",
                    obj.optInt("mark", preset?.mark ?: 0).takeIf { m -> m in 0..3 } ?: 0,
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val shortWindow = maxHeight < 400.dp
    val largeText = LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxSize().then(if (shortWindow) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Column(
            (if (shortWindow) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState()))
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
            Text(stringResource(R.string.editor_main), style = MaterialTheme.typography.titleSmall)
            for (i in listOf(1, 2, 3, 0, 4, 5, 6)) {
                if (i == 0)
                    Text(stringResource(R.string.editor_extra), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp))
                run {
                    val title = stringResource(labels[i])
                    val tint = workoutBackground(when(i) { 1 -> Stage.WORK; 2,5 -> Stage.REST; else -> Stage.PREPARE }, false)
                    Surface(color = MaterialTheme.colorScheme.surface) {
                    BoxWithConstraints(Modifier.padding(vertical = 4.dp)) {
                        val stacked = maxWidth < 400.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
                        val fieldWidth = if (stacked) maxWidth.value - 104f - 16f else 124f
                        val valueSize = minOf(26f, (fieldWidth - 32f) /
                            (values[i].length.coerceAtLeast(4) * .65f * androidx.compose.ui.platform.LocalDensity.current.fontScale))
                            .coerceAtLeast(14f)
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
                                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = valueSize.sp),
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
            HorizontalDivider()
            PresetAppearance(
                obj.optString("accent", preset?.accent ?: "blue"), obj.optInt("mark", preset?.mark ?: 0),
            ) { accent, mark -> change { it.put("accent", accent).put("mark", mark) } }

        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (!largeText) Text(stringResource(R.string.total), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val totalValue = if (plan != null) formatTime(plan.durationMs())
                        else if (valid.all { it }) stringResource(R.string.too_long)
                        else stringResource(R.string.fix_fields)
                    Text(totalValue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = context.getString(R.string.total) + ": " + totalValue })
                }
                val previewLabel = stringResource(R.string.plan_preview)
                TextButton(onClick = { preview = true
                    Telemetry.event("plan_preview", mapOf("source" to "editor")) },
                    enabled = plan != null, modifier = Modifier.semantics { contentDescription = previewLabel }) {
                    Text(stringResource(R.string.view_plan_short))
                }
            }
            val startAction: @Composable (Modifier) -> Unit = { actionModifier ->
                Button(
                    onClick = {
                        plan?.let {
                            drafts.write(key, raw)
                            onStart(Preset(preset?.id ?: "new",
                                name.trim().ifEmpty { context.getString(R.string.default_name) }, it,
                                obj.optString("accent", "blue").takeIf { a -> a in presetAccents } ?: "blue",
                                obj.optInt("mark", 0).coerceIn(0, 3)))
                        }
                    },
                    enabled = plan != null,
                    shape = MaterialTheme.shapes.small,
                    modifier = actionModifier.heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.start))
                }
            }
            val saveAction: @Composable (Modifier) -> Unit = { actionModifier ->
                OutlinedButton(
                    onClick = { requestSave() },
                    enabled = plan != null,
                    shape = MaterialTheme.shapes.small,
                    modifier = actionModifier.heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
            if (largeText) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                startAction(Modifier.fillMaxWidth())
                saveAction(Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                startAction(Modifier.weight(1f))
                saveAction(Modifier.weight(1f))
            }
        }
    }
    }
    if (preview && plan != null) PlanPreviewDialog(plan, name.takeIf { it.isNotBlank() }) { preview = false }
    if (leave) AppDialog(stringResource(R.string.unsaved), onDismiss = { leave = false }) {
        Text(stringResource(R.string.unsaved_body))
        DialogActions(
            confirm = { m -> TextButton(onClick = { leave = false; requestSave() }, enabled = plan != null,
                modifier = m) { Text(stringResource(R.string.save)) } },
            dismiss = { m -> TextButton(onClick = { leave = false }, modifier = m) { Text(stringResource(R.string.stay)) } },
            alternative = { m -> TextButton(onClick = {
                drafts.clear(key); leave = false; onCancel?.invoke()
            }, modifier = m) { Text(stringResource(R.string.reset)) } },
        )
    }
    if (naming) {
        AppDialog(stringResource(R.string.save_mode), onDismiss = { naming = false }) {
            val keyboard = LocalSoftwareKeyboardController.current
            fun saveNamed() { keyboard?.hide(); naming = false; save() }
            OutlinedTextField(name, { v -> if (v.length <= 100) change { it.put("name", v) } },
                label = { Text(stringResource(R.string.name)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveNamed() }),
                modifier = Modifier.fillMaxWidth())
            DialogActions(
                confirm = { m -> TextButton(onClick = { saveNamed() }, modifier = m) { Text(stringResource(R.string.save)) } },
                dismiss = { m -> TextButton(onClick = { naming = false }, modifier = m) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
