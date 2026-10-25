package com.icloudalbum.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时长文本，例如 "1:23" 或 "1:02:03"。
 * 0 或负数（普通照片）返回空串，方便调用方直接判断是否显示角标。
 */
fun formatDuration(seconds: Double): String {
    if (seconds <= 0.0) return ""
    val total = seconds.toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

/** 拍摄时间，例如 "2024年10月1日 08:05"。 */
fun formatTakenAt(millis: Long): String {
    if (millis <= 0L) return "时间未知"
    return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(millis))
}

/** 文件大小，例如 "2.4 MB"；未知时返回空串。 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.0f KB", kb)
}

/**
 * 下载/保存图标（material-icons-core 里没有 Download 图标，这里用 Canvas 画）。
 * 调用方通过 modifier 指定尺寸，例如 Modifier.size(24.dp)。
 */
@Composable
fun DownloadGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f
        val cx = w / 2f
        // 向下的箭头
        drawLine(
            color = tint,
            start = Offset(cx, h * 0.12f),
            end = Offset(cx, h * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(cx - w * 0.20f, h * 0.42f),
            end = Offset(cx, h * 0.63f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(cx + w * 0.20f, h * 0.42f),
            end = Offset(cx, h * 0.63f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 底部托盘
        drawLine(
            color = tint,
            start = Offset(w * 0.20f, h * 0.82f),
            end = Offset(w * 0.80f, h * 0.82f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.20f, h * 0.82f),
            end = Offset(w * 0.20f, h * 0.70f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.80f, h * 0.82f),
            end = Offset(w * 0.80f, h * 0.70f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 相册/照片占位图（没有可用的 PhotoLibrary 图标，用 Canvas 画一个相框 + 山峰 + 太阳）。
 * 调用方通过 modifier 指定尺寸，例如 Modifier.size(56.dp)。
 */
@Composable
fun PhotoGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.045f
        // 相框
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke, stroke),
            size = Size(w - stroke * 2f, h - stroke * 2f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(width = stroke),
        )
        // 太阳
        drawCircle(color = tint, radius = w * 0.065f, center = Offset(w * 0.32f, h * 0.32f))
        // 山峰
        val mountain = Path().apply {
            moveTo(w * 0.18f, h * 0.76f)
            lineTo(w * 0.40f, h * 0.48f)
            lineTo(w * 0.56f, h * 0.66f)
            lineTo(w * 0.68f, h * 0.53f)
            lineTo(w * 0.82f, h * 0.76f)
        }
        drawPath(path = mountain, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

/**
 * 视频时长角标：小播放三角 + 时长文本。
 * compact = true 用于网格单元格，false 用于查看器。
 */
@Composable
fun DurationPill(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
) {
    if (text.isEmpty()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 4.dp else 6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(
                horizontal = if (compact) 4.dp else 9.dp,
                vertical = if (compact) 1.dp else 5.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (compact) 10.dp else 16.dp),
            )
            Spacer(Modifier.width(1.dp))
            Text(
                text = text,
                color = Color.White,
                fontSize = if (compact) 9.sp else 13.sp,
                lineHeight = if (compact) 10.sp else 15.sp,
            )
        }
    }
}
