package ir.panbeh.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ir.panbeh.app.ui.theme.Panbeh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** One open dialog: confirm, prompt (with [input]) or alert (no cancel). */
class DialogRequest(
    val text: String,
    val cancel: Boolean,
    val ok: String?,
    val danger: Boolean,
    val input: DialogInput?,
    val result: CompletableDeferred<String?>,
)

data class DialogInput(val placeholder: String? = null, val value: String = "", val filter: ((String) -> String)? = null, val hint: String? = null)

/** In-app replacements for confirm/prompt/alert, callable from any coroutine (dialog.tsx). */
object Dialogs {
    val queue = MutableStateFlow<List<DialogRequest>>(emptyList())

    private suspend fun show(text: String, cancel: Boolean, ok: String?, danger: Boolean, input: DialogInput?): String? {
        val r = DialogRequest(text, cancel, ok, danger, input, CompletableDeferred())
        queue.update { it + r }
        return try { r.result.await() } finally { queue.update { l -> l - r } }
    }

    suspend fun confirm(text: String, ok: String? = null, danger: Boolean = false) = show(text, true, ok, danger, null) != null
    /** The trimmed text, "" if left empty, null if cancelled. */
    suspend fun prompt(text: String, input: DialogInput = DialogInput(), ok: String? = null) = show(text, true, ok, false, input)?.trim()
    suspend fun alert(text: String) { show(text, false, null, false, null) }
}

/** Shows the front of [Dialogs.queue]; put once at the root. */
@Composable
fun DialogHost() {
    val queue by Dialogs.queue.collectAsStateCompat()
    val r = queue.firstOrNull() ?: return
    var value by remember(r) { mutableStateOf(r.input?.value ?: "") }
    val focus = remember(r) { FocusRequester() }
    AlertDialog(
        onDismissRequest = { r.result.complete(null) },
        text = {
            Column {
                Text(r.text)
                r.input?.let { inp ->
                    OutlinedTextField(
                        value, { value = inp.filter?.invoke(it) ?: it },
                        Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus), singleLine = true,
                        placeholder = inp.placeholder?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { r.result.complete(value) }),
                        supportingText = inp.hint?.let { { Text(it) } },
                    )
                    LaunchedEffect(r) { focus.requestFocus() }
                }
            }
        },
        confirmButton = {
            TextButton({ r.result.complete(if (r.input != null) value else "") }) {
                Text(r.ok ?: if (r.cancel) "تأیید" else "باشه", color = if (r.danger) Panbeh.colors.danger else Panbeh.colors.accent)
            }
        },
        dismissButton = if (r.cancel) ({ TextButton({ r.result.complete(null) }) { Text("لغو") } }) else null,
    )
}
