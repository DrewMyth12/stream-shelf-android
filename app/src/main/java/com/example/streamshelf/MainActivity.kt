package tv.streamshelf.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF080B12)
private val Panel = Color(0xFF151F2E)
private val PanelRaised = Color(0xFF202D40)
private val Mint = Color(0xFF74E5C1)
private val Muted = Color(0xFFA2ADBC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StreamShelfApp() }
    }
}

private enum class ShelfTab(val label: String) {
    LIVE("LIVE TV"), MOVIES("MOVIES"), SERIES("SERIES")
}

private enum class SortOrder(val label: String) {
    TITLE("A–Z"), YEAR_NEWEST("YEAR ↓"), YEAR_OLDEST("YEAR ↑");
    fun next() = entries[(ordinal + 1) % entries.size]
}

private data class ShelfOptions(
    val providerCategories: Boolean = true,
    val streamingServices: Boolean = true,
    val years: Boolean = true,
    val languages: Boolean = true,
    val genres: Boolean = true,
    val collections: Boolean = true,
    val sortOrder: SortOrder = SortOrder.TITLE,
)

private class ShelfOptionsStore(context: Context) {
    private val prefs = context.getSharedPreferences("shelf_options", Context.MODE_PRIVATE)
    fun load() = ShelfOptions(
        providerCategories = prefs.getBoolean("categories", true),
        streamingServices = prefs.getBoolean("services", true),
        years = prefs.getBoolean("years", true),
        languages = prefs.getBoolean("languages", true),
        genres = prefs.getBoolean("genres", true),
        collections = prefs.getBoolean("collections", true),
        sortOrder = runCatching { SortOrder.valueOf(prefs.getString("sort", SortOrder.TITLE.name)!!) }.getOrDefault(SortOrder.TITLE),
    )
    fun save(value: ShelfOptions) {
        prefs.edit().putBoolean("categories", value.providerCategories).putBoolean("services", value.streamingServices)
            .putBoolean("years", value.years).putBoolean("languages", value.languages).putBoolean("genres", value.genres)
            .putBoolean("collections", value.collections).putString("sort", value.sortOrder.name).apply()
    }
}

