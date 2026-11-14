package com.gezimos.katapult.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.MainViewModel
import com.gezimos.katapult.R
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.util.ShortcutHelper
import com.gezimos.katapult.ui.settings.*


@Composable
fun AppContextMenu(
    viewModel: MainViewModel,
    app: AppModel,
    iconPicker: ActivityResultLauncher<Array<String>>,
    showReorder: Boolean,
    showHide: Boolean,
    onDismiss: () -> Unit,
    onClearBadge: (String) -> Unit,
    onChange: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var changeIconApp by remember { mutableStateOf<AppModel?>(null) }
    var page by remember(app.key) { mutableStateOf(AppMenuPage.MAIN) }
    val policyRefresh = viewModel.policyRefresh
    val shortcuts = remember(app.key) {
        if (app.shortcutId.isEmpty()) ShortcutHelper.queryShortcuts(context, app.packageName)
            .filter { it.isDynamic || it.isDeclaredInManifest }
            .map { it.id to ShortcutHelper.label(it) }.sortedBy { it.second.lowercase() }
        else emptyList()
    }
    val enabledIds = remember(viewModel.shortcutRefresh) {
        viewModel.prefs.getSavedShortcuts().filter { it.packageName == app.packageName }.map { it.shortcutId }.toSet()
    }
    val isSystemApp = remember(app.packageName) {
        try { context.packageManager.getApplicationInfo(app.packageName, 0).flags and
            android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 } catch (_: Exception) { true }
    }
    val back = { if (page == AppMenuPage.MAIN) onDismiss() else page = AppMenuPage.MAIN }

    if (!viewModel.showRenameDialog && !viewModel.reorderMode &&
        changeIconApp == null
    ) {
        BottomSheet(onDismiss = onDismiss, onBack = back) {
            PaperSheetHeading(app.label, onDismiss, if (page == AppMenuPage.MAIN) null else back)
            when (page) {
                AppMenuPage.MAIN -> {
                    if (app.shortcutId.isNotEmpty()) {
                        AppMenuEntry(R.string.menu_app_icon, stringResource(R.string.menu_remove)) {
                            viewModel.removeShortcut(app); onDismiss()
                        }
                    } else if (shortcuts.isNotEmpty()) {
                        Spacer(Modifier.height(9.dp))
                        GroupHeading(stringResource(R.string.app_shortcuts), stringResource(R.string.menu_app_icon), 1.dp)
                        shortcuts.forEach { (id, label) ->
                            PaperShortcutEntry(label, stringResource(R.string.menu_app_icon), id in enabledIds,
                                onOpen = { onDismiss(); ShortcutHelper.launch(context, app.packageName, id) },
                                onToggle = { enabled -> viewModel.setShortcutEnabled(app.packageName, id, label, enabled) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        if (app.shortcutId.isEmpty()) {
                            PaperTileAction(stringResource(R.string.kotatsu_title), com.gezimos.katapult.paper.R.drawable.paper_tile_kotatsu) { page = AppMenuPage.KOTATSU }
                            PaperTileAction(stringResource(R.string.menu_android), com.gezimos.katapult.paper.R.drawable.paper_tile_android) { page = AppMenuPage.ANDROID }
                        }
                        if (onChange != null) PaperTileAction(stringResource(R.string.menu_change), com.gezimos.katapult.paper.R.drawable.paper_tile_change, onChange)
                        else if (showReorder) PaperTileAction(stringResource(R.string.menu_move), com.gezimos.katapult.paper.R.drawable.paper_tile_move) { viewModel.enterReorderMode(app) }
                    }
                }
                AppMenuPage.KOTATSU -> {
                    GroupHeading(stringResource(R.string.kotatsu_title), ruleWidth = 1.dp)
                    AppMenuEntry(R.string.menu_name, app.label) { viewModel.beginRename(app) }
                    AppMenuEntry(R.string.menu_app_icon, stringResource(R.string.menu_change)) { changeIconApp = app }
                    if (showHide) AppMenuEntry(R.string.menu_app_icon, stringResource(R.string.hide)) { viewModel.hideApp(app.key); onDismiss() }
                    if (viewModel.canClearBadge(app.packageName)) AppMenuEntry(R.string.menu_notification_dot, stringResource(R.string.menu_clear)) {
                        onClearBadge(app.packageName); onDismiss()
                    }
                }
                AppMenuPage.ANDROID -> {
                    GroupHeading(stringResource(R.string.menu_android), ruleWidth = 1.dp)
                    AppMenuEntry(R.string.app_info, stringResource(com.gezimos.katapult.paper.R.string.paper_open), true) {
                        onDismiss(); viewModel.launchIntent(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${app.packageName}") })
                    }
                    AppMenuEntry(R.string.notifications, stringResource(com.gezimos.katapult.paper.R.string.paper_open), true) {
                        onDismiss(); viewModel.launchIntent(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName) })
                    }
                    if (!isSystemApp) AppMenuEntry(R.string.uninstall, stringResource(com.gezimos.katapult.paper.R.string.paper_open), true) {
                        onDismiss(); viewModel.launchIntent(context, Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply { data = Uri.parse("package:${app.packageName}") })
                    }
                }
            }
        }
    }

    val renameDraft = viewModel.renameDraft
    if (viewModel.showRenameDialog && renameDraft != null) androidx.compose.runtime.key(renameDraft) {
        RenameDialog(
            textFieldValue = renameDraft.value,
            onValueChange = { renameDraft.value = it },
            onDismiss = {
                if (viewModel.renameDraft === renameDraft) {
                    if (viewModel.showRenameDialog) viewModel.renameDraft = null
                    viewModel.showRenameDialog = false
                    onDismiss()
                }
            },
            onConfirm = { newName ->
                if (viewModel.renameDraft === renameDraft) viewModel.renameApp(app.key, newName, app.label)
            },
        )
    }

    changeIconApp?.let { target ->
        ChangeIconDialog(
            viewModel = viewModel,
            app = target,
            onDismiss = {
                changeIconApp = null
                onDismiss()
            },
            onImportClick = {
                viewModel.beginIconImport(target.key)
                iconPicker.launch(arrayOf("image/png", "image/svg+xml"))
                changeIconApp = null
                onDismiss()
            },
        )
    }
}

private enum class AppMenuPage { MAIN, KOTATSU, ANDROID }

@Composable
private fun AppMenuEntry(name: Int, action: String, external: Boolean = false, onClick: () -> Unit) {
    Entry(SettingsEntry.Open("$name:$action", stringResource(name), "", onClick,
        actionLabel = action, external = external), leaderColor = LocalPalette.current.muted)
}

@Composable
private fun RenameDialog(
    textFieldValue: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    BottomSheet(onDismiss = onDismiss, imePadding = true) {
        PaperSheetHeading(stringResource(R.string.menu_name), onDismiss)
        BasicTextField(
            value = textFieldValue,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 18.sp,
                fontFamily = LatoFamily,
                color = LocalInk.current,
            ),
            cursorBrush = SolidColor(LocalInk.current.copy(alpha = 0f)),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.5.dp, LocalInk.current)
                .padding(12.dp)
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = stringResource(R.string.cancel),
                fontSize = 18.sp,
                fontFamily = LatoFamily,
                color = LocalInk.current,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.save),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = LatoFamily,
                color = LocalInk.current,
                modifier = Modifier
                    .clickable { onConfirm(textFieldValue.text) }
                    .padding(12.dp),
            )
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)

