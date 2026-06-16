package dev.aaa1115910.bv.player

data class VideoPlayerOptions(
    val userAgent: String? = null,
    val referer: String? = null,
    val enableFfmpegAudioRenderer: Boolean,
    val enableSoftwareVideoDecoder: Boolean,
    val enableDynamicLoadControl: Boolean = false,
    val enableTunneling: Boolean = false,
    val enableMediaCodecHighPriority: Boolean = false
)