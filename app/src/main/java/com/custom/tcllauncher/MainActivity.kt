package com.custom.tcllauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

data class TvApp(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val banner: Drawable?
)

data class TrendingMedia(
    val title: String,
    val subtitle: String,
    val badge: String,
    val accentColor: Color,
    val targetPackage: String,
    val deepLinkUrl: String? = null
)

class MainActivity : ComponentActivity() {
    private var installedAppsState by mutableStateOf<List<TvApp>>(emptyList())
    private var isPointerModeActive by mutableStateOf(false)
    private var isWaitingToBindPointerKey by mutableStateOf(false)
    private var customPointerKeyCode by mutableIntStateOf(KeyEvent.KEYCODE_PROG_BLUE)
    private var pointerX by mutableFloatStateOf(960f)
    private var pointerY by mutableFloatStateOf(540f)
    private var screenWidth by mutableIntStateOf(1920)
    private var screenHeight by mutableIntStateOf(1080)
    private var isPointerClicking by mutableStateOf(false)
    private var nightCinemaMode by mutableStateOf(false)
    private var consecutiveMoveCount = 0
    private var lastMoveTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("tcl_launcher_prefs", Context.MODE_PRIVATE)
        customPointerKeyCode = prefs.getInt("pointer_keycode", KeyEvent.KEYCODE_PROG_BLUE)
        installedAppsState = getInstalledTvApps(this)

