package com.gezimos.katapult.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk

/** One button owns the Home-proportioned icon tile and its label. */
@Composable
fun PaperTileAction(label: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 54.dp).inkFocus()
        .clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(54.dp).border(2.dp, LocalInk.current, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(38.dp), tint = LocalInk.current)
        }
        Text(label, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 17.25.sp,
            color = LocalInk.current, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {})
    }
}
