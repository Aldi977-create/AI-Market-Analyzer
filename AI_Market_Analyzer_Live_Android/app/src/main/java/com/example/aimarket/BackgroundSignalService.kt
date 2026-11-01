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

    private var lastClosedTime = 0L
    private var lastSignal: String? = null

    private var activeSymbol = "BTCUSDT"
    private var activeInterval = "1m"

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

        activeSymbol = intent?.getStringExtra(EXTRA_SYMBOL)
            ?: prefs.getString("symbol", "BTCUSDT")
            ?: "BTCUSDT"

        activeInterval = intent?.getStringExtra(EXTRA_INTERVAL)
            ?: prefs.getString("interval", "1m")
            ?: "1m"

        prefs.edit()
            .putBoolean("enabled", true)
            .putString("symbol", activeSymbol)
            .putString("interval", activeInterval)
            .apply()

        repository?.stop()
        repository = null

        lastClosedTime = 0L
        lastSignal = null

        val symbol = activeSymbol
        val interval = activeInterval

        repository = LiveMarketRepository { candles, live, message ->

            updateStatus(
                if (live) "Memantau $symbol • $interval"
                else message
            )

            if (candles.isEmpty()) {
                return@LiveMarketRepository
            }

            // Semua pemrosesan candle diserialkan agar urutannya terjaga.
            serviceScope.launch {
                processingMutex.withLock {
                    try {
                        val closedCandles = candles
                            .filter { it.closed }
                            .sortedBy { it.time }

                        if (closedCandles.isEmpty()) {
                            return@withLock
                        }

                        // Proses candle lama satu per satu.
                        for (candle in closedCandles) {
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

                        val newestClosed = closedCandles.last()

                        val analysis =
                            AnalysisEngine.analyze(closedCandles)
                                ?: return@withLock

                        val signal =
                            analysis.signal.uppercase(Locale.US)

                        if (signal != "BUY" && signal != "SELL") {
                            lastSignal = signal
                            return@withLock
                        }

                        // Hindari sinyal yang sama berulang pada candle berikutnya.
                        if (signal == lastSignal) {
                            return@withLock
                        }

                        val entry =
                            analysis.entryPrice ?: analysis.lastPrice

                        val sl = analysis.stopLoss
                        val tp1 = analysis.takeProfit1
                        val tp2 = analysis.takeProfit2
                        val tp3 = analysis.takeProfit3

                        if (
                            !entry.isFinite() || entry <= 0.0 ||
                            sl == null || !sl.isFinite() ||
                            tp1 == null || !tp1.isFinite() ||
                            tp2 == null || !tp2.isFinite()
                        ) {
                            lastSignal = signal
                            updateStatus("Level sinyal $signal tidak valid")
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

                        // Simpan sinyal terakhir meskipun entry diblokir.
                        // Ini mencegah percobaan berulang untuk arah yang sama.
                        lastSignal = signal

                        if (insertedId > 0L) {
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
                        } else {
                            updateStatus(
                                "$symbol • $interval memiliki entry OPEN " +
                                    "atau sinyal tidak dapat disimpan"
                            )
                        }

                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Gagal memproses market", e)
                        updateStatus("Terjadi kesalahan pemrosesan market")
                    }
                }
            }
        }

        repository?.start(symbol, interval)

        return START_STICKY
    }

    private fun notificationId(entryId: Long): Int {
        return (entryId % Int.MAX_VALUE)
            .toInt()
            .coerceAtLeast(1)
    }

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

        val tp3Text = tp3?.let { price(it) } ?: "Tidak tersedia"

        val body =
            "$symbol $signal • Skor $score\n" +
                "Entry: ${price(entry)}\n" +
                "SL: ${price(sl)}\n" +
                "TP1: ${price(tp1)}\n" +
                "TP2: ${price(tp2)}\n" +
                "TP3: $tp3Text"

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
