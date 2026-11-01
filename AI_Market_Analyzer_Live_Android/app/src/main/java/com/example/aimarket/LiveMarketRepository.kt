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

    fun start(symbol: String, interval: String) {
        stop()

        val myGeneration = generation.incrementAndGet()

        stopped = false
        currentSymbol = symbol.uppercase()
        currentInterval = interval

        synchronized(candles) {
            candles.clear()
        }

        when (currentSymbol) {
            "XAUUSD" -> {
                publish(
                    myGeneration,
                    false,
                    "Menghubungkan ke feed XAUUSD…"
                )
                startGoldFeed(myGeneration)
            }

            "BTCUSD" -> {
                publish(
                    myGeneration,
                    false,
                    "Menghubungkan ke Bitstamp BTC/USD…"
                )
                startBitstampFeed(myGeneration)
            }

            else -> {
                publish(
                    myGeneration,
                    false,
                    "Memuat candle historis Binance…"
                )
                startBinanceFeed(myGeneration)
            }
        }
    }

    private fun startBinanceFeed(myGeneration: Int) {
        Thread {
            try {
                val url =
                    "https://api.binance.com/api/v3/klines" +
                        "?symbol=$currentSymbol" +
                        "&interval=$currentInterval&limit=120"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }

                    val arr = JSONArray(response.body?.string() ?: "[]")
                    val parsed = mutableListOf<Candle>()

                    for (i in 0 until arr.length()) {
                        val k = arr.getJSONArray(i)
                        val closeTime = k.getLong(6)

                        parsed.add(
                            Candle(
                                time = k.getLong(0),
                                open = k.getString(1).toDouble(),
                                high = k.getString(2).toDouble(),
                                low = k.getString(3).toDouble(),
                                close = k.getString(4).toDouble(),
                                volume = k.getString(5).toDouble(),
                                closed = closeTime < System.currentTimeMillis()
                            )
                        )
                    }

                    if (!isCurrent(myGeneration)) return@Thread

                    synchronized(candles) {
                        candles.clear()
                        candles.addAll(parsed)
                    }
                }

                if (!isCurrent(myGeneration)) return@Thread

                publish(myGeneration, true, "LIVE • Binance")
                connectSocket(myGeneration)

            } catch (e: Exception) {
                if (isCurrent(myGeneration)) {
                    publish(
                        myGeneration,
                        false,
                        "Binance gagal: ${e.message ?: "periksa internet"}"
                    )
                }
            }
        }.start()
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

                        val candle = Candle(
                            time = k.getLong("t"),
                            open = k.getString("o").toDouble(),
                            high = k.getString("h").toDouble(),
                            low = k.getString("l").toDouble(),
                            close = k.getString("c").toDouble(),
                            volume = k.getString("v").toDouble(),
                            closed = k.getBoolean("x")
                        )

                        synchronized(candles) {
                            val index =
                                candles.indexOfLast { it.time == candle.time }

                            if (index >= 0) {
                                candles[index] = candle
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
                        "Koneksi Binance terputus: " +
                            (t.message ?: "mencoba kembali")
                    )

                    main.postDelayed({
                        if (isCurrent(myGeneration)) {
                            connectSocket(myGeneration)
                        }
                    }, 3000)
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
                        "Koneksi Binance ditutup"
                    )

                    main.postDelayed({
                        if (isCurrent(myGeneration)) {
                            connectSocket(myGeneration)
                        }
                    }, 2000)
                }
            }
        )
    }

    private fun startBitstampFeed(myGeneration: Int) {
        Thread {
            while (isCurrent(myGeneration)) {
                try {
                    val step = intervalToSeconds(currentInterval)
                    val stepMs = step * 1000L

                    val ohlcUrl =
                        "https://www.bitstamp.net/api/v2/ohlc/btcusd/" +
                            "?step=$step&limit=120"

                    val ohlcRoot = getJson(ohlcUrl)

                    val data = ohlcRoot.optJSONObject("data")
                        ?: throw IllegalStateException(
                            "Respons OHLC Bitstamp tidak valid"
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
                            "Harga ticker BTCUSD tidak valid"
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

                        val timestampSeconds =
                            bar.optString("timestamp").toLongOrNull()
                                ?: continue

                        val time = timestampSeconds * 1000L

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

                        if (
                            !open.isFinite() ||
                            !high.isFinite() ||
                            !low.isFinite() ||
                            !close.isFinite() ||
                            open <= 0.0 ||
                            high <= 0.0 ||
                            low <= 0.0 ||
                            close <= 0.0 ||
                            high < low
                        ) {
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
                            "Bitstamp belum mengirim candle BTCUSD"
                        )
                    }

                    parsed.sortBy { it.time }

                    // Cari candle yang sedang berjalan berdasarkan waktu lokal.
                    val currentBucket = (now / stepMs) * stepMs
                    val currentIndex =
                        parsed.indexOfLast { it.time == currentBucket }

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
                    } else {
                        // Jika Bitstamp belum mengembalikan candle saat ini,
                        // buat candle sementara dari harga penutupan sebelumnya
                        // dan harga ticker terbaru.
                        val previousClose = parsed.last().close

                        val liveCandle = Candle(
                            time = currentBucket,
                            open = previousClose,
                            high = maxOf(previousClose, lastPrice),
                            low = minOf(previousClose, lastPrice),
                            close = lastPrice,
                            volume = 0.0,
                            closed = false
                        )

                        parsed.add(liveCandle)
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
                        "LIVE • BTC/USD • Bitstamp REST"
                    )

                } catch (e: Exception) {
                    if (!isCurrent(myGeneration)) return@Thread

                    publish(
                        myGeneration,
                        false,
                        "BTCUSD Bitstamp tidak tersedia: " +
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

    private fun intervalToSeconds(interval: String): Int {
        return when (interval) {
            "1m" -> 60
            "5m" -> 300
            "15m" -> 900
            "1h" -> 3600
            else -> 60
        }
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
                    val age = tick.optLong("quoteAgeSeconds", Long.MAX_VALUE)

                    if (marketState != "open" || stale || age > 300L) {
                        synchronized(candles) {
                            candles.clear()
                        }

                        publish(
                            myGeneration,
                            false,
                            "MARKET TUTUP / DATA KEDALUWARSA • " +
                                "chart dan sinyal disembunyikan"
                        )
                    } else {
                        val candleJson = getJson(
                            "https://biquote.io/api/XAUUSD/ohlc" +
                                "?interval=$currentInterval&limit=120"
                        )

                        val bars =
                            candleJson.optJSONArray("bars") ?: JSONArray()

                        val parsed = mutableListOf<Candle>()

                        for (i in 0 until bars.length()) {
                            val bar = bars.optJSONObject(i) ?: continue

                            val openTime = bar.optString("openTime")

                            val time = try {
                                Instant.parse(openTime).toEpochMilli()
                            } catch (_: Exception) {
                                continue
                            }

                            val open =
                                bar.optDouble("open", Double.NaN)
                            val high =
                                bar.optDouble("high", Double.NaN)
                            val low =
                                bar.optDouble("low", Double.NaN)
                            val close =
                                bar.optDouble("close", Double.NaN)

                            if (
                                !open.isFinite() ||
                                !high.isFinite() ||
                                !low.isFinite() ||
                                !close.isFinite() ||
                                high < low
                            ) {
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
                                    closed = !bar.optBoolean("isOpen", false)
                                )
                            )
                        }

                        if (!isCurrent(myGeneration)) return@Thread

                        val sorted = parsed.sortedBy { it.time }

                        if (sorted.isEmpty()) {
                            synchronized(candles) {
                                candles.clear()
                            }

                            publish(
                                myGeneration,
                                false,
                                "Feed XAUUSD aktif, candle belum tersedia"
                            )
                        } else {
                            synchronized(candles) {
                                candles.clear()
                                candles.addAll(sorted.takeLast(300))
                            }

                            publish(
                                myGeneration,
                                true,
                                "LIVE • XAUUSD via Biquote"
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (!isCurrent(myGeneration)) return@Thread

                    publish(
                        myGeneration,
                        false,
                        "XAUUSD tidak tersedia • " +
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
        stopSocket()
    }
}
