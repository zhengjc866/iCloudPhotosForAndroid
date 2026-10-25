package com.icloudalbum.app.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.icloudalbum.app.data.MediaFilter
import com.icloudalbum.app.data.PhotoItem
import com.icloudalbum.app.data.TimeFilter
import com.icloudalbum.app.ui.components.DurationPill
import com.icloudalbum.app.ui.components.PhotoGlyph
import com.icloudalbum.app.ui.components.formatDuration
import com.icloudalbum.app.ui.components.formatFileSize
import com.icloudalbum.app.vm.GalleryUiState
import com.icloudalbum.app.vm.PhotoSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 相册主界面：顶部大标题 + 月份分组网格 + 下拉刷新 + 触底加载更多。
 *
 * 用 BoxWithConstraints 根据可用宽度/方向自适应列数：竖屏手机 3 列，横屏 4~6 列。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPhotoClick: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    onDownload: (PhotoItem) -> Unit,
    onFilterChange: (MediaFilter) -> Unit,
    onTimeChange: (TimeFilter?) -> Unit,
    onNeedYearCounts: (List<Int>) -> Unit,
) {
    val gridState = rememberLazyGridState()
    var menuExpanded by remember { mutableStateOf(false) }
    var downloadTarget by remember { mutableStateOf<PhotoItem?>(null) }

    // 0 = 根据屏幕宽度自动；其余为双指缩放/菜单手动选定的列数（2~8）。
    var manualColumns by rememberSaveable { mutableIntStateOf(0) }
    var pinchAccum by remember { mutableFloatStateOf(1f) }
    var showDensityPill by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    // 点年视图卡片后，切回月视图并滚动到该年第一张。
    var pendingScrollYear by remember { mutableStateOf<Int?>(null) }
    // 当前是否处于年视图（用于返回键：年视图 -> 月视图）。
    var yearModeActive by remember { mutableStateOf(false) }

    BackHandler(enabled = yearModeActive) {
        manualColumns = 3
        yearModeActive = false
    }

    LaunchedEffect(manualColumns) {
        if (manualColumns != 0) {
            showDensityPill = true
            delay(1400)
            showDensityPill = false
        }
    }

    // 从年视图点进某一年：切回月视图后滚动到该年的第一张照片。
    LaunchedEffect(manualColumns, state.items, pendingScrollYear) {
        val year = pendingScrollYear ?: return@LaunchedEffect
        if (manualColumns < YEAR_GROUP_COLUMNS) {
            delay(60)
            val index = gridIndexForYear(state.items, year)
            if (index > 0) gridState.scrollToItem(index)
            pendingScrollYear = null
        }
    }

    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }

    // 触底加载更多：最后可见格子离列表末尾不足 12 个时请求下一页。
    LaunchedEffect(gridState, state.endReached, state.loadingMore, state.filter) {
        if (state.endReached || state.loadingMore || state.items.isEmpty()) return@LaunchedEffect
        val total = state.items.size
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex >= total - 12) {
                    onLoadMore()
                }
            }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "iCloud 相册",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.items.isNotEmpty()) {
                            // 顶部必须显示"真实总数/当前筛选"的数量，而不是已加载条数：
                            // 之前写成"全部 · N 张"（筛选到某月时）看起来就像总数错了。
                            val label = buildList {
                                if (state.filter != MediaFilter.ALL) add(state.filter.label)
                                state.timeFilter?.let { add(it.label) }
                            }.joinToString(" · ").ifEmpty { "全部" }
                            val countText: String = run {
                                val unscoped = state.filter == MediaFilter.ALL && state.timeFilter == null
                                val total = state.totalCount
                                val windowCount = state.timeCount
                                when {
                                    unscoped && total != null -> "$total 张"
                                    windowCount != null -> "$windowCount 张"
                                    state.endReached -> "${state.items.size} 张"
                                    else -> "已加载 ${state.items.size} 张"
                                }
                            }
                            Text(
                                text = if (scrolled) {
                                    "已加载 ${state.items.size} 张"
                                } else {
                                    "$label · $countText"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (manualColumns == 0) {
                                            "网格密度：自动（双指可缩放）"
                                        } else {
                                            "网格密度：$manualColumns 列"
                                        },
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    manualColumns = nextDensity(manualColumns)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                onClick = {
                                    menuExpanded = false
                                    onRefresh()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenSettings()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onSignOut()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            FilterChipRow(
                selected = state.filter,
                onSelect = onFilterChange,
                timeLabel = state.timeFilter?.label ?: "时间",
                timeActive = state.timeFilter != null,
                onTimeClick = { showTimeDialog = true },
            )

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val autoColumns = gridColumns(width = maxWidth, height = maxHeight)
                val columns = if (manualColumns == 0) autoColumns else manualColumns.coerceIn(2, 8)
                // 缩到一定程度（列数较多时）自动从"按月"切换为"按年"分组。
                val yearMode = columns >= YEAR_GROUP_COLUMNS
                androidx.compose.runtime.SideEffect { yearModeActive = yearMode }
                val groups = remember(state.items, yearMode) { groupItems(state.items, yearMode) }
                val entries = remember(state.items, groups) { buildGridEntries(state.items, groups) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(autoColumns) {
                        // iOS 相册式双指缩放：捏合放大 → 列数变少（格子变大），
                        // 捏合缩小 → 列数变多。只在双指时消费事件，单指滚动不受影响。
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pinchAccum = 1f
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom.isFinite() && zoom != 1f) {
                                        pinchAccum *= zoom
                                        val current = if (manualColumns == 0) autoColumns else manualColumns
                                        when {
                                            pinchAccum >= 1.30f -> {
                                                manualColumns = (current - 1).coerceAtLeast(2)
                                                pinchAccum = 1f
                                            }
                                            pinchAccum <= 0.77f -> {
                                                manualColumns = (current + 1).coerceAtMost(8)
                                                pinchAccum = 1f
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.loading && state.items.isEmpty()) {
                        LoadingPane()
                    } else if (yearMode) {
                        YearListView(
                            items = state.items,
                            totalCount = state.items.size,
                            libraryTotal = run {
                                val unscoped = state.filter == MediaFilter.ALL && state.timeFilter == null
                                if (unscoped) state.totalCount else null
                            },
                            windowLabel = state.timeFilter?.label,
                            yearCounts = state.yearCounts,
                            windowCount = state.timeCount,
                            onNeedYearCounts = onNeedYearCounts,
                            endReached = state.endReached,
                            loadingMore = state.loadingMore,
                            onLoadMore = onLoadMore,
                            onYearClick = { year ->
                                pendingScrollYear = year
                                manualColumns = 3
                            },
                        )
                    } else {
                        PhotoGrid(
                            state = state,
                            entries = entries,
                            columns = columns,
                            gridState = gridState,
                            onRefresh = onRefresh,
                            onPhotoClick = onPhotoClick,
                            onPhotoLongPress = { downloadTarget = it },
                        )
                    }
                }

                if (showDensityPill) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 28.dp),
                    ) {
                        Text(
                            text = buildString {
                                append(if (manualColumns == 0) "自动列数" else "$manualColumns 列")
                                if (yearMode) append(" · 按年分组")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                }
            }
        }
    }

    val target = downloadTarget
    if (showTimeDialog) {
        val availableYears = remember(state.items) {
            val now = Calendar.getInstance().get(Calendar.YEAR)
            val fromData = state.items.mapTo(HashSet()) {
                Calendar.getInstance().apply { timeInMillis = it.takenAt }.get(Calendar.YEAR)
            }
            ((now downTo 2000).toSet() + fromData).sortedDescending()
        }
        TimeFilterDialog(
            current = state.timeFilter,
            years = availableYears,
            onDismiss = { showTimeDialog = false },
            onApply = { time ->
                showTimeDialog = false
                onTimeChange(time)
            },
        )
    }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { downloadTarget = null },
            title = { Text(if (target.isVideo) "保存视频原片？" else "保存原图？") },
            text = {
                Text(
                    buildString {
                        append(target.filename)
                        val size = formatFileSize(target.fileSize)
                        if (size.isNotEmpty()) {
                            append("\n")
                            append(size)
                        }
                        append("\n")
                        append("将保存到系统相册 Pictures/iCloudAlbum 目录。")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        downloadTarget = null
                        onDownload(target)
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 竖屏手机 3 列，横屏/平板自适应 4~6 列。 */
private fun gridColumns(width: Dp, height: Dp): Int {
    val landscape = width > height
    return when {
        width < 480.dp -> if (landscape) 4 else 3
        width < 700.dp -> if (landscape) 5 else 4
        width < 960.dp -> if (landscape) 6 else 5
        else -> 6
    }
}

/** 列数达到这个值（即"缩小到一定程度"）时，分组从按月切换为按年。 */
private const val YEAR_GROUP_COLUMNS = 6

/** 年视图自动预加载页数（每页 100 张）。 */
private const val AUTO_LOAD_PAGES = 15

@Composable
private fun FilterChipRow(
    selected: MediaFilter,
    onSelect: (MediaFilter) -> Unit,
    timeLabel: String,
    timeActive: Boolean,
    onTimeClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaFilter.values().forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label, style = MaterialTheme.typography.labelLarge) },
                shape = RoundedCornerShape(50),
            )
        }
        // 时间筛选：选年份/月份后走服务端 assetDate 区间查询，直接跳到那段时间。
        FilterChip(
            selected = timeActive,
            onClick = onTimeClick,
            label = {
                Text(
                    text = if (timeActive) timeLabel else "时间",
                    style = MaterialTheme.typography.labelLarge,
                )
            },
            shape = RoundedCornerShape(50),
        )
    }
}

@Composable
private fun TimeFilterDialog(
    current: TimeFilter?,
    years: List<Int>,
    onDismiss: () -> Unit,
    onApply: (TimeFilter?) -> Unit,
) {
    var pickedYear by remember { mutableStateOf(current?.year) }
    var pickedMonth by remember { mutableStateOf(current?.month) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    pickedYear == null -> "按时间筛选"
                    pickedMonth != null -> "${pickedYear}年${pickedMonth}月"
                    else -> "${pickedYear}年"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val year = pickedYear
                if (year == null) {
                    Text(
                        text = "选择年份",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    years.forEach { y ->
                        TextButton(
                            onClick = {
                                pickedYear = y
                                pickedMonth = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "$y 年",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { pickedYear = null }) { Text("‹ 年份") }
                        TextButton(onClick = { onApply(TimeFilter(year, null)) }) {
                            Text("整年")
                        }
                    }
                    (1..12).chunked(4).forEach { rowMonths ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            rowMonths.forEach { m ->
                                TextButton(onClick = { onApply(TimeFilter(year, m)) }) {
                                    Text("${m}月")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pickedYear == null) {
                TextButton(onClick = { onApply(null) }) { Text("全部时间") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 按年或按月把照片分组；items 本身已按拍摄时间倒序。 */
private fun groupItems(items: List<PhotoItem>, yearMode: Boolean): List<PhotoSection> {
    if (items.isEmpty()) return emptyList()
    val pattern = if (yearMode) "yyyy年" else "yyyy年M月"
    val fmt = SimpleDateFormat(pattern, Locale.CHINA)
    val map = LinkedHashMap<String, MutableList<PhotoItem>>()
    items.forEach { item ->
        val key = fmt.format(Date(item.takenAt))
        map.getOrPut(key) { mutableListOf() }.add(item)
    }
    return map.map { PhotoSection(it.key, it.value) }
}

/** 菜单里循环切换网格密度：自动 → 2 → 3 → 4 → 5 → 6 → 8 → 自动。 */
private fun nextDensity(current: Int): Int = when (current) {
    0 -> 2
    2 -> 3
    3 -> 4
    4 -> 5
    5 -> 6
    6 -> 8
    else -> 0
}

/** 网格里的一行：要么是占满整行的月份标题，要么是一个照片格子。 */
private sealed interface GridEntry {
    data class Header(val title: String, val count: Int) : GridEntry
    data class Photo(val item: PhotoItem, val index: Int) : GridEntry
}

private fun entryKey(entry: GridEntry): Any = when (entry) {
    is GridEntry.Header -> "header:${entry.title}"
    is GridEntry.Photo -> entry.item.id
}

/**
 * 把 state.sections 拍平成网格条目，同时把每张照片映射回它在 state.items 里的下标，
 * 因为 onPhotoClick 需要的是 state.items 的下标而不是格子下标。
 */
private fun buildGridEntries(
    items: List<PhotoItem>,
    groups: List<PhotoSection>,
): List<GridEntry> {
    val indexById = HashMap<String, Int>(items.size)
    items.forEachIndexed { index, item -> indexById[item.id] = index }

    val sections: List<PhotoSection> = when {
        groups.isNotEmpty() -> groups
        items.isNotEmpty() -> listOf(PhotoSection(title = "全部照片", items = items))
        else -> emptyList()
    }

    val entries = ArrayList<GridEntry>(items.size + sections.size)
    sections.forEach { section ->
        entries.add(GridEntry.Header(title = section.title, count = section.items.size))
        section.items.forEach { item ->
            entries.add(GridEntry.Photo(item = item, index = indexById[item.id] ?: 0))
        }
    }
    return entries
}

@Composable
private fun PhotoGrid(
    state: GalleryUiState,
    entries: List<GridEntry>,
    columns: Int,
    gridState: LazyGridState,
    onRefresh: () -> Unit,
    onPhotoClick: (Int) -> Unit,
    onPhotoLongPress: (PhotoItem) -> Unit,
) {
    val error = state.error
    // 月份标题吸顶：标题滚出屏幕后，在顶部补一条同样的标题。
    val stickyHeader by remember(gridState, entries) {
        derivedStateOf {
            if (entries.isEmpty()) return@derivedStateOf null
            val info = gridState.layoutInfo
            val firstIndex = info.visibleItemsInfo.firstOrNull()?.index
                ?: return@derivedStateOf null
            var headerIndex = -1
            var header: GridEntry.Header? = null
            for (i in firstIndex downTo 0) {
                val entry = entries.getOrNull(i)
                if (entry is GridEntry.Header) {
                    headerIndex = i
                    header = entry
                    break
                }
            }
            if (header == null) return@derivedStateOf null
            val headerItem = info.visibleItemsInfo.firstOrNull { it.index == headerIndex }
            if (headerItem != null && headerItem.offset.y >= 0) null else header
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!error.isNullOrBlank()) {
                item(key = "error_banner", span = { GridItemSpan(maxLineSpan) }) {
                    ErrorBanner(message = error, onRetry = onRefresh)
                }
            }

            if (entries.isEmpty()) {
                if (error.isNullOrBlank()) {
                    item(key = "empty_state", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyPane(
                            onRefresh = onRefresh,
                            filter = state.filter,
                            timeLabel = state.timeFilter?.label,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = entries,
                    key = { _, entry -> entryKey(entry) },
                    span = { _, entry ->
                        if (entry is GridEntry.Header) {
                            GridItemSpan(maxLineSpan)
                        } else {
                            GridItemSpan(1)
                        }
                    },
                    contentType = { _, entry -> if (entry is GridEntry.Header) 0 else 1 },
                ) { _, entry ->
                    when (entry) {
                        is GridEntry.Header -> SectionHeader(title = entry.title, count = entry.count)
                        is GridEntry.Photo -> PhotoCell(
                            item = entry.item,
                            onClick = { onPhotoClick(entry.index) },
                            onLongPress = { onPhotoLongPress(entry.item) },
                        )
                    }
                }
            }

            if (state.loadingMore) {
                item(key = "loading_more", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            if (state.endReached && entries.isNotEmpty()) {
                item(key = "load_end", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "已加载全部照片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val sticky = stickyHeader
        if (sticky != null) {
            Surface(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(),
            ) {
                SectionHeader(title = sticky.title, count = sticky.count, bottomPadding = 6.dp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    item: PhotoItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "保存原图",
                onLongClick = onLongPress,
            ),
    ) {
        // Signed URLs rotate every hour, so cache by photo id: reopening the album
        // then renders instantly instead of refetching 100 CDN thumbnails.
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.thumbUrl)
                .memoryCacheKey("thumb:${item.id}:${item.thumbUrl?.hashCode() ?: 0}")
                .diskCacheKey("thumb:${item.id}")
                .crossfade(false)
                .build(),
            contentDescription = item.filename,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize(),
        )

        if (item.favorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = "收藏",
                    tint = Color.White,
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        if (item.isVideo) {
            DurationPill(
                text = formatDuration(item.durationSec),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                compact = true,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    topPadding: Dp = 14.dp,
    bottomPadding: Dp = 6.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = topPadding, bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- 年视图

private data class YearGroup(val year: Int, val covers: List<PhotoItem>, val count: Int)

private fun groupByYear(items: List<PhotoItem>): List<YearGroup> {
    val map = LinkedHashMap<Int, MutableList<PhotoItem>>()
    items.forEach { item ->
        val year = Calendar.getInstance().apply { timeInMillis = item.takenAt }.get(Calendar.YEAR)
        map.getOrPut(year) { mutableListOf() }.add(item)
    }
    return map.map { (year, list) -> YearGroup(year, list.take(5), list.size) }
}

/** 年视图里"某一年第一张"在月视图网格条目中的下标（含月份标题行）。 */
private fun gridIndexForYear(items: List<PhotoItem>, year: Int): Int {
    var index = 0
    var lastMonthKey = ""
    for (item in items) {
        val cal = Calendar.getInstance().apply { timeInMillis = item.takenAt }
        val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        if (key != lastMonthKey) {
            lastMonthKey = key
            if (cal.get(Calendar.YEAR) == year) return index
            index++ // 月份标题占一行
        }
        index++
    }
    return 0
}

/** iOS 相册那种「每年一张拼贴封面」的年视图。点一下进入该年的月视图。 */
@Composable
private fun YearListView(
    items: List<PhotoItem>,
    totalCount: Int,
    libraryTotal: Int?,
    windowLabel: String?,
    yearCounts: Map<Int, Int>,
    windowCount: Int?,
    onNeedYearCounts: (List<Int>) -> Unit,
    endReached: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onYearClick: (Int) -> Unit,
) {
    val years = remember(items) { groupByYear(items) }
    val listState = rememberLazyListState()

    // 让每年的张数变成精确值（后台按 rank 边界算，缓存持久化）。
    LaunchedEffect(years.map { it.year }) {
        if (years.isNotEmpty()) onNeedYearCounts(years.map { it.year })
    }

    // 年视图里自动往后加载，让更早的年份自己出现（不用手动滑），最多自动补 15 页，
    // 之后仍可由用户下滑继续加载。
    var autoLoads by remember { mutableIntStateOf(0) }
    LaunchedEffect(endReached, loadingMore, years.size, autoLoads) {
        if (endReached || loadingMore) return@LaunchedEffect
        if (autoLoads >= AUTO_LOAD_PAGES) return@LaunchedEffect
        delay(300)
        autoLoads++
        onLoadMore()
    }

    // 年视图里也要能继续加载：滑到接近底部时请求下一页，旧年份才会出现。
    LaunchedEffect(years.size, endReached, loadingMore) {
        if (endReached || loadingMore || years.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= years.size - 2) onLoadMore()
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "year_list_title") {
            Text(
                text = if (libraryTotal != null && libraryTotal > totalCount) {
                    "$totalCount 张（图库共 $libraryTotal 张）· 按年"
                } else {
                    "$totalCount 张 · 按年"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        items(years, key = { it.year }) { group ->
            YearCard(
                group = group,
                windowLabel = windowLabel,
                windowCount = windowCount,
                exactCount = yearCounts[group.year],
                onClick = { onYearClick(group.year) },
            )
        }
        if (loadingMore) {
            item(key = "year_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
        item(key = "year_list_hint") {
            Text(
                text = if (endReached) {
                    "已加载全部照片；点年份查看该年的月份"
                } else {
                    "继续下滑加载更早的年份；点年份查看该年的月份；双指放大可回到照片网格"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun YearCard(
    group: YearGroup,
    windowLabel: String?,
    windowCount: Int?,
    exactCount: Int?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp),
        ) {
            Row(Modifier.fillMaxSize()) {
                ThumbCell(group.covers.getOrNull(0), Modifier.weight(0.62f).fillMaxHeight())
                Column(Modifier.weight(0.38f).fillMaxHeight()) {
                    Row(Modifier.weight(1f)) {
                        ThumbCell(group.covers.getOrNull(1), Modifier.weight(1f).fillMaxHeight())
                        ThumbCell(group.covers.getOrNull(2), Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f)) {
                        ThumbCell(group.covers.getOrNull(3), Modifier.weight(1f).fillMaxHeight())
                        ThumbCell(group.covers.getOrNull(4), Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.42f),
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            ) {
                Text(
                    // 时间筛选生效时卡片标题要用实际时间段（否则筛了"2023年7月"却写"2023年"）
                    // 年份张数优先用 rank 边界算出的精确值，未算好时标注"已加载"。
                    text = if (windowLabel != null) {
                        "${windowLabel} · ${windowCount ?: group.count} 张"
                    } else {
                        val count = exactCount ?: group.count
                        val suffix = if (exactCount == null) " 张（已加载）" else " 张"
                        "${group.year}年 · $count$suffix"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun ThumbCell(item: PhotoItem?, modifier: Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .padding(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (item != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.thumbUrl)
                    .memoryCacheKey("thumb:${item.id}:${item.thumbUrl?.hashCode() ?: 0}")
                    .diskCacheKey("thumb:${item.id}")
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoadingPane() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "正在从 iCloud 加载照片…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyPane(
    onRefresh: () -> Unit,
    filter: MediaFilter = MediaFilter.ALL,
    timeLabel: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhotoGlyph(
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                timeLabel != null -> "「${timeLabel}」没有照片"
                filter != MediaFilter.ALL -> "「${filter.label}」里还没有内容"
                else -> "还没有照片"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "下拉即可刷新，也可以点右上角的刷新按钮重新加载",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRefresh) {
            Text("立即刷新")
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) {
                Text("重试", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
