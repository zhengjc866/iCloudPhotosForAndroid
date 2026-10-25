package com.icloudalbum.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.icloudalbum.app.data.PhotoItem
import com.icloudalbum.app.ui.components.DownloadGlyph
import com.icloudalbum.app.ui.components.PhotoGlyph
import com.icloudalbum.app.ui.components.formatDuration
import com.icloudalbum.app.ui.components.formatFileSize
import com.icloudalbum.app.ui.components.formatTakenAt

private const val MinScale = 1f
private const val MaxScale = 5f
private const val DoubleTapScale = 2.5f
private const val BarBackgroundAlpha = 0.45f

/**
 * 全屏照片查看器：横向 Pager 翻页 + 双指缩放/拖动 + 双击放大。
 *
 * 缩放状态下会消费掉手势事件，因此不会误触发 Pager 翻页；1 倍时事件不消费，
 * 左右滑动仍是正常的翻页。
 */
@Composable
fun PhotoViewerScreen(
    items: List<PhotoItem>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDownload: (PhotoItem) -> Unit,
    onPlay: (PhotoItem) -> Unit,
) {
    BackHandler { onClose() }

    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("没有可查看的照片", color = Color.White)
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
        return
    }

    val startIndex = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    val currentPage = pagerState.currentPage.coerceIn(0, items.lastIndex)
    val current = items[currentPage]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            ZoomablePhoto(photo = items[page], onPlay = onPlay)
        }

        ViewerTopBar(
            index = currentPage,
            total = items.size,
            onClose = onClose,
            onDownload = { onDownload(current) },
        )

        ViewerInfoBar(
            photo = current,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ViewerTopBar(
    index: Int,
    total: Int,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = BarBackgroundAlpha))
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "${index + 1} / $total",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDownload) {
            DownloadGlyph(
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = "保存原图" },
            )
        }
    }
}

@Composable
private fun ViewerInfoBar(photo: PhotoItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = BarBackgroundAlpha))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = photo.filename,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildInfoLine(photo),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
        )
        if (photo.isVideo) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (photo.streamUrl != null) {
                    "点中间的播放按钮在线播放；长按相册格子可保存原片"
                } else {
                    "该视频暂无可播放地址，可长按相册格子保存原片"
                },
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun buildInfoLine(photo: PhotoItem): String {
    val parts = ArrayList<String>(4)
    parts.add(formatTakenAt(photo.takenAt))
    if (photo.width > 0 && photo.height > 0) {
        parts.add("${photo.width} × ${photo.height}")
    }
    val fileSize = formatFileSize(photo.fileSize)
    if (fileSize.isNotEmpty()) {
        parts.add(fileSize)
    }
    if (photo.isVideo) {
        val duration = formatDuration(photo.durationSec)
        parts.add(if (duration.isEmpty()) "视频" else "视频 $duration")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ZoomablePhoto(photo: PhotoItem, onPlay: (PhotoItem) -> Unit) {
    val context = LocalContext.current
    val model: Any? = photo.previewUrl ?: photo.thumbUrl
    var scale by remember(photo.id) { mutableFloatStateOf(MinScale) }
    var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }
    var loading by remember(photo.id) { mutableStateOf(model != null) }
    var failed by remember(photo.id) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .memoryCacheKey("preview:${photo.id}:${photo.previewUrl?.hashCode() ?: 0}")
                .diskCacheKey("preview:${photo.id}")
                .crossfade(false)
                .build(),
            contentDescription = photo.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    val maxX = size.width * (scale - MinScale) / 2f
                    val maxY = size.height * (scale - MinScale) / 2f
                    translationX = offset.x.coerceIn(-maxX, maxX)
                    translationY = offset.y.coerceIn(-maxY, maxY)
                }
                .pointerInput(photo.id) {
                    // 自己实现缩放/平移手势：只有处于放大状态或检测到多指时才消费事件，
                    // 这样 1 倍时不会抢走 Pager 的左右滑动。
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val multiTouch = event.changes.count { it.pressed } > 1
                            if (scale > MinScale || multiTouch) {
                                val newScale = (scale * zoomChange).coerceIn(MinScale, MaxScale)
                                scale = newScale
                                offset = if (newScale > MinScale) {
                                    offset + panChange
                                } else {
                                    Offset.Zero
                                }
                                event.changes.forEach { change ->
                                    if (change.pressed) {
                                        change.consume()
                                    }
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                }
                .pointerInput(photo.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > MinScale) {
                                scale = MinScale
                                offset = Offset.Zero
                            } else {
                                scale = DoubleTapScale
                                offset = Offset.Zero
                            }
                        },
                    )
                },
            onLoading = {
                loading = true
                failed = false
            },
            onSuccess = {
                loading = false
                failed = false
            },
            onError = {
                loading = false
                failed = true
            },
        )

        if (photo.isVideo && model != null && !failed) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { if (photo.streamUrl != null) onPlay(photo) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "视频",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        if (loading && !failed) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }

        if (failed || model == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                PhotoGlyph(
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (photo.isVideo) "视频缩略图暂不可用" else "图片加载失败",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
