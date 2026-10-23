package com.kekih.simplefilemanager

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pure Android Views player — no Compose interop.
 * SurfaceView hardware overlay = smooth video without artifacts.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var isAudio = false
    private var userSeeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            finish()
            return
        }

        isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!isAudio) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        val audioUi = findViewById<View>(R.id.audio_ui)

        if (isAudio) {
            playerView?.visibility = View.GONE
            audioUi.visibility = View.VISIBLE
            setupAudioUi(file.name)
        } else {
            playerView?.visibility = View.VISIBLE
            audioUi.visibility = View.GONE
        }

        val uri = resolveUri(file)
        player = buildPlayer().also { exo ->
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = true

            if (!isAudio) {
                playerView?.player = exo
            }

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && isAudio) {
                        findViewById<TextView>(R.id.time_total)?.text =
                            formatTime(exo.duration.coerceAtLeast(0))
                    }
                    if (state == Player.STATE_ENDED) {
                        exo.seekTo(0)
                        exo.pause()
                        updatePlayPauseButton(false)
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    updatePlayPauseButton(playing)
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Try software decoder fallback once
                    android.widget.Toast.makeText(
                        this@PlayerActivity,
                        "Ошибка воспроизведения: ${error.message?.take(80)}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }

    private fun resolveUri(file: File): Uri {
        return try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    private fun buildPlayer(): ExoPlayer {
        // Prefer hardware, fall back to software — reduces artifacts on weak GPUs
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        // Smaller, tighter buffers = less lag / memory pressure on phones
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 5_000,
                /* maxBufferMs */ 20_000,
                /* bufferForPlaybackMs */ 1_000,
                /* bufferForPlaybackAfterRebufferMs */ 2_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()
    }

    private fun setupAudioUi(fileName: String) {
        findViewById<TextView>(R.id.audio_title)?.text = fileName

        findViewById<ImageButton>(R.id.btn_back_audio)?.setOnClickListener { finish() }

        val playPause = findViewById<ImageButton>(R.id.btn_play_pause)
        playPause?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
        }

        val seekBar = findViewById<SeekBar>(R.id.seek_bar)
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = player ?: return
                val dur = p.duration
                if (dur > 0) {
                    val pos = progress * dur / 1000
                    findViewById<TextView>(R.id.time_current)?.text = formatTime(pos)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val p = player ?: return
                val dur = p.duration
                if (dur > 0 && sb != null) {
                    p.seekTo(sb.progress * dur / 1000)
                }
            }
        })
    }

    private fun updateProgress() {
        if (userSeeking) return
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition.coerceAtLeast(0)
        if (dur > 0) {
            findViewById<SeekBar>(R.id.seek_bar)?.progress =
                ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
            findViewById<TextView>(R.id.time_total)?.text = formatTime(dur)
        }
        findViewById<TextView>(R.id.time_current)?.text = formatTime(pos)
    }

    private fun updatePlayPauseButton(playing: Boolean) {
        if (!isAudio) return
        findViewById<ImageButton>(R.id.btn_play_pause)?.setImageResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    override fun onStart() {
        super.onStart()
        if (isAudio) handler.post(progressRunnable)
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(progressRunnable)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressRunnable)
        playerView?.player = null
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_IS_AUDIO = "is_audio"

        fun formatTime(ms: Long): String {
            if (ms <= 0) return "0:00"
            val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
            val m = totalSec / 60
            val s = totalSec % 60
            return "%d:%02d".format(m, s)
        }
    }
}
