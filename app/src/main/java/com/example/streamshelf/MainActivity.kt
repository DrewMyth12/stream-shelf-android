package tv.streamshelf.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF080B12)
private val Mint = Color(0xFF72E6C1)
private val Muted = Color(0xFF9AA5B5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StreamShelfApp() }
    }
}

@Composable
private fun StreamShelfApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val vault = remember { CredentialVault(context.applicationContext) }
    var base by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var tmdbKey by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<CatalogEntry?>(null) }
    var episodes by remember { mutableStateOf<List<SeriesEpisode>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vault.load()?.let {
            base = it.baseUrl
            username = it.username
            password = it.password
            tmdbKey = it.tmdbApiKey
            busy = true
            runCatching { withContext(Dispatchers.IO) { XtreamRepository(it).loadCatalog() } }
                .onSuccess { snapshot = it }
                .onFailure { error = it.message ?: "Could not load this provider catalog" }
            busy = false
        }
    }

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Ink)) {
            if (showSettings || snapshot == null) {
                ProviderSetup(
                    base = base, onBase = { base = it }, username = username, onUsername = { username = it },
                    password = password, onPassword = { password = it }, tmdbKey = tmdbKey, onTmdbKey = { tmdbKey = it },
                    error = error, busy = busy,
                    onConnect = {
                        error = null
                        val normalized = base.trim().trimEnd('/')
                        if (!(normalized.startsWith("https://") || normalized.startsWith("http://"))) {
                            error = "Enter a complete provider URL beginning with https:// or http://"
                        } else if (username.isBlank() || password.isBlank()) {
                            error = "Enter the provider username and password"
                        } else {
                            busy = true
                            scope.launch {
                                runCatching {
                                    val cfg = ProviderConfig(normalized, username.trim(), password, tmdbKey.trim())
                                    withContext(Dispatchers.IO) { XtreamRepository(cfg).loadCatalog() }.also { vault.save(cfg) }
                                }.onSuccess { snapshot = it; showSettings = false }
                                    .onFailure { error = it.message ?: "Provider connection failed" }
                                busy = false
                            }
                        }
                    },
                    onContinueWithoutAccount = {
                        snapshot = demoCatalog()
                        showSettings = false
                    },
                    onCancel = if (snapshot != null) ({ showSettings = false }) else null,
                )
            } else {
                LibraryScreen(
                    data = snapshot!!,
                    query = query,
                    onQuery = { query = it },
                    onSettings = { showSettings = true },
                    selectedSeries = selectedSeries,
                    episodes = episodes,
                    loadingEpisodes = busy,
                    onBackFromSeries = { selectedSeries = null; episodes = null },
                    onEnrich = {
                        val config = vault.load()
                        if (config?.tmdbApiKey.isNullOrBlank()) error = "Add your TMDB API key in Settings before matching titles"
                        else if (!busy) {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { TmdbRepository(config!!.tmdbApiKey).enrich(snapshot!!) } }
                                    .onSuccess { snapshot = it }
                                    .onFailure { error = it.message ?: "TMDB matching failed" }
                                busy = false
                            }
                        }
                    },
                    onOpenSeries = { entry ->
                        selectedSeries = entry
                        episodes = emptyList()
                        val config = vault.load()
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
                        if (url.isNullOrBlank()) error = "Series episode playback is being added next"
                        else activity?.startActivity(Intent(context, PlayerActivity::class.java).putExtra("stream_url", url).putExtra("title", entry.title))
                    },
                    error = error
                )
            }
        }
    }
}

private fun demoCatalog() = CatalogSnapshot(
    live = listOf(CatalogEntry("demo-live", "Demo News", "demo-live", "News", CatalogKind.LIVE, streamUrl = null)),
    movies = listOf(CatalogEntry("demo-movie", "Demo Movie", "demo-movies", "Movies", CatalogKind.MOVIE)),
    series = listOf(CatalogEntry("demo-series", "Demo Series", "demo-series", "Series", CatalogKind.SERIES))
)

