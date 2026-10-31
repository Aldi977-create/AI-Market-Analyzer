package com.example.aimarket

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

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
    private var socket: okhttp3.WebSocket? = null
    private var currentSymbol = "BTCUSDT"
    private var currentInterval = "1m"
    private val candles = mutableListOf<Candle>()
    @Volatile private var stopped = false

    fun start(symbol: String, interval: String) {
        stopSocket()
        stopped = false
        currentSymbol = symbol.uppercase()
        currentInterval = interval
        synchronized(candles) { candles.clear() }

        if (currentSymbol == "XAUUSD") {
            publish(false, "Menghubungkan ke feed XAUUSD…")
            startGoldFeed()
            return
        }

        publish(false, "Memuat candle historis…")
        Thread {
            try {
                val url = "https://api.binance.com/api/v3/klines?symbol=$currentSymbol&interval=$currentInterval&limit=120"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val arr = JSONArray(response.body?.string() ?: "[]")
                    synchronized(candles) {
                        candles.clear()
                        for (i in 0 until arr.length()) {
                            val k = arr.getJSONArray(i)
                            candles.add(Candle(
                                time = k.getLong(0),
                                open = k.getString(1).toDouble(),
                                high = k.getString(2).toDouble(),
                                low = k.getString(3).toDouble(),
                                close = k.getString(4).toDouble(),
                                volume = k.getString(5).toDouble(),
                                closed = k.getLong(6) < System.currentTimeMillis()
                            ))
                        }
                    }
                    publish(true, "Terhubung ke data publik Binance")
                    connectSocket()
                }
            } catch (e: Exception) {
                publish(false, "Gagal memuat data: ${e.message ?: "periksa internet"}")
            }
        }.start()
    }

    /**
     * XAUUSD feed via Biquote REST API. We require marketState=open and a fresh quote.
     * If the market is closed, stale, or the API fails, clear the chart candles so
     * old gold data cannot be mistaken for a live market.
     */
    private fun startGoldFeed() {
        Thread {
            while (!stopped && currentSymbol == "XAUUSD") {
                try {
                    val tick = getJson("https://biquote.io/api/XAUUSD?allowStale=false")
                    val marketState = tick.optString("marketState", "unknown").lowercase()
                    val stale = tick.optBoolean("stale", true)
                    val age = tick.optLong("quoteAgeSeconds", Long.MAX_VALUE)

                    if (marketState != "open" || stale || age > 300L) {
                        synchronized(candles) { candles.clear() }
                        publish(false, "MARKET TUTUP / DATA KEDALUWARSA • chart dan sinyal disembunyikan")
                    } else {
                        val candleJson = getJson(
                            "https://biquote.io/api/XAUUSD/ohlc?interval=$currentInterval&limit=120"
                        )
                        val bars = candleJson.optJSONArray("bars") ?: JSONArray()
                        val parsed = mutableListOf<Candle>()
                        for (i in 0 until bars.length()) {
                            val bar = bars.optJSONObject(i) ?: continue
                            val openTime = bar.optString("openTime")
                            val time = try {
                                Instant.parse(openTime).toEpochMilli()
                            } catch (_: Exception) {
                                continue
                            }
                            val open = bar.optDouble("open", Double.NaN)
                            val high = bar.optDouble("high", Double.NaN)
                            val low = bar.optDouble("low", Double.NaN)
                            val close = bar.optDouble("close", Double.NaN)
                            if (!open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()) continue
                            parsed += Candle(
                                time = time,
                                open = open,
                                high = high,
                                low = low,
                                close = close,
                                volume = bar.optDouble("tickVolume", bar.optDouble("volume", 0.0)),
                                closed = !bar.optBoolean("isOpen", false)
                            )
                        }
                        val sorted = parsed.sortedBy { it.time }
                        if (sorted.isEmpty()) {
                            synchronized(candles) { candles.clear() }
                            publish(false, "Feed XAUUSD aktif, tetapi candle belum tersedia")
                        } else {
                            synchronized(candles) {
                                candles.clear()
                                candles.addAll(sorted.takeLast(300))
                            }
                            publish(true, "LIVE • XAUUSD via Biquote")
                        }
                    }
                } catch (e: Exception) {
                    synchronized(candles) { candles.clear() }
                    publish(false, "XAUUSD tidak tersedia • chart disembunyikan (${e.message ?: "koneksi"})")
                }

                try {
                    Thread.sleep(15_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun connectSocket() {
        if (stopped) return
        val stream = currentSymbol.lowercase() + "@kline_" + currentInterval
        val request = Request.Builder().url("wss://stream.binance.com:9443/ws/$stream").build()
        socket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                publish(true, "LIVE • Binance WebSocket")
            }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
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
                        val index = candles.indexOfLast { it.time == candle.time }
                        if (index >= 0) candles[index] = candle else candles.add(candle)
                        while (candles.size > 300) candles.removeAt(0)
                    }
                    publish(true, if (candle.closed) "LIVE • candle ditutup" else "LIVE • candle berjalan")
                } catch (_: Exception) {
                    publish(false, "Pesan data tidak dapat dibaca")
                }
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                publish(false, "Koneksi terputus: ${t.message ?: "mencoba kembali"}")
                if (!stopped && currentSymbol != "XAUUSD") main.postDelayed({ connectSocket() }, 3000)
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                if (!stopped && currentSymbol != "XAUUSD") main.postDelayed({ connectSocket() }, 2000)
            }
        })
    }

    private fun publish(live: Boolean, status: String) {
        val snapshot = synchronized(candles) { candles.toList() }
        main.post { if (!stopped) onUpdate(snapshot, live, status) }
    }

    private fun stopSocket() {
        socket?.close(1000, "switch symbol")
        socket = null
    }

    fun stop() {
        stopped = true
        stopSocket()
    }
}
