package com.example.aimarket

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private val Bg = Color(0xFF080D18)
private val Panel = Color(0xFF111A2A)
private val Muted = Color(0xFF9AA8BD)
private val Green = Color(0xFF36D399)
private val Red = Color(0xFFFF657A)
private val Amber = Color(0xFFFFC857)

private const val SIGNAL_CHANNEL_ID = "market_signal_alerts"
private const val PREFS_NAME = "market_monitor"
private const val PREF_SYMBOL = "symbol"
private const val PREF_INTERVAL = "interval"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createSignalNotificationChannel(this)
        setContent {
            LiveAnalyzerApp()
        }
    }
}

@Composable
private fun LiveAnalyzerApp() {
    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val journalRepository = remember {
        TradingJournalRepository(context.applicationContext)
    }

    var notificationsEnabled by remember {
        mutableStateOf(hasNotificationPermission(context))
    }

    var backgroundEnabled by remember {
        mutableStateOf(prefs.getBoolean("enabled", false))
    }

    // Menandai bahwa dialog izin diminta dari tombol pemantauan background.
    var pendingBackgroundStart by remember {
        mutableStateOf(false)
    }

    var symbol by remember {
        mutableStateOf(prefs.getString(PREF_SYMBOL, "BTCUSD") ?: "BTCUSD")
    }

    var interval by remember {
        mutableStateOf(prefs.getString(PREF_INTERVAL, "1m") ?: "1m")
    }

    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var connected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Menghubungkan…") }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Data dan status candle timeframe yang lebih tinggi.
    var higherTimeframeCandles by remember {
        mutableStateOf<List<Candle>>(emptyList())
    }
    var higherTimeframeName by remember {
        mutableStateOf(HigherTimeframeFilter.higherTimeframeFor(interval).orEmpty())
    }
    var higherTimeframeReady by remember { mutableStateOf(false) }
    var higherTimeframeStatus by remember {
        mutableStateOf("Menunggu data timeframe tinggi…")
    }

    var journalEntries by remember {
        mutableStateOf<List<TradingJournal>>(emptyList())
    }

    var totalJournalCount by remember { mutableIntStateOf(0) }
    var closedJournalCount by remember { mutableIntStateOf(0) }
    var stopLossCount by remember { mutableIntStateOf(0) }
    var winningCount by remember { mutableIntStateOf(0) }
    var journalFilter by remember { mutableStateOf("PAIR AKTIF") }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            notificationsEnabled = hasNotificationPermission(context)

            // Jika izin diberikan dari tombol background, langsung mulai service.
            if (granted && pendingBackgroundStart) {
                val intent = android.content.Intent(
                    context,
                    BackgroundSignalService::class.java
                ).putExtra(
                    BackgroundSignalService.EXTRA_SYMBOL,
                    symbol
                ).putExtra(
                    BackgroundSignalService.EXTRA_INTERVAL,
                    interval
                )

                ContextCompat.startForegroundService(context, intent)

                backgroundEnabled = true

                prefs.edit()
                    .putBoolean("enabled", true)
                    .putString(PREF_SYMBOL, symbol)
                    .putString(PREF_INTERVAL, interval)
                    .apply()
            }

            pendingBackgroundStart = false
        }

    DisposableEffect(context, prefs) {
        val activity = context as? ComponentActivity

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundEnabled = prefs.getBoolean("enabled", false)
                notificationsEnabled = hasNotificationPermission(context)
            }
        }

        activity?.lifecycle?.addObserver(observer)

        onDispose {
            activity?.lifecycle?.removeObserver(observer)
        }
    }

    LaunchedEffect(symbol, interval) {
        prefs.edit()
            .putString(PREF_SYMBOL, symbol)
            .putString(PREF_INTERVAL, interval)
            .apply()
    }

    LaunchedEffect(journalRepository) {
        launch {
            journalRepository.observeAll().collectLatest {
                journalEntries = it
            }
        }

        launch {
            journalRepository.observeTotalCount().collectLatest {
                totalJournalCount = it
            }
        }

        launch {
            journalRepository.observeClosedCount().collectLatest {
                closedJournalCount = it
            }
        }

        launch {
            journalRepository.observeStopLossCount().collectLatest {
                stopLossCount = it
            }
        }

        launch {
            journalRepository.observeWinningCount().collectLatest {
                winningCount = it
            }
        }
    }

    // Repository mengambil candle aktif dan candle timeframe tinggi
    // dari sumber feed yang sama untuk pair yang sedang dipilih.
    DisposableEffect(symbol, interval, refreshKey) {
        higherTimeframeCandles = emptyList()
        higherTimeframeName =
            HigherTimeframeFilter.higherTimeframeFor(interval).orEmpty()
        higherTimeframeReady = false
        higherTimeframeStatus = if (higherTimeframeName.isBlank()) {
            "Timeframe tidak didukung"
        } else {
            "Menunggu data $higherTimeframeName…"
        }

        val repo = LiveMarketRepository(
            onHigherTimeframeUpdate = { data, timeframeName, ready, message ->
                higherTimeframeCandles = data
                higherTimeframeName = timeframeName
                higherTimeframeReady = ready
                higherTimeframeStatus = message
            },
            onUpdate = { data, live, message ->
                candles = data
                connected = live
                status = message
            }
        )

        repo.start(symbol, interval)

        onDispose {
            repo.stop()
        }
    }

    // Sinyal baru hanya boleh lolos jika filter HTF tersedia dan searah.
    // Jika data HTF belum siap, AnalysisEngine harus menghasilkan WAIT.
    val result = remember(
        candles,
        higherTimeframeCandles,
        higherTimeframeReady,
        interval
    ) {
        AnalysisEngine.analyze(
            candles = candles,
            higherTimeframeCandles = if (higherTimeframeReady) {
                higherTimeframeCandles
            } else {
                null
            },
            timeframe = interval
        )
    }

    val lastPrice = candles.lastOrNull()?.close

    val openJournalCount = journalEntries.count {
        it.status == TradingJournal.STATUS_OPEN
    }

    // Prioritaskan posisi OPEN yang cocok dengan pair dan timeframe aktif.
    val activeJournal = journalEntries
        .asSequence()
        .filter {
            it.status == TradingJournal.STATUS_OPEN &&
                it.symbol.equals(symbol, ignoreCase = true) &&
                it.timeframe.equals(interval, ignoreCase = true)
        }
        .maxByOrNull { it.signalTime }

    // Sinyal jurnal tetap ditampilkan selama posisi virtual masih OPEN.
    val shownSignal = when {
        activeJournal != null -> activeJournal.direction
        symbol == "XAUUSD" && !connected -> "MARKET CLOSED"
        result != null -> result.signal
        else -> "ANALYZING"
    }

    val shownScore = activeJournal?.score ?: result?.score
    val shownEntry = activeJournal?.entryPrice ?: result?.entryPrice
    val shownStop = activeJournal?.stopLoss ?: result?.stopLoss
    val shownTp1 = activeJournal?.takeProfit1 ?: result?.takeProfit1
    val shownTp2 = activeJournal?.takeProfit2 ?: result?.takeProfit2
    val shownTp3 = activeJournal?.takeProfit3 ?: result?.takeProfit3

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Panel,
            primary = Green,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Bg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "MARKET LAB",
                            color = Green,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )

                        Text(
                            "AI Market Analyzer",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        color = if (connected) Color(0xFF17382F)
                        else Color(0xFF49351A),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            if (connected) "● LIVE" else "● OFFLINE",
                            modifier = Modifier.padding(
                                horizontal = 11.dp,
                                vertical = 7.dp
                            ),
                            color = if (connected) Green else Amber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                PanelCard {
                    Text(
                        when (symbol) {
                            "BTCUSD" -> "BTC / USD • BITSTAMP FEED"
                            "XAUUSD" -> "GOLD / XAUUSD • BIQUOTE FEED"
                            else -> "CRYPTO • DATA PUBLIK BINANCE"
                        },
                        color = Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        listOf(
                            "BTCUSD", "BTCUSDT", "ETHUSDT", "BNBUSDT",
                            "SOLUSDT", "XRPUSDT", "XAUUSD"
                        ).forEach { pair ->
                            Chip(pair, symbol == pair) {
                                symbol = pair
                            }
                        }
                    }

                    Text("Timeframe candle", color = Muted, fontSize = 12.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("1m", "5m", "15m", "1h").forEach { timeframe ->
                            Chip(timeframe, interval == timeframe) {
                                interval = timeframe
                            }
                        }
                    }
                }

                PanelCard {
                    Text(symbol, color = Muted, fontSize = 13.sp)

                    Text(
                        lastPrice?.let(::price) ?: "Menunggu data…",
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        status,
                        color = if (connected) Green else Amber,
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(4.dp))

                    if (symbol == "XAUUSD" && !connected) {
                        Text(
                            "Feed XAUUSD belum tersedia atau tidak terhubung. Posisi jurnal yang masih OPEN tetap ditampilkan, tetapi harga live belum tentu tersedia.",
                            color = Amber,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    } else {
                        CandleChart(
                            Modifier.fillMaxWidth().height(145.dp),
                            candles.takeLast(70)
                        )

                        Text(
                            when (symbol) {
                                "BTCUSD" -> "Harga BTC/USD • sumber Bitstamp • candle tertutup untuk analisis"
                                "XAUUSD" -> "Harga emas • sumber Biquote • candle tertutup untuk analisis"
                                else -> "Harga crypto • sumber Binance • candle tertutup untuk analisis"
                            },
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }

                PanelCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "AUTO SIGNAL",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        Surface(
                            color = when (shownSignal) {
                                "BUY" -> Color(0xFF17382F)
                                "SELL" -> Color(0xFF48212A)
                                else -> Color(0xFF49351A)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                shownSignal,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 8.dp
                                ),
                                color = when (shownSignal) {
                                    "BUY" -> Green
                                    "SELL" -> Red
                                    else -> Amber
                                },
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    if (activeJournal != null) {
                        Text(
                            "POSISI VIRTUAL MASIH OPEN • Sinyal ${activeJournal.direction} dipertahankan dari Trading Journal sampai posisi ditutup.",
                            color = Green,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Level entry, SL, dan TP di bawah berasal dari posisi jurnal yang aktif, bukan dari perhitungan sinyal terbaru.",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )

                        if (activeJournal.tp1Hit || activeJournal.tp2Hit || activeJournal.tp3Hit) {
                            Text(
                                "Target tercapai: " + buildList {
                                    if (activeJournal.tp1Hit) add("TP1")
                                    if (activeJournal.tp2Hit) add("TP2")
                                    if (activeJournal.tp3Hit) add("TP3")
                                }.joinToString(", ") +
                                    " • Sisa posisi %.0f%%".format(
                                        activeJournal.remainingFraction.coerceIn(0.0, 1.0) * 100.0
                                    ),
                                color = Amber,
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        Text(
                            when {
                                result == null ->
                                    "Mengumpulkan minimal 52 candle tertutup untuk analisis."
                                else ->
                                    "Skor aturan teknikal: ${result.score}/100"
                            },
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }

                    if (activeJournal == null && symbol == "XAUUSD" && !connected) {
                        Text(
                            "Tidak ada sinyal baru saat feed XAUUSD tidak tersedia.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }

                    if (activeJournal == null) {
                        Text(
                            "FILTER HTF ${higherTimeframeName.ifBlank { "—" }} • $higherTimeframeStatus",
                            color = if (higherTimeframeReady) Green else Amber,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }

                    if (result != null && connected) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Metric("RSI 14", result.rsi?.let { "%.1f".format(it) } ?: "—")
                            Metric("EMA 9", result.ema9?.let(::price) ?: "—")
                            Metric("EMA 21", result.ema21?.let(::price) ?: "—")
                            Metric("EMA 50", result.ema50?.let(::price) ?: "—")
                        }
                    }

                    if (activeJournal == null && result != null && connected) {
                        HorizontalDivider(color = Color(0xFF283448))
                        Text("Alasan sinyal terbaru", fontWeight = FontWeight.Bold)

                        result.reasons.forEach { reason ->
                            Text(
                                "• $reason",
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    } else if (activeJournal != null && !activeJournal.note.isNullOrBlank()) {
                        HorizontalDivider(color = Color(0xFF283448))
                        Text("Catatan posisi", fontWeight = FontWeight.Bold)
                        Text(
                            activeJournal.note!!,
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }

                    val hasActivePosition = activeJournal != null
                    val waiting = !hasActivePosition &&
                        (result == null || result.signal == "WAIT")

                    Text(
                        "LEVEL ENTRY & TARGET",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Metric("Entry", if (waiting) "WAIT" else shownEntry?.let(::price) ?: "—")
                        Metric("Stop Loss", if (waiting) "WAIT" else shownStop?.let(::price) ?: "—")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Metric("TP 1", if (waiting) "WAIT" else shownTp1?.let(::price) ?: "—")
                        Metric("TP 2", if (waiting) "WAIT" else shownTp2?.let(::price) ?: "—")
                        Metric("TP 3", if (waiting) "WAIT" else shownTp3?.let(::price) ?: "—")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Metric("ATR 14", result?.atr?.let(::price) ?: "—")
                        if (activeJournal != null) {
                            Metric("HASIL BERJALAN", "%+.2fR".format(activeJournal.realizedR))
                        }
                    }

                    Text(
                        when {
                            activeJournal != null ->
                                "Posisi virtual masih OPEN. Level jurnal dipertahankan; harga live dapat berubah dan hasil virtual bukan hasil transaksi broker."
                            waiting ->
                                "WAIT berarti belum ada konfirmasi yang cukup. Level entry, SL, dan TP untuk sinyal baru belum tersedia."
                            else ->
                                "*Level estimasi berbasis ATR dan volatilitas timeframe; bukan instruksi order. Harga eksekusi dapat berbeda. Periksa spread, biaya, dan risiko."
                        },
                        color = Amber,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )

                    if (shownScore != null && activeJournal != null) {
                        Text(
                            "Skor saat sinyal dicatat: $shownScore/100",
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                }

                // TRADING JOURNAL
                PanelCard {
                    Text(
                        "TRADING JOURNAL",
                        color = Green,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Text(
                        "Riwayat sinyal virtual dari database lokal di HP.",
                        color = Muted,
                        fontSize = 11.sp
                    )

                    val winRate = if (closedJournalCount > 0) {
                        winningCount * 100.0 / closedJournalCount
                    } else {
                        0.0
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        JournalStat("TOTAL", totalJournalCount.toString(), Green, Modifier.weight(1f))
                        JournalStat("OPEN", openJournalCount.toString(), Amber, Modifier.weight(1f))
                        JournalStat("SELESAI", closedJournalCount.toString(), Color.White, Modifier.weight(1f))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        JournalStat("MENANG", winningCount.toString(), Green, Modifier.weight(1f))
                        JournalStat("STOP LOSS", stopLossCount.toString(), Red, Modifier.weight(1f))
                        JournalStat("WIN RATE", "%.1f%%".format(winRate), Amber, Modifier.weight(1f))
                    }

                    Text("Filter riwayat", color = Muted, fontSize = 12.sp)

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Chip("PAIR AKTIF", journalFilter == "PAIR AKTIF") {
                            journalFilter = "PAIR AKTIF"
                        }
                        Chip("SEMUA PAIR", journalFilter == "SEMUA PAIR") {
                            journalFilter = "SEMUA PAIR"
                        }
                    }

                    val filteredEntries = journalEntries.filter {
                        journalFilter == "SEMUA PAIR" || it.symbol == symbol
                    }

                    if (filteredEntries.isEmpty()) {
                        Text(
                            "Belum ada catatan sinyal untuk filter ini. Sinyal BUY/SELL akan tampil jika layanan pencatatan berhasil menyimpannya.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    } else {
                        filteredEntries.take(50).forEach { entry ->
                            JournalEntryCard(entry)
                        }

                        if (filteredEntries.size > 50) {
                            Text(
                                "Menampilkan 50 catatan terbaru dari ${filteredEntries.size} catatan.",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Button(
                    onClick = { refreshKey++ },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF17382F))
                ) {
                    Text("Reconnect / Refresh data", color = Green)
                }

                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context)) {
                            pendingBackgroundStart = true
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val intent = android.content.Intent(
                                context,
                                BackgroundSignalService::class.java
                            ).putExtra(
                                BackgroundSignalService.EXTRA_SYMBOL,
                                symbol
                            ).putExtra(
                                BackgroundSignalService.EXTRA_INTERVAL,
                                interval
                            )

                            if (backgroundEnabled) {
                                intent.action = BackgroundSignalService.ACTION_STOP
                                context.startService(intent)
                                backgroundEnabled = false
                                prefs.edit()
                                    .putBoolean("enabled", false)
                                    .apply()
                            } else {
                                ContextCompat.startForegroundService(context, intent)
                                backgroundEnabled = true
                                prefs.edit()
                                    .putBoolean("enabled", true)
                                    .putString(PREF_SYMBOL, symbol)
                                    .putString(PREF_INTERVAL, interval)
                                    .apply()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (backgroundEnabled) Color(0xFF4A2530) else Color(0xFF17382F)
                    )
                ) {
                    Text(
                        if (backgroundEnabled) {
                            "Hentikan pemantauan latar belakang"
                        } else {
                            "Mulai pemantauan latar belakang (15 detik)"
                        },
                        color = if (backgroundEnabled) Red else Green
                    )
                }

                Text(
                    if (backgroundEnabled) {
                        "Pemantauan telah diminta aktif. Status ini mengikuti preferensi aplikasi dan bukan pemeriksaan langsung proses Android."
                    } else {
                        "Pemantauan latar belakang tidak diaktifkan. Pilihan pair/timeframe tetap disimpan saat dipilih."
                    },
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context)) {
                            pendingBackgroundStart = false
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationsEnabled = hasNotificationPermission(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF17382F))
                ) {
                    Text(
                        if (notificationsEnabled) {
                            "Notifikasi BUY / SELL aktif ✓"
                        } else {
                            "Aktifkan notifikasi BUY / SELL"
                        },
                        color = Green
                    )
                }

                Text(
                    if (notificationsEnabled) {
                        "Izin notifikasi tersedia. Notifikasi entry baru dikelola oleh layanan pemantauan latar belakang agar tidak terjadi notifikasi ganda."
                    } else {
                        "Aktifkan izin notifikasi dan pastikan notifikasi aplikasi tidak diblokir di pengaturan Android."
                    },
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Text(
                    "BTCUSD memakai feed Bitstamp, pair crypto lain memakai Binance, dan XAUUSD memakai feed Biquote. Tidak melakukan transaksi otomatis. Harga antarplatform dapat berbeda karena spread, waktu pembaruan, dan sumber data.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun JournalStat(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF182338),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                label,
                color = Muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                value,
                color = color,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
private fun JournalEntryCard(entry: TradingJournal) {
    val isClosed = entry.status != TradingJournal.STATUS_OPEN &&
        entry.status != TradingJournal.STATUS_CANCELLED

    val finalR = entry.resultR ?: entry.realizedR
    val isProfit = isClosed && finalR > 0.0

    val statusColor = when {
        entry.status == TradingJournal.STATUS_OPEN -> Amber
        entry.status == TradingJournal.STATUS_SL && !isProfit -> Red
        isProfit -> Green
        entry.status == TradingJournal.STATUS_SL -> Amber
        else -> Muted
    }

    val closedPercent = (
        (1.0 - entry.remainingFraction) * 100.0
    ).coerceIn(0.0, 100.0)

    val displayedR = if (isClosed) {
        entry.resultR ?: entry.realizedR
    } else {
        entry.realizedR
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0C1423),
        shape = RoundedCornerShape(13.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "${entry.symbol} • ${entry.timeframe}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    Text(
                        "${entry.direction} • Skor ${entry.score}/100",
                        color = if (entry.direction == "BUY") Green else Red,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = statusColor.copy(alpha = 0.13f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        displayJournalStatus(entry),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF283448))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Metric("ENTRY", price(entry.entryPrice))
                Metric("STOP LOSS", price(entry.stopLoss))
                Metric("TP 1", price(entry.takeProfit1))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Metric("TP 2", price(entry.takeProfit2))
                Metric("TP 3", entry.takeProfit3?.let(::price) ?: "—")
                Metric(
                    if (isClosed) "HASIL AKHIR" else "PROFIT SEMENTARA",
                    "%+.2fR".format(displayedR)
                )
            }

            Text(
                "Posisi ditutup: %.0f%% • Sisa posisi: %.0f%%".format(
                    closedPercent,
                    entry.remainingFraction.coerceIn(0.0, 1.0) * 100.0
                ),
                color = if (closedPercent > 0.0) Green else Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            LinearProgressIndicator(
                progress = { closedPercent.toFloat() / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Green,
                trackColor = Color(0xFF283448)
            )

            Text(
                "Alokasi: TP1 50% • TP2 30% • TP3 20%",
                color = Muted,
                fontSize = 10.sp
            )

            Text(
                "Sinyal: ${formatJournalTime(entry.signalTime)}",
                color = Muted,
                fontSize = 10.sp
            )

            if (entry.exitTime != null) {
                Text(
                    "Selesai: ${formatJournalTime(entry.exitTime)}",
                    color = Muted,
                    fontSize = 10.sp
                )
            }

            val targets = buildList {
                add(if (entry.tp1Hit) "TP1 ✓" else "TP1 —")
                add(if (entry.tp2Hit) "TP2 ✓" else "TP2 —")
                if (entry.takeProfit3 != null) {
                    add(if (entry.tp3Hit) "TP3 ✓" else "TP3 —")
                }
            }

            Text(
                targets.joinToString("   "),
                color = if (entry.tp1Hit || entry.tp2Hit || entry.tp3Hit) Green else Muted,
                fontSize = 10.sp
            )

            if (!entry.note.isNullOrBlank()) {
                Text(entry.note, color = Amber, fontSize = 10.sp)
            }
        }
    }
}

private fun displayJournalStatus(entry: TradingJournal): String {
    return when (entry.status) {
        TradingJournal.STATUS_OPEN -> {
            when {
                entry.tp3Hit -> "TP3 TERCAPAI"
                entry.tp2Hit -> "TP2 TERCAPAI"
                entry.tp1Hit -> "TP1 TERCAPAI"
                else -> "OPEN"
            }
        }

        TradingJournal.STATUS_TP3 -> "TP3 SELESAI"
        TradingJournal.STATUS_TP2_FINAL -> "TP2 SELESAI"
        TradingJournal.STATUS_TP1_FINAL -> "TP1 SELESAI"
        TradingJournal.STATUS_SL -> "STOP LOSS"
        TradingJournal.STATUS_CANCELLED -> "DIBATALKAN"
        else -> entry.status
    }
}

private fun formatJournalTime(time: Long): String {
    return try {
        java.text.SimpleDateFormat(
            "dd MMM yyyy, HH:mm",
            java.util.Locale.getDefault()
        ).format(java.util.Date(time))
    } catch (_: Exception) {
        time.toString()
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF17382F),
            selectedLabelColor = Green,
            containerColor = Panel,
            labelColor = Muted
        )
    )
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = content
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun CandleChart(modifier: Modifier, data: List<Candle>) {
    Canvas(modifier) {
        if (data.isEmpty()) return@Canvas

        val lo = data.minOf { it.low }
        val hi = data.maxOf { it.high }
        val range = max(hi - lo, 1e-9)
        val slot = size.width / data.size
        val body = max(2f, slot * 0.55f)

        fun y(v: Double): Float =
            size.height - (((v - lo) / range).toFloat() * size.height)

        data.forEachIndexed { i, candle ->
            val x = i * slot + slot / 2
            val color = if (candle.close >= candle.open) Green else Red

            drawLine(
                color = color,
                start = Offset(x, y(candle.high)),
                end = Offset(x, y(candle.low)),
                strokeWidth = 1.2f
            )

            val top = min(y(candle.open), y(candle.close))
            val bottom = max(y(candle.open), y(candle.close))

            drawRect(
                color = color,
                topLeft = Offset(x - body / 2, top),
                size = androidx.compose.ui.geometry.Size(
                    body,
                    max(2f, bottom - top)
                )
            )
        }
    }
}

private fun price(v: Double): String =
    if (v >= 1000) "%,.2f".format(v)
    else if (v >= 1) "%.4f".format(v)
    else "%.6f".format(v)

private fun createSignalNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val channel = NotificationChannel(
            SIGNAL_CHANNEL_ID,
            "Sinyal BUY / SELL",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description =
                "Peringatan saat entry BUY atau SELL baru berhasil dicatat"
        }

        manager.createNotificationChannel(channel)
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    val runtimePermissionGranted =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    return runtimePermissionGranted &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
