package com.gezimos.katapult.util

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class IconCollection(val id: String) {
    SIMPLE("simple-icons"), TABLER("tabler-icons"), MATERIAL("material-icons");

    companion object {
        fun fromId(id: String): IconCollection = entries.first { it.id == id }
    }
}

data class CatalogueIcon(
    val id: String,
    val collection: IconCollection,
    val name: String,
    val label: String,
    val category: String,
    val asset: String,
    val sha256: String,
    val searchText: String,
)

/** One immutable owner of the curated IDs, searches and exact identity mappings. */
class IconCatalogue private constructor(
    val icons: List<CatalogueIcon>,
    private val packages: Map<String, String>,
    private val components: Map<String, String>,
) {
    private val byId = icons.associateBy { it.id }

    fun icon(id: String): CatalogueIcon? = byId[id]

    fun automaticIcon(packageName: String, activityClass: String): String? {
        val activity = if (activityClass.startsWith('.')) packageName + activityClass else activityClass
        return components["$packageName/$activity"] ?: packages[packageName]
    }

    fun search(collection: IconCollection, query: String): List<CatalogueIcon> {
        val terms = normalize(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return icons.filter { icon -> icon.collection == collection && terms.all { it in icon.searchText } }
    }

    companion object {
        private const val CATALOGUE_SHA256 = "b57f328dd4feb39d174b514c856aaf464615de51790a2004f61d1b61b4160e3d"
        @Volatile private var instance: IconCatalogue? = null

        fun load(context: Context): IconCatalogue = instance ?: synchronized(this) {
            instance ?: parse(context).also { instance = it }
        }

        private fun parse(context: Context): IconCatalogue {
            val bytes = context.assets.open("icons/catalogue.json").use { it.readBytes() }
            require(sha256(bytes) == CATALOGUE_SHA256) { "Curated icon catalogue hash mismatch" }
            val document = JSONObject(bytes.toString(Charsets.UTF_8))
            require(document.getString("format") == "kotatsu-icon-catalogue" && document.getInt("schema") == 1)
            require(document.getLong("selectionRevision") == 3730L)
            val rows = document.getJSONArray("entries")
            val icons = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val collection = IconCollection.fromId(row.getString("collection"))
                val id = row.getString("id")
                val expectedPrefix = when (collection) {
                    IconCollection.SIMPLE -> "simple-icons:"
                    IconCollection.TABLER -> "tabler-icons:outline:"
                    IconCollection.MATERIAL -> "material-icons:outlined:"
                }
                require(id.startsWith(expectedPrefix) && id.removePrefix(expectedPrefix) == row.getString("name"))
                val asset = row.getString("asset")
                require(Regex("svg/[0-9a-f]{40}\\.svg").matches(asset))
                val digest = row.getString("sha256")
                require(Regex("[0-9a-f]{64}").matches(digest))
                CatalogueIcon(id, collection, row.getString("name"), row.getString("label"),
                    row.getString("category"), asset, digest, normalize(row.getString("search")))
            }
            require(icons.size == 2174 && icons.map { it.id }.toSet().size == icons.size)
            val counts = mapOf(IconCollection.SIMPLE to 843, IconCollection.TABLER to 1034, IconCollection.MATERIAL to 297)
            require(counts.all { (collection, count) -> icons.count { it.collection == collection } == count })
            val known = icons.associateBy { it.id }
            fun mappings(name: String): Map<String, String> {
                val input = document.getJSONObject(name)
                return input.keys().asSequence().associateWith { key ->
                    input.getString(key).also { id -> require(known[id]?.collection == IconCollection.SIMPLE) }
                }
            }
            return IconCatalogue(icons, mappings("packages"), mappings("components"))
        }

        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Size-aware bounded bitmap cache; no parent or application state reaches into it. */
object CatalogueIconRenderer {
    private val bitmaps = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun render(context: Context, icon: CatalogueIcon, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        val key = "${icon.sha256}:$sizePx"
        bitmaps.get(key)?.let { return it }
        val bytes = context.assets.open("icons/${icon.asset}").use { it.readBytes() }
        require(IconCatalogue.sha256(bytes) == icon.sha256) { "Curated icon payload mismatch: ${icon.id}" }
        val bitmap = bytes.inputStream().use { SvgRasterizer.rasterize(it, sizePx) } ?: return null
        bitmaps.put(key, bitmap)
        return bitmap
    }
}
