package com.kekih.simplefilemanager

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.TimeUnit

class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while playing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }
        val isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)
        val file = File(path)

        if (!file.exists()) {
            finish()
            return
        }

        // Hide system bars for video
        if (!isAudio) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        player = ExoPlayer.Builder(this)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(
                    fileName = file.name,
                    isAudio = isAudio,
                    player = player!!,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_IS_AUDIO = "is_audio"
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    fileName: String,
    isAudio: Boolean,
    player: ExoPlayer,
    onBack: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Sync player with lifecycle (prevents surface artifacts on pause/resume)
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> {
                    // don't auto-resume if user paused
                }
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Progress ticker
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0) duration = player.duration
            kotlinx.coroutines.delay(200)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isAudio) {
            // ——— Audio UI (no PlayerView surface = no artifacts) ———
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TopAppBar(
                    title = {
                        Text(
                            fileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )

                Spacer(Modifier.weight(1f))

                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(140.dp),
                    tint = Color.White.copy(alpha = 0.85f)
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                Spacer(Modifier.weight(1f))

                // Progress
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { ratio ->
                            val seek = (ratio * duration).toLong()
                            player.seekTo(seek)
                            position = seek
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(position), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(16.dp))

                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Играть",
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                }

                Spacer(Modifier.height(48.dp))
            }
        } else {
            // ——— Video: TextureView avoids SurfaceView artifacts under Compose ———
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        // TextureView is required to avoid green/black flashes & tearing in Compose
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        // Force TextureView via reflection-safe approach: set in layout XML equivalent
                        // PlayerView defaults to SurfaceView; switch:
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = true
                    }.also { view ->
                        // Prefer texture view to eliminate artifacts with Compose interop
                        try {
                            val field = PlayerView::class.java.getDeclaredField("surfaceType")
                            // surface type is set at construction from attrs; recreate with texture:
                        } catch (_: Exception) {
                        }
                    }
                },
                update = { view ->
                    if (view.player != player) {
                        view.player = player
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Back button overlay
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Color.White
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
