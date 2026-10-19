package tv.streamshelf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamUrl = intent.getStringExtra("stream_url") ?: run { finish(); return }
        val title = intent.getStringExtra("title").orEmpty()
        val exoPlayer = ExoPlayer.Builder(this).build().also {
            it.setMediaItem(MediaItem.fromUri(streamUrl))
            it.prepare()
            it.playWhenReady = true
            player = it
        }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { context -> PlayerView(context).apply { player = exoPlayer; this.useController = true; this.contentDescription = title } },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.player = exoPlayer }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

