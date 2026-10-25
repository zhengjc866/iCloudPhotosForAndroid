package com.icloudalbum.app.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.icloudalbum.app.data.PhotoRepository
import com.icloudalbum.app.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: PhotoRepository,
    private val store: SessionStore,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
        viewModelScope.launch {
            val info = runCatching { repo.accountInfo() }.getOrNull()
            _state.update {
                it.copy(
                    loggedIn = store.isLoggedIn,
                    account = info?.fullName ?: store.fullName ?: "iCloud 用户",
                    dsid = info?.dsid ?: store.dsid,
                    server = info?.server ?: store.ckDatabaseUrl,
                    version = version,
                )
            }
        }
    }

    fun setLoadedCount(count: Int) {
        _state.update { it.copy(loadedCount = count) }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.signOut()
            _state.update { it.copy(loggedIn = false, account = null, message = "已退出登录") }
            onDone()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun runSelfTest() {
        if (_state.value.testing) return
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testLines = emptyList()) }
            val lines = try {
                repo.selfTest()
            } catch (t: Throwable) {
                listOf("✗ 自检异常：${t.message ?: t.javaClass.simpleName}")
            }
            _state.update { it.copy(testing = false, testLines = lines) }
            load()
            _state.update { it.copy(testLines = lines) }
        }
    }

    fun dismissTest() = _state.update { it.copy(testLines = emptyList()) }

    companion object {
        fun factory(repo: PhotoRepository, store: SessionStore, ctx: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(repo, store, ctx) as T
            }
    }
}
