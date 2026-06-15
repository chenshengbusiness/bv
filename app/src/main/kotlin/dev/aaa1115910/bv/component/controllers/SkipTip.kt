package dev.aaa1115910.bv.component.controllers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

@Composable
fun SkipTips(
    modifier: Modifier = Modifier,
    showBackToStart: Boolean,
    showSkipToNextEp: Boolean,
    showPreviewTip: Boolean,
    playNextCountdown: Int = -1,
    nextVideoTitle: String = "",
    showNextVideoPreview: Boolean = false,
    nextVideoCover: String? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Column 自动管理堆叠，出现/消失时其他 tip 平滑移动
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PlayerTip(
                show = showPreviewTip,
                text = "视频需付费，当前为试看片段",
                icon = Icons.Outlined.Info,
            )
            val skipText = buildString {
                append("即将播放")
                if (nextVideoTitle.isNotEmpty()) {
                    append("：$nextVideoTitle")
                }
                if (playNextCountdown in 1..3) {
                    append(" (${playNextCountdown}s)")
                }
            }
            PlayerTip(
                show = showSkipToNextEp || showNextVideoPreview,
                text = skipText,
                icon = Icons.Outlined.SkipNext,
                coverUrl = nextVideoCover
            )
            PlayerTip(
                show = showBackToStart,
                text = "从上次播放位置继续，按确认键从头播放",
                icon = Icons.Outlined.Replay,
            )
        }
    }
}

@Composable
fun PlayerTip(
    show: Boolean,
    text: String,
    icon: ImageVector,
    coverUrl: String? = null,
) {
    AnimatedVisibility(
        visible = show,
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(tween(400)),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(350)
        ) + fadeOut(tween(280))
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                .background(Color.Black.copy(alpha = 0.6f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(width = 80.dp, height = 45.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Preview
@Composable
private fun SkipTipsPreview() {
    SkipTips(
        showBackToStart = true,
        showSkipToNextEp = true,
        showPreviewTip = true
    )
}