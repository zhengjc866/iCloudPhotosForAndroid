package com.icloudalbum.app.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.icloudalbum.app.App

/**
 * Streams an iCloud video with ExoPlayer.
 *
 * Plain Activity + PlayerView on purpose: media3's PlayerView owns a SurfaceView,
 * and the iCloud media URLs require the session Cookie header, so a custom data
 * source is wired up here instead of going through a generic player screen.
 */
class VideoPlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(playerView)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(24, 18, 24, 18)
            text = "正在加载视频…"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        }
        root.addView(status)
        setContentView(root)

        if (title.isNotBlank()) this.title = title
        if (subtitle.isNotBlank()) status.text = "正在加载视频…\n$subtitle"
        if (url.isBlank()) {
            status.text = "没有可播放的地址"
            return
        }

        val cookie = (application as App).container.sessionStore.cookieHeader.orEmpty()
        val headers = linkedMapOf(
            "Cookie" to cookie,
            "Origin" to "https://www.icloud.com",
            "Referer" to "https://www.icloud.com/",
            "User-Agent" to UA,
            "Accept" to "*/*",
        )
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(UA)
        val mediaSource = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(url))

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.setMediaSource(mediaSource)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        status.visibility = android.view.View.VISIBLE
                        status.text = "缓冲中…"
                    }
                    Player.STATE_READY -> status.visibility = android.view.View.GONE
                    Player.STATE_ENDED -> status.visibility = android.view.View.VISIBLE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                status.visibility = android.view.View.VISIBLE
                status.text = "播放失败（${error.errorCodeName}）\n可以在相册里长按该视频保存原片"
            }
        })
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        playerView.player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"

        fun intent(context: Context, url: String, title: String, subtitle: String): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
    }
}
