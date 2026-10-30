package com.focus.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focus.launcher.R
import com.focus.launcher.data.AppEntry
import com.focus.launcher.ui.theme.LocalFocusColors

/** Bordered black (or white) panel in the middle of the screen. Every popup in the app is one. */
@Composable
fun FocusDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    tall: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = LocalFocusColors.current
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.88f)
                .then(if (tall) Modifier.fillMaxHeight(0.82f) else Modifier.heightIn(max = 600.dp))
                .background(c.bg)
                .border(1.dp, c.faint)
                .padding(vertical = 10.dp),
        ) {
            if (title != null) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 12.dp)) {
                    T(title, size = 20.sp, weight = FontWeight.Medium, maxLines = 2)
                    if (subtitle != null) {
                        VSpace(4.dp)
                        T(subtitle, size = 13.sp, color = c.dim, lineHeight = 18.sp)
                    }
                }
                Hairline()
            }
            content()
        }
    }
}

@Composable
fun MenuRow(text: String, modifier: Modifier = Modifier, detail: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    val c = LocalFocusColors.current
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        T(text, Modifier.weight(1f), size = 17.sp, maxLines = 1)
        if (detail != null) T(detail, size = 14.sp, color = c.dim, maxLines = 1)
        if (selected) Box(Modifier.size(8.dp).background(c.fg, CircleShape))
    }
}

/** Pick exactly one of [options]. */
@Composable
fun <V> ChoiceDialog(
    title: String,
    options: List<Pair<V, String>>,
    selected: V?,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onSelect: (V) -> Unit,
) {
    FocusDialog(onDismiss, title, subtitle) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for ((value, label) in options) {
                MenuRow(label, selected = value == selected) {
                    onSelect(value)
                    onDismiss()
                }
            }
        }
    }
}

/** Tick any number of [options]; the result is delivered when the user taps Done. */
@Composable
fun <V> MultiChoiceDialog(
    title: String,
    options: List<Pair<V, String>>,
    selected: Set<V>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onConfirm: (Set<V>) -> Unit,
) {
    var current by remember { mutableStateOf(selected) }
    FocusDialog(onDismiss, title, subtitle) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            for ((value, label) in options) {
                MenuRow(label, selected = value in current) {
                    current = if (value in current) current - value else current + value
                }
            }
        }
        DialogButtons(onDismiss, confirmLabel = stringResource(R.string.common_done)) {
            onConfirm(current)
            onDismiss()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    FocusDialog(onDismiss, title) {
        T(message, Modifier.padding(horizontal = 24.dp, vertical = 16.dp), size = 15.sp, color = LocalFocusColors.current.dim, lineHeight = 21.sp)
        DialogButtons(onDismiss, confirmLabel) {
            onConfirm()
            onDismiss()
        }
    }
}

@Composable
fun DialogButtons(onDismiss: () -> Unit, confirmLabel: String, cancelLabel: String = stringResource(R.string.common_cancel), onConfirm: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        T(cancelLabel, Modifier.clickable(onClick = onDismiss).padding(12.dp), size = 16.sp, color = LocalFocusColors.current.dim)
        T(confirmLabel, Modifier.clickable(onClick = onConfirm).padding(12.dp), size = 16.sp, weight = FontWeight.Medium)
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    confirmLabel: String = stringResource(R.string.common_save),
    numeric: Boolean = false,
    /** Several lines: Enter makes a new line, "Save" is the only way to confirm. */
    multiline: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val submit = {
        onConfirm(value.text.trim())
        onDismiss()
    }
    FocusDialog(onDismiss, title, subtitle) {
        UnderlinedField(
            value = value,
            onValueChange = { next -> value = if (numeric) next.copy(text = next.text.filter(Char::isDigit).take(4)) else next },
            placeholder = placeholder,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp).focusRequester(focus),
            imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            onImeAction = submit,
            singleLine = !multiline,
        )
        DialogButtons(onDismiss, confirmLabel, onConfirm = submit)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Text field: just text, a cursor and a line under it. One line unless told otherwise. */
@Composable
fun UnderlinedField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onImeAction: () -> Unit = {},
    singleLine: Boolean = true,
) {
    val c = LocalFocusColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6,
        textStyle = focusTextStyle(size = 19.sp),
        cursorBrush = SolidColor(c.fg),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        decorationBox = { inner ->
            Column {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    if (value.text.isEmpty()) T(placeholder, size = 19.sp, color = c.faint, maxLines = 1)
                    inner()
                }
                Hairline(color = c.dim)
            }
        },
    )
}

/** Searchable list of apps; used for corner shortcuts, fast apps and adding timers. */
@Composable
fun AppPickerDialog(
    title: String,
    apps: List<AppEntry>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    leading: List<Pair<String, () -> Unit>> = emptyList(),
    /** A last row that keeps the dialog open, e.g. "All apps…" under a shortened list. */
    more: Pair<String, () -> Unit>? = null,
    onPick: (AppEntry) -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val shown = remember(apps, query.text) {
        val q = query.text.trim()
        if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
    }
    FocusDialog(onDismiss, title, subtitle, tall = true) {
        UnderlinedField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.common_search),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            imeAction = ImeAction.Search,
        )
        LazyColumn(Modifier.weight(1f)) {
            if (query.text.isBlank()) {
                items(leading, key = { "leading:" + it.first }) { (label, action) ->
                    MenuRow(label, detail = stringResource(R.string.common_default_choice)) {
                        action()
                        onDismiss()
                    }
                }
            }
            items(shown, key = { it.key }) { app ->
                MenuRow(app.label, detail = if (app.isWorkProfile) stringResource(R.string.common_work_app) else null) {
                    onPick(app)
                    onDismiss()
                }
            }
            if (more != null) item(key = "more") { MenuRow(more.first, onClick = more.second) }
        }
    }
}
