package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R

@Composable
fun systemCategory(
    onSensitive: () -> Unit, onPermissions: () -> Unit, onDefault: () -> Unit, onSearch: () -> Unit,
    onConfig: () -> Unit, onAppLicense: () -> Unit, onFontLicense: () -> Unit,
): SettingsCategory = SettingsCategory("system", stringResource(R.string.section_system), listOf(
    SettingsGroup("access", stringResource(R.string.paper_group_access), listOf(
        SettingsEntry.Open("sensitive", stringResource(R.string.sensitive_access), stringResource(R.string.sensitive_access_desc), onSensitive),
        SettingsEntry.Open("permissions", stringResource(R.string.permissions), stringResource(R.string.permissions_desc), onPermissions),
    )),
    SettingsGroup("android", stringResource(R.string.paper_group_android), listOf(
        SettingsEntry.Link("default", stringResource(R.string.set_default_launcher), stringResource(R.string.set_default_launcher_desc), onDefault),
        SettingsEntry.Link("search", stringResource(R.string.search_settings), stringResource(R.string.search_settings_desc), onSearch),
    )),
    SettingsGroup("file", stringResource(R.string.paper_group_settings_file), listOf(
        SettingsEntry.Open("config", stringResource(R.string.config_backup), stringResource(R.string.config_backup_desc), onConfig),
    )),
    SettingsGroup("licenses", stringResource(R.string.paper_group_licenses), listOf(
        SettingsEntry.Link("app_license", stringResource(R.string.lic_app_title), stringResource(R.string.lic_app_desc), onAppLicense),
        SettingsEntry.Link("font_license", stringResource(R.string.lic_font_title), stringResource(R.string.lic_font_desc), onFontLicense),
    )),
), summary = stringResource(R.string.paper_summary_system))

@Composable
fun aboutCategory(version: String, onSource: () -> Unit, onUpstream: () -> Unit): SettingsCategory =
    SettingsCategory("about", stringResource(R.string.section_about), listOf(
        SettingsGroup("version", stringResource(R.string.paper_group_version), listOf(
            SettingsEntry.Metadata("version", stringResource(R.string.app_name), version),
        )),
        SettingsGroup("sources", stringResource(R.string.paper_group_sources), listOf(
            SettingsEntry.Link("source", stringResource(R.string.github_label), stringResource(R.string.github_desc), onSource),
            SettingsEntry.Link("upstream", stringResource(R.string.upstream_source_label), stringResource(R.string.upstream_source_desc), onUpstream),
        )),
    ), summary = stringResource(R.string.paper_summary_about, version))