@Composable
private fun ProviderSetup(
    base: String, onBase: (String) -> Unit, username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit, tmdbKey: String, onTmdbKey: (String) -> Unit,
    error: String?, busy: Boolean, onConnect: () -> Unit, onContinueWithoutAccount: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 42.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("STREAM SHELF", color = Mint, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Text("Connect your library", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Enter Xtream Codes details from your provider. Secrets are encrypted on this device; they are not included in the app build.", color = Muted, fontSize = 14.sp)
        Entry("Provider URL", base, onBase)
        Entry("Username", username, onUsername)
        Entry("Password", password, onPassword, secret = true)
        Entry("TMDB API key (optional for this build)", tmdbKey, onTmdbKey, secret = true)
        if (base.trimStart().startsWith("http://", ignoreCase = true)) {
            Text("This provider uses HTTP, which does not encrypt account details while in transit. Prefer HTTPS if your provider supports it.", color = Color(0xFFFFC078), fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionCard(if (busy) "CONNECTING…" else "CONNECT") { if (!busy) onConnect() }
            ActionCard("BROWSE DEMO") { if (!busy) onContinueWithoutAccount() }
            onCancel?.let { ActionCard("CANCEL", it) }
        }
        error?.let { Text(it, color = Color(0xFFFF8484), fontSize = 13.sp) }
        Text("TMDB catalog data requires your own API key and attribution. The key is optional until metadata matching is enabled.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Entry(label: String, value: String, onValue: (String) -> Unit, secret: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(0.72f).height(48.dp).background(Color(0xFF182231), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(220.dp))
        BasicTextField(value, onValue, singleLine = true, visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionCard(label: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(190.dp).height(48.dp),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(containerColor = Color(0xFF263447), focusedContainerColor = Mint)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LibraryScreen(
    data: CatalogSnapshot,
    onSettings: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    selectedSeries: CatalogEntry?,
    episodes: List<SeriesEpisode>?,
    loadingEpisodes: Boolean,
    onBackFromSeries: () -> Unit,
    onEnrich: () -> Unit,
    onOpenSeries: (CatalogEntry) -> Unit,
    onPlay: (CatalogEntry) -> Unit,
    error: String?,
) {
    val dataToShow = if (query.isBlank()) data else CatalogSnapshot(
        live = data.live.filter { it.title.contains(query, true) || it.category.contains(query, true) },
        movies = data.movies.filter { it.title.contains(query, true) || it.category.contains(query, true) || it.tmdbGenres.any { genre -> genre.contains(query, true) } },
        series = data.series.filter { it.title.contains(query, true) || it.category.contains(query, true) || it.tmdbGenres.any { genre -> genre.contains(query, true) } },
    )
    LazyColumn(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111A2A), Ink))).padding(start = 52.dp, top = 28.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Header(onSettings, selectedSeries != null, onBackFromSeries, onEnrich, query, onQuery) }
        if (selectedSeries != null) {
            item { Text(selectedSeries.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold) }
            if (loadingEpisodes) item { Text("Loading episodes…", color = Muted) }
            if (episodes?.isEmpty() == true && !loadingEpisodes) item { Text("No playable episodes were returned for this series.", color = Muted) }
            val bySeason = episodes.orEmpty().groupBy { it.season }.toSortedMap()
            bySeason.forEach { (season, rows) ->
                item { ShelfEpisodes(if (season > 0) "Season $season" else "Episodes", rows, onPlay) }
            }
        } else {
        item { Featured(onPlay, dataToShow.live.firstOrNull()) }
        if (error != null) item { Text(error, color = Color(0xFFFF8484), fontSize = 13.sp) }
        groupedShelves("Live TV", dataToShow.live, onPlay)
        groupedShelves("Movies", dataToShow.movies, onPlay)
        if (dataToShow.movies.any { it.tmdbGenres.isNotEmpty() }) {
            dataToShow.movies.filter { it.tmdbGenres.isNotEmpty() }.groupBy { it.year?.toString() ?: "Year unknown" }
                .toSortedMap(compareByDescending { it.toIntOrNull() ?: 0 }).forEach { (year, entries) ->
                    item { ShelfEntries("Movies • $year", entries, onPlay) }
                }
            dataToShow.movies.filter { it.tmdbGenres.isNotEmpty() }.groupBy { it.language ?: "Language unknown" }
                .toSortedMap().forEach { (language, entries) ->
                    item { ShelfEntries("Movies • $language", entries, onPlay) }
                }
            dataToShow.movies.filter { it.tmdbGenres.isNotEmpty() }.flatMap { entry -> entry.tmdbGenres.map { it to entry } }
                .groupBy({ it.first }, { it.second }).toSortedMap().forEach { (genre, entries) ->
                    item { ShelfEntries("Movies • $genre", entries, onPlay) }
                }
        }
        dataToShow.series.groupBy { it.category }.toSortedMap().forEach { (category, entries) ->
            item { ShelfEntries("Series • $category • ${entries.size}", entries, onOpenSeries) }
        }
        if (dataToShow.series.any { it.tmdbGenres.isNotEmpty() }) {
            dataToShow.series.filter { it.tmdbGenres.isNotEmpty() }.groupBy { it.year?.toString() ?: "Year unknown" }
                .toSortedMap(compareByDescending { it.toIntOrNull() ?: 0 }).forEach { (year, entries) ->
                    item { ShelfEntries("Series • $year", entries, onOpenSeries) }
                }
            dataToShow.series.filter { it.tmdbGenres.isNotEmpty() }.groupBy { it.language ?: "Language unknown" }
                .toSortedMap().forEach { (language, entries) ->
                    item { ShelfEntries("Series • $language", entries, onOpenSeries) }
                }
            dataToShow.series.filter { it.tmdbGenres.isNotEmpty() }.flatMap { entry -> entry.tmdbGenres.map { it to entry } }
                .groupBy({ it.first }, { it.second }).toSortedMap().forEach { (genre, entries) ->
                    item { ShelfEntries("Series • $genre", entries, onOpenSeries) }
                }
        }
        if (dataToShow.live.isEmpty() && dataToShow.movies.isEmpty() && dataToShow.series.isEmpty()) item { Text(if (query.isNotBlank()) "No titles match your search." else "Connected, but the provider returned no catalog entries.", color = Muted) }
        }
    }
}

private fun LazyListScope.groupedShelves(kind: String, rows: List<CatalogEntry>, onPlay: (CatalogEntry) -> Unit) {
    rows.groupBy { it.category }.toSortedMap().forEach { (category, entries) ->
        item { ShelfEntries("$kind • $category • ${entries.size}", entries, onPlay) }
    }
}

@Composable
private fun Header(onSettings: () -> Unit, inSeries: Boolean = false, onBack: () -> Unit = {}, onEnrich: () -> Unit = {}, query: String, onQuery: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 52.dp)) {
        Text("STREAM", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Text("SHELF", color = Mint, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.width(42.dp))
        if (inSeries) ActionCard("←  LIBRARY", onBack)
        else {
            BasicTextField(query, onQuery, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                modifier = Modifier.width(180.dp).background(Color(0xFF182231), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner -> if (query.isBlank()) Text("Search", color = Muted, fontSize = 13.sp); inner() })
            Spacer(Modifier.width(10.dp))
            ActionCard("MATCH TMDB", onEnrich)
        }
        Spacer(Modifier.weight(1f))
        ActionCard("SETTINGS", onClick = onSettings)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShelfEpisodes(title: String, entries: List<SeriesEpisode>, onPlay: (CatalogEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { "episode:${it.id}" }) { episode ->
                val entry = CatalogEntry(episode.id, episode.title, "${episode.season}", "S${episode.season}E${episode.episode}", CatalogKind.MOVIE, streamUrl = episode.streamUrl)
                Card(onClick = { onPlay(entry) }, modifier = Modifier.width(230.dp).height(84.dp),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.colors(containerColor = Color(0xFF202A39), focusedContainerColor = Mint)) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                        Text("Episode ${episode.episode}", color = Mint, fontSize = 11.sp)
                        Text(episode.title.take(32), color = Color.White, fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Featured(onPlay: (CatalogEntry) -> Unit, entry: CatalogEntry?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 52.dp)
            .height(142.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFF263C50), Color(0xFF142031), Color(0xFF101521))), RoundedCornerShape(18.dp))
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("YOUR LIBRARY, REIMAGINED", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(8.dp))
            Text("Find something worth watching.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("One catalog. Sorted your way.", color = Muted, fontSize = 14.sp)
        }
        if (entry != null) ActionCard("▶  PLAY LIVE TV") { onPlay(entry) }
        else Text("LIVE TV", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShelfEntries(title: String, entries: List<CatalogEntry>, onPlay: (CatalogEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries.take(50), key = { "${it.kind}:${it.id}" }) { entry ->
                Card(onClick = { onPlay(entry) }, modifier = Modifier.width(164.dp).height(236.dp),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.colors(containerColor = Color(0xFF202A39), focusedContainerColor = Mint)) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(model = entry.posterUrl, contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE080B12)))).padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title.take(30), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            val tags = listOfNotNull(entry.year?.toString(), entry.language, entry.tmdbGenres.takeIf { it.isNotEmpty() }?.take(2)?.joinToString(" · ")).joinToString(" · ")
                            Text((if (tags.isBlank()) entry.category else tags).take(34), color = Muted, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Shelf(title: String, entries: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries) { entry ->
                Card(
                    onClick = {},
                    modifier = Modifier.width(150.dp).height(62.dp).focusable(),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.colors(containerColor = Color(0xFF202A39), focusedContainerColor = Mint)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                        Text(entry, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

