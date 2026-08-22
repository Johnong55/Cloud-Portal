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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
    var selectedSectionName by rememberSaveable { mutableStateOf(AppSection.Browser.name) }
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
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            HomeReveal(revealed, delayMillis = 0) { MinimalBrandHeader(hasSession) }
        }
        item {
            HomeReveal(revealed, delayMillis = 70) { PrimaryCloudEntry(hasSession, onOpenBrowser) }
        }
        item {
            HomeReveal(revealed, delayMillis = 140) { MinimalServiceList(onOpenService) }
        }
        item {
            HomeReveal(revealed, delayMillis = 210) { DownloadShortcut(onOpenDownloads) }
        }
        item {
            HomeReveal(revealed, delayMillis = 280) { PrivacyNote() }
        }
    }
}

@Composable
private fun HomeReveal(
    visible: Boolean,
    delayMillis: Int,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = 420, delayMillis = delayMillis)) +
            slideInVertically(
                animationSpec = tween(durationMillis = 480, delayMillis = delayMillis),
                initialOffsetY = { it / 5 },
            ),
    ) {
        content()
    }
}

@Composable
private fun MinimalBrandHeader(hasSession: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.semantics { contentDescription = "Cloud Portal" },
            ) {
                Text("☁", fontSize = 19.sp)
            }
        }
        Spacer(Modifier.width(11.dp))
        Text(
            "Cloud Portal",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        SessionBadge(hasSession)
    }
}

@Composable
private fun SessionBadge(hasSession: Boolean) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(if (hasSession) Color(0xFF1AA981) else Color(0xFF8A909F), CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (hasSession) "Đã kết nối" else "Chưa kết nối",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrimaryCloudEntry(hasSession: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "KHÔNG GIAN CỦA BẠN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ảnh, tệp &\nghi chú.",
                        fontSize = 27.sp,
                        lineHeight = 31.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.4).sp,
                    )
                }
                PortalArtwork(Modifier.size(108.dp))
            }
            Text(
                if (hasSession) {
                    "Phiên iCloud đã sẵn sàng để tiếp tục."
                } else {
                    "Đăng nhập để bắt đầu trên thiết bị này."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                onClick = onClick,
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(
                    if (hasSession) "Tiếp tục với iCloud" else "Đăng nhập iCloud",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PortalArtwork(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "portal-art")
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
        ),
        label = "portal-orbit",
    )
    val floating by motion.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "portal-float",
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = Color(0xFF7B61FF)
    val centerColor = MaterialTheme.colorScheme.primaryContainer
    val centerContent = MaterialTheme.colorScheme.onPrimaryContainer

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(primary.copy(alpha = 0.16f), Color.Transparent),
                ),
                radius = size.minDimension * 0.48f,
            )
            drawCircle(
                color = primary.copy(alpha = 0.12f),
                radius = size.minDimension * 0.37f,
                style = Stroke(width = 1.2.dp.toPx()),
            )
            drawArc(
                color = primary,
                startAngle = rotation,
                sweepAngle = 82f,
                useCenter = false,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = secondary.copy(alpha = 0.75f),
                startAngle = -rotation * 0.7f,
                sweepAngle = 48f,
                useCenter = false,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Surface(
            modifier = Modifier
                .size(54.dp)
                .graphicsLayer { translationY = floating },
            color = centerColor,
            contentColor = centerContent,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 5.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("☁", fontSize = 27.sp)
            }
        }
    }
}

