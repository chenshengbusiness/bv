package dev.aaa1115910.bv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.aaa1115910.bv.player.impl.exo.ExoMediaPlayer

@OptIn(UnstableApi::class)
@Composable
fun BvVideoPlayer(
    modifier: Modifier = Modifier,
    videoPlayer: AbstractVideoPlayer?,
) {
    if (videoPlayer is ExoMediaPlayer) {
        var isFirstFrameRendered by remember { mutableStateOf(false) }

        DisposableEffect(videoPlayer) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    isFirstFrameRendered = true
                }
            }
            videoPlayer.mPlayer?.addListener(listener)
            onDispose {
                videoPlayer.mPlayer?.removeListener(listener)
            }
        }

        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = videoPlayer.mPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                        useController = false
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { playerView ->
                    playerView.player = videoPlayer.mPlayer
                },
                onRelease = { playerView ->
                    playerView.player = null
                }
            )

            AnimatedVisibility(
                visible = !isFirstFrameRendered,
                exit = fadeOut(tween(500))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }
    }
}