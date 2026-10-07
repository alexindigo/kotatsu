package com.gezimos.katapult.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.R

/** Callback-only decision component; dismissal is always Cancel. */
@Composable
fun ReorderExitSheet(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    BottomSheet(onDismiss = onCancel) {
        Text(
            text = stringResource(R.string.reorder_unsaved),
            fontSize = 18.sp,
            fontFamily = LatoFamily,
            color = LocalInk.current,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        BottomSheetOption(stringResource(R.string.save), icon = Icons.Rounded.Save, onClick = onSave)
        BottomSheetOption(stringResource(R.string.discard), icon = Icons.Rounded.Delete, onClick = onDiscard)
        BottomSheetOption(stringResource(R.string.cancel), icon = Icons.Rounded.Close, onClick = onCancel)
    }
}
