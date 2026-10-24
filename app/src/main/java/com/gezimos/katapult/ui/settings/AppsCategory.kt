package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.util.PrefsManager

@Composable
fun appsCategory(
    isMudita: Boolean, sort: Int, showIcon: Boolean, hideButton: Boolean, infinite: Boolean, hideArrows: Boolean,
    onSort: (Int) -> Unit, onIcon: (Boolean) -> Unit, onHidden: () -> Unit, onSystem: () -> Unit,
    onButton: (Boolean) -> Unit, onInfinite: (Boolean) -> Unit, onArrows: (Boolean) -> Unit,
): SettingsCategory {
    val sorts = listOf(PrefsManager.APP_SORT_MANUAL to R.string.app_sort_alphabetical,
        PrefsManager.APP_SORT_RECENT to R.string.app_sort_recent, PrefsManager.APP_SORT_USED to R.string.app_sort_used)
        .map { (id, label) -> SettingsChoice(id.toString(), stringResource(label)) }
    val current = sorts.firstOrNull { it.id == sort.toString() } ?: sorts.first()
    return SettingsCategory("apps", stringResource(R.string.section_all_apps), listOf(
        SettingsGroup("list", stringResource(R.string.paper_group_app_list), listOf(
            SettingsEntry.Value("sort", stringResource(R.string.app_sort_order), stringResource(R.string.app_sort_order_desc), current.label, sorts, { onSort(it.toInt()) }, current.id),
            SettingsEntry.Switch("icon", stringResource(R.string.show_katapult_icon), stringResource(R.string.show_katapult_icon_desc), showIcon, onIcon),
        )),
        SettingsGroup("visibility", stringResource(R.string.paper_group_visibility), listOf(
            SettingsEntry.Open("hidden", stringResource(R.string.hidden_apps), stringResource(R.string.hidden_apps_desc), onHidden),
            SettingsEntry.Open("system", stringResource(R.string.system_apps), stringResource(R.string.system_apps_desc), onSystem, isMudita),
        )),
        SettingsGroup("navigation", stringResource(R.string.paper_group_navigation), listOf(
            SettingsEntry.Switch("button", stringResource(R.string.hide_all_apps_button), stringResource(R.string.hide_all_apps_button_desc), hideButton, onButton),
            SettingsEntry.Switch("infinite", stringResource(R.string.infinite_scroll), stringResource(R.string.infinite_scroll_desc), infinite, onInfinite),
            SettingsEntry.Switch("arrows", stringResource(R.string.hide_arrow_buttons), stringResource(R.string.hide_arrow_buttons_desc), hideArrows, onArrows),
        )),
    ), summary = current.label)
}
