package ru.tabatabata

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

internal fun supportMailIntent(recipient: String, body: String, subject: String) =
    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(recipient, "@.") +
        "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body)))
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)

@Composable
internal fun SupportDialog(onClose: () -> Unit, onExternal: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    val device = remember(locale) { SupportDevice(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
        Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, locale) }
    var description by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
            Surface(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                .heightIn(max = maxHeight * .94f).align(androidx.compose.ui.Alignment.Center),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp).testTag("support-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.report_problem), style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.report_hint), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(description, onValueChange = { description = it.take(2000) },
                            label = { Text(stringResource(R.string.report_description)) }, minLines = 3,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                            modifier = Modifier.fillMaxWidth().testTag("support-description"))
                        Text(stringResource(R.string.report_included), style = MaterialTheme.typography.labelLarge)
                        Text(device.technicalText(), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("support-technical"))
                        Text(stringResource(R.string.report_privacy), style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = {
                            val intent = supportMailIntent(BuildConfig.SUPPORT_EMAIL,
                                supportReport(description, device), context.getString(R.string.report_subject))
                            runCatching { onExternal(); context.startActivity(intent) }.onFailure {
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.report_no_mail)) }
                            }
                        }) { Text(stringResource(R.string.report_email)) }
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText(context.getString(R.string.report_subject), supportReport(description, device)))
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.report_copied)) }
                        }) { Text(stringResource(R.string.report_copy)) }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.preview_close)) }
                    }
                    SnackbarHost(snackbar)
                }
            }
        }
    }
}
