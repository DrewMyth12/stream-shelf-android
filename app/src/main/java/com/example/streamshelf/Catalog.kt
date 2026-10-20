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
    val tmdbProviders: List<String> = emptyList(),
    val collection: String? = null,
    val availabilityUrl: String? = null,
)

data class ProviderConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val tmdbApiKey: String = "",
    val tmdbRegion: String = "IN",
)

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
        val plain = JSONObject().put("base", config.baseUrl).put("user", config.username).put("pass", config.password)
            .put("tmdb", config.tmdbApiKey).put("region", config.tmdbRegion).toString()
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
        ProviderConfig(json.getString("base"), json.getString("user"), json.getString("pass"), json.optString("tmdb"), json.optString("region", "IN"))
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
class TmdbRepository(
    private val apiKey: String,
    private val region: String = "IN",
    private val metadataCache: TmdbMetadataCache? = null,
) {
    suspend fun enrich(snapshot: CatalogSnapshot, limitPerKind: Int = 60): CatalogSnapshot = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext snapshot
        val movieGenres = fetchGenres("movie")
        val tvGenres = fetchGenres("tv")
        suspend fun enrichRows(rows: List<CatalogEntry>, type: String, genres: Map<Int, String>): List<CatalogEntry> =
            rows.mapIndexed { index, row ->
                if (index >= limitPerKind || row.tmdbId != null && row.tmdbGenres.isNotEmpty() && row.tmdbProviders.isNotEmpty()) row
                else runCatching { enrichEntry(row, type, genres) }.getOrDefault(row)
            }
        CatalogSnapshot(
            live = snapshot.live,
            movies = enrichRows(snapshot.movies, "movie", movieGenres),
            series = enrichRows(snapshot.series, "tv", tvGenres),
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

    private suspend fun enrichEntry(entry: CatalogEntry, type: String, genres: Map<Int, String>): CatalogEntry {
        metadataCache?.get(entry.kind, entry.id, region)?.let { return entry.applyMetadata(it) }
        val match = bestMatch(entry, type) ?: return entry
        val tmdbId = match.optInt("id").takeIf { it > 0 } ?: return entry
        val objectPath = if (type == "movie") "/movie/$tmdbId" else "/tv/$tmdbId"
        val details = runCatching { tmdbObject(objectPath) }.getOrDefault(JSONObject())
        val watchData = runCatching { tmdbObject("$objectPath/watch/providers") }.getOrDefault(JSONObject())
        val regionalProviders = watchData.optJSONObject("results")?.optJSONObject(region.uppercase())
        val providers = listOf("flatrate", "free", "ads", "rent", "buy")
            .flatMap { regionalProviders?.optJSONArray(it).objectRows().orEmpty() }
            .map { it.optString("provider_name") }.filter { it.isNotBlank() }.distinct().sorted()
        val date = match.optString(if (type == "movie") "release_date" else "first_air_date")
        val genreIds = match.optJSONArray("genre_ids") ?: JSONArray()
        val names = (0 until genreIds.length()).mapNotNull { genres[genreIds.optInt(it)] }
        val poster = match.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" }
        val metadata = CatalogMetadata(
            tmdbId = tmdbId,
            posterUrl = poster,
            year = date.take(4).toIntOrNull(),
            language = match.optString("original_language").takeIf { it.isNotBlank() },
            tmdbGenres = names,
            overview = match.optString("overview").takeIf { it.isNotBlank() },
            tmdbProviders = providers,
            collection = details.optJSONObject("belongs_to_collection")?.optString("name")?.takeIf { !it.isNullOrBlank() },
            availabilityUrl = regionalProviders?.optString("link")?.takeIf { it.startsWith("https://") },
        )
        metadataCache?.put(entry.kind, entry.id, region, metadata)
        return entry.applyMetadata(metadata)
    }

    private fun CatalogEntry.applyMetadata(metadata: CatalogMetadata) = copy(
            posterUrl = metadata.posterUrl ?: posterUrl,
            year = metadata.year ?: year,
            language = metadata.language ?: language,
            tmdbId = metadata.tmdbId,
            tmdbGenres = metadata.tmdbGenres,
            overview = metadata.overview ?: overview,
            tmdbProviders = metadata.tmdbProviders,
            collection = metadata.collection,
            availabilityUrl = metadata.availabilityUrl,
        )

    private fun tmdbObject(path: String): JSONObject {
        val connection = URL("https://api.themoviedb.org/3$path?api_key=${Uri.encode(apiKey)}&region=${Uri.encode(region.uppercase())}").openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("TMDB request failed (${connection.responseCode})")
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    private fun JSONArray?.objectRows(): List<JSONObject> = this?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()

    suspend fun bestMatch(entry: CatalogEntry, requestedType: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || entry.kind == CatalogKind.LIVE) return@withContext null
        val type = requestedType ?: if (entry.kind == CatalogKind.MOVIE) "movie" else "tv"
        val query = Uri.encode(entry.title)
        val year = entry.year?.let { if (type == "movie") "&year=$it" else "&first_air_date_year=$it" }.orEmpty()
        val endpoint = if (type == "movie") "search/movie" else "search/tv"
        val connection = URL("https://api.themoviedb.org/3/$endpoint?api_key=${Uri.encode(apiKey)}&query=$query$year&include_adult=false&region=${Uri.encode(region.uppercase())}").openConnection() as HttpURLConnection
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

    private fun normalize(value: String): String {
        val cleaned = value
            .replace(Regex("[\\[(](19|20)\\d{2}[\\])]"), " ")
            .replace(Regex("\\b(19|20)\\d{2}\\b\\s*$"), " ")
            .replace(Regex("\\b(2160p|1080p|720p|4k|uhd|fhd|hevc|h\\.?265|h\\.?264|x265|x264|web[- ]?dl|webrip|bluray|brrip|hdr10?|10bit|dubbed|subbed|multi[- ]?audio)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bS\\d{1,2}(?:E\\d{1,3})?\\b", RegexOption.IGNORE_CASE), " ")
        return cleaned.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val aa = a.split(' ').toSet()
        val bb = b.split(' ').toSet()
        val overlap = aa.intersect(bb).size.toDouble()
        return (2 * overlap / (aa.size + bb.size)) * (if (a.contains(b) || b.contains(a)) 1.0 else 0.9)
    }
}

data class CatalogMetadata(
    val tmdbId: Int,
    val posterUrl: String?,
    val year: Int?,
    val language: String?,
    val tmdbGenres: List<String>,
    val overview: String?,
    val tmdbProviders: List<String>,
    val collection: String?,
    val availabilityUrl: String?,
)

/** Small on-device cache avoids repeating metadata lookups every time the app opens. */
class TmdbMetadataCache(context: Context) {
    private val prefs = context.getSharedPreferences("tmdb_metadata_cache", Context.MODE_PRIVATE)
    fun get(kind: CatalogKind, id: String, region: String): CatalogMetadata? = runCatching {
        val json = prefs.getString(key(kind, id, region), null)?.let(::JSONObject) ?: return null
        if (System.currentTimeMillis() - json.optLong("saved") > 30L * 24 * 60 * 60 * 1000) return null
        CatalogMetadata(json.getInt("id"), json.optString("poster").ifBlank { null }, json.optInt("year").takeIf { it > 0 },
            json.optString("language").ifBlank { null }, json.getJSONArray("genres").strings(), json.optString("overview").ifBlank { null },
            json.getJSONArray("providers").strings(), json.optString("collection").ifBlank { null }, json.optString("availabilityUrl").ifBlank { null })
    }.getOrNull()
    fun put(kind: CatalogKind, id: String, region: String, metadata: CatalogMetadata) {
        val json = JSONObject().put("saved", System.currentTimeMillis()).put("id", metadata.tmdbId)
            .put("poster", metadata.posterUrl).put("year", metadata.year ?: 0).put("language", metadata.language)
            .put("genres", JSONArray(metadata.tmdbGenres)).put("overview", metadata.overview)
            .put("providers", JSONArray(metadata.tmdbProviders)).put("collection", metadata.collection).put("availabilityUrl", metadata.availabilityUrl)
        prefs.edit().putString(key(kind, id, region), json.toString()).apply()
    }
    private fun key(kind: CatalogKind, id: String, region: String) = "${region.uppercase()}:${kind.name}:$id"
    private fun JSONArray.strings() = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}

