package com.trijohn.cloudportal

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.trijohn.cloudportal.ui.CloudPortalTheme
import kotlinx.coroutines.delay
import java.net.URI
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var webSession: ICloudWebSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        webSession = ICloudWebSession(this)
        setContent {
            CloudPortalTheme {
                CloudPortalApp(webSession)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webSession.isInitialized) webSession.onResume()
    }

    override fun onPause() {
        if (::webSession.isInitialized) webSession.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webSession.isInitialized) webSession.destroy()
        super.onDestroy()
    }
}

private enum class AppSection(val label: String, val symbol: String) {
    Home("Trang chủ", "⌂"),
    Browser("iCloud", "☁"),
    Downloads("Tải về", "↓"),
    Session("Phiên", "●"),
}

private data class CloudService(
    val name: String,
    val description: String,
    val symbol: String,
    val accent: Color,
    val url: String,
)

private val cloudServices = listOf(
    CloudService("Ảnh", "Thư viện và album", "▧", Color(0xFF7B61FF), ICloudWebSession.PHOTOS_URL),
    CloudService("Drive", "Tệp và thư mục", "↥", Color(0xFF2388FF), ICloudWebSession.DRIVE_URL),
    CloudService("Ghi chú", "Notes trên iCloud", "≡", Color(0xFFF5B940), ICloudWebSession.NOTES_URL),
)

@Composable
private fun CloudPortalApp(session: ICloudWebSession) {
    val context = LocalContext.current
    val downloads = remember { DownloadRepository(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedSectionName by rememberSaveable { mutableStateOf(AppSection.Home.name) }
    val selectedSection = AppSection.valueOf(selectedSectionName)
    var message by remember { mutableStateOf<String?>(null) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooser = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val selectedFiles = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pendingFileCallback?.onReceiveValue(selectedFiles)
        pendingFileCallback = null
    }

    DisposableEffect(session, downloads) {
        session.onMessage = { message = it }
        session.onDownloadRequested = { request ->
            downloads.enqueue(request)
                .onSuccess { fileName -> message = "Đang tải $fileName vào thư mục Downloads." }
                .onFailure { error -> message = error.message ?: "Không thể tải tệp từ iCloud." }
        }
        session.onFileChooserRequested = { callback, parameters ->
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = callback
            try {
                fileChooser.launch(parameters.createIntent())
            } catch (_: ActivityNotFoundException) {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = null
                message = "Không tìm thấy trình chọn tệp trên thiết bị."
            }
        }

        onDispose {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            session.onMessage = null
            session.onDownloadRequested = null
            session.onFileChooserRequested = null
        }
    }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current)
        message = null
    }

    BackHandler(enabled = selectedSection != AppSection.Home || session.canGoBack) {
        when {
            selectedSection == AppSection.Browser && session.canGoBack -> session.goBack()
            selectedSection != AppSection.Home -> selectedSectionName = AppSection.Home.name
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectedSection != AppSection.Browser) {
                CloudBottomBar(selectedSection) { selectedSectionName = it.name }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedSection) {
                AppSection.Home -> HomeScreen(
                    hasSession = session.hasStoredSession,
                    onOpenService = { url ->
                        session.load(url)
                        selectedSectionName = AppSection.Browser.name
                    },
                    onOpenBrowser = { selectedSectionName = AppSection.Browser.name },
                    onOpenDownloads = { selectedSectionName = AppSection.Downloads.name },
                )

                AppSection.Browser -> BrowserScreen(
                    session = session,
                    onExitBrowser = { selectedSectionName = AppSection.Home.name },
                )
                AppSection.Downloads -> DownloadsScreen(downloads) { message = it }
                AppSection.Session -> SessionScreen(
                    session = session,
                    onOpenBrowser = { selectedSectionName = AppSection.Browser.name },
                    onMessage = { message = it },
                )
            }
        }
    }
}

