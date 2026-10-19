package tv.streamshelf.app

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CatalogKind { LIVE, MOVIE, SERIES }

data class CatalogEntry(
    val id: String,
    val title: String,
    val categoryId: String,
    val category: String,
    val kind: CatalogKind,
    val posterUrl: String? = null,
    val year: Int? = null,
    val language: String? = null,
    val streamUrl: String? = null,
    val tmdbId: Int? = null,
    val tmdbGenres: List<String> = emptyList(),
    val overview: String? = null,
)

data class ProviderConfig(val baseUrl: String, val username: String, val password: String, val tmdbApiKey: String = "")

data class CatalogSnapshot(val live: List<CatalogEntry>, val movies: List<CatalogEntry>, val series: List<CatalogEntry>)

data class SeriesEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val streamUrl: String,
)

/** Xtream Codes API client. Only credentials entered in this app are used. */
class XtreamRepository(private val config: ProviderConfig) {
    suspend fun loadCatalog(): CatalogSnapshot = withContext(Dispatchers.IO) {
        val liveCats = getArray("get_live_categories").associate { it.optString("category_id") to it.optString("category_name") }
        val movieCats = getArray("get_vod_categories").associate { it.optString("category_id") to it.optString("category_name") }
        val seriesCats = getArray("get_series_categories").associate { it.optString("category_id") to it.optString("category_name") }
        val live = getArray("get_live_streams").mapNotNull { row ->
            row.entry(CatalogKind.LIVE, liveCats, streamPath = "live", extension = "ts")
        }
        val movies = getArray("get_vod_streams").mapNotNull { row ->
            val ext = row.optString("container_extension", "mp4").ifBlank { "mp4" }
            row.entry(CatalogKind.MOVIE, movieCats, streamPath = "movie", extension = ext)
        }
        val series = getArray("get_series").mapNotNull { row -> row.entry(CatalogKind.SERIES, seriesCats, streamPath = "series") }
        CatalogSnapshot(live, movies, series)
    }

    private fun JSONObject.entry(kind: CatalogKind, cats: Map<String, String>, streamPath: String, extension: String = ""): CatalogEntry? {
        val id = optString(if (kind == CatalogKind.SERIES) "series_id" else "stream_id").takeIf { it.isNotBlank() } ?: return null
        val catId = optString("category_id")
        val title = optString("name").ifBlank { optString("title") }
        if (title.isBlank()) return null
        val segment = listOf(config.username, config.password, id).joinToString("/") { Uri.encode(it) }
        val suffix = if (extension.isBlank()) "" else ".${Uri.encode(extension)}"
        val path = if (streamPath.isBlank()) segment + suffix else "$streamPath/$segment$suffix"
        return CatalogEntry(
            id = id, title = title, categoryId = catId, category = cats[catId] ?: "Uncategorized",
            kind = kind, posterUrl = optString("stream_icon").takeIf { it.startsWith("http") },
            year = optString("year").toIntOrNull(), language = optString("language").takeIf { it.isNotBlank() },
            streamUrl = if (kind == CatalogKind.SERIES) null else "${config.baseUrl.trimEnd('/')}/$path",
            tmdbId = optInt("tmdb").takeIf { it > 0 }
        )
    }

