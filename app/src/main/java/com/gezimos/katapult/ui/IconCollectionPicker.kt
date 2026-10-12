package com.gezimos.katapult.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.R
import com.gezimos.katapult.util.CatalogueIcon
import com.gezimos.katapult.util.IconCatalogue
import com.gezimos.katapult.util.IconCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The same picker for every collection; consumers inject rendering and actions. */
@Composable
fun IconCollectionPicker(
    catalogue: IconCatalogue,
    shape: Shape,
    render: (CatalogueIcon) -> Bitmap?,
    onChoose: suspend (String) -> Boolean,
    onChosen: () -> Unit,
    onImport: () -> Unit,
) {
    var collection by remember { mutableStateOf(IconCollection.SIMPLE) }
    var query by remember { mutableStateOf("") }
    var requestedPage by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val matching = remember(catalogue, collection, query) { catalogue.search(collection, query) }
    val pages = maxOf(1, (matching.size + 19) / 20)
    val page = requestedPage.coerceIn(0, pages - 1)
    val visible = matching.drop(page * 20).take(20)
    val scroll = rememberScrollState()
    val height = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    Column(Modifier.fillMaxWidth().heightIn(max = height).verticalScroll(scroll)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (item in IconCollection.entries) {
                val title = collectionTitle(item)
                val selected = collection == item
                val tabShape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.weight(1f)
                        .then(if (selected) Modifier.background(LocalInk.current, tabShape)
                            else Modifier.border(1.5.dp, LocalInk.current, tabShape))
                        .clickable(enabled = !saving) {
                            collection = item
                            requestedPage = 0
                            saveFailed = false
                        }.padding(horizontal = 4.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title, fontFamily = LatoFamily, fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) LocalSurface.current else LocalInk.current)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().border(1.5.dp, LocalInk.current).padding(10.dp)) {
            val searchLabel = stringResource(R.string.search_icons)
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(160); requestedPage = 0 },
                enabled = !saving,
                singleLine = true,
                textStyle = TextStyle(color = LocalInk.current, fontFamily = LatoFamily, fontSize = 15.sp),
                cursorBrush = SolidColor(LocalInk.current),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = searchLabel },
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(searchLabel, color = LocalInk.current.copy(alpha = 0.6f),
                        fontSize = 15.sp, fontFamily = LatoFamily)
                    inner()
                },
            )
        }
        if (saveFailed) Text(stringResource(R.string.icon_selection_failed), color = LocalInk.current,
            fontFamily = LatoFamily, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        if (matching.isEmpty()) {
            Text(stringResource(R.string.no_matching_icons), color = LocalInk.current,
                fontFamily = LatoFamily, modifier = Modifier.padding(vertical = 24.dp))
        } else for (row in 0 until (visible.size + 4) / 5) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (column in 0 until 5) {
                    val entry = visible.getOrNull(row * 5 + column)
                    if (entry == null) Spacer(Modifier.weight(1f).height(72.dp))
                    else key(entry.id) {
                        IconCatalogueTile(entry, shape, render, !saving, Modifier.weight(1f)) {
                            saving = true
                            saveFailed = false
                            scope.launch {
                                try {
                                    if (onChoose(entry.id)) onChosen() else saveFailed = true
                                } catch (error: Exception) {
                                    android.util.Log.w("KotatsuIcons", "Icon choice failed: ${entry.id}", error)
                                    saveFailed = true
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cd_previous), fontFamily = LatoFamily, fontSize = 14.sp,
                color = LocalInk.current.copy(alpha = if (page > 0 && !saving) 1f else 0.35f),
                modifier = Modifier.clickable(enabled = page > 0 && !saving) { requestedPage = page - 1 }.padding(6.dp))
            Text(stringResource(R.string.icon_page, page + 1, pages, matching.size), fontFamily = LatoFamily,
                fontSize = 12.sp, color = LocalInk.current, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.cd_next), fontFamily = LatoFamily, fontSize = 14.sp,
                color = LocalInk.current.copy(alpha = if (page + 1 < pages && !saving) 1f else 0.35f),
                modifier = Modifier.clickable(enabled = page + 1 < pages && !saving) { requestedPage = page + 1 }.padding(6.dp))
        }
        Text(stringResource(R.string.choose_png), fontFamily = LatoFamily, fontSize = 14.sp,
            color = LocalInk.current, modifier = Modifier.fillMaxWidth().clickable(enabled = !saving, onClick = onImport)
                .padding(vertical = 12.dp))
    }
}

@Composable
private fun collectionTitle(collection: IconCollection): String = stringResource(when (collection) {
    IconCollection.SIMPLE -> R.string.simple_icons
    IconCollection.TABLER -> R.string.tabler_icons
    IconCollection.MATERIAL -> R.string.material_icons
})

@Composable
private fun IconCatalogueTile(
    entry: CatalogueIcon,
    shape: Shape,
    render: (CatalogueIcon) -> Bitmap?,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val image by produceState<Result<Bitmap?>?>(null, entry.id, render) {
        value = withContext(Dispatchers.IO) { runCatching { render(entry) } }
    }
    val unavailable = image != null && image?.getOrNull() == null
    val accessibleLabel = "${entry.label}, ${collectionTitle(entry.collection)}, ${entry.name}"
    Column(modifier.clickable(enabled = enabled && !unavailable && image != null, onClick = onClick)
        .semantics { contentDescription = accessibleLabel }, horizontalAlignment = Alignment.CenterHorizontally) {
        AppIconCircle(image?.getOrNull(), size = 48.dp, borderWidth = 1.5.dp, shape = shape)
        Text(if (unavailable) stringResource(R.string.icon_unavailable) else entry.label,
            fontFamily = LatoFamily, fontSize = 10.sp, color = LocalInk.current,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
