package com.example.aimarket

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
    val closed: Boolean = true
)

data class AnalysisResult(
    val lastPrice: Double,
    val signal: String,
    val score: Int,
    val rsi: Double?,
    val ema9: Double?,
    val ema21: Double?,
    val ema50: Double?,
    val macd: Double?,
    val atr: Double?,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val reasons: List<String>,
    val entryPrice: Double? = null,
    val takeProfit1: Double? = null,
    val takeProfit2: Double? = null,
    val takeProfit3: Double? = null
)

object AnalysisEngine {
    private data class Pivot(val index: Int, val price: Double)

    fun analyze(candles: List<Candle>): AnalysisResult? {
        if (candles.size < 52) return null

        // Gunakan candle yang sudah tutup agar sinyal tidak mudah berubah intrabar.
        val stable = candles.filter { it.closed }
        if (stable.size < 52) return null

        val closes = stable.map { it.close }
        val last = closes.last()
        val e9 = ema(closes, 9) ?: return null
        val e21 = ema(closes, 21) ?: return null
        val e50 = ema(closes, 50) ?: return null
        val rsiValue = rsi(closes, 14)
        val atrValue = atr(stable, 14)
        val macdValue = (ema(closes, 12) ?: last) - (ema(closes, 26) ?: last)

        var score = 50
        val reasons = mutableListOf<String>()

        // Indikator dasar yang sudah ada sebelumnya.
        if (e9 > e21) {
            score += 10
            reasons += "EMA 9 di atas EMA 21: momentum jangka pendek bullish."
        } else {
            score -= 10
            reasons += "EMA 9 di bawah EMA 21: momentum jangka pendek bearish."
        }

        if (e21 > e50) {
            score += 10
            reasons += "EMA 21 di atas EMA 50: tren menengah bullish."
        } else {
            score -= 10
            reasons += "EMA 21 di bawah EMA 50: tren menengah bearish."
        }

        if (rsiValue != null) {
            when {
                rsiValue in 52.0..68.0 -> {
                    score += 7
                    reasons += "RSI ${"%.1f".format(rsiValue)} mendukung momentum bullish."
                }
                rsiValue in 32.0..48.0 -> {
                    score -= 7
                    reasons += "RSI ${"%.1f".format(rsiValue)} mendukung momentum bearish."
                }
                rsiValue > 70.0 -> {
                    score -= 3
                    reasons += "RSI ${"%.1f".format(rsiValue)} tinggi; waspadai pullback."
                }
                rsiValue < 30.0 -> {
                    score += 3
                    reasons += "RSI ${"%.1f".format(rsiValue)} rendah; pantulan belum terkonfirmasi."
                }
                else -> reasons += "RSI ${"%.1f".format(rsiValue)} belum memberi konfirmasi kuat."
            }
        }

        if (macdValue > 0) {
            score += 4
            reasons += "MACD line di atas nol."
        } else {
            score -= 4
            reasons += "MACD line di bawah nol."
        }

        // SMC sederhana: swing high/low, BOS dan indikasi CHOCH.
        val highs = findPivotHighs(stable)
        val lows = findPivotLows(stable)
        val previousHigh = highs.lastOrNull { it.index < stable.lastIndex - 1 }
        val previousLow = lows.lastOrNull { it.index < stable.lastIndex - 1 }
        val latestHigh = highs.lastOrNull()
        val latestLow = lows.lastOrNull()

        val bullishBos = previousHigh != null && last > previousHigh.price &&
            stable.last().close > stable.last().open
        val bearishBos = previousLow != null && last < previousLow.price &&
            stable.last().close < stable.last().open

        when {
            bullishBos -> {
                score += 9
                reasons += "SMC: harga menembus swing high sebelumnya (indikasi bullish BOS)."
            }
            bearishBos -> {
                score -= 9
                reasons += "SMC: harga menembus swing low sebelumnya (indikasi bearish BOS)."
            }
            latestHigh != null && latestLow != null &&
                latestHigh.index > latestLow.index && e9 < e21 -> {
                score -= 2
                reasons += "SMC: struktur terakhir berpotensi berubah bearish; CHOCH belum terkonfirmasi."
            }
            latestLow != null && latestHigh != null &&
                latestLow.index > latestHigh.index && e9 > e21 -> {
                score += 2
                reasons += "SMC: struktur terakhir berpotensi berubah bullish; CHOCH belum terkonfirmasi."
            }
            else -> reasons += "SMC: belum ada penembusan swing yang terkonfirmasi."
        }

        // Trendline sederhana dari dua pivot terakhir.
        val trendlineHighs = highs.takeLast(2)
        val trendlineLows = lows.takeLast(2)
        when {
            trendlineLows.size == 2 &&
                trendlineLows[1].price > trendlineLows[0].price -> {
                score += 5
                reasons += "Trendline: dua swing low terakhir naik, mendukung tren naik."
            }
            trendlineLows.size == 2 &&
                trendlineLows[1].price < trendlineLows[0].price -> {
                score -= 5
                reasons += "Trendline: dua swing low terakhir turun, mendukung tren lemah."
            }
            trendlineHighs.size == 2 &&
                trendlineHighs[1].price < trendlineHighs[0].price -> {
                score -= 5
                reasons += "Trendline: dua swing high terakhir turun, mendukung tren turun."
            }
            trendlineHighs.size == 2 &&
                trendlineHighs[1].price > trendlineHighs[0].price -> {
                score += 5
                reasons += "Trendline: dua swing high terakhir naik, mendukung tren naik."
            }
            else -> reasons += "Trendline: pivot belum cukup untuk menentukan arah."
        }

        // SNR: posisi harga terhadap swing support/resistance terdekat.
        val support = lows.lastOrNull { it.price <= last }?.price
        val resistance = highs.lastOrNull { it.price >= last }?.price
        val proximity = atrValue?.times(0.6) ?: (last * 0.002)

        if (support != null && abs(last - support) <= proximity) {
            score += 4
            reasons += "SNR: harga dekat support swing (${formatPrice(support)}); pantulan tetap perlu konfirmasi."
        } else if (resistance != null && abs(resistance - last) <= proximity) {
            score -= 4
            reasons += "SNR: harga dekat resistance swing (${formatPrice(resistance)}); waspadai penolakan harga."
        } else {
            reasons += "SNR: harga belum dekat support/resistance swing yang terdeteksi."
        }

        // SND: zona demand/supply pendekatan sederhana dari candle pivot.
        val demand = findDemandZone(stable, last)
        val supply = findSupplyZone(stable, last)
        when {
            demand != null && last >= demand.first && last <= demand.second -> {
                score += 4
                reasons += "SND: harga berada di zona demand perkiraan; belum menjamin harga memantul."
            }
            supply != null && last >= supply.first && last <= supply.second -> {
                score -= 4
                reasons += "SND: harga berada di zona supply perkiraan; belum menjamin harga turun."
            }
            demand != null && last > demand.second &&
                last - demand.second <= proximity -> {
                score += 2
                reasons += "SND: harga dekat batas atas zona demand perkiraan."
            }
            supply != null && last < supply.first &&
                supply.first - last <= proximity -> {
                score -= 2
                reasons += "SND: harga dekat batas bawah zona supply perkiraan."
            }
            else -> reasons += "SND: tidak ada zona supply/demand dekat harga yang terdeteksi."
        }

        score = score.coerceIn(0, 100)
        val signal = when {
            score >= 70 -> "BUY"
            score <= 30 -> "SELL"
            else -> "WAIT"
        }

        // WAIT tidak memberi level entry/SL/TP. Untuk BUY/SELL, jarak dihitung
        // dinamis dari ATR agar mengikuti volatilitas instrumen/timeframe.
        val actionable = signal == "BUY" || signal == "SELL"
        val direction = if (signal == "SELL") -1.0 else 1.0
        val entry = if (actionable) last else null
        val stop = if (actionable && atrValue != null) {
            if (signal == "SELL") last + 1.5 * atrValue else last - 1.5 * atrValue
        } else null
        val tp1 = if (actionable && atrValue != null) last + direction * 1.0 * atrValue else null
        val tp2 = if (actionable && atrValue != null) last + direction * 2.0 * atrValue else null
        val tp3 = if (actionable && atrValue != null) last + direction * 3.0 * atrValue else null

        reasons += if (signal == "WAIT") {
            "WAIT: belum ada konfirmasi yang cukup; entry, SL, dan TP tidak ditampilkan."
        } else {
            "Entry memakai harga candle tertutup terakhir; SL dan TP1/TP2/TP3 dihitung dinamis dari ATR. Harga eksekusi nyata dapat berbeda."
        }
        reasons += "SMC, trendline, SNR, dan SND adalah deteksi teknikal sederhana, bukan jaminan hasil atau probabilitas profit."
        reasons += "Sinyal dihitung dari candle yang sudah tutup; bukan instruksi transaksi otomatis."

        return AnalysisResult(
            lastPrice = last,
            signal = signal,
            score = score,
            rsi = rsiValue,
            ema9 = e9,
            ema21 = e21,
            ema50 = e50,
            macd = macdValue,
            atr = atrValue,
            stopLoss = stop,
            takeProfit = tp2,
            reasons = reasons,
            entryPrice = entry,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            takeProfit3 = tp3
        )
    }

