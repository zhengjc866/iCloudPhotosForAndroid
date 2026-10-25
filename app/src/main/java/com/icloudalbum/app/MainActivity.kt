package com.icloudalbum.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.icloudalbum.app.data.AuthState
import com.icloudalbum.app.data.PhotoItem
import com.icloudalbum.app.ui.gallery.GalleryScreen
import com.icloudalbum.app.ui.login.LoginActivity
import com.icloudalbum.app.ui.login.LoginLaunchScreen
import com.icloudalbum.app.ui.player.VideoPlayerActivity
import com.icloudalbum.app.ui.settings.SettingsScreen
import com.icloudalbum.app.ui.theme.ICloudAlbumTheme
import com.icloudalbum.app.ui.viewer.PhotoViewerScreen
import com.icloudalbum.app.vm.GalleryViewModel
import com.icloudalbum.app.vm.SettingsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ICloudAlbumTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val container = app.container
    val galleryVm: GalleryViewModel = viewModel(
        factory = GalleryViewModel.factory(container.repository),
    )
    val state by galleryVm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf("gallery") }
    var pendingDownload by remember { mutableStateOf<PhotoItem?>(null) }

    LaunchedEffect(Unit) { galleryVm.bootstrap() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        galleryVm.consumeMessage()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val item = pendingDownload
        pendingDownload = null
        when {
            granted && item != null -> galleryVm.download(item)
            !granted -> scope.launch { snackbarHostState.showSnackbar("需要存储权限才能保存照片") }
        }
    }

    // System back: viewer -> close viewer, settings -> gallery, otherwise exit.
    BackHandler(enabled = state.viewerIndex != null || screen == "settings") {
        when {
            state.viewerIndex != null -> galleryVm.closeViewer()
            screen == "settings" -> screen = "gallery"
        }
    }

    fun saveToGallery(item: PhotoItem) {
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingDownload = item
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            galleryVm.download(item)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            screen == "settings" -> {
                val settingsVm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(
                        container.repository,
                        container.sessionStore,
                        context.applicationContext,
                    ),
                )
                val settingsState by settingsVm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.items.size) { settingsVm.setLoadedCount(state.items.size) }
                SettingsScreen(
                    state = settingsState,
                    onBack = { screen = "gallery" },
                    onSignOut = {
                        galleryVm.signOut()
                        screen = "gallery"
                    },
                    onRunSelfTest = { settingsVm.runSelfTest() },
                    onDismissTest = { settingsVm.dismissTest() },
                )
            }

            state.authState == AuthState.UNKNOWN -> SplashView()

            state.authState == AuthState.EXPIRED || state.authState == AuthState.LOGGED_OUT -> {
                LoginLaunchScreen(
                    error = state.error,
                    onOpenLogin = {
                        context.startActivity(
                            Intent(context, LoginActivity::class.java),
                        )
                    },
                )
            }

            else -> GalleryScreen(
                state = state,
                onRefresh = galleryVm::refresh,
                onLoadMore = galleryVm::loadMore,
                onPhotoClick = { index -> galleryVm.openViewer(index) },
                onOpenSettings = { screen = "settings" },
                onSignOut = { galleryVm.signOut() },
                onDownload = { item -> saveToGallery(item) },
                onFilterChange = galleryVm::setFilter,
                onTimeChange = galleryVm::setTimeFilter,
                onNeedYearCounts = galleryVm::ensureYearCounts,
            )
        }

        val viewerIndex = state.viewerIndex
        if (viewerIndex != null) {
            PhotoViewerScreen(
                items = state.items,
                initialIndex = viewerIndex,
                onClose = { galleryVm.closeViewer() },
                onDownload = { item -> saveToGallery(item) },
                onPlay = { item ->
                    val url = item.streamUrl
                    if (url.isNullOrBlank()) {
                        scope.launch {
                            snackbarHostState.showSnackbar("该视频暂无可播放地址，可长按相册格子保存原片")
                        }
                    } else {
                        context.startActivity(
                            VideoPlayerActivity.intent(
                                context = context,
                                url = url,
                                title = item.filename,
                                subtitle = "在线播放 · ${item.mimeType}",
                            ),
                        )
                    }
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SplashView() {    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在连接 iCloud…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
