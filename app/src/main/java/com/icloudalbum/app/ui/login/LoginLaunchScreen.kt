package com.icloudalbum.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Small launcher screen shown while logged out. The actual sign-in happens in
 * [LoginActivity], a plain WebView Activity (see its kdoc for why).
 */
@Composable
fun LoginLaunchScreen(
    error: String?,
    onOpenLogin: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "iCloud 相册",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "登录一次，之后直接浏览和下载 iCloud 照片。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onOpenLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("打开 iCloud 登录页")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "登录页是独立的全屏 WebView。若页面空白，请在登录页点「诊断 → 复制诊断」把信息发给开发者。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
