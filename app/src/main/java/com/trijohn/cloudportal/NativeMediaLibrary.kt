package com.trijohn.cloudportal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Size
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal enum class MediaFilter(val label: String) {
    All("Tất cả"),
    Images("Ảnh"),
    Videos("Video"),
}

internal data class MediaViewerState(
    val media: List<LocalMedia>,
    val initialIndex: Int,
)

@Composable
internal fun NativeMediaLibraryScreen(
    repository: DownloadRepository,
    onOpenViewer: (MediaViewerState) -> Unit,
) {
    var media by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var filterName by remember { mutableStateOf(MediaFilter.All.name) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    val filter = MediaFilter.valueOf(filterName)

    LaunchedEffect(refreshKey) {
        isLoading = true
        media = withContext(Dispatchers.IO) { repository.listDownloadedMedia() }
        isLoading = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4_000)
            val latest = withContext(Dispatchers.IO) { repository.listDownloadedMedia() }
            if (latest != media) media = latest
        }
    }

    val filteredMedia = remember(media, filter) {
        media.filter {
            when (filter) {
                MediaFilter.All -> true
                MediaFilter.Images -> it.kind == LocalMediaKind.Image
                MediaFilter.Videos -> it.kind == LocalMediaKind.Video
            }
        }
    }
    val groupedMedia = remember(filteredMedia) {
        filteredMedia.groupBy { MediaDateLabels.dayLabel(it.completedAtMillis) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp,
            top = 8.dp,
            end = 14.dp,
            bottom = 30.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "THƯ VIỆN NATIVE",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.3.sp,
                        )
                        Text(
                            "Ảnh & video đã tải",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            "${media.count { it.kind == LocalMediaKind.Image }} ảnh  •  " +
                                "${media.count { it.kind == LocalMediaKind.Video }} video",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .clickable { refreshKey++ },
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("↻", fontSize = 20.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaFilter.entries.forEach { item ->
                        val selected = item == filter
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { filterName = item.name },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                item.label,
                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        when {
            isLoading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            filteredMedia.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                MediaLibraryEmptyState(filter)
            }

            else -> groupedMedia.forEach { (dayLabel, group) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        dayLabel,
                        modifier = Modifier.padding(start = 6.dp, top = 15.dp, bottom = 7.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                    )
                }
                items(group, key = { it.uri.toString() }) { item ->
                    MediaGridItem(
                        media = item,
                        onClick = {
                            onOpenViewer(
                                MediaViewerState(
                                    media = filteredMedia,
                                    initialIndex = filteredMedia.indexOf(item).coerceAtLeast(0),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaLibraryEmptyState(filter: MediaFilter) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (filter == MediaFilter.Videos) "▶" else "▧", fontSize = 38.sp)
        Text(
            if (filter == MediaFilter.All) "Chưa có media đã tải" else "Không có ${filter.label.lowercase()}",
            fontWeight = FontWeight.Black,
        )
        Text(
            "Ảnh và video tải trực tiếp hoặc được giải nén từ ZIP sẽ tự xuất hiện tại đây.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MediaGridItem(media: LocalMedia, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(0.88f)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        MediaBitmap(
            uri = media.uri,
            requestedSize = 560,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(42.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.64f)),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (media.kind == LocalMediaKind.Video) {
                Surface(color = Color.White.copy(alpha = 0.94f), shape = CircleShape) {
                    Text(
                        "▶",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = Color.Black,
                        fontSize = 9.sp,
                    )
                }
                Spacer(Modifier.width(5.dp))
            }
            Text(
                MediaDateLabels.timeLabel(media.completedAtMillis),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MediaBitmap(
    uri: Uri,
    requestedSize: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri, requestedSize) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(requestedSize, requestedSize), null)
                    .also(Bitmap::prepareToDraw)
            }.getOrNull()
        }
    }
    if (bitmap == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

@Composable
internal fun NativeMediaViewer(
    state: MediaViewerState,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = state.initialIndex.coerceIn(0, state.media.lastIndex.coerceAtLeast(0)),
        pageCount = { state.media.size },
    )
    var currentImageZoomed by remember { mutableStateOf(false) }
    val currentMedia = state.media.getOrNull(pagerState.currentPage)

    ViewerSystemBars()
    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !currentImageZoomed,
            key = { state.media[it].uri.toString() },
        ) { page ->
            val media = state.media[page]
            when (media.kind) {
                LocalMediaKind.Image -> ZoomableMediaImage(
                    media = media,
                    isCurrent = page == pagerState.currentPage,
                    onZoomChanged = { zoomed ->
                        if (page == pagerState.currentPage) currentImageZoomed = zoomed
                    },
                )
                LocalMediaKind.Video -> NativeVideoPage(media, page == pagerState.currentPage)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)),
                )
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerCircleButton("×", "Đóng", onClose)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${pagerState.currentPage + 1} / ${state.media.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    currentMedia?.fileName.orEmpty(),
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ViewerCircleButton("↗", "Chia sẻ") {
                val media = currentMedia ?: return@ViewerCircleButton
                if (!shareMedia(context, media)) onMessage("Không thể chia sẻ tệp này.")
            }
            Spacer(Modifier.width(8.dp))
            ViewerCircleButton("⋯", "Mở bằng ứng dụng khác") {
                val media = currentMedia ?: return@ViewerCircleButton
                if (!openMediaExternally(context, media)) onMessage("Không tìm thấy ứng dụng để mở tệp.")
            }
        }

        currentMedia?.let { media ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        MediaDateLabels.fullTimestamp(media.completedAtMillis),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${if (media.kind == LocalMediaKind.Image) "Ảnh" else "Video"}  •  " +
                            formatMediaBytes(media.sizeBytes),
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (currentImageZoomed) {
                    Text("Đang phóng to", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ZoomableMediaImage(
    media: LocalMedia,
    isCurrent: Boolean,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember(media.uri) { mutableFloatStateOf(1f) }
    var offset by remember(media.uri) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 4.5f)
        val maxX = containerSize.width * (newScale - 1f) / 2f
        val maxY = containerSize.height * (newScale - 1f) / 2f
        scale = newScale
        offset = if (newScale <= 1.01f) {
            Offset.Zero
        } else {
            Offset(
                x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                y = (offset.y + panChange.y).coerceIn(-maxY, maxY),
            )
        }
        onZoomChanged(newScale > 1.02f)
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
            onZoomChanged(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .transformable(
                state = transformState,
                canPan = { scale > 1.02f },
                lockRotationOnZoomPan = true,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MediaBitmap(
            uri = media.uri,
            requestedSize = 2_560,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

@Composable
private fun NativeVideoPage(media: LocalMedia, isCurrent: Boolean) {
    val context = LocalContext.current
    var player by remember(media.uri) { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember(media.uri) { mutableStateOf(false) }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            player?.pause()
            isPlaying = false
        }
    }
    DisposableEffect(media.uri) {
        onDispose { player?.stopPlayback() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    val controls = MediaController(viewContext)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setVideoURI(media.uri)
                    setOnPreparedListener { prepared ->
                        prepared.isLooping = false
                        seekTo(1)
                    }
                    setOnCompletionListener { isPlaying = false }
                    player = this
                }
            },
            update = { view -> if (player !== view) player = view },
        )
        if (!isPlaying && isCurrent) {
            Surface(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .clickable {
                        player?.start()
                        isPlaying = true
                    },
                color = Color.White.copy(alpha = 0.92f),
                contentColor = Color.Black,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) { Text("▶", fontSize = 28.sp) }
            }
        }
    }
}

@Composable
private fun ViewerCircleButton(symbol: String, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        color = Color.White.copy(alpha = 0.14f),
        contentColor = Color.White,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ViewerSystemBars() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        if (activity == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            val previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = previousBehavior
            }
        }
    }
}

private fun shareMedia(context: Context, media: LocalMedia): Boolean = try {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = media.mimeType
        putExtra(Intent.EXTRA_STREAM, media.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Chia sẻ bằng").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private fun openMediaExternally(context: Context, media: LocalMedia): Boolean = try {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(media.uri, media.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Mở bằng").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private fun formatMediaBytes(bytes: Long): String = when {
    bytes <= 0L -> "Không rõ dung lượng"
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> String.format(Locale.US, "%.1f KB", bytes / 1_024f)
    bytes < 1_073_741_824L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
    else -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824f)
}

private object MediaDateLabels {
    private val locale = Locale.forLanguageTag("vi-VN")

    fun dayLabel(timestamp: Long): String {
        if (timestamp <= 0L) return "Không rõ ngày"
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        if (isSameDay(target, today)) return "Hôm nay"
        today.add(Calendar.DAY_OF_YEAR, -1)
        if (isSameDay(target, today)) return "Hôm qua"
        return SimpleDateFormat("EEEE, dd/MM/yyyy", locale).format(Date(timestamp))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    fun timeLabel(timestamp: Long): String = if (timestamp > 0L) {
        SimpleDateFormat("HH:mm", locale).format(Date(timestamp))
    } else {
        "iCloud"
    }

    fun fullTimestamp(timestamp: Long): String = if (timestamp > 0L) {
        SimpleDateFormat("HH:mm • dd/MM/yyyy", locale).format(Date(timestamp))
    } else {
        "Đã tải từ iCloud"
    }

    private fun isSameDay(first: Calendar, second: Calendar): Boolean =
        first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
            first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}