@Composable
private fun StreamShelfApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val vault = remember { CredentialVault(context.applicationContext) }
    val metadataCache = remember { TmdbMetadataCache(context.applicationContext) }
    val optionsStore = remember { ShelfOptionsStore(context.applicationContext) }
    var base by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var tmdbKey by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("IN") }
    var savedConfig by remember { mutableStateOf<ProviderConfig?>(null) }
    var options by remember { mutableStateOf(optionsStore.load()) }
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var initialLoadComplete by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(ShelfTab.LIVE) }
    var selectedSeries by remember { mutableStateOf<CatalogEntry?>(null) }
    var episodes by remember { mutableStateOf<List<SeriesEpisode>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vault.load()?.let { config ->
            savedConfig = config
            base = config.baseUrl
            username = config.username
            password = config.password
            tmdbKey = config.tmdbApiKey
            region = config.tmdbRegion
            busy = true
            runCatching { withContext(Dispatchers.IO) { XtreamRepository(config).loadCatalog() } }
                .onSuccess { snapshot = it }
                .onFailure { error = it.message ?: "Could not load your provider catalog" }
            busy = false
        }
        initialLoadComplete = true
    }

    fun updateOptions(updated: ShelfOptions) {
        options = updated
        optionsStore.save(updated)
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Ink)) {
            if (!initialLoadComplete && savedConfig != null) {
                LoadingScreen()
            } else if (showSettings || snapshot == null) {
                SettingsScreen(
                    isFirstRun = snapshot == null,
                    base = base, onBase = { base = it }, username = username, onUsername = { username = it },
                    password = password, onPassword = { password = it }, tmdbKey = tmdbKey, onTmdbKey = { tmdbKey = it },
                    region = region, onRegion = { region = it.uppercase().filter(Char::isLetter).take(2) },
                    options = options, onOptions = ::updateOptions,
                    error = error, busy = busy,
                    onConnect = {
                        error = null
                        val normalized = base.trim().trimEnd('/')
                        val normalizedRegion = region.trim().uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "IN"
                        if (!(normalized.startsWith("https://") || normalized.startsWith("http://"))) error = "Enter a complete provider URL beginning with https:// or http://"
                        else if (username.isBlank() || password.isBlank()) error = "Enter the provider username and password"
                        else {
                            busy = true
                            scope.launch {
                                runCatching {
                                    val config = ProviderConfig(normalized, username.trim(), password, tmdbKey.trim(), normalizedRegion)
                                    withContext(Dispatchers.IO) { XtreamRepository(config).loadCatalog() }.also { vault.save(config); savedConfig = config }
                                }.onSuccess { snapshot = it; showSettings = false; selectedSeries = null }
                                    .onFailure { error = it.message ?: "Provider connection failed" }
                                busy = false
                            }
                        }
                    },
                    onSave = {
                        val prior = savedConfig
                        if (prior != null) {
                            val normalized = base.trim().trimEnd('/')
                            val normalizedRegion = region.trim().uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "IN"
                            if (!(normalized.startsWith("https://") || normalized.startsWith("http://"))) error = "Enter a complete provider URL beginning with https:// or http://"
                            else if (username.isBlank() || password.isBlank()) error = "Enter the provider username and password"
                            else {
                                val config = ProviderConfig(normalized, username.trim(), password, tmdbKey.trim(), normalizedRegion)
                                val providerChanged = config.baseUrl != prior.baseUrl || config.username != prior.username || config.password != prior.password
                                if (providerChanged) {
                                    busy = true
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { XtreamRepository(config).loadCatalog() } }
                                            .onSuccess { snapshot = it; vault.save(config); savedConfig = config; status = "Provider and settings updated."; showSettings = false }
                                            .onFailure { error = it.message ?: "Could not reconnect to the updated provider" }
                                        busy = false
                                    }
                                } else {
                                    vault.save(config)
                                    savedConfig = config
                                    status = "Settings saved. Choose MATCH TITLES to refresh TMDB groups."
                                    showSettings = false
                                }
                            }
                        }
                    },
                    onDemo = { snapshot = demoCatalog(); showSettings = false },
                    onCancel = if (snapshot != null) ({ showSettings = false }) else null,
                )
            } else {
                LibraryScreen(
                    data = snapshot!!,
                    activeTab = activeTab,
                    onTab = { activeTab = it; selectedSeries = null; episodes = null },
                    query = query, onQuery = { query = it },
                    options = options, onSort = { updateOptions(options.copy(sortOrder = options.sortOrder.next())) },
                    busy = busy, status = status, error = error,
                    onSettings = { showSettings = true; error = null },
                    onEnrich = {
                        val current = savedConfig ?: vault.load()
                        if (current?.tmdbApiKey.isNullOrBlank()) error = "Add a TMDB API key in Settings before matching titles"
                        else if (!busy) {
                            busy = true
                            error = null
                            status = "Matching up to 60 movies and 60 series. This can take a few minutes the first time."
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { TmdbRepository(current!!.tmdbApiKey, current.tmdbRegion, metadataCache).enrich(snapshot!!) }
                                }.onSuccess {
                                    val matched = it.movies.count { row -> row.tmdbId != null } + it.series.count { row -> row.tmdbId != null }
                                    snapshot = it
                                    status = "TMDB matching complete: $matched movie and series titles matched. Streaming availability by JustWatch."
                                }.onFailure { error = it.message ?: "TMDB matching failed"; status = null }
                                busy = false
                            }
                        }
                    },
                    onBackFromSeries = { selectedSeries = null; episodes = null },
                    selectedSeries = selectedSeries, episodes = episodes,
                    onOpenSeries = { entry ->
                        selectedSeries = entry
                        episodes = emptyList()
                        val config = savedConfig
                        if (config == null) error = "Reconnect your provider to load series episodes"
                        else {
                            busy = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { XtreamRepository(config).loadEpisodes(entry.id) } }
                                    .onSuccess { episodes = it }
                                    .onFailure { error = it.message ?: "Could not load episodes" }
                                busy = false
                            }
                        }
                    },
                    onPlay = { entry ->
                        val url = entry.streamUrl
                        if (url.isNullOrBlank()) error = "No playable stream URL is available for this item"
                        else activity?.startActivity(Intent(context, PlayerActivity::class.java).putExtra("stream_url", url).putExtra("title", entry.title))
                    },
                    onAvailability = { entry ->
                        val url = entry.availabilityUrl ?: entry.tmdbId?.let {
                            "https://www.themoviedb.org/${if (entry.kind == CatalogKind.MOVIE) "movie" else "tv"}/$it/watch?locale=${region.lowercase()}"
                        } ?: "https://www.themoviedb.org/"
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF141D2B), Ink))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("STREAM  SHELF", color = Mint, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("Loading your library…", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("This can take a moment while the TV reconnects to your provider.", color = Muted, fontSize = 12.sp)
        }
    }
}