@Composable
private fun ChangeIconDialog(
    viewModel: MainViewModel,
    app: AppModel,
    onDismiss: () -> Unit,
    onImportClick: () -> Unit,
) {
    val context = LocalContext.current
    val pickerShape = appIconShape(viewModel.roundedIcons)
    val sizePx = remember(context) { (48 * context.resources.displayMetrics.density).toInt() }
    val hasOverride = remember(viewModel.shortcutRefresh) {
        viewModel.prefs.getIconOverride(app.key) != null
    }
    val catalogue by produceState<Result<com.gezimos.katapult.util.IconCatalogue>?>(null, context) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.gezimos.katapult.util.IconCatalogue.load(context) }
        }
    }
    val render = remember(context, sizePx) {
        { icon: com.gezimos.katapult.util.CatalogueIcon ->
            com.gezimos.katapult.util.CatalogueIconRenderer.render(context, icon, sizePx)
        }
    }

    BottomSheet(onDismiss = onDismiss, imePadding = true) {
        PaperSheetHeading(app.label, onDismiss)
        if (hasOverride) {
            BottomSheetOption(stringResource(R.string.reset_icon), icon = Icons.Rounded.Restore) {
                viewModel.clearIconOverride(app.key)
                onDismiss()
            }
        }

        val loaded = catalogue?.getOrNull()
        if (loaded != null) {
            IconCollectionPicker(
                catalogue = loaded,
                shape = pickerShape,
                render = render,
                onChoose = { id -> viewModel.setCatalogueIcon(app.key, id).await() },
                onChosen = onDismiss,
                onImport = onImportClick,
            )
        } else {
            Text(stringResource(if (catalogue == null) R.string.icons_loading else R.string.icon_catalogue_failed),
                color = LocalInk.current, fontFamily = LatoFamily)
            BottomSheetOption(stringResource(R.string.choose_png), icon = Icons.Rounded.Upload, onClick = onImportClick)
        }
    }
}

@Composable
private fun PickerPageBar(page: Int, totalPages: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    if (totalPages <= 1) return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page > 0) {
            ArrowButton(iconRes = R.drawable.ic_arrow_left, onClick = onPrev)
        } else {
            Spacer(Modifier.size(ArrowSize))
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until totalPages) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(6.dp)
                        .then(
                            if (i == page) Modifier.background(LocalInk.current, CircleShape)
                            else Modifier.border(1.5.dp, LocalInk.current, CircleShape)
                        )
                )
            }
        }
        if (page < totalPages - 1) {
            ArrowButton(iconRes = R.drawable.ic_arrow_right, onClick = onNext)
        } else {
            Spacer(Modifier.size(ArrowSize))
        }
    }
}
