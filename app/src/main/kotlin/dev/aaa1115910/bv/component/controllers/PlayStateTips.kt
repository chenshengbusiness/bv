package dev.aaa1115910.bv.component.controllers

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.aaa1115910.bv.ui.theme.BVTheme
import io.github.g0dkar.qrcode.QRCode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@Composable
fun PlayStateTips(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isError: Boolean,
    errorMessage: String? = null
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        if (!isPlaying && !isBuffering && !isError) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                PauseIcon()
            }
        }
        if (isBuffering && !isError) {
            BufferingTip(
                modifier = Modifier
                    .align(Alignment.Center),
                speed = ""
            )
        }
        if (isError) {
            PlayErrorTip(
                modifier = Modifier.align(Alignment.Center),
                errorMessage = errorMessage
            )
        }
    }
}

@Composable
fun OptimizationStatusQrCode() {
    val info = remember {
        """
        === 极致优化状态 ===
        Smart GC: ${dev.aaa1115910.bv.util.Prefs.enableSmartGcBeforePlay}
        Smart Quality: ${dev.aaa1115910.bv.util.Prefs.enableSmartHighestQuality}
        Dynamic LoadControl: ${dev.aaa1115910.bv.util.Prefs.enableDynamicLoadControl}
        Tunneling: ${dev.aaa1115910.bv.util.Prefs.enableTunneling}
        High Priority: ${dev.aaa1115910.bv.util.Prefs.enableMediaCodecHighPriority}
        """.trimIndent()
    }
    
    val qrImage = remember(info) {
        try {
            val output = ByteArrayOutputStream()
            QRCode(info).render(margin = 2).writeImage(output)
            val input = ByteArrayInputStream(output.toByteArray())
            BitmapFactory.decodeStream(input).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    
    qrImage?.let {
        Surface(
            colors = SurfaceDefaults.colors(containerColor = Color.Black.copy(0.5f)),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(12.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "扫码查看优化状态",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Image(
                    bitmap = it,
                    contentDescription = "Optimization Info QR",
                    modifier = Modifier.size(42.dp).clip(MaterialTheme.shapes.extraSmall)
                )
            }
        }
    }
}

@Composable
fun PauseIcon(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        colors = SurfaceDefaults.colors(
            containerColor = Color.Black.copy(0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Icon(
            modifier = Modifier
                .padding(12.dp, 4.dp)
                .size(50.dp),
            imageVector = Icons.Rounded.Pause,
            contentDescription = null,
            tint = Color.White
        )
    }
}

@Composable
fun BufferingTip(
    modifier: Modifier = Modifier,
    speed: String
) {
    Surface(
        modifier = modifier,
        colors = SurfaceDefaults.colors(
            containerColor = Color.Black.copy(0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(36.dp)
                    .padding(8.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
            Text(
                modifier = Modifier,
                text = "缓冲中...$speed",
                fontSize = 24.sp
            )
        }
    }
}

@Composable
fun PlayErrorTip(
    modifier: Modifier = Modifier,
    errorMessage: String?
) {
    val qrImage = remember(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            try {
                val output = ByteArrayOutputStream()
                val content = if (errorMessage.length > 1200) errorMessage.take(1200) + "..." else errorMessage
                QRCode(content).render().writeImage(output)
                val input = ByteArrayInputStream(output.toByteArray())
                BitmapFactory.decodeStream(input).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    Surface(
        modifier = modifier,
        colors = SurfaceDefaults.colors(
            containerColor = Color.Black.copy(0.7f)
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(360.dp)
            ) {
                Text(
                    text = "播放器正在抽风",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = " _(:з」∠)_",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "错误原因：",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Text(
                    text = errorMessage ?: "未知错误",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            qrImage?.let {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = it,
                            contentDescription = "Error QR Code",
                            modifier = Modifier.size(140.dp)
                        )
                    }
                    Text(
                        text = "手机扫码复制完整错误",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun PauseIconPreview() {
    BVTheme {
        Box(modifier = Modifier.padding(10.dp)) {
            PauseIcon()
        }
    }
}

@Preview
@Composable
private fun BufferingTipPreview() {
    BVTheme {
        BufferingTip(
            modifier = Modifier.padding(10.dp),
            speed = ""
        )
    }
}

@Preview
@Composable
private fun PlayErrorTipPreview() {
    BVTheme {
        PlayErrorTip(errorMessage = "This is a test error.")
    }
}