@Composable
private fun CloudBottomBar(selected: AppSection, onSelected: (AppSection) -> Unit) {
    NavigationBar(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        AppSection.entries.forEach { section ->
            NavigationBarItem(
                selected = selected == section,
                onClick = { onSelected(section) },
                icon = {
                    Text(
                        text = section.symbol,
                        fontSize = if (section == AppSection.Session) 13.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                label = { Text(section.label, fontSize = 11.sp) },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    hasSession: Boolean,
    onOpenService: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { BrandHeader(hasSession) }
        item { HeroCard(onOpenBrowser) }
        item {
            SectionHeader(
                title = "Ứng dụng iCloud",
                subtitle = "Mở bên trong Cloud Portal, không chuyển sang Chrome",
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                cloudServices.forEach { service ->
                    ServiceTile(
                        modifier = Modifier.weight(1f),
                        service = service,
                        onClick = { onOpenService(service.url) },
                    )
                }
            }
        }
        item { QuickActionCard(hasSession, onOpenBrowser, onOpenDownloads) }
        item { PrivacyNote() }
    }
}

@Composable
private fun BrandHeader(hasSession: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2388FF), Color(0xFF7B61FF))))
                .semantics { contentDescription = "Cloud Portal" },
            contentAlignment = Alignment.Center,
        ) {
            Text("☁", color = Color.White, fontSize = 24.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Cloud Portal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "iCloud riêng trên Pixel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SessionBadge(hasSession)
    }
}

