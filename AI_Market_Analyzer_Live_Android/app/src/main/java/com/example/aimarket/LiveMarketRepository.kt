package com.example.aimarket

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LiveMarketRepository(
    private val onUpdate: (List<Candle>, Boolean, String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var currentSymbol = "BTCUSDT"
    private var currentInterval = "1m"
    private val candles = mutableListOf<Candle>()
    @Volatile private var stopped = false

    fun start(symbol: String, interval: String) {
        stopSocket()
        stopped = false
        currentSymbol = symbol.uppercase()
        currentInterval = interval
        candles.clear()
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

    private fun connectSocket() {
        if (stopped) return
        val stream = currentSymbol.lowercase() + "@kline_" + currentInterval
        val request = Request.Builder().url("wss://stream.binance.com:9443/ws/$stream").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                publish(true, "LIVE • Binance WebSocket")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
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
                        if (index >= 0) candles[index] = candle
                        else candles.add(candle)
                        while (candles.size > 300) candles.removeAt(0)
                    }
                    publish(true, if (candle.closed) "LIVE • candle ditutup" else "LIVE • candle berjalan")
                } catch (_: Exception) {
                    publish(false, "Pesan data tidak dapat dibaca")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                publish(false, "Koneksi terputus: ${t.message ?: "mencoba kembali"}")
                if (!stopped) main.postDelayed({ connectSocket() }, 3000)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped) main.postDelayed({ connectSocket() }, 2000)
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
