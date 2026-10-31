package com.example.aimarket

import kotlin.math.abs

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
    val reasons: List<String>
)

object AnalysisEngine {
    fun analyze(candles: List<Candle>): AnalysisResult? {
        if (candles.size < 52) return null
        // Use closed candles only for the signal to reduce intrabar flicker.
        val stable = candles.filter { it.closed }
        if (stable.size < 52) return null
        val closes = stable.map { it.close }
        val last = closes.last()
        val e9 = ema(closes, 9) ?: return null
        val e21 = ema(closes, 21) ?: return null
        val e50 = ema(closes, 50) ?: return null
        val rsi = rsi(closes, 14)
        val atr = atr(stable, 14)
        val macd = (ema(closes, 12) ?: last) - (ema(closes, 26) ?: last)

        var score = 50
        val reasons = mutableListOf<String>()
        if (e9 > e21) { score += 15; reasons += "EMA 9 di atas EMA 21: momentum pendek mengarah naik." }
        else { score -= 15; reasons += "EMA 9 di bawah EMA 21: momentum pendek mengarah turun." }
        if (e21 > e50) { score += 15; reasons += "EMA 21 di atas EMA 50: tren lebih besar mendukung bullish." }
        else { score -= 15; reasons += "EMA 21 di bawah EMA 50: tren lebih besar mendukung bearish." }
        if (rsi != null) {
            when {
                rsi in 52.0..68.0 -> { score += 10; reasons += "RSI ${"%.1f".format(rsi)} mendukung momentum bullish." }
                rsi in 32.0..48.0 -> { score -= 10; reasons += "RSI ${"%.1f".format(rsi)} mendukung momentum bearish." }
                rsi > 70.0 -> { score -= 5; reasons += "RSI ${"%.1f".format(rsi)} tinggi; risiko pullback meningkat." }
                rsi < 30.0 -> { score += 5; reasons += "RSI ${"%.1f".format(rsi)} rendah; pantulan belum terkonfirmasi." }
                else -> reasons += "RSI ${"%.1f".format(rsi)} belum memberi konfirmasi kuat."
            }
        }
        if (macd > 0) { score += 5; reasons += "MACD line di atas nol." }
        else { score -= 5; reasons += "MACD line di bawah nol." }
        score = score.coerceIn(0, 100)
        val signal = when {
            score >= 70 -> "BUY"
            score <= 30 -> "SELL"
            else -> "WAIT"
        }
        val stop = atr?.let { if (signal == "SELL") last + 1.5 * it else last - 1.5 * it }
        val target = atr?.let { if (signal == "SELL") last - 2.0 * it else last + 2.0 * it }
        reasons += "Sinyal dihitung dari candle yang sudah tutup; bukan jaminan hasil atau probabilitas profit."
        return AnalysisResult(last, signal, score, rsi, e9, e21, e50, macd, atr, stop, target, reasons)
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        val alpha = 2.0 / (period + 1)
        var value = values.take(period).average()
        for (x in values.drop(period)) value = alpha * x + (1 - alpha) * value
        return value
    }
    private fun rsi(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var ag = gain / period; var al = loss / period
        for (i in (period + 1) until values.size) {
            val d = values[i] - values[i - 1]
            ag = (ag * (period - 1) + maxOf(d, 0.0)) / period
            al = (al * (period - 1) + maxOf(-d, 0.0)) / period
        }
        if (al == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + ag / al)
    }
    private fun atr(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period + 1) return null
        return candles.drop(1).mapIndexed { i, c ->
            maxOf(c.high - c.low, abs(c.high - candles[i].close), abs(c.low - candles[i].close))
        }.takeLast(period).average()
    }
}
