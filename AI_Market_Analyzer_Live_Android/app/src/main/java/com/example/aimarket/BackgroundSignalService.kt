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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

class BackgroundSignalService : Service() {

    private var repository: LiveMarketRepository? = null

    private var lastClosedTime = 0L
    private var lastSignal: String? = null

    private var activeSymbol = "BTCUSDT"
    private var activeInterval = "1m"

    // Posisi virtual untuk mencegah sinyal searah berulang.
    private var tradeDirection: String? = null
    private var tradeEntry = 0.0
    private var tradeStopLoss = 0.0
    private var tradeTakeProfit3 = 0.0

    // Setelah SL/TP3 tercapai, sinyal searah harus netral
    // atau berbalik arah dahulu sebelum boleh muncul lagi.
    private var waitingForReset = false

    override fun onCreate() {
        super.onCreate()
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

        val prefs = getSharedPreferences("market_monitor", MODE_PRIVATE)

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

        lastClosedTime = 0L
        lastSignal = null
        tradeDirection = null
        tradeEntry = 0.0
        tradeStopLoss = 0.0
        tradeTakeProfit3 = 0.0
        waitingForReset = false

        repository = LiveMarketRepository { candles, live, message ->

            // Perbarui status service tanpa mengubah notifikasi sinyal.
            updateStatus(
                if (live) "Memantau $activeSymbol • $activeInterval"
                else message
            )

            if (candles.isEmpty()) return@LiveMarketRepository

            val latestPrice = candles.lastOrNull()?.close ?: return@LiveMarketRepository
            if (!latestPrice.isFinite() || latestPrice <= 0.0) {
                return@LiveMarketRepository
            }

            // Pantau posisi virtual pada setiap pembaruan harga.
            checkTradeExit(latestPrice)

            // Analisis hanya candle yang sudah ditutup.
            val closedCandles = candles.filter { it.closed }
            if (closedCandles.size < 30) return@LiveMarketRepository

            val newestClosed = closedCandles.last()

            // Analisis hanya sekali untuk setiap candle tertutup.
            if (newestClosed.time <= lastClosedTime) {
                return@LiveMarketRepository
            }

            val analysis = AnalysisEngine.analyze(closedCandles)
                ?: run {
                    lastClosedTime = newestClosed.time
                    return@LiveMarketRepository
                }

            lastClosedTime = newestClosed.time
            val signal = analysis.signal.uppercase(Locale.US)

            // Jika posisi sebelumnya sudah berakhir, jangan langsung
            // membuka sinyal baru searah tanpa reset sinyal.
            if (waitingForReset) {
                if (signal == "WAIT" || signal != tradeDirection) {
                    waitingForReset = false
                } else {
                    lastSignal = signal
                    return@LiveMarketRepository
                }
            }

            // Abaikan sinyal selain BUY dan SELL.
            if (signal != "BUY" && signal != "SELL") {
                lastSignal = signal
                return@LiveMarketRepository
            }

            // Jangan kirim sinyal sama berulang kali.
            if (signal == lastSignal || tradeDirection == signal) {
                lastSignal = signal
                return@LiveMarketRepository
            }

            val atr = analysis.atr
            val entry = analysis.lastPrice

            if (atr == null || !atr.isFinite() || atr <= 0.0 ||
                !entry.isFinite() || entry <= 0.0
            ) {
                lastSignal = signal
                return@LiveMarketRepository
            }

            val slDistance = 1.5 * atr
            val tp3Distance = 3.0 * atr

            tradeDirection = signal
            tradeEntry = entry

            if (signal == "BUY") {
                tradeStopLoss = entry - slDistance
                tradeTakeProfit3 = entry + tp3Distance
            } else {
                tradeStopLoss = entry + slDistance
                tradeTakeProfit3 = entry - tp3Distance
            }

            waitingForReset = false
            lastSignal = signal

            sendSignalNotification(
                signal = signal,
                entry = entry,
                atr = atr,
                score = analysis.score
            )
        }

        repository?.start(activeSymbol, activeInterval)

        return START_STICKY
    }

    private fun checkTradeExit(price: Double) {
        val direction = tradeDirection ?: return

        val stoppedOut: Boolean
        val targetReached: Boolean

        if (direction == "BUY") {
            stoppedOut = price <= tradeStopLoss
            targetReached = price >= tradeTakeProfit3
        } else {
            stoppedOut = price >= tradeStopLoss
            targetReached = price <= tradeTakeProfit3
        }

        if (stoppedOut || targetReached) {
            val outcome = if (targetReached) {
                "TP3 tercapai"
            } else {
                "SL tersentuh"
            }

            updateStatus("$activeSymbol • $outcome • menunggu reset sinyal")

            tradeDirection = null
            waitingForReset = true
        }
    }

    private fun sendSignalNotification(
        signal: String,
        entry: Double,
        atr: Double,
        score: Int
    ) {
        val sl = if (signal == "BUY") {
            entry - 1.5 * atr
        } else {
            entry + 1.5 * atr
        }

        val sign = if (signal == "BUY") 1.0 else -1.0

        val tp1 = entry + sign * atr
        val tp2 = entry + sign * 2.0 * atr
        val tp3 = entry + sign * 3.0 * atr

        // Jumlah desimal disesuaikan dengan besarnya harga.
        val decimals = when {
            entry >= 1000.0 -> 2
            entry >= 1.0 -> 4
            else -> 6
        }

        fun price(value: Double): String =
            String.format(Locale.US, "%.${decimals}f", value)

        val body =
            "$activeSymbol $signal • Skor $score\n" +
            "Entry: ${price(entry)}\n" +
            "SL: ${price(sl)}\n" +
            "TP1: ${price(tp1)}\n" +
            "TP2: ${price(tp2)}\n" +
            "TP3: ${price(tp3)}"

        if (!notificationsAllowed()) {
            updateStatus("Sinyal $signal terdeteksi, tetapi izin notifikasi belum aktif")
            return
        }

        val notification = NotificationCompat.Builder(this, SIGNAL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Sinyal baru: $signal")
            .setContentText("$activeSymbol • Entry ${price(entry)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(
                SIGNAL_ID,
                notification
            )
        } catch (_: SecurityException) {
            updateStatus("Izin notifikasi ditolak di pengaturan Android")
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

        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun updateStatus(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(
                FOREGROUND_ID,
                statusNotification(text)
            )
        } catch (_: SecurityException) {
            // Jangan biarkan masalah izin notifikasi menghentikan service.
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
            val manager = getSystemService(NotificationManager::class.java)

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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.aimarket.STOP_MONITOR"
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_INTERVAL = "interval"

        const val STATUS_CHANNEL = "market_monitor_status"
        const val SIGNAL_CHANNEL = "market_signal_alerts"

        const val FOREGROUND_ID = 4101
        const val SIGNAL_ID = 4102
    }
}