    private fun findPivotHighs(candles: List<Candle>, radius: Int = 2): List<Pivot> {
        if (candles.size < radius * 2 + 1) return emptyList()
        val result = mutableListOf<Pivot>()
        for (i in radius until candles.size - radius) {
            val price = candles[i].high
            var isPivot = true
            for (j in (i - radius)..(i + radius)) {
                if (j != i && candles[j].high >= price) {
                    isPivot = false
                    break
                }
            }
            if (isPivot) result += Pivot(i, price)
        }
        return result
    }

    private fun findPivotLows(candles: List<Candle>, radius: Int = 2): List<Pivot> {
        if (candles.size < radius * 2 + 1) return emptyList()
        val result = mutableListOf<Pivot>()
        for (i in radius until candles.size - radius) {
            val price = candles[i].low
            var isPivot = true
            for (j in (i - radius)..(i + radius)) {
                if (j != i && candles[j].low <= price) {
                    isPivot = false
                    break
                }
            }
            if (isPivot) result += Pivot(i, price)
        }
        return result
    }

    private fun findDemandZone(candles: List<Candle>, price: Double): Pair<Double, Double>? {
        val pivots = findPivotLows(candles)
        val pivot = pivots.lastOrNull { it.price <= price * 1.01 } ?: return null
        val candle = candles[pivot.index]
        return Pair(candle.low, max(candle.open, candle.close))
    }

    private fun findSupplyZone(candles: List<Candle>, price: Double): Pair<Double, Double>? {
        val pivots = findPivotHighs(candles)
        val pivot = pivots.lastOrNull { it.price >= price * 0.99 } ?: return null
        val candle = candles[pivot.index]
        return Pair(min(candle.open, candle.close), candle.high)
    }

    private fun formatPrice(value: Double): String = "%.4f".format(value)

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1)
        var value = values.take(period).average()
        for (x in values.drop(period)) value = alpha * x + (1 - alpha) * value
        return value
    }

    private fun rsi(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var averageGain = gain / period
        var averageLoss = loss / period
        for (i in (period + 1) until values.size) {
            val d = values[i] - values[i - 1]
            averageGain = (averageGain * (period - 1) + max(d, 0.0)) / period
            averageLoss = (averageLoss * (period - 1) + max(-d, 0.0)) / period
        }
        if (averageLoss == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + averageGain / averageLoss)
    }

    private fun atr(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period + 1) return null
        return candles.drop(1).mapIndexed { i, candle ->
            max(
                candle.high - candle.low,
                max(
                    abs(candle.high - candles[i].close),
                    abs(candle.low - candles[i].close)
                )
            )
        }.takeLast(period).average()
    }
}