@Composable
private fun MinimalServiceList(onOpenService: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "DỊCH VỤ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(18.dp),
        ) {
            Column {
                cloudServices.forEachIndexed { index, service ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenService(service.url) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            color = service.accent.copy(alpha = 0.13f),
                            contentColor = service.accent,
                            shape = RoundedCornerShape(11.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(service.symbol, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(service.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                service.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            "›",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                    if (index != cloudServices.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 65.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadShortcut(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("↓", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Tệp đã tải", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Xem ảnh và tài liệu đã lưu trên thiết bị",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp)
    }
}

@Composable
private fun PrivacyNote() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(Color(0xFF1AA981), CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            "Phiên iCloud chỉ được lưu trên thiết bị này.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrowserScreen(session: ICloudWebSession, onExitBrowser: () -> Unit) {
    ImmersiveSystemBars()
    var servicesExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
        }

        BottomBrowserBar(
            session = session,
            servicesExpanded = servicesExpanded,
            onToggleServices = { servicesExpanded = !servicesExpanded },
            onServiceSelected = { url ->
                servicesExpanded = false
                session.load(url)
            },
            onExitBrowser = onExitBrowser,
        )
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
private fun BottomBrowserBar(
    modifier: Modifier = Modifier,
    session: ICloudWebSession,
    servicesExpanded: Boolean,
    onToggleServices: () -> Unit,
    onServiceSelected: (String) -> Unit,
    onExitBrowser: () -> Unit,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth(),
        color = Color(0xF51B1B1F),
        contentColor = Color.White,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (servicesExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    cloudServices.forEach { service ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onServiceSelected(service.url) },
                            color = service.accent.copy(alpha = 0.28f),
                            contentColor = Color.White,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                "${service.symbol}  ${service.name}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusControl("⌂", "Về Cloud Portal", true, onExitBrowser)
                Spacer(Modifier.width(5.dp))
                FocusControl("‹", "Quay lại", session.canGoBack, session::goBack)
                Spacer(Modifier.width(5.dp))
                FocusControl("›", "Tiến tới", session.canGoForward, session::goForward)
                Spacer(Modifier.width(5.dp))
                FocusControl(if (session.isLoading) "×" else "↻", "Tải lại", true) {
                    if (session.isLoading) session.stopLoading() else session.reload()
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onToggleServices)
                        .semantics { contentDescription = "Chuyển dịch vụ iCloud" },
                    color = Color.White.copy(alpha = 0.13f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("☁", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                activeCloudService(session.currentUrl),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                displayHost(session.currentUrl),
                                color = Color.White.copy(alpha = 0.58f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        Text(
                            if (servicesExpanded) "⌄" else "⌃",
                            fontSize = 15.sp,
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
            .size(38.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        color = Color.White.copy(alpha = if (enabled) 0.13f else 0.06f),
        contentColor = Color.White.copy(alpha = if (enabled) 1f else 0.28f),
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun activeCloudService(url: String): String = when {
    url.contains("/photos", ignoreCase = true) -> "Ảnh iCloud"
    url.contains("/iclouddrive", ignoreCase = true) -> "iCloud Drive"
    url.contains("/notes", ignoreCase = true) -> "Ghi chú"
    else -> "iCloud"
}

@Composable
private fun DownloadsScreen(repository: DownloadRepository, onMessage: (String) -> Unit) {
    var downloads by remember { mutableStateOf(repository.listDownloads()) }
    var refreshNow by remember { mutableIntStateOf(0) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(refreshNow) {
        while (true) {
            downloads = repository.listDownloads()
            nowMillis = System.currentTimeMillis()
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
                subtitle = "ZIP nhiều ảnh được tự giải nén vào Downloads/Cloud Portal, kể cả khi app chạy nền.",
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
                    nowMillis = nowMillis,
                    onOpen = {
                        if (!repository.openDownload(download)) {
                            onMessage("Không thể mở tệp hoặc thư mục ảnh trong Downloads.")
                        }
                    },
                    onRetryExtraction = {
                        if (repository.retryExtraction(download)) {
                            onMessage("Đang thử giải nén lại ${download.fileName}.")
                        } else {
                            onMessage("Không thể thử giải nén lại tệp này.")
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
private fun DownloadCard(
    download: CloudDownload,
    nowMillis: Long,
    onOpen: () -> Unit,
    onRetryExtraction: () -> Unit,
) {
    val showsCompletionTime = download.state in setOf(DownloadState.Complete, DownloadState.Extracted)
    val completionLabel = if (showsCompletionTime) {
        DownloadTimePolicy.label(download.completedAtMillis, nowMillis)
    } else {
        null
    }
    val isRecent = showsCompletionTime && DownloadTimePolicy.isRecent(download.completedAtMillis, nowMillis)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRecent) {
                Color(0xFF1AA981).copy(alpha = 0.11f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            download.stateLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            color = download.state.stateColor(),
                        )
                        if (isRecent) {
                            Spacer(Modifier.width(7.dp))
                            Surface(
                                color = Color(0xFF1AA981),
                                contentColor = Color.White,
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    "MỚI",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.7.sp,
                                )
                            }
                        }
                    }
                    completionLabel?.let { label ->
                        Text(
                            "Hoàn tất • $label",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (download.state) {
                    DownloadState.Complete -> {
                        TextButton(onClick = onOpen) { Text("Mở", fontWeight = FontWeight.Bold) }
                    }

                    DownloadState.Extracted -> {
                        TextButton(onClick = onOpen) { Text("Xem ảnh", fontWeight = FontWeight.Bold) }
                    }

                    DownloadState.ExtractionFailed -> {
                        TextButton(onClick = onRetryExtraction) { Text("Thử lại", fontWeight = FontWeight.Bold) }
                    }

                    else -> Unit
                }
            }
            if (
                download.state in setOf(
                    DownloadState.Pending,
                    DownloadState.Running,
                    DownloadState.Paused,
                    DownloadState.Extracting,
                )
            ) {
                val progress = download.progress
                if (progress != null && download.state != DownloadState.Extracting) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (download.state == DownloadState.Extracting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Đang tạo thư mục ảnh trong Downloads…",
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

private fun CloudDownload.stateLabel(): String = when (state) {
    DownloadState.Pending -> "Đang chờ"
    DownloadState.Running -> "Đang tải"
    DownloadState.Paused -> "Tạm dừng"
    DownloadState.Complete -> "Đã tải xong"
    DownloadState.Extracting -> "Đang tự động giải nén"
    DownloadState.Extracted -> "Đã giải nén $extractedFileCount tệp"
    DownloadState.ExtractionFailed -> "Giải nén thất bại"
    DownloadState.Failed -> "Tải thất bại"
    DownloadState.Missing -> "Không còn trên thiết bị"
}

private fun DownloadState.stateColor(): Color = when (this) {
    DownloadState.Complete, DownloadState.Extracted -> Color(0xFF1AA981)
    DownloadState.Failed, DownloadState.Missing, DownloadState.ExtractionFailed -> Color(0xFFD4515C)
    DownloadState.Paused -> Color(0xFFF0A52B)
    DownloadState.Extracting -> Color(0xFF7B61FF)
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