@Composable
private fun SessionBadge(hasSession: Boolean) {
    Surface(
        color = if (hasSession) Color(0xFF1AA981).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (hasSession) Color(0xFF1AA981) else Color(0xFF8A909F), CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (hasSession) "Đã lưu phiên" else "Chưa đăng nhập",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HeroCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(30.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF0E5CE5), Color(0xFF5B44D7), Color(0xFF8C5EEA)),
                    ),
                )
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
                    Text(
                        "WEBVIEW RIÊNG • COOKIE BỀN VỮNG",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "iCloud của bạn,\nngay trên Pixel.",
                    color = Color.White,
                    fontSize = 31.sp,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "Đăng nhập một lần. Cloud Portal giữ cookie và trạng thái web trong vùng dữ liệu riêng của app.",
                    color = Color.White.copy(alpha = 0.84f),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                )
                Surface(color = Color.White, shape = RoundedCornerShape(15.dp)) {
                    Text(
                        "Mở iCloud  →",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        color = Color(0xFF263B88),
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceTile(modifier: Modifier, service: CloudService, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .height(142.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(13.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = service.accent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(13.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(service.symbol, color = service.accent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }
            Column {
                Text(service.name, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
                Text(
                    service.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QuickActionCard(hasSession: Boolean, onOpenBrowser: () -> Unit, onOpenDownloads: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    color = Color(0xFF1AA981).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (hasSession) "✓" else "↗", color = Color(0xFF1AA981), fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (hasSession) "Phiên iCloud đang được giữ" else "Sẵn sàng đăng nhập iCloud",
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        if (hasSession) "Mở lại app sẽ tiếp tục phiên hiện tại" else "Apple ID và 2FA nhập trực tiếp trên trang Apple",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = onOpenBrowser, shape = RoundedCornerShape(14.dp)) {
                    Text("Tiếp tục iCloud", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenDownloads,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Tệp đã tải", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PrivacyNote() {
    Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Text("⌾", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(9.dp))
        Text(
            "Cookie nằm trong sandbox của Cloud Portal và không được sao lưu. Xóa dữ liệu app hoặc dùng mục Phiên sẽ đăng xuất hoàn toàn.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun BrowserScreen(session: ICloudWebSession, onExitBrowser: () -> Unit) {
    ImmersiveSystemBars()
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                (session.webView.parent as? ViewGroup)?.removeView(session.webView)
                session.webView.also { session.onWebViewAttached() }
            },
            update = { session.onWebViewAttached() },
        )

        if (session.isLoading) {
            LinearProgressIndicator(
                progress = { session.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }

        if (controlsExpanded) {
            FocusControlPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                session = session,
                onExitBrowser = onExitBrowser,
                onDismiss = { controlsExpanded = false },
            )
        } else {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(38.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                    .clickable { controlsExpanded = true }
                    .semantics { contentDescription = "Mở điều khiển iCloud" },
                color = Color(0xD91B1B1F),
                contentColor = Color.White,
                shadowElevation = 8.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("•••", fontSize = 15.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun ImmersiveSystemBars() {
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

@Composable
private fun FocusControlPanel(
    modifier: Modifier = Modifier,
    session: ICloudWebSession,
    onExitBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(10.dp)
            .fillMaxWidth(),
        color = Color(0xF21B1B1F),
        contentColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusControl("⌂", "Về Cloud Portal", true, onExitBrowser)
                Spacer(Modifier.width(7.dp))
                FocusControl("‹", "Quay lại", session.canGoBack, session::goBack)
                Spacer(Modifier.width(7.dp))
                FocusControl("›", "Tiến tới", session.canGoForward, session::goForward)
                Spacer(Modifier.width(7.dp))
                FocusControl(if (session.isLoading) "×" else "↻", "Tải lại", true) {
                    if (session.isLoading) session.stopLoading() else session.reload()
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        session.pageTitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Apple • ${displayHost(session.currentUrl)}",
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FocusControl("×", "Thu gọn điều khiển", true, onDismiss)
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cloudServices.forEach { service ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                session.load(service.url)
                                onDismiss()
                            },
                        color = service.accent.copy(alpha = 0.24f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            "${service.symbol}  ${service.name}",
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusControl(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        color = Color.White.copy(alpha = if (enabled) 0.13f else 0.06f),
        contentColor = Color.White.copy(alpha = if (enabled) 1f else 0.28f),
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DownloadsScreen(repository: DownloadRepository, onMessage: (String) -> Unit) {
    var downloads by remember { mutableStateOf(repository.listDownloads()) }
    var refreshNow by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshNow) {
        while (true) {
            downloads = repository.listDownloads()
            delay(1_500)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(
                eyebrow = "DOWNLOAD MANAGER",
                title = "Tệp từ iCloud",
                subtitle = "Tải nền, tiếp tục khi app đóng và lưu trong Downloads của Android.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!repository.openSystemDownloads()) onMessage("Không thể mở thư mục Downloads.")
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Mở Downloads", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { refreshNow++ }, shape = RoundedCornerShape(14.dp)) {
                    Text("↻", fontSize = 19.sp)
                }
            }
        }
        if (downloads.isEmpty()) {
            item { EmptyDownloads() }
        } else {
            items(downloads, key = { it.id }) { download ->
                DownloadCard(
                    download = download,
                    onOpen = {
                        if (!repository.openDownload(download)) {
                            onMessage("Tệp chưa sẵn sàng hoặc đã bị xóa khỏi Downloads.")
                        }
                    },
                )
            }
            item {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        repository.clearHistory()
                        downloads = emptyList()
                        onMessage("Đã xóa lịch sử; các tệp trong Downloads vẫn được giữ nguyên.")
                    },
                ) {
                    Text("Xóa lịch sử hiển thị")
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloads() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("↓", fontSize = 35.sp, color = MaterialTheme.colorScheme.primary)
            Text("Chưa có tệp tải xuống", fontWeight = FontWeight.Black)
            Text(
                "Vào iCloud Photos hoặc Drive, chọn Download; tệp sẽ xuất hiện tại đây.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadCard(download: CloudDownload, onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    color = download.state.stateColor().copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("▤", color = download.state.stateColor(), fontSize = 21.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        download.fileName,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        download.state.stateLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = download.state.stateColor(),
                    )
                }
                if (download.state == DownloadState.Complete) {
                    TextButton(onClick = onOpen) { Text("Mở", fontWeight = FontWeight.Bold) }
                }
            }
            if (download.state in setOf(DownloadState.Pending, DownloadState.Running, DownloadState.Paused)) {
                val progress = download.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SessionScreen(
    session: ICloudWebSession,
    onOpenBrowser: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var showClearConfirmation by remember { mutableStateOf(false) }
    val webViewVersion = remember { WebView.getCurrentWebViewPackage()?.versionName ?: "Không xác định" }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Xóa toàn bộ phiên iCloud?") },
            text = {
                Text("Cookie, local storage, cache và lịch sử WebView sẽ bị xóa. Bạn sẽ phải đăng nhập và nhập 2FA lại.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        session.clearSession {
                            onMessage("Đã xóa toàn bộ phiên iCloud khỏi Cloud Portal.")
                        }
                    },
                ) {
                    Text("Xóa và đăng xuất", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Hủy") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeader(
                eyebrow = "PRIVATE WEB SESSION",
                title = "Phiên iCloud",
                subtitle = "Kiểm soát cookie và trạng thái đăng nhập được giữ riêng trong ứng dụng.",
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (session.hasStoredSession) {
                        Color(0xFF1AA981).copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                ),
                shape = RoundedCornerShape(26.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(50.dp),
                            color = if (session.hasStoredSession) Color(0xFF1AA981) else Color(0xFF818797),
                            shape = CircleShape,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(if (session.hasStoredSession) "✓" else "○", color = Color.White, fontSize = 22.sp)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                if (session.hasStoredSession) "Phiên đang được lưu" else "Chưa có cookie phiên",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                if (session.hasStoredSession) "Sẽ tiếp tục sau khi đóng và mở lại app" else "Mở iCloud để đăng nhập Apple Account",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenBrowser, shape = RoundedCornerShape(14.dp)) {
                        Text(if (session.hasStoredSession) "Tiếp tục phiên iCloud" else "Đăng nhập iCloud", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            SettingsCard(
                rows = listOf(
                    "Cookie" to "Được ghi vào bộ nhớ riêng của app",
                    "Web storage" to "Bật cho trạng thái Photos và Drive",
                    "Tên miền" to "Chỉ HTTPS thuộc Apple",
                    "WebView" to webViewVersion,
                    "Sao lưu" to "Tắt — phiên không rời thiết bị",
                ),
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Giới hạn cần biết", fontWeight = FontWeight.Black)
                    Text(
                        "Apple vẫn quyết định thời hạn phiên và có thể yêu cầu 2FA lại. Gỡ app hoặc xóa dữ liệu Android cũng sẽ xóa phiên vĩnh viễn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                    )
                    TextButton(onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:${context.packageName}".toUri()
                                },
                            )
                        } catch (_: ActivityNotFoundException) {
                            onMessage("Không thể mở cài đặt ứng dụng.")
                        }
                    }) {
                        Text("Mở cài đặt ứng dụng")
                    }
                }
            }
        }
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showClearConfirmation = true },
                enabled = session.hasStoredSession,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Xóa cookie và đăng xuất", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text(
                "Cloud Portal là ứng dụng độc lập, không liên kết hoặc được Apple bảo trợ.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(rows: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.first, modifier = Modifier.weight(0.38f), fontWeight = FontWeight.Bold)
                    Text(
                        row.second,
                        modifier = Modifier.weight(0.62f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PageHeader(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            eyebrow,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
        )
        Text(title, fontSize = 29.sp, lineHeight = 33.sp, fontWeight = FontWeight.Black)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 21.sp,
        )
    }
}

private fun displayHost(url: String): String = runCatching {
    URI(url).host?.lowercase(Locale.ROOT) ?: "icloud.com"
}.getOrDefault("icloud.com")

private fun DownloadState.stateLabel(): String = when (this) {
    DownloadState.Pending -> "Đang chờ"
    DownloadState.Running -> "Đang tải"
    DownloadState.Paused -> "Tạm dừng"
    DownloadState.Complete -> "Đã tải xong"
    DownloadState.Failed -> "Tải thất bại"
    DownloadState.Missing -> "Không còn trên thiết bị"
}

private fun DownloadState.stateColor(): Color = when (this) {
    DownloadState.Complete -> Color(0xFF1AA981)
    DownloadState.Failed, DownloadState.Missing -> Color(0xFFD4515C)
    DownloadState.Paused -> Color(0xFFF0A52B)
    else -> Color(0xFF2388FF)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1_024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1_024 && unit < units.lastIndex) {
        value /= 1_024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}
