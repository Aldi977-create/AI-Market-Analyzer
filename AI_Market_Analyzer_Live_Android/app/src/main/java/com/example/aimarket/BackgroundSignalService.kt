package com.example.aimarket

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class BackgroundSignalService : Service() {

    private var repository: LiveMarketRepository? = null

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val processingMutex = Mutex()

    private lateinit var journal: TradingJournalRepository

    @Volatile
    private var lastClosedTime = 0L

    @Volatile
    private var activeSymbol = "BTCUSDT"

    @Volatile
    private var activeInterval = "1m"

    @Volatile
    private var feedGeneration = 0

    override fun onCreate() {
        super.onCreate()

        journal = TradingJournalRepository(applicationContext)
        createChannels()

        startForeground(
            FOREGROUND_ID,
            statusNotification("Memulai pemantauan market…")
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        val prefs =
            getSharedPreferences("market_monitor", MODE_PRIVATE)

        val symbol = (
            intent?.getStringExtra(EXTRA_SYMBOL)
                ?: prefs.getString("symbol", "BTCUSDT")
                ?: "BTCUSDT"
            ).trim().uppercase(Locale.US)

        val interval = (
            intent?.getStringExtra(EXTRA_INTERVAL)
                ?: prefs.getString("interval", "1m")
                ?: "1m"
            ).trim()

        if (symbol.isBlank() || interval.isBlank()) {
            updateStatus("Simbol atau timeframe tidak valid")
            return START_NOT_STICKY
        }

        activeSymbol = symbol
        activeInterval = interval

        prefs.edit()
            .putBoolean("enabled", true)
            .putString("symbol", symbol)
            .putString("interval", interval)
            .apply()

        // Invalidasi callback feed sebelumnya sebelum mengganti feed.
        val myFeedGeneration = synchronized(this) {
            feedGeneration += 1
            feedGeneration
        }

        repository?.stop()
        repository = null
        lastClosedTime = 0L

        updateStatus("Memulai pemantauan $symbol • $interval")

        repository = LiveMarketRepository { candles, live, message ->

            // Callback dari repository lama tidak boleh memproses data.
            if (!isCurrentFeed(myFeedGeneration, symbol, interval)) {
                return@LiveMarketRepository
            }

            updateStatus(
                if (live) {
                    "Memantau $symbol • $interval"
                } else {
                    message
                }
            )

            if (candles.isEmpty()) {
                return@LiveMarketRepository
            }

            serviceScope.launch {
                processingMutex.withLock {
                    if (!isCurrentFeed(
                            myFeedGeneration,
                            symbol,
                            interval
                        )
                    ) {
                        return@withLock
                    }

                    try {
                        val closedCandles = candles
                            .asSequence()
                            .filter { it.closed }
                            .filter {
                                it.time > 0L &&
                                    it.high.isFinite() &&
                                    it.low.isFinite() &&
                                    it.high > 0.0 &&
                                    it.low > 0.0 &&
                                    it.high >= it.low
                            }
                            .distinctBy { it.time }
                            .sortedBy { it.time }
                            .toList()

                        if (closedCandles.isEmpty()) {
                            return@withLock
                        }

                        // Proses candle tertutup secara kronologis.
                        for (candle in closedCandles) {
                            if (!isCurrentFeed(
                                    myFeedGeneration,
                                    symbol,
                                    interval
                                )
                            ) {
                                return@withLock
                            }

                            if (candle.time <= lastClosedTime) {
                                continue
                            }

                            journal.processClosedCandle(
                                symbol = symbol,
                                timeframe = interval,
                                candleTime = candle.time,
                                high = candle.high,
                                low = candle.low
                            )

                            lastClosedTime = candle.time
                        }

                        if (!isCurrentFeed(
                                myFeedGeneration,
                                symbol,
                                interval
                            )
                        ) {
                            return@withLock
                        }

                        val newestClosed = closedCandles.last()

                        val analysis =
                            AnalysisEngine.analyze(closedCandles)
                                ?: return@withLock

                        val signal =
                            analysis.signal.uppercase(Locale.US)

                        if (
                            signal != TradingJournal.DIRECTION_BUY &&
                            signal != TradingJournal.DIRECTION_SELL
                        ) {
                            return@withLock
                        }

                        // Posisi searah diperiksa di repository.
                        // BUY dan SELL berlawanan boleh dicatat terpisah.
                        if (
                            journal.hasOpenEntry(
                                symbol,
                                interval,
                                signal
                            )
                        ) {
                            updateStatus(
                                "Sinyal $signal dilewati: posisi searah masih OPEN"
                            )
                            return@withLock
                        }

                        val entry =
                            analysis.entryPrice ?: analysis.lastPrice

                        val sl = analysis.stopLoss
                        val tp1 = analysis.takeProfit1
                        val tp2 = analysis.takeProfit2
                        val tp3 = analysis.takeProfit3

                        if (
                            !entry.isFinite() ||
                            entry <= 0.0 ||
                            sl == null ||
                            !sl.isFinite() ||
                            tp1 == null ||
                            !tp1.isFinite() ||
                            tp2 == null ||
                            !tp2.isFinite()
                        ) {
                            updateStatus(
                                "Level sinyal $signal tidak valid"
                            )
                            return@withLock
                        }

                        // Pastikan level entry dan target sesuai arah.
                        val levelsValid =
                            if (signal == TradingJournal.DIRECTION_BUY) {
                                sl < entry &&
                                    tp1 > entry &&
                                    tp2 > tp1 &&
                                    (tp3 == null || (
                                        tp3.isFinite() && tp3 > tp2
                                    ))
                            } else {
                                sl > entry &&
                                    tp1 < entry &&
                                    tp2 < tp1 &&
                                    (tp3 == null || (
                                        tp3.isFinite() && tp3 < tp2
                                    ))
                            }

                        if (!levelsValid) {
                            updateStatus(
                                "Level sinyal $signal tidak konsisten"
                            )
                            return@withLock
                        }

                        // Jangan catat sinyal dari candle yang tidak valid.
                        if (newestClosed.time <= 0L) {
                            return@withLock
                        }

                        val insertedId = journal.recordSignal(
                            symbol = symbol,
                            timeframe = interval,
                            direction = signal,
                            signalTime = System.currentTimeMillis(),
                            signalCandleTime = newestClosed.time,
                            entryPrice = entry,
                            stopLoss = sl,
                            takeProfit1 = tp1,
                            takeProfit2 = tp2,
                            takeProfit3 = tp3,
                            score = analysis.score
                        )

                        if (insertedId > 0L) {
                            if (!isCurrentFeed(
                                    myFeedGeneration,
                                    symbol,
                                    interval
                                )
                            ) {
                                return@withLock
                            }

                            updateStatus(
                                "Jurnal mencatat $symbol $signal • $interval"
                            )

                            sendSignalNotification(
                                notificationId = notificationId(insertedId),
                                symbol = symbol,
                                signal = signal,
                                entry = entry,
                                sl = sl,
                                tp1 = tp1,
                                tp2 = tp2,
                                tp3 = tp3,
                                score = analysis.score
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Gagal memproses market", e)

                        if (isCurrentFeed(
                                myFeedGeneration,
                                symbol,
                                interval
                            )
                        ) {
                            updateStatus(
                                "Terjadi kesalahan pemrosesan market"
                            )
                        }
                    }
                }
            }
        }

        repository?.start(symbol, interval)

        return START_STICKY
    }

    private fun isCurrentFeed(
        expectedGeneration: Int,
        symbol: String,
        interval: String
    ): Boolean {
        return synchronized(this) {
            feedGeneration == expectedGeneration &&
                activeSymbol.equals(symbol, ignoreCase = true) &&
                activeInterval.equals(interval, ignoreCase = true)
        }
    }

    private fun notificationId(entryId: Long): Int =
        (entryId % Int.MAX_VALUE).toInt().coerceAtLeast(1)

    private fun sendSignalNotification(
        notificationId: Int,
        symbol: String,
        signal: String,
        entry: Double,
        sl: Double,
        tp1: Double,
        tp2: Double,
        tp3: Double?,
        score: Int
    ) {
        val decimals = when {
            entry >= 1000.0 -> 2
            entry >= 1.0 -> 4
            else -> 6
        }

        fun price(value: Double): String =
            String.format(Locale.US, "%.${decimals}f", value)

        val body =
            "$symbol $signal • Skor $score\n" +
                "Entry: ${price(entry)}\n" +
                "SL: ${price(sl)}\n" +
                "TP1: ${price(tp1)}\n" +
                "TP2: ${price(tp2)}\n" +
                "TP3: ${tp3?.let { price(it) } ?: "Tidak tersedia"}"

        if (!notificationsAllowed()) {
            updateStatus(
                "Entry tersimpan, tetapi izin notifikasi belum aktif"
            )
            return
        }

        val notification =
            NotificationCompat.Builder(this, SIGNAL_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Sinyal entry baru: $signal")
                .setContentText("$symbol • Entry ${price(entry)}")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(body)
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

        try {
            NotificationManagerCompat.from(this).notify(
                notificationId,
                notification
            )
        } catch (_: SecurityException) {
            updateStatus(
                "Izin notifikasi ditolak di pengaturan Android"
            )
        }
    }

    private fun notificationsAllowed(): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return NotificationManagerCompat
            .from(this)
            .areNotificationsEnabled()
    }

    private fun updateStatus(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(
                FOREGROUND_ID,
                statusNotification(text)
            )
        } catch (_: SecurityException) {
            // Izin notifikasi tidak boleh menghentikan service.
        }
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("AI Market Analyzer aktif")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    STATUS_CHANNEL,
                    "Pemantauan market",
                    NotificationManager.IMPORTANCE_LOW
                )
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    SIGNAL_CHANNEL,
                    "Sinyal BUY/SELL",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun stopMonitoring() {
        synchronized(this) {
            feedGeneration += 1
        }

        repository?.stop()
        repository = null

        getSharedPreferences("market_monitor", MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", false)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    override fun onDestroy() {
        synchronized(this) {
            feedGeneration += 1
        }

        repository?.stop()
        repository = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BackgroundSignalService"

        const val ACTION_STOP =
            "com.example.aimarket.STOP_MONITOR"

        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_INTERVAL = "interval"

        const val STATUS_CHANNEL = "market_monitor_status"
        const val SIGNAL_CHANNEL = "market_signal_alerts"

        const val FOREGROUND_ID = 4101
    }
}
