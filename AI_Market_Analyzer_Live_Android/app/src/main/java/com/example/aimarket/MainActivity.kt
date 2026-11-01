package com.example.aimarket

import android.Manifest
import android.app.Notification
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest
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
    var observedContext by remember { mutableStateOf("") }
    var observedSignal by remember { mutableStateOf<String?>(null) }
    var observedClosedCandleTime by remember { mutableLongStateOf(0L) }

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
            notificationsEnabled = granted || Build.VERSION.SDK_INT < 33
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

    DisposableEffect(symbol, interval, refreshKey) {
        val repo = LiveMarketRepository { data, live, message ->
            candles = data
            connected = live
            status = message
        }

        repo.start(symbol, interval)

        onDispose {
            repo.stop()
        }
    }

    val result = remember(candles) {
        AnalysisEngine.analyze(candles)
    }

    val lastPrice = candles.lastOrNull()?.close

    LaunchedEffect(symbol, interval, candles, result) {
        val closedCandle = candles.lastOrNull { it.closed }
        val signal = result?.signal

        if (closedCandle == null || signal == null) {
            return@LaunchedEffect
        }

        val currentContext = "$symbol|$interval"

        if (observedContext != currentContext ||
            observedClosedCandleTime == 0L
        ) {
            observedContext = currentContext
            observedClosedCandleTime = closedCandle.time
            observedSignal = signal
            return@LaunchedEffect
        }

        if (closedCandle.time > observedClosedCandleTime) {
            val isNewActionSignal =
                signal in listOf("BUY", "SELL") && signal != observedSignal

            if (isNewActionSignal && hasNotificationPermission(context)) {
                showSignalNotification(
                    context,
                    symbol,
                    interval,
                    signal,
                    result.score,
                    result.lastPrice
                )
            }

            observedClosedCandleTime = closedCandle.time
            observedSignal = signal
        }
    }

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
                            Chip(pair, symbol == pair) { symbol = pair }
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
                            "Chart dan sinyal XAUUSD disembunyikan karena pasar tutup, data kedaluwarsa, atau feed belum tersedia. Coba refresh saat pasar aktif.",
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
                        Text("AUTO SIGNAL", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                        val shownSignal =
                            if (symbol == "XAUUSD" && !connected) {
                                "MARKET CLOSED"
                            } else {
                                result?.signal ?: "ANALYZING"
                            }

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
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = when (shownSignal) {
                                    "BUY" -> Green
                                    "SELL" -> Red
                                    else -> Amber
                                },
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Text(
                        if (result == null) {
                            "Mengumpulkan minimal 52 candle tertutup untuk analisis."
                        } else {
                            "Skor aturan teknikal: ${result.score}/100"
                        },
                        color = Muted,
                        fontSize = 12.sp
                    )

                    if (symbol == "XAUUSD" && !connected) {
                        Text(
                            "Tidak ada sinyal baru saat pasar tutup atau data XAUUSD tidak valid.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    } else if (result != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Metric("RSI 14", result.rsi?.let { "%.1f".format(it) } ?: "—")
                            Metric("EMA 9", result.ema9?.let(::price) ?: "—")
                            Metric("EMA 21", result.ema21?.let(::price) ?: "—")
                            Metric("EMA 50", result.ema50?.let(::price) ?: "—")
                        }

                        HorizontalDivider(color = Color(0xFF283448))
                        Text("Alasan sinyal", fontWeight = FontWeight.Bold)

                        result.reasons.forEach { reason ->
                            Text(
                                "• $reason",
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }

                        val waiting = result.signal == "WAIT"

                        Text("LEVEL ENTRY & TARGET", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Metric("Entry", if (waiting) "WAIT" else result.entryPrice?.let(::price) ?: "—")
                            Metric("Stop Loss", if (waiting) "WAIT" else result.stopLoss?.let(::price) ?: "—")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Metric("TP 1", if (waiting) "WAIT" else result.takeProfit1?.let(::price) ?: "—")
                            Metric("TP 2", if (waiting) "WAIT" else result.takeProfit2?.let(::price) ?: "—")
                            Metric("TP 3", if (waiting) "WAIT" else result.takeProfit3?.let(::price) ?: "—")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Metric("ATR 14", result.atr?.let(::price) ?: "—")
                        }

                        Text(
                            if (waiting) {
                                "WAIT berarti belum ada konfirmasi yang cukup. Level entry, SL, dan TP belum diberikan."
                            } else {
                                "*Level estimasi berbasis ATR dan volatilitas timeframe; bukan instruksi order. Harga eksekusi dapat berbeda. Periksa spread, biaya, dan risiko."
                            },
                            color = Amber,
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
                        JournalStat(
                            "OPEN",
                            (totalJournalCount - closedJournalCount).coerceAtLeast(0).toString(),
                            Amber,
                            Modifier.weight(1f)
                        )
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
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val intent = android.content.Intent(
                                context,
                                BackgroundSignalService::class.java
                            ).putExtra(BackgroundSignalService.EXTRA_SYMBOL, symbol)
                                .putExtra(BackgroundSignalService.EXTRA_INTERVAL, interval)

                            if (backgroundEnabled) {
                                intent.action = BackgroundSignalService.ACTION_STOP
                                context.startService(intent)
                                backgroundEnabled = false
                                prefs.edit().putBoolean("enabled", false).apply()
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
                        if (backgroundEnabled) "Hentikan pemantauan latar belakang"
                        else "Mulai pemantauan latar belakang (15 detik)",
                        color = if (backgroundEnabled) Red else Green
                    )
                }

                Text(
                    "Pemantauan memakai layanan Android yang berjalan dengan notifikasi permanen. Pilihan pair/timeframe disimpan saat dipilih.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationsEnabled = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF17382F))
                ) {
                    Text(
                        if (notificationsEnabled) "Notifikasi BUY / SELL aktif ✓"
                        else "Aktifkan notifikasi BUY / SELL",
                        color = Green
                    )
                }

                Text(
                    if (notificationsEnabled) {
                        "Notifikasi aplikasi terbuka muncul saat koneksi live aktif dan sinyal berubah ke BUY/SELL pada candle tertutup."
                    } else {
                        "Aktifkan izin notifikasi agar HP memberi peringatan saat muncul sinyal BUY/SELL baru."
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
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun JournalEntryCard(entry: TradingJournal) {
    val isWin = entry.status == TradingJournal.STATUS_TP3 ||
        entry.status == TradingJournal.STATUS_TP2_FINAL ||
        entry.status == TradingJournal.STATUS_TP1_FINAL

    val statusColor = when {
        entry.status == TradingJournal.STATUS_OPEN -> Amber
        entry.status == TradingJournal.STATUS_SL -> Red
        isWin -> Green
        else -> Muted
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
                Metric("HASIL (R)", entry.resultR?.let { "%+.2fR".format(it) } ?: "—")
            }

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
            description = "Peringatan saat sinyal crypto berubah menjadi BUY atau SELL baru"
        }

        manager.createNotificationChannel(channel)
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

private fun showSignalNotification(
    context: Context,
    symbol: String,
    interval: String,
    signal: String,
    score: Int,
    currentPrice: Double
) {
    if (!hasNotificationPermission(context)) return

    val manager = context.getSystemService(
        Context.NOTIFICATION_SERVICE
    ) as NotificationManager

    val currency = when (symbol) {
        "BTCUSD", "XAUUSD" -> "USD"
        else -> "USDT"
    }

    val body = "$symbol • $interval • skor $score/100 • harga ${price(currentPrice)} $currency"

    val notification =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, SIGNAL_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Sinyal $signal baru — $symbol")
            .setContentText(body)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "$body\nSinyal berbasis aturan teknikal, bukan jaminan profit. Periksa chart sebelum mengambil keputusan."
                )
            )
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

    manager.notify((symbol + interval).hashCode(), notification)
}
