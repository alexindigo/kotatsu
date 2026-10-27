package com.gezimos.katapult.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.withSave
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import com.gezimos.katapult.util.ContrastMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** One owner for the wallpaper and its size-dependent derivative; all mutation is on Main. */
class WallpaperContrast(private val scope: CoroutineScope) {
    var wallpaper by mutableStateOf<Bitmap?>(null)
        private set
    var map by mutableStateOf<ImageBitmap?>(null)
        private set
    private var size = IntSize.Zero
    private var build: Job? = null

    fun updateWallpaper(bitmap: Bitmap?) {
        if (wallpaper === bitmap) return
        wallpaper = bitmap
        rebuild()
    }

    fun resize(value: IntSize) {
        if (size == value) return
        size = value
        rebuild()
    }

    private fun rebuild() {
        build?.cancel()
        map = null
        val bitmap = wallpaper ?: return
        val target = size
        if (target.width <= 0 || target.height <= 0) return
        build = scope.launch {
            map = withContext(Dispatchers.Default) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val ink = ContrastMap.build(pixels, bitmap.width, bitmap.height, target.width, target.height)
                Bitmap.createBitmap(ink, target.width, target.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }
}

internal data class ContrastSpace(val image: ImageBitmap, val origin: LayoutCoordinates)
internal val LocalContrastMap = staticCompositionLocalOf<ContrastSpace?> { null }

/** The origin is the actual box filled by the wallpaper, outside any content insets. */
@Composable
internal fun ContrastInkBox(
    contrast: WallpaperContrast,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var origin by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(modifier.onSizeChanged(contrast::resize).onGloballyPositioned { origin = it }) {
        val map = contrast.map
        val coordinates = origin
        CompositionLocalProvider(LocalContrastMap provides
            if (map != null && coordinates != null) ContrastSpace(map, coordinates) else null,
        ) { content() }
    }
}

/** Repaint only the caller's alpha mask. Solids must not be wrapped in this modifier. */
fun Modifier.contrastInk(): Modifier = composed {
    val space = LocalContrastMap.current
    if (space == null) this else {
        var position by remember { mutableStateOf<Offset?>(null) }
        val layerPaint = remember { Paint() }
        this
            .onGloballyPositioned { element ->
                position = if (space.origin.isAttached) space.origin.localPositionOf(element, Offset.Zero) else null
            }
            .drawWithContent {
                val offset = position
                if (offset != null && space.origin.isAttached) {
                    val elementSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    val canvas = drawContext.canvas
                    // Bound the compositing operation itself; a RenderNode graphicsLayer can
                    // change HWUI's raster path for unrelated solid siblings (API31/swangle).
                    canvas.withSave {
                        canvas.clipRect(0f, 0f, size.width, size.height)
                        canvas.withSaveLayer(Rect(Offset.Zero, size), layerPaint) {
                            drawContent()
                            drawImage(space.image,
                                srcOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                                srcSize = elementSize,
                                dstSize = elementSize,
                                blendMode = BlendMode.SrcIn,
                            )
                        }
                    }
                } else drawContent()
            }
    }
}
