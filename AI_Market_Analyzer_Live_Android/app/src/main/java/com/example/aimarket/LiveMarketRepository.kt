package com.example.aimarket

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveMarketRepository(
    private val onUpdate: (List<Candle>, Boolean, String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var socket: okhttp3.WebSocket? = null

    @Volatile
    private var currentSymbol = "BTCUSDT"

    @Volatile
    private var currentInterval = "1m"

    private val candles = mutableListOf<Candle>()

    @Volatile
    private var stopped = true

    private val generation = AtomicInteger(0)

    @Volatile
    private var reconnectScheduled = false

    fun start(symbol: String, interval: String) {
        stop()

        val myGeneration = generation.incrementAndGet()

        stopped = false
        currentSymbol = symbol.uppercase()
        currentInterval = interval
        reconnectScheduled = false

        synchronized(candles) {
            candles.clear()
        }

        when (currentSymbol) {
            "XAUUSD" -> {
                publish(myGeneration, false, "Menghubungkan ke feed XAUUSD…")
                startGoldFeed(myGeneration)
            }

            "BTCUSD" -> {
                publish(myGeneration, false, "Menghubungkan ke Bitstamp BTC/USD…")
                startBitstampFeed(myGeneration)
            }

            else -> {
                publish(myGeneration, false, "Memuat candle historis Binance…")
                startBinanceFeed(myGeneration)
            }
        }
    }

    private fun startBinanceFeed(myGeneration: Int) {
        Thread {
            if (!loadBinanceHistory(myGeneration)) {
                if (isCurrent(myGeneration)) {
                    scheduleBinanceReconnect(myGeneration, 3000)
                }
                return@Thread
            }

            if (!isCurrent(myGeneration)) return@Thread

            publish(myGeneration, true, "LIVE • Binance")
            connectSocket(myGeneration)
        }.start()
    }

    private fun loadBinanceHistory(myGeneration: Int): Boolean {
        return try {
            val url =
                "https://api.binance.com/api/v3/klines" +
                    "?symbol=$currentSymbol" +
                    "&interval=$currentInterval&limit=120"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val parsed = mutableListOf<Candle>()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }

                val arr = JSONArray(response.body?.string() ?: "[]")
                val now = System.currentTimeMillis()

                for (i in 0 until arr.length()) {
                    val k = arr.getJSONArray(i)
                    val openTime = k.getLong(0)
                    val closeTime = k.getLong(6)

                    val open = k.getString(1).toDouble()
                    val high = k.getString(2).toDouble()
                    val low = k.getString(3).toDouble()
                    val close = k.getString(4).toDouble()
                    val volume = k.getString(5).toDouble()

                    if (!validCandle(openTime, open, high, low, close)) {
                        continue
                    }

                    parsed.add(
                        Candle(
                            time = openTime,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            volume = volume,
                            closed = closeTime < now
                        )
                    )
                }
            }

            if (!isCurrent(myGeneration) || parsed.isEmpty()) {
                return false
            }

            synchronized(candles) {
                val byTime = candles.associateBy { it.time }.toMutableMap()

                parsed.forEach { historical ->
                    val existing = byTime[historical.time]

                    when {
                        existing == null -> {
                            byTime[historical.time] = historical
                        }

                        historical.closed -> {
                            // Data historis mengonfirmasi candle telah tutup.
                            // Gunakan data historis agar status closed tidak
                            // tertahan false jika socket belum mengirim update.
                            byTime[historical.time] = historical
                        }

                        else -> {
                            // Candle historis masih berjalan. Pertahankan
                            // candle socket yang sudah ada jika tersedia.
                            byTime[historical.time] = existing
                        }
                    }
                }

                candles.clear()
                candles.addAll(
                    byTime.values.sortedBy { it.time }.takeLast(300)
                )
            }

            if (isCurrent(myGeneration)) {
                publish(
                    myGeneration,
                    true,
                    "LIVE • Binance • historis diperbarui"
                )
            }

            true
        } catch (e: Exception) {
            if (isCurrent(myGeneration)) {
                publish(
                    myGeneration,
                    false,
                    "Binance gagal memuat candle: " +
                        (e.message ?: "periksa internet")
                )
            }
            false
        }
    }

    private fun connectSocket(myGeneration: Int) {
        if (!isCurrent(myGeneration)) return

        val stream =
            currentSymbol.lowercase() + "@kline_" + currentInterval

        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/$stream")
            .build()

        socket = client.newWebSocket(
            request,
            object : okhttp3.WebSocketListener() {
                override fun onOpen(
                    webSocket: okhttp3.WebSocket,
                    response: okhttp3.Response
                ) {
                    if (isCurrent(myGeneration)) {
                        publish(
                            myGeneration,
                            true,
                            "LIVE • Binance WebSocket"
                        )
                    } else {
                        webSocket.close(1000, "Koneksi sudah diganti")
                    }
                }

                override fun onMessage(
                    webSocket: okhttp3.WebSocket,
                    text: String
                ) {
                    if (!isCurrent(myGeneration)) return

                    try {
                        val root = JSONObject(text)
                        val k = root.getJSONObject("k")
                        val time = k.getLong("t")

                        val candle = Candle(
                            time = time,
                            open = k.getString("o").toDouble(),
                            high = k.getString("h").toDouble(),
                            low = k.getString("l").toDouble(),
                            close = k.getString("c").toDouble(),
                            volume = k.getString("v").toDouble(),
                            closed = k.getBoolean("x")
                        )

                        if (!validCandle(
                                candle.time,
                                candle.open,
                                candle.high,
                                candle.low,
                                candle.close
                            )
                        ) {
                            return
                        }

                        synchronized(candles) {
                            val index =
                                candles.indexOfLast { it.time == candle.time }

                            if (index >= 0) {
                                val existing = candles[index]

                                // Jangan menurunkan status candle yang sudah
                                // ditandai tutup menjadi candle berjalan.
                                candles[index] =
                                    if (existing.closed && !candle.closed) {
                                        existing
                                    } else {
                                        candle
                                    }
                            } else {
                                candles.add(candle)
                            }

                            candles.sortBy { it.time }

                            while (candles.size > 300) {
                                candles.removeAt(0)
                            }
                        }

                        publish(
                            myGeneration,
                            true,
                            if (candle.closed) {
                                "LIVE • Binance • candle ditutup"
                            } else {
                                "LIVE • Binance • candle berjalan"
                            }
                        )
                    } catch (_: Exception) {
                        publish(
                            myGeneration,
                            false,
                            "Data Binance tidak dapat dibaca"
                        )
                    }
                }

                override fun onFailure(
                    webSocket: okhttp3.WebSocket,
                    t: Throwable,
                    response: okhttp3.Response?
                ) {
                    if (!isCurrent(myGeneration)) return

                    publish(
                        myGeneration,
                        false,
                        "Koneksi Binance terputus; menyambungkan ulang…"
                    )

                    scheduleBinanceReconnect(myGeneration, 3000)
                }

                override fun onClosed(
                    webSocket: okhttp3.WebSocket,
                    code: Int,
                    reason: String
                ) {
                    if (!isCurrent(myGeneration)) return

                    publish(
                        myGeneration,
                        false,
                        "Koneksi Binance ditutup; menyambungkan ulang…"
                    )

                    scheduleBinanceReconnect(myGeneration, 2000)
                }
            }
        )
    }

    private fun scheduleBinanceReconnect(
        myGeneration: Int,
        delayMs: Long
    ) {
        synchronized(this) {
            if (reconnectScheduled || !isCurrent(myGeneration)) return
            reconnectScheduled = true
        }

        main.postDelayed({
            synchronized(this) {
                reconnectScheduled = false
            }

            if (!isCurrent(myGeneration)) return@postDelayed

            Thread {
                if (!isCurrent(myGeneration)) return@Thread

                val historyLoaded = loadBinanceHistory(myGeneration)

                if (isCurrent(myGeneration)) {
                    if (historyLoaded) {
                        connectSocket(myGeneration)
                    } else {
                        scheduleBinanceReconnect(myGeneration, 5000)
                    }
                }
            }.start()
        }, delayMs)
    }

    private fun startBitstampFeed(myGeneration: Int) {
        Thread {
            while (isCurrent(myGeneration)) {
                try {
                    val step = intervalToSeconds(currentInterval)
                    val stepMs = step * 1000L

                    val ohlcRoot = getJson(
                        "https://www.bitstamp.net/api/v2/ohlc/btcusd/" +
                            "?step=$step&limit=120"
                    )

                    val data = ohlcRoot.optJSONObject("data")
                        ?: throw IllegalStateException(
                            "Respons OHLC tidak valid"
                        )

                    val bars = data.optJSONArray("ohlc")
                        ?: throw IllegalStateException(
                            "Candle BTCUSD tidak tersedia"
                        )

                    val ticker = getJson(
                        "https://www.bitstamp.net/api/v2/ticker/btcusd/"
                    )

                    val lastPrice = ticker.optString("last")
                        .toDoubleOrNull()
                        ?: throw IllegalStateException(
                            "Harga ticker tidak valid"
                        )

                    if (!lastPrice.isFinite() || lastPrice <= 0.0) {
                        throw IllegalStateException(
                            "Harga BTCUSD tidak valid"
                        )
                    }

                    val now = System.currentTimeMillis()
                    val parsed = mutableListOf<Candle>()

                    for (i in 0 until bars.length()) {
                        val bar = bars.optJSONObject(i) ?: continue
                        val seconds = bar.optString("timestamp")
                            .toLongOrNull() ?: continue

                        if (seconds <= 0L ||
                            seconds > Long.MAX_VALUE / 1000L
                        ) continue

                        val time = seconds * 1000L

                        val open = bar.optString("open").toDoubleOrNull()
                            ?: continue
                        val high = bar.optString("high").toDoubleOrNull()
                            ?: continue
                        val low = bar.optString("low").toDoubleOrNull()
                            ?: continue
                        val close = bar.optString("close").toDoubleOrNull()
                            ?: continue
                        val volume = bar.optString("volume")
                            .toDoubleOrNull() ?: 0.0

                        if (!validCandle(time, open, high, low, close)) {
                            continue
                        }

                        parsed.add(
                            Candle(
                                time = time,
                                open = open,
                                high = high,
                                low = low,
                                close = close,
                                volume = volume,
                                closed = time + stepMs <= now
                            )
                        )
                    }

                    if (parsed.isEmpty()) {
                        throw IllegalStateException(
                            "Belum ada candle BTCUSD"
                        )
                    }

                    parsed.sortBy { it.time }

                    val bucket = (now / stepMs) * stepMs
                    val currentIndex = parsed.indexOfLast { it.time == bucket }

                    if (currentIndex >= 0) {
                        val old = parsed[currentIndex]
                        parsed[currentIndex] = Candle(
                            time = old.time,
                            open = old.open,
                            high = maxOf(old.high, lastPrice),
                            low = minOf(old.low, lastPrice),
                            close = lastPrice,
                            volume = old.volume,
                            closed = false
                        )
                    } else if (bucket >= parsed.last().time) {
                        val previousClose = parsed.last().close
                        parsed.add(
                            Candle(
                                time = bucket,
                                open = previousClose,
                                high = maxOf(previousClose, lastPrice),
                                low = minOf(previousClose, lastPrice),
                                close = lastPrice,
                                volume = 0.0,
                                closed = false
                            )
                        )
                        parsed.sortBy { it.time }
                    }

                    if (!isCurrent(myGeneration)) return@Thread

                    synchronized(candles) {
                        candles.clear()
                        candles.addAll(parsed.takeLast(300))
                    }

                    publish(
                        myGeneration,
                        true,
                        "LIVE • BTC/USD • Bitstamp"
                    )
                } catch (e: Exception) {
                    if (!isCurrent(myGeneration)) return@Thread

                    publish(
                        myGeneration,
                        false,
                        "Bitstamp bermasalah; mencoba lagi: " +
                            (e.message ?: "periksa internet")
                    )
                }

                try {
                    Thread.sleep(15_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.start()
    }

    private fun startGoldFeed(myGeneration: Int) {
        Thread {
            while (isCurrent(myGeneration)) {
                try {
                    val tick = getJson(
                        "https://biquote.io/api/XAUUSD?allowStale=false"
                    )

                    val marketState =
                        tick.optString("marketState", "unknown").lowercase()
                    val stale = tick.optBoolean("stale", true)
                    val age = tick.optLong(
                        "quoteAgeSeconds",
                        Long.MAX_VALUE
                    )

                    if (marketState != "open" || stale || age > 300L) {
                        publish(
                            myGeneration,
                            false,
                            "XAUUSD: pasar tutup atau harga feed kedaluwarsa"
                        )
                    } else {
                        val candleJson = getJson(
                            "https://biquote.io/api/XAUUSD/ohlc" +
                                "?interval=$currentInterval&limit=120"
                        )

                        val bars = candleJson.optJSONArray("bars")
                            ?: JSONArray()
                        val parsed = mutableListOf<Candle>()
                        val now = System.currentTimeMillis()

                        for (i in 0 until bars.length()) {
                            val bar = bars.optJSONObject(i) ?: continue

                            val timeText = bar.optString("openTime").trim()
                            val time = parseTimestampMillis(timeText) ?: continue

                            // Tolak waktu tidak valid atau terlalu jauh di
                            // masa depan, tetapi toleransi 60 detik diberikan
                            // untuk perbedaan jam antarserver.
                            if (time > now + 60_000L) continue

                            val open = bar.optDouble("open", Double.NaN)
                            val high = bar.optDouble("high", Double.NaN)
                            val low = bar.optDouble("low", Double.NaN)
                            val close = bar.optDouble("close", Double.NaN)

                            if (!validCandle(time, open, high, low, close)) {
                                continue
                            }

                            parsed.add(
                                Candle(
                                    time = time,
                                    open = open,
                                    high = high,
                                    low = low,
                                    close = close,
                                    volume = bar.optDouble(
                                        "tickVolume",
                                        bar.optDouble("volume", 0.0)
                                    ),
                                    closed = !bar.optBoolean(
                                        "isOpen",
                                        false
                                    )
                                )
                            )
                        }

                        if (!isCurrent(myGeneration)) return@Thread

                        if (parsed.isEmpty()) {
                            publish(
                                myGeneration,
                                false,
                                "Feed XAUUSD aktif, candle belum tersedia"
                            )
                        } else {
                            // Hindari timestamp duplikat dan urutkan candle.
                            val unique = parsed
                                .groupBy { it.time }
                                .map { (_, items) ->
                                    items.last()
                                }
                                .sortedBy { it.time }
                                .takeLast(300)

                            synchronized(candles) {
                                candles.clear()
                                candles.addAll(unique)
                            }

                            publish(
                                myGeneration,
                                true,
                                "LIVE • XAUUSD via Biquote • diperbarui berkala"
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (!isCurrent(myGeneration)) return@Thread

                    publish(
                        myGeneration,
                        false,
                        "XAUUSD tidak tersedia; mencoba lagi: " +
                            (e.message ?: "periksa koneksi")
                    )
                }

                try {
                    Thread.sleep(15_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.start()
    }

    /**
     * Biquote biasanya mengirim openTime sebagai ISO-8601 UTC.
     * Fallback numerik menerima timestamp dalam detik atau milidetik.
     */
    private fun parseTimestampMillis(value: String): Long? {
        if (value.isBlank()) return null

        try {
            return Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            // Lanjutkan ke format numerik.
        }

        val numeric = value.toLongOrNull() ?: return null
        if (numeric <= 0L) return null

        return when {
            numeric < 100_000_000_000L -> {
                if (numeric > Long.MAX_VALUE / 1000L) {
                    null
                } else {
                    numeric * 1000L
                }
            }

            else -> numeric
        }
    }

    private fun validCandle(
        time: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double
    ): Boolean {
        return time > 0L &&
            open.isFinite() &&
            high.isFinite() &&
            low.isFinite() &&
            close.isFinite() &&
            open > 0.0 &&
            high > 0.0 &&
            low > 0.0 &&
            close > 0.0 &&
            high >= low &&
            high >= open &&
            high >= close &&
            low <= open &&
            low <= close
    }

    private fun intervalToSeconds(interval: String): Int {
        return when (interval) {
            "1m" -> 60
            "5m" -> 300
            "15m" -> 900
            "1h" -> 3600
            else -> 60
        }
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }

            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun isCurrent(myGeneration: Int): Boolean {
        return !stopped && generation.get() == myGeneration
    }

    private fun publish(
        myGeneration: Int,
        live: Boolean,
        status: String
    ) {
        val snapshot = synchronized(candles) {
            candles.toList()
        }

        main.post {
            if (isCurrent(myGeneration)) {
                onUpdate(snapshot, live, status)
            }
        }
    }

    private fun stopSocket() {
        socket?.close(1000, "switch symbol")
        socket = null
    }

    fun stop() {
        stopped = true
        generation.incrementAndGet()
        reconnectScheduled = false
        stopSocket()
    }
}