private fun demoCatalog() = CatalogSnapshot(
    live = listOf(CatalogEntry("demo-live", "World News", "news", "News", CatalogKind.LIVE)),
    movies = listOf(CatalogEntry("demo-movie", "Demo Feature", "movies", "Movies", CatalogKind.MOVIE, year = 2024)),
    series = listOf(CatalogEntry("demo-series", "Demo Series", "series", "Series", CatalogKind.SERIES, year = 2023)),
)

@Composable
private fun SettingsScreen(
    isFirstRun: Boolean,
    base: String, onBase: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    tmdbKey: String, onTmdbKey: (String) -> Unit,
    region: String, onRegion: (String) -> Unit,
    options: ShelfOptions, onOptions: (ShelfOptions) -> Unit,
    error: String?, busy: Boolean,
    onConnect: () -> Unit, onSave: () -> Unit, onDemo: () -> Unit, onCancel: (() -> Unit)?,
) {
    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF141D2B), Ink))).padding(horizontal = 64.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("STREAM  SHELF", color = Mint, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp) }
        item { Text(if (isFirstRun) "Connect your library" else "Your library settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item { Text("A remote-friendly library for Live TV, Movies, and Series. Your provider login stays encrypted on this TV.", color = Muted, fontSize = 13.sp) }
        item { SettingsEntry("Provider URL", base, onBase) }
        if (base.trimStart().startsWith("http://", ignoreCase = true)) {
            item { Text("This provider uses HTTP. Account and playback requests are not encrypted while in transit; use HTTPS if your provider supports it.", color = Color(0xFFFFC078), fontSize = 11.sp) }
        }
        item { SettingsEntry("Username", username, onUsername) }
        item { SettingsEntry("Password", password, onPassword, secret = true) }
        item { Text("METADATA & STREAMING GROUPS", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        item { SettingsEntry("TMDB API key (optional)", tmdbKey, onTmdbKey, secret = true) }
        item { SettingsEntry("Availability region (2-letter code)", region, onRegion, maxLength = 2) }
        item { Text("TMDB matches title, poster, year, language, genre and collection. Streaming services are based on regional JustWatch availability. Matching checks the first 60 movies and 60 series each run; results are cached on this TV.", color = Muted, fontSize = 11.sp) }
        item { Text("CUSTOM HOME SHELVES", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp)) }
        item { SettingToggle("Provider categories", options.providerCategories) { onOptions(options.copy(providerCategories = !options.providerCategories)) } }
        item { SettingToggle("Streaming services (Netflix, Prime Video, and more)", options.streamingServices) { onOptions(options.copy(streamingServices = !options.streamingServices)) } }
        item { SettingToggle("Years", options.years) { onOptions(options.copy(years = !options.years)) } }
        item { SettingToggle("Languages", options.languages) { onOptions(options.copy(languages = !options.languages)) } }
        item { SettingToggle("Genres", options.genres) { onOptions(options.copy(genres = !options.genres)) } }
        item { SettingToggle("Collections", options.collections) { onOptions(options.copy(collections = !options.collections)) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SORT TITLES", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            ActionCard(options.sortOrder.label) { onOptions(options.copy(sortOrder = options.sortOrder.next())) }
        } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ActionCard(if (busy) "CONNECTING…" else if (isFirstRun) "CONNECT" else "SAVE & RETURN", enabled = !busy, onClick = if (isFirstRun) onConnect else onSave)
                if (isFirstRun) ActionCard("BROWSE DEMO", onClick = onDemo)
                onCancel?.let { ActionCard("CANCEL", onClick = it) }
            }
        }
        error?.let { message -> item { Text(message, color = Color(0xFFFF8585), fontSize = 13.sp) } }
        item { Text("TMDB metadata © TMDB. Streaming availability data provided by JustWatch. This app is not endorsed or certified by TMDB.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun SettingsEntry(label: String, value: String, onValue: (String) -> Unit, secret: Boolean = false, maxLength: Int = Int.MAX_VALUE) {
    Row(
        Modifier.fillMaxWidth(0.72f).height(44.dp).background(PanelRaised, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(240.dp))
        BasicTextField(
            value = value,
            onValueChange = { onValue(it.take(maxLength)) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingToggle(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(350.dp))
        ActionCard(if (enabled) "ON" else "OFF", onClick = onClick, width = 90.dp)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionCard(label: String, width: androidx.compose.ui.unit.Dp = 150.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.width(width).height(42.dp),
        shape = CardDefaults.shape(RoundedCornerShape(11.dp)),
        colors = CardDefaults.colors(containerColor = PanelRaised, focusedContainerColor = Mint),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun LibraryScreen(
    data: CatalogSnapshot,
    activeTab: ShelfTab, onTab: (ShelfTab) -> Unit,
    query: String, onQuery: (String) -> Unit,
    options: ShelfOptions, onSort: () -> Unit,
    busy: Boolean, status: String?, error: String?,
    onSettings: () -> Unit, onEnrich: () -> Unit,
    onBackFromSeries: () -> Unit, selectedSeries: CatalogEntry?, episodes: List<SeriesEpisode>?,
    onOpenSeries: (CatalogEntry) -> Unit, onPlay: (CatalogEntry) -> Unit, onAvailability: (CatalogEntry) -> Unit,
) {
    BackHandler(enabled = selectedSeries != null) { onBackFromSeries() }
    val entries = when (activeTab) {
        ShelfTab.LIVE -> data.live
        ShelfTab.MOVIES -> data.movies
        ShelfTab.SERIES -> data.series
    }
    val filtered = entries.filter { row ->
        query.isBlank() || row.title.contains(query, true) || row.category.contains(query, true) ||
            row.tmdbGenres.any { it.contains(query, true) } || row.tmdbProviders.any { it.contains(query, true) } ||
            row.year?.toString()?.contains(query, true) == true || row.language?.contains(query, true) == true ||
            row.collection?.contains(query, true) == true
    }
    val sortRows: (List<CatalogEntry>) -> List<CatalogEntry> = { rows ->
        when (options.sortOrder) {
            SortOrder.TITLE -> rows.sortedBy { it.title.lowercase() }
            SortOrder.YEAR_NEWEST -> rows.sortedWith(compareByDescending<CatalogEntry> { it.year ?: 0 }.thenBy { it.title.lowercase() })
            SortOrder.YEAR_OLDEST -> rows.sortedWith(compareBy<CatalogEntry> { it.year ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111A28), Ink))).padding(start = 46.dp, top = 22.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("STREAM", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("SHELF", color = Mint, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.width(28.dp))
                ShelfTab.entries.forEach { tab ->
                    val count = when (tab) { ShelfTab.LIVE -> data.live.size; ShelfTab.MOVIES -> data.movies.size; ShelfTab.SERIES -> data.series.size }
                    ActionCard("${tab.label}  $count", onClick = { onTab(tab) }, width = 154.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                ActionCard("SETTINGS", onClick = onSettings, width = 116.dp)
            }
        }
        if (selectedSeries != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard("← LIBRARY", onClick = onBackFromSeries)
                    Text(selectedSeries.title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (busy) item { Text("Loading episodes…", color = Muted, fontSize = 13.sp) }
            if (episodes?.isEmpty() == true && !busy) item { Text("No episodes were returned for this series.", color = Muted, fontSize = 13.sp) }
            episodes.orEmpty().groupBy { it.season }.toSortedMap().forEach { (season, rows) ->
                item { EpisodeShelf(if (season > 0) "SEASON $season" else "EPISODES", rows, onPlay) }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(end = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(360.dp).height(40.dp).background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 13.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) Text("Search this library", color = Muted, fontSize = 12.sp)
                        BasicTextField(query, onQuery, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp), modifier = Modifier.fillMaxWidth())
                    }
                    ActionCard("SORT  ${options.sortOrder.label}", onClick = onSort, width = 140.dp)
                    ActionCard(if (busy) "MATCHING…" else "MATCH TITLES", onClick = onEnrich, width = 144.dp, enabled = !busy)
                    Spacer(Modifier.weight(1f))
                    Text(if (activeTab == ShelfTab.LIVE) "CHANNELS" else "TITLES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            item { HeroPanel(activeTab, filtered.size) }
            error?.let { message -> item { Text(message, color = Color(0xFFFF8585), fontSize = 12.sp) } }
            status?.let { message -> item { Text(message, color = Mint, fontSize = 11.sp) } }
            item {
                Text(
                    when (activeTab) { ShelfTab.LIVE -> "LIVE TV"; ShelfTab.MOVIES -> "MOVIES"; ShelfTab.SERIES -> "SERIES" },
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                )
            }
            if (options.providerCategories) addGroups("PROVIDER CATEGORIES", filtered, { it.category }, sortRows, onOpenSeries, onPlay, activeTab)
            if (activeTab != ShelfTab.LIVE && options.streamingServices) {
                val services = filtered.flatMap { it.tmdbProviders }.distinct().sorted()
                services.take(16).forEach { service ->
                    val rows = filtered.filter { service in it.tmdbProviders }
                    item { EntryShelf(service.uppercase(), sortRows(rows), activeTab, onOpenSeries, onPlay) }
                }
            }
            if (activeTab != ShelfTab.LIVE && options.collections) {
                filtered.filter { !it.collection.isNullOrBlank() }.groupBy { it.collection!! }.toSortedMap().forEach { (name, rows) ->
                    item { EntryShelf("COLLECTION  •  ${name.uppercase()}", sortRows(rows), activeTab, onOpenSeries, onPlay) }
                }
            }
            if (activeTab != ShelfTab.LIVE && options.years) {
                filtered.filter { it.year != null }.groupBy { it.year!! }.toSortedMap(compareByDescending { it }).forEach { (year, rows) ->
                    item { EntryShelf("YEAR  •  $year", sortRows(rows), activeTab, onOpenSeries, onPlay) }
                }
            }
            if (activeTab != ShelfTab.LIVE && options.languages) {
                filtered.filter { !it.language.isNullOrBlank() }.groupBy { it.language!!.uppercase() }.toSortedMap().forEach { (language, rows) ->
                    item { EntryShelf("LANGUAGE  •  $language", sortRows(rows), activeTab, onOpenSeries, onPlay) }
                }
            }
            if (activeTab != ShelfTab.LIVE && options.genres) {
                filtered.flatMap { row -> row.tmdbGenres.map { it to row } }.groupBy({ it.first }, { it.second }).toSortedMap().forEach { (genre, rows) ->
                    item { EntryShelf("GENRE  •  ${genre.uppercase()}", sortRows(rows), activeTab, onOpenSeries, onPlay) }
                }
            }
            if (filtered.isNotEmpty()) item { EntryShelf("ALL ${activeTab.label}", sortRows(filtered), activeTab, onOpenSeries, onPlay) }
            if (filtered.isEmpty()) item { Text(if (query.isNotBlank()) "No titles match your search." else "No ${activeTab.label.lowercase()} were returned by the provider.", color = Muted, fontSize = 13.sp) }
            item {
                Row(Modifier.fillMaxWidth().padding(end = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TMDB metadata  •  Streaming availability by JustWatch", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    val linked = filtered.firstOrNull { !it.availabilityUrl.isNullOrBlank() } ?: entries.firstOrNull { !it.availabilityUrl.isNullOrBlank() }
                    if (linked != null) ActionCard("AVAILABILITY INFO", onClick = { onAvailability(linked) }, width = 155.dp)
                }
            }
        }
    }
}

private fun LazyListScope.addGroups(
    title: String,
    rows: List<CatalogEntry>,
    keyOf: (CatalogEntry) -> String,
    sortRows: (List<CatalogEntry>) -> List<CatalogEntry>,
    onOpenSeries: (CatalogEntry) -> Unit,
    onPlay: (CatalogEntry) -> Unit,
    tab: ShelfTab,
) {
    rows.groupBy(keyOf).toSortedMap().forEach { (group, entries) ->
        item { EntryShelf("$title  •  ${group.uppercase()}  •  ${entries.size}", sortRows(entries), tab, onOpenSeries, onPlay) }
    }
}

@Composable
private fun HeroPanel(tab: ShelfTab, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(end = 46.dp).height(126.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFF20364A), Color(0xFF172436), Panel)), RoundedCornerShape(18.dp))
            .padding(horizontal = 25.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("YOUR ENTERTAINMENT, ORGANIZED", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(when (tab) { ShelfTab.LIVE -> "Go straight to Live TV."; ShelfTab.MOVIES -> "Find your next movie."; ShelfTab.SERIES -> "Pick up a new series." }, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("$count ${if (tab == ShelfTab.LIVE) "channels" else "titles"}  •  Grouped for the way you watch", color = Muted, fontSize = 12.sp)
        }
        Text(when (tab) { ShelfTab.LIVE -> "◉  LIVE"; ShelfTab.MOVIES -> "▣  FILMS"; ShelfTab.SERIES -> "▤  SERIES" }, color = Mint, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EntryShelf(title: String, entries: List<CatalogEntry>, tab: ShelfTab, onOpenSeries: (CatalogEntry) -> Unit, onPlay: (CatalogEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = 46.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("${entries.size}", color = Muted, fontSize = 10.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            items(entries.take(36), key = { "${it.kind}:${it.id}:$title" }) { entry ->
                Card(
                    onClick = { if (tab == ShelfTab.SERIES) onOpenSeries(entry) else onPlay(entry) },
                    modifier = Modifier.width(152.dp).height(194.dp),
                    shape = CardDefaults.shape(RoundedCornerShape(11.dp)),
                    colors = CardDefaults.colors(containerColor = PanelRaised, focusedContainerColor = Mint),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (!entry.posterUrl.isNullOrBlank()) AsyncImage(model = entry.posterUrl, contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Column(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0080B12))))
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(entry.title.take(34), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            val tags = listOfNotNull(entry.year?.toString(), entry.language?.uppercase(), entry.tmdbGenres.firstOrNull()).joinToString("  •  ")
                            Text((if (tags.isBlank()) entry.category else tags).take(34), color = Muted, fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeShelf(title: String, episodes: List<SeriesEpisode>, onPlay: (CatalogEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(episodes, key = { "episode:${it.id}" }) { episode ->
                Card(
                    onClick = { onPlay(CatalogEntry(episode.id, episode.title, "${episode.season}", "S${episode.season}E${episode.episode}", CatalogKind.MOVIE, streamUrl = episode.streamUrl)) },
                    modifier = Modifier.width(224.dp).height(72.dp),
                    shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = CardDefaults.colors(containerColor = PanelRaised, focusedContainerColor = Mint),
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                        Text("EPISODE ${episode.episode}", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(episode.title.take(32), color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