        setContent {
            TclAppleTvLauncher(
                apps = installedAppsState,
                isPointerActive = isPointerModeActive,
                isWaitingToBindKey = isWaitingToBindPointerKey,
                nightCinemaMode = nightCinemaMode,
                onToggleNightMode = { nightCinemaMode = !nightCinemaMode },
                pointerX = pointerX,
                pointerY = pointerY,
                isPointerClicking = isPointerClicking,
                onScreenSizeChanged = { size ->
                    if (size.width > 0 && size.height > 0) {
                        screenWidth = size.width
                        screenHeight = size.height
                    }
                },
                onTogglePointerMode = { togglePointerMode() },
                onStartKeyBind = {
                    isWaitingToBindPointerKey = true
                    Toast.makeText(
                        this,
                        "Press the BLUE button on your TCL remote now to bind it!",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onLaunchApp = { pkg, deepLink -> launchPackage(pkg, deepLink) },
                onOpenInputs = { openTclInputs() },
                onOpenWifi = { openSystemScreen(Settings.ACTION_WIFI_SETTINGS) },
                onOpenSettings = { openSystemScreen(Settings.ACTION_SETTINGS) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically refreshes app list whenever returning to the Home screen
        installedAppsState = getInstalledTvApps(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Never allow the Back button to close or exit the TV Home Screen Launcher
        if (isPointerModeActive) {
            isPointerModeActive = false
            Toast.makeText(this, "⚪ Pointer Mode OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePointerMode() {
        isPointerModeActive = !isPointerModeActive
        if (isPointerModeActive) {
            pointerX = screenWidth / 2f
            pointerY = screenHeight / 2f
            Toast.makeText(
                this,
                "🔵 Pointer Mode ON — D-Pad to move, OK to click, Blue button to exit",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "⚪ Pointer Mode OFF — Standard D-Pad & OK button active",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // 0. Custom Key Binding Mode (if user clicked 'Rebind' pill)
        if (isWaitingToBindPointerKey && event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                keyCode != KeyEvent.KEYCODE_ENTER &&
                keyCode != KeyEvent.KEYCODE_BACK
            ) {
                customPointerKeyCode = keyCode
                getSharedPreferences("tcl_launcher_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("pointer_keycode", keyCode)
                    .apply()
                isWaitingToBindPointerKey = false
                Toast.makeText(
                    this,
                    "✅ Bound remote button (KeyCode $keyCode) to Pointer Mode!",
                    Toast.LENGTH_SHORT
                ).show()
                togglePointerMode()
                return true
            } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                isWaitingToBindPointerKey = false
                return true
            }
        }

        // 1. Toggle Pointer Mode on Blue Remote Button (KEYCODE_PROG_BLUE = 186, F4 = 134, or custom bound)
        if (keyCode == KeyEvent.KEYCODE_PROG_BLUE ||
            keyCode == KeyEvent.KEYCODE_F4 ||
            keyCode == customPointerKeyCode
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                togglePointerMode()
            }
            return true
        }

        // 2. Colored Remote Shortcuts when Pointer Mode is OFF:
        // Green = PlayStation/HDMI Inputs, Yellow = Night Dimmer
        if (!isPointerModeActive && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_PROG_YELLOW -> {
                    nightCinemaMode = !nightCinemaMode
                    Toast.makeText(
                        this,
                        if (nightCinemaMode) "🌙 Night Dimmer ON" else "☀️ Bright Mode ON",
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
                KeyEvent.KEYCODE_PROG_GREEN -> {
                    openTclInputs()
                    return true
                }
            }
        }

        // 3. When Pointer Mode is ON, D-Pad moves the cursor & OK clicks under the cursor.
        // When Pointer Mode is OFF, this block is skipped so standard D-Pad & OK work natively!
        if (isPointerModeActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val now = SystemClock.uptimeMillis()
                        consecutiveMoveCount = if (now - lastMoveTime < 220) {
                            (consecutiveMoveCount + 1).coerceAtMost(12)
                        } else {
                            0
                        }
                        lastMoveTime = now
                        val step = 18f + (consecutiveMoveCount * 4.5f)
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> pointerY = (pointerY - step).coerceIn(12f, (screenHeight - 12).toFloat())
                            KeyEvent.KEYCODE_DPAD_DOWN -> pointerY = (pointerY + step).coerceIn(12f, (screenHeight - 12).toFloat())
                            KeyEvent.KEYCODE_DPAD_LEFT -> pointerX = (pointerX - step).coerceIn(12f, (screenWidth - 12).toFloat())
                            KeyEvent.KEYCODE_DPAD_RIGHT -> pointerX = (pointerX + step).coerceIn(12f, (screenWidth - 12).toFloat())
                        }
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        isPointerClicking = true
                        simulateTouchClick(pointerX, pointerY)
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        isPointerClicking = false
                    }
                    return true
                }

                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        isPointerModeActive = false
                        Toast.makeText(this, "⚪ Pointer Mode OFF", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun simulateTouchClick(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val upEvent = MotionEvent.obtain(downTime, downTime + 30, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        window.decorView.dispatchTouchEvent(downEvent)
        window.decorView.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
    }

    private fun getInstalledTvApps(context: Context): List<TvApp> {
        return try {
            val pm = context.packageManager
            val leanback = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) }
            val main = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            (pm.queryIntentActivities(leanback, 0) + pm.queryIntentActivities(main, 0))
                .mapNotNull { info ->
                    val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                    val pkg = appInfo.packageName
                    if (pkg == context.packageName || pkg.contains("tvlauncher") || pkg.contains("setupwraith")) {
                        return@mapNotNull null
                    }
                    TvApp(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = pkg,
                        icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull(),
                        banner = runCatching { pm.getApplicationBanner(appInfo) }.getOrNull()
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.name.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun launchPackage(packageName: String, deepLinkUrl: String? = null) {
        if (packageName == "TCL_HDMI_INPUTS") {
            openTclInputs()
            return
        }
        if (deepLinkUrl != null) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
                return
            } catch (_: Exception) {}
        }
        try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(this, "App not installed ($packageName)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not launch $packageName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTclInputs() {
        val candidates = listOf(
            "com.tcl.tv",
            "com.tcl.source",
            "com.google.android.tv",
            "com.android.tv"
        )
        for (pkg in candidates) {
            try {
                val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) {}
        }
        openSystemScreen(Settings.ACTION_SETTINGS)
    }

    private fun openSystemScreen(action: String) {
        try {
            startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {}
    }
}

suspend fun fetchLiveTrendingShows(): List<TrendingMedia> = withContext(Dispatchers.IO) {
    val defaultShelf = listOf(
        TrendingMedia("PlayStation / HDMI", "Switch to Console & eARC Soundbar", "🎮 CONSOLE & INPUTS", Color(0xFF0070D1), "TCL_HDMI_INPUTS"),
        TrendingMedia("Continue Watching", "4K Dolby Vision • HDR10+", "NETFLIX", Color(0xFFE50914), "com.netflix.ninja"),
        TrendingMedia("Prime Video Cinema", "IMDb Top Rated • IMAX Enhanced", "PRIME VIDEO", Color(0xFF00A8E1), "com.amazon.amazonvideo.livingroom"),
        TrendingMedia("SonyLIV Sports & Originals", "Live Cricket, Football & Shows", "SONYLIV", Color(0xFF6C5CE7), "com.sonyliv"),
        TrendingMedia("YouTube 4K Lounge", "60fps HDR • Ambient & Music", "YOUTUBE", Color(0xFFFF0000), "com.google.android.youtube.tv")
    )
    try {
        val conn = (URL("https://api.tvmaze.com/shows?page=0").openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
        }
        if (conn.responseCode == 200) {
            val jsonArray = JSONArray(conn.inputStream.bufferedReader().readText())
            val topShows = mutableListOf<TrendingMedia>()
            val colors = listOf(Color(0xFFE50914), Color(0xFF8E44AD), Color(0xFF27AE60), Color(0xFFF39C12))
            for (i in 0 until minOf(jsonArray.length(), 15)) {
                val obj = jsonArray.getJSONObject(i)
                val score = obj.optJSONObject("rating")?.optDouble("average", 0.0) ?: 0.0
                if (score >= 8.3 && topShows.size < 3) {
                    val name = obj.optString("name", "Featured Show")
                    val genres = obj.optJSONArray("genres")
                    val genreStr = if (genres != null && genres.length() > 0) genres.getString(0) else "Drama"
                    val network = obj.optJSONObject("network")?.optString("name") ?: "4K HDR"
                    topShows.add(
                        TrendingMedia(
                            title = name,
                            subtitle = "★ $score IMDb/TV • $genreStr",
                            badge = "TRENDING ON $network",
                            accentColor = colors[topShows.size % colors.size],
                            targetPackage = "com.google.android.youtube.tv",
                            deepLinkUrl = "https://www.youtube.com/results?search_query=${Uri.encode("$name official trailer 4k")}"
                        )
                    )
                }
            }
            if (topShows.isNotEmpty()) return@withContext defaultShelf + topShows
        }
    } catch (_: Exception) {}
    defaultShelf
}

suspend fun fetchLiveWeather(): String = withContext(Dispatchers.IO) {
    try {
        val conn = (URL("https://wttr.in/?format=%t+%C").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("User-Agent", "curl/7.68.0")
        }
        if (conn.responseCode == 200) {
            val text = conn.inputStream.bufferedReader().readText().trim()
            if (text.isNotEmpty() && text.length < 32) return@withContext text
        }
    } catch (_: Exception) {}
    "Android 14 • Mini LED"
}

@Composable
fun TclAppleTvLauncher(
    apps: List<TvApp>,
    isPointerActive: Boolean,
    isWaitingToBindKey: Boolean,
    nightCinemaMode: Boolean,
    onToggleNightMode: () -> Unit,
    pointerX: Float,
    pointerY: Float,
    isPointerClicking: Boolean,
    onScreenSizeChanged: (IntSize) -> Unit,
    onTogglePointerMode: () -> Unit,
    onStartKeyBind: () -> Unit,
    onLaunchApp: (String, String?) -> Unit,
    onOpenInputs: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }
    var weatherText by remember { mutableStateOf("Loading Weather...") }
    var cinemaShelf by remember {
        mutableStateOf(
            listOf(
                TrendingMedia("PlayStation / HDMI", "Switch to Console & eARC Soundbar", "🎮 CONSOLE & INPUTS", Color(0xFF0070D1), "TCL_HDMI_INPUTS"),
                TrendingMedia("Continue Watching", "4K Dolby Vision • HDR10+", "NETFLIX", Color(0xFFE50914), "com.netflix.ninja"),
                TrendingMedia("Prime Video Cinema", "IMDb Top Rated • IMAX Enhanced", "PRIME VIDEO", Color(0xFF00A8E1), "com.amazon.amazonvideo.livingroom"),
                TrendingMedia("SonyLIV Sports & Originals", "Live Cricket, Football & Shows", "SONYLIV", Color(0xFF6C5CE7), "com.sonyliv"),
                TrendingMedia("YouTube 4K Lounge", "60fps HDR • Ambient & Music", "YOUTUBE", Color(0xFFFF0000), "com.google.android.youtube.tv")
            )
        )
    }

    LaunchedEffect(Unit) {
        // Request initial D-Pad focus on the first Top Shelf card immediately on startup
        delay(150)
        runCatching { initialFocusRequester.requestFocus() }
        cinemaShelf = fetchLiveTrendingShows()
        weatherText = fetchLiveWeather()
        while (true) {
            val now = Date()
            currentTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
            currentDate = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
            delay(10_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { onScreenSizeChanged(it) }
            .background(Color.Black)
            .alpha(if (nightCinemaMode) 0.62f else 1.0f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 42.dp, vertical = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Smart TV Pro • 65\" Mini LED",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    QuickActionPill(
                        label = if (isPointerActive) "🔵 Pointer: ON (Press Blue to Exit)" else "🔵 Blue Key: Pointer",
                        onClick = onTogglePointerMode
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    QuickActionPill(
                        label = if (isWaitingToBindKey) "⏳ Press Blue Remote Button..." else "🟢 HDMI • 🟡 Dimmer • ⚙️ Rebind",
                        onClick = onStartKeyBind
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickActionPill("🎮 PlayStation / HDMI") { onOpenInputs() }
                    QuickActionPill(if (nightCinemaMode) "☀️ Bright" else "🌙 Night Dimmer") { onToggleNightMode() }
                    QuickActionPill("📶 Wi-Fi") { onOpenWifi() }
                    QuickActionPill("⚙️ Settings") { onOpenSettings() }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(currentTime, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("$currentDate • $weatherText", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "CINEMA & GAMING LOUNGE • QUICK LAUNCH & LIVE IMDb TOP PICKS",
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(cinemaShelf) { index, item ->
                    CinemaHeroCard(
                        pick = item,
                        modifier = if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier,
                        onClick = { onLaunchApp(item.targetPackage, item.deepLinkUrl) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(26.dp))
            Text(
                text = "ALL INSTALLED APPS (${apps.size})",
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppleTvAppTile(app = app, onClick = { onLaunchApp(app.packageName, null) })
                }
            }
        }
        if (isPointerActive) {
            VirtualPointerCursor(x = pointerX, y = pointerY, isClicking = isPointerClicking)
        }
    }
}

@Composable
fun VirtualPointerCursor(x: Float, y: Float, isClicking: Boolean) {
    val clickScale by animateFloatAsState(if (isClicking) 0.78f else 1.0f, label = "cursorClickScale")
    val cursorSizePx = with(LocalDensity.current) { 36.dp.toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((x - cursorSizePx / 2f).roundToInt(), (y - cursorSizePx / 2f).roundToInt()) }
            .size(36.dp)
            .scale(clickScale)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color(0xFF0A84FF).copy(alpha = 0.35f), radius = size.minDimension / 2f, center = center)
            drawCircle(color = Color.White, radius = size.minDimension / 2.6f, center = center, style = Stroke(width = 3.dp.toPx()))
            drawCircle(color = if (isClicking) Color(0xFF30D158) else Color(0xFF0A84FF), radius = 4.5.dp.toPx(), center = center)
        }
    }
}

@Composable
fun QuickActionPill(label: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1.0f, label = "pillScale")
    Box(
        modifier = Modifier
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .background(if (isFocused) Color.White else Color(0xFF161618))
            .border(1.dp, if (isFocused) Color.White else Color(0xFF2C2C2E), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (isFocused) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CinemaHeroCard(pick: TrendingMedia, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.06f else 1.0f, label = "cardScale")
    Box(
        modifier = modifier
            .width(265.dp)
            .height(128.dp)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(colors = listOf(pick.accentColor.copy(alpha = 0.50f), Color(0xFF121214))))
            .border(if (isFocused) 2.5.dp else 1.dp, if (isFocused) Color.White else Color(0xFF2C2C2E), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(pick.badge.uppercase(), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column {
                Text(pick.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(3.dp))
                Text(pick.subtitle, color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun AppleTvAppTile(app: TvApp, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.10f else 1.0f, label = "tileScale")
    val bitmap: ImageBitmap? = remember(app.packageName) {
        val drawable = app.banner ?: app.icon
        runCatching { drawable?.toBitmap(width = 240, height = 135)?.asImageBitmap() }.getOrNull()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (isFocused) Color(0xFF242428) else Color(0xFF141416))
                .border(if (isFocused) 2.5.dp else 1.dp, if (isFocused) Color.White else Color(0xFF262629), RoundedCornerShape(22.dp))
                .padding(12.dp)
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = app.name, modifier = Modifier.fillMaxSize())
            } else {
                Text(app.name.take(2).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(app.name, color = if (isFocused) Color.White else Color.Gray, fontSize = 13.sp, fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
