package com.gezimos.katapult.ui.input

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.gezimos.katapult.ui.LocalInk

private val LocalEditorInput = staticCompositionLocalOf<WindowEditorInput?> { null }

@Composable
fun WindowEditorInputScope(input: WindowEditorInput, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEditorInput provides input, content = content)
}

/** The decor binding also survives replacement of a Compose root in the same real window. */
@Composable
fun currentWindowEditorInput(): WindowEditorInput? = LocalEditorInput.current ?: WindowEditorInput.from(LocalView.current)

@Composable
private fun Modifier.ownedEditor(autofocus: Boolean, enabled: Boolean): Modifier {
    val input = checkNotNull(currentWindowEditorInput()) { "Text editor requires a bound Android window" }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled)
    val registration = remember(input, focus) {
        input.owner.register({ requester.requestFocus() }, { if (focused) focus.clearFocus(force = true) }, { currentEnabled })
    }
    DisposableEffect(registration) { onDispose { input.owner.unregister(registration) } }
    LaunchedEffect(registration) { if (autofocus && enabled) requester.requestFocus() }
    val page = LocalEditorPageInput.current
    return then(if (page == null) Modifier else Modifier.then(EditorPlacementElement(page, registration)))
        .focusRequester(requester).onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) input.owner.focused(registration) else input.owner.blurred(registration)
    }
}

@Composable
fun PaperTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(LocalInk.current),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit = { it() },
) {
    BasicTextField(value, onValueChange, modifier.ownedEditor(autofocus, enabled),
        enabled = enabled, singleLine = singleLine, textStyle = textStyle, cursorBrush = cursorBrush,
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation, decorationBox = decorationBox)
}

@Composable
fun PaperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(LocalInk.current),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit = { it() },
) {
    BasicTextField(value, onValueChange, modifier.ownedEditor(autofocus, enabled),
        enabled = enabled, singleLine = singleLine, textStyle = textStyle, cursorBrush = cursorBrush,
        keyboardOptions = keyboardOptions, decorationBox = decorationBox)
}
