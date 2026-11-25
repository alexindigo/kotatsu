package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** A decision inside the settings book. The caller owns the preview, outcome and navigation. */
@Composable
fun ConfirmationPage(
    title: String,
    question: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    detail: String = "",
    cancelLabel: String? = null,
    onCancel: () -> Unit = onBack,
    preview: @Composable () -> Unit = {},
) {
    PageFrame(title, onBack) {
        PagedContent(header = {
            GroupHeading(question, titleIsHeading = true)
            PaperHelp(detail, topPadding = 6.dp)
        }) {
            preview()
            EmphasizedSheetAction(confirmLabel, onConfirm)
            if (cancelLabel != null) SheetAction(cancelLabel, onCancel)
        }
    }
}