    suspend fun loadEpisodes(seriesId: String): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        val url = apiUrl("get_series_info", "&series_id=${Uri.encode(seriesId)}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val seasons = json.optJSONObject("episodes") ?: JSONObject()
            val episodes = mutableListOf<SeriesEpisode>()
            val seasonKeys = seasons.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            for (seasonKey in seasonKeys) {
                val seasonRows = seasons.optJSONArray(seasonKey) ?: continue
                for (index in 0 until seasonRows.length()) {
                    val row = seasonRows.optJSONObject(index) ?: continue
                    val id = row.optString("id").ifBlank { row.optString("episode_id") }
                    if (id.isBlank()) continue
                    val extension = row.optString("container_extension", "mp4").ifBlank { "mp4" }
                    val path = listOf(config.username, config.password, id).joinToString("/") { Uri.encode(it) }
                    episodes += SeriesEpisode(
                        id = id,
                        title = row.optString("title").ifBlank { row.optString("name", "Episode ${index + 1}") },
                        season = seasonKey.toIntOrNull() ?: 0,
                        episode = row.optInt("episode_num", index + 1),
                        streamUrl = "${config.baseUrl.trimEnd('/')}/series/$path.${Uri.encode(extension)}",
                    )
                }
            }
            episodes.sortedWith(compareBy<SeriesEpisode> { it.season }.thenBy { it.episode })
        } finally {
            connection.disconnect()
        }
    }

    private fun getArray(action: String): List<JSONObject> {
        val url = apiUrl(action)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            connection.inputStream.bufferedReader().use { reader ->
                val json = reader.readText()
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun apiUrl(action: String, extra: String = ""): URL = URL(
        "${config.baseUrl.trimEnd('/')}/player_api.php?username=${Uri.encode(config.username)}&password=${Uri.encode(config.password)}&action=$action$extra"
    )
}

/** AES-GCM vault backed by a non-exportable Android Keystore key. */
class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("provider_vault", Context.MODE_PRIVATE)
    private val alias = "stream-shelf-provider-v1"

    fun save(config: ProviderConfig) {
        val plain = JSONObject().put("base", config.baseUrl).put("user", config.username).put("pass", config.password).put("tmdb", config.tmdbApiKey).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString("provider", "$iv:$data").commit()) { "Unable to save encrypted provider settings" }
    }

    fun load(): ProviderConfig? = runCatching {
        val parts = prefs.getString("provider", null)?.split(":", limit = 2) ?: return null
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        ProviderConfig(json.getString("base"), json.getString("user"), json.getString("pass"), json.optString("tmdb"))
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

/** A bounded search helper. Matching is conservative and leaves uncertain results unmatched. */
class TmdbRepository(private val apiKey: String) {
    suspend fun enrich(snapshot: CatalogSnapshot): CatalogSnapshot = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext snapshot
        val movieGenres = fetchGenres("movie")
        val tvGenres = fetchGenres("tv")
        suspend fun enrichRows(rows: List<CatalogEntry>, genres: Map<Int, String>): List<CatalogEntry> =
            rows.mapIndexed { index, row ->
                if (index >= 15 || row.tmdbGenres.isNotEmpty()) row else enrichEntry(row, genres)
            }
        CatalogSnapshot(
            live = snapshot.live,
            movies = enrichRows(snapshot.movies, movieGenres),
            series = enrichRows(snapshot.series, tvGenres),
        )
    }

    private suspend fun fetchGenres(type: String): Map<Int, String> = withContext(Dispatchers.IO) {
        val connection = URL("https://api.themoviedb.org/3/genre/$type/list?api_key=${Uri.encode(apiKey)}").openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            val rows = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).optJSONArray("genres") ?: JSONArray() }
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.associate { it.optInt("id") to it.optString("name") }
        } finally { connection.disconnect() }
    }

    private suspend fun enrichEntry(entry: CatalogEntry, genres: Map<Int, String>): CatalogEntry {
        val match = bestMatch(entry) ?: return entry
        val date = match.optString(if (entry.kind == CatalogKind.MOVIE) "release_date" else "first_air_date")
        val genreIds = match.optJSONArray("genre_ids") ?: JSONArray()
        val names = (0 until genreIds.length()).mapNotNull { genres[genreIds.optInt(it)] }
        val poster = match.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" }
        return entry.copy(
            posterUrl = poster ?: entry.posterUrl,
            year = date.take(4).toIntOrNull() ?: entry.year,
            language = match.optString("original_language").takeIf { it.isNotBlank() } ?: entry.language,
            tmdbId = match.optInt("id").takeIf { it > 0 } ?: entry.tmdbId,
            tmdbGenres = names,
            overview = match.optString("overview").takeIf { it.isNotBlank() },
        )
    }

    suspend fun bestMatch(entry: CatalogEntry): JSONObject? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || entry.kind == CatalogKind.LIVE) return@withContext null
        val type = if (entry.kind == CatalogKind.MOVIE) "movie" else "tv"
        val query = Uri.encode(entry.title)
        val year = entry.year?.let { if (type == "movie") "&year=$it" else "&first_air_date_year=$it" }.orEmpty()
        val connection = URL("https://api.themoviedb.org/3/search/$type?api_key=${Uri.encode(apiKey)}&query=$query$year&include_adult=false").openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            val result = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).optJSONArray("results") ?: JSONArray() }
            val target = normalize(entry.title)
            (0 until result.length()).mapNotNull { result.optJSONObject(it) }
                .map { candidate -> candidate to similarity(target, normalize(candidate.optString(if (type == "movie") "title" else "name"))) }
                .filter { (_, score) -> score >= 0.82 }
                .maxByOrNull { it.second }?.first
        } finally { connection.disconnect() }
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val aa = a.split(' ').toSet()
        val bb = b.split(' ').toSet()
        val overlap = aa.intersect(bb).size.toDouble()
        return (2 * overlap / (aa.size + bb.size)) * (if (a.contains(b) || b.contains(a)) 1.0 else 0.9)
    }
}

