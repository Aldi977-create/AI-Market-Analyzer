package com.example.aimarket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class BackgroundSignalService : Service() {
    private var repository: LiveMarketRepository? = null
    private var lastClosedTime = 0L
    private var lastSignal: String? = null
    private var activeSymbol = "BTCUSDT"
    private var activeInterval = "1m"

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(FOREGROUND_ID, statusNotification("Memulai pemantauan market…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("market_monitor", MODE_PRIVATE)
        activeSymbol = intent?.getStringExtra(EXTRA_SYMBOL)
            ?: prefs.getString("symbol", "BTCUSDT") ?: "BTCUSDT"
        activeInterval = intent?.getStringExtra(EXTRA_INTERVAL)
            ?: prefs.getString("interval", "1m") ?: "1m"

        prefs.edit().putBoolean("enabled", true)
            .putString("symbol", activeSymbol)
            .putString("interval", activeInterval).apply()

        repository?.stop()
        lastClosedTime = 0L
        lastSignal = null

        repository = LiveMarketRepository { candles, live, message ->
            NotificationManagerCompat.from(this).notify(
                FOREGROUND_ID,
                statusNotification(
                    if (live) "Memantau $activeSymbol • $activeInterval" else message
                )
            )

            val analysis = AnalysisEngine.analyze(candles)
                ?: return@LiveMarketRepository
            val closed = candles.lastOrNull { it.closed }
                ?: return@LiveMarketRepository

            if (lastClosedTime == 0L) {
                lastClosedTime = closed.time
                lastSignal = analysis.signal
                return@LiveMarketRepository
            }

            if (closed.time > lastClosedTime) {
                val newAction = analysis.signal in listOf("BUY", "SELL") &&
                    analysis.signal != lastSignal

                if (newAction) sendSignalNotification(analysis)

                lastClosedTime = closed.time
                lastSignal = analysis.signal
            }
        }.also {
            it.start(activeSymbol, activeInterval)
        }

        return START_STICKY
    }

    private fun sendSignalNotification(result: AnalysisResult) {
        val direction = result.signal
        val entry = result.lastPrice
        val atr = result.atr ?: return

        val sl = if (direction == "BUY") entry - 1.5 * atr
            else entry + 1.5 * atr
        val sign = if (direction == "BUY") 1.0 else -1.0

        val tp1 = entry + sign * atr
        val tp2 = entry + sign * 2.0 * atr
        val tp3 = entry + sign * 3.0 * atr

        val decimals = 2
        fun price(v: Double) =
            "%.${decimals}f".format(java.util.Locale.US, v)

        val body = "$activeSymbol $direction • skor ${result.score}\n" +
            "Entry: ${price(entry)} | SL: ${price(sl)}\n" +
            "TP1: ${price(tp1)} | TP2: ${price(tp2)} | TP3: ${price(tp3)}"

        val notification = NotificationCompat.Builder(this, SIGNAL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Sinyal baru: $direction")
            .setContentText("$activeSymbol • Entry ${price(entry)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            notification
        )
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
            .edit().putBoolean("enabled", false).apply()

        stopForeground(STOP_FOREGROUND_REMOVE)
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
    }
}
