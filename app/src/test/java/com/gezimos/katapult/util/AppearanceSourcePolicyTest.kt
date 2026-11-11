package com.gezimos.katapult.util

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Surfaces consume palette tokens; artwork and raster analysis are not UI colours. */
class AppearanceSourcePolicyTest {
    private val root = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it, "paper-ui/src/main").isDirectory }
    private val palette = "paper-ui/src/main/res/values/paper_palette.xml"
    // Exact snippets, not file exemptions: UI code added to these files is still checked.
    private val pixelProcessing = mapOf(
        "app/src/main/java/com/gezimos/katapult/util/ContrastMap.kt" to listOf(
            "0xff000000.toInt()", "0xffffffff.toInt()"), // Binary per-pixel contrast mask.
        "app/src/main/java/com/gezimos/katapult/util/IconUtility.kt" to listOf(
            "tinted.setTint(Color.BLACK)"), // Canonical silhouette before palette tinting at use.
        "kotatsu-notes/src/main/java/com/alexindigo/kotatsu/notes/QuoteAuthorViews.kt" to listOf(
            "bitmap.eraseColor(Color.WHITE)", "pixels[y * bitmap.width + x] != Color.WHITE",
            "textSize = size; color = Color.BLACK"), // Shaped glyph coverage measurement only.
    )
    private val literal = Regex(
        """\bColor\s*\.\s*(?:[A-Z][A-Za-z_]*|rgb|argb|parseColor)\b|\bColor\s*\(|\b0x[0-9a-fA-F]{8}\b|(?<!&)#[0-9a-fA-F]{3,8}\b|@android:color/[A-Za-z_]+|\bandroid\.R\.color\.[A-Za-z_]+|\bset(?:TextColor|BackgroundColor|HintTextColor|Color|Tint|ColorFilter)\s*\(\s*-?[0-9]|\bcolor\s*=\s*-?[0-9]""")
    private val aliasImport = Regex("""import\s+(?:android\.graphics|androidx\.compose\.ui\.graphics)\.Color\s+as\s+(\w+)""")

    private fun normalizeAliases(source: String): String = aliasImport.findAll(source).fold(source) { text, match ->
        text.replace(Regex("\\b${Regex.escape(match.groupValues[1])}(?=\\s*[.(])"), "Color")
    }

    private fun checked(file: File): Boolean {
        val path = file.relativeTo(root).invariantSeparatorsPath
        if (!path.contains("/src/main/") || path == palette) return false
        if (file.extension in setOf("kt", "java")) return true
        return file.extension == "xml" && Regex("/res/(layout|values)[^/]*/").containsMatchIn(path)
    }

    private fun violations(file: File): List<String> {
        val path = file.relativeTo(root).invariantSeparatorsPath
        // The palette factory may construct Colours from resource IDs, never new literals.
        val resourceConstruction = if (path == "paper-ui/src/main/java/com/gezimos/katapult/ui/PaperTokens.kt") listOf(
            "Color(context.getColor(ink))", "Color(context.getColor(paper))", "Color(context.getColor(R.color.paper_muted))",
        ) else emptyList()
        return normalizeAliases(file.readText()).lines().mapIndexedNotNull { index, original ->
            val line = (pixelProcessing[path].orEmpty() + resourceConstruction).fold(original) { text, allowed -> text.replace(allowed, "") }
            if (literal.containsMatchIn(line)) "$path:${index + 1}: $original" else null
        }
    }

    @Test fun everySurfaceUsesThePalette() {
        val sources = root.listFiles()!!.filter { it.isDirectory }.flatMap { module ->
            File(module, "src/main").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && checked(it) }?.toList().orEmpty()
        }
        assertTrue("Source inventory was empty", sources.isNotEmpty())
        assertEquals("Colour literals outside the palette", emptyList<String>(), sources.flatMap(::violations))
    }

    @Test fun detectorRecognizesCodeAndXmlButNotCharacterEntities() {
        listOf("Color.White", "Color.BLACK", "Color.rgb(1, 2, 3)", "Color(0xff000000)",
            "Color(argb)", "setTextColor(-1)", "paint.color = 16777215", "android.R.color.white",
            "android:textColor=\"#777777\"", "@android:color/white").forEach { assertTrue(it, literal.containsMatchIn(it)) }
        assertTrue(literal.containsMatchIn(normalizeAliases("import android.graphics.Color as AndroidColor\nAndroidColor.WHITE")))
        assertFalse(literal.containsMatchIn("&#233;"))
        assertFalse(literal.containsMatchIn("@color/paper_light_ink"))
    }
}
