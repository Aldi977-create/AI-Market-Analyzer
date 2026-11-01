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
        val stable = candles.filter { it.closed }.takeLast(300)
        if (stable.size < 52) return null

        val closes = stable.map { it.close }
        val lastCandle = stable.last()
        val last = lastCandle.close

        val e9 = ema(closes, 9) ?: return null
        val e21 = ema(closes, 21) ?: return null
        val e50 = ema(closes, 50) ?: return null
        val rsiValue = rsi(closes, 14)
        val atrValue = atr(stable, 14)
        val macdValue =
            (ema(closes, 12) ?: last) - (ema(closes, 26) ?: last)

        val atr = atrValue?.takeIf { it > 0.0 && it.isFinite() }
        val reasons = mutableListOf<String>()
        var score = 50

        // Arah tren utama.
        val bullishTrend = e9 > e21 && e21 > e50
        val bearishTrend = e9 < e21 && e21 < e50

        when {
            bullishTrend -> {
                score += 16
                reasons += "Tren bullish: EMA 9 > EMA 21 > EMA 50."
            }
            bearishTrend -> {
                score -= 16
                reasons += "Tren bearish: EMA 9 < EMA 21 < EMA 50."
            }
            e9 > e21 -> {
                score += 7
                reasons += "Momentum jangka pendek bullish, tetapi susunan EMA belum lengkap."
            }
            e9 < e21 -> {
                score -= 7
                reasons += "Momentum jangka pendek bearish, tetapi susunan EMA belum lengkap."
            }
            else -> reasons += "EMA berdekatan; arah tren belum jelas."
        }

        // RSI: hindari menghukum tren kuat hanya karena RSI tinggi/rendah.
        if (rsiValue != null) {
            when {
                rsiValue >= 55.0 && rsiValue <= 70.0 -> {
                    score += 9
                    reasons += "RSI ${"%.1f".format(rsiValue)} mendukung momentum bullish."
                }
                rsiValue > 70.0 -> {
                    score += if (bullishTrend) 3 else -3
                    reasons += "RSI ${"%.1f".format(rsiValue)} tinggi; hindari mengejar harga tanpa konfirmasi."
                }
                rsiValue >= 30.0 && rsiValue <= 45.0 -> {
                    score -= 9
                    reasons += "RSI ${"%.1f".format(rsiValue)} mendukung momentum bearish."
                }
                rsiValue < 30.0 -> {
                    score += if (bearishTrend) -3 else 3
                    reasons += "RSI ${"%.1f".format(rsiValue)} rendah; waspadai pantulan."
                }
                else -> reasons += "RSI ${"%.1f".format(rsiValue)} belum memberi konfirmasi kuat."
            }
        }

        if (macdValue > 0.0) {
            score += 5
            reasons += "MACD line berada di atas nol."
        } else if (macdValue < 0.0) {
            score -= 5
            reasons += "MACD line berada di bawah nol."
        }

        // Momentum candle dibandingkan ATR.
        val body = abs(lastCandle.close - lastCandle.open)
        val bullishCandle = lastCandle.close > lastCandle.open
        val bearishCandle = lastCandle.close < lastCandle.open

        val strongBullishCandle =
            atr != null && bullishCandle && body >= atr * 0.65

        val strongBearishCandle =
            atr != null && bearishCandle && body >= atr * 0.65

        if (strongBullishCandle) {
            score += 8
            reasons += "Candle bullish memiliki body besar relatif terhadap ATR."
        } else if (strongBearishCandle) {
            score -= 8
            reasons += "Candle bearish memiliki body besar relatif terhadap ATR."
        }

        // Struktur swing dan breakout.
        val highs = findPivotHighs(stable)
        val lows = findPivotLows(stable)

        // Pivot yang cukup lama untuk dianggap telah terkonfirmasi.
        val confirmedHighs = highs.filter { it.index < stable.lastIndex - 1 }
        val confirmedLows = lows.filter { it.index < stable.lastIndex - 1 }

        val previousHigh = confirmedHighs.lastOrNull()
        val previousLow = confirmedLows.lastOrNull()

        val bullishBreakout =
            previousHigh != null &&
                lastCandle.close > previousHigh.price &&
                bullishCandle

        val bearishBreakout =
            previousLow != null &&
                lastCandle.close < previousLow.price &&
                bearishCandle

        if (bullishBreakout) {
            score += 12
            reasons += "Breakout bullish: candle tertutup menembus swing high."
        } else if (bearishBreakout) {
            score -= 12
            reasons += "Breakout bearish: candle tertutup menembus swing low."
        } else {
            reasons += "Belum ada breakout swing yang terkonfirmasi."
        }

        // Urutan swing untuk konfirmasi struktur.
        val lastTwoHighs = confirmedHighs.takeLast(2)
        val lastTwoLows = confirmedLows.takeLast(2)

        if (lastTwoHighs.size == 2 && lastTwoLows.size == 2) {
            val higherHigh =
                lastTwoHighs[1].price > lastTwoHighs[0].price
            val higherLow =
                lastTwoLows[1].price > lastTwoLows[0].price
            val lowerHigh =
                lastTwoHighs[1].price < lastTwoHighs[0].price
            val lowerLow =
                lastTwoLows[1].price < lastTwoLows[0].price

            when {
                higherHigh && higherLow -> {
                    score += 7
                    reasons += "Struktur harga membentuk higher high dan higher low."
                }
                lowerHigh && lowerLow -> {
                    score -= 7
                    reasons += "Struktur harga membentuk lower high dan lower low."
                }
            }
        }

        // Volume hanya dipakai bila feed menyediakan data volume yang berguna.
        val recentVolumes = stable.takeLast(21).dropLast(1)
            .map { it.volume }
            .filter { it > 0.0 && it.isFinite() }

        if (recentVolumes.size >= 10 && lastCandle.volume > 0.0) {
            val averageVolume = recentVolumes.average()

            if (averageVolume > 0.0 &&
                lastCandle.volume >= averageVolume * 1.5
            ) {
                when {
                    bullishCandle -> {
                        score += 4
                        reasons += "Volume di atas rata-rata mendukung candle bullish."
                    }
                    bearishCandle -> {
                        score -= 4
                        reasons += "Volume di atas rata-rata mendukung candle bearish."
                    }
                }
            }
        }

        // Support dan resistance dari pivot terkonfirmasi.
        val support = confirmedLows.lastOrNull {
            it.price < last
        }?.price

        val resistance = confirmedHighs.lastOrNull {
            it.price > last
        }?.price

        val proximity = atr?.times(0.6) ?: last * 0.002

        if (support != null && last - support <= proximity) {
            score += 3
            reasons += "Harga relatif dekat support swing."
        }

        if (resistance != null && resistance - last <= proximity) {
            score -= 3
            reasons += "Harga relatif dekat resistance swing."
        }

        score = score.coerceIn(0, 100)

        // Sinyal harus searah dengan tren atau memiliki breakout yang jelas.
        val bullishSetup =
            bullishTrend || (e9 > e21 && bullishBreakout)

        val bearishSetup =
            bearishTrend || (e9 < e21 && bearishBreakout)

        val signal = when {
            score >= 70 && bullishSetup -> "BUY"
            score <= 30 && bearishSetup -> "SELL"
            else -> "WAIT"
        }

        val actionable = signal == "BUY" || signal == "SELL"
        val direction = if (signal == "BUY") 1.0 else -1.0

        var entry: Double? = null
        var stop: Double? = null
        var tp1: Double? = null
        var tp2: Double? = null
        var tp3: Double? = null

        if (actionable && atr != null) {
            entry = last

            // SL berbasis struktur swing dengan batas minimum ATR.
            val structuralStop = if (signal == "BUY") {
                support?.takeIf { it < entry }?.minus(atr * 0.10)
            } else {
                resistance?.takeIf { it > entry }?.plus(atr * 0.10)
            }

            val minimumRisk = atr * 1.2

            val calculatedStop = if (signal == "BUY") {
                min(entry - minimumRisk, structuralStop ?: (entry - minimumRisk))
            } else {
                max(entry + minimumRisk, structuralStop ?: (entry + minimumRisk))
            }

            val risk = abs(entry - calculatedStop)

            // Hindari level yang tidak valid.
            if (risk > 0.0 && risk.isFinite()) {
                stop = calculatedStop

                val target1 = entry + direction * risk
                val target2 = entry + direction * risk * 2.0
                val target3 = entry + direction * risk * 3.0

                // Periksa ruang harga sampai resistance/support terdekat.
                // Jika hambatan terlalu dekat, target yang melewatinya disembunyikan.
                val obstacle = if (signal == "BUY") {
                    resistance?.takeIf { it > entry }
                } else {
                    support?.takeIf { it < entry }
                }

                fun targetIsPlausible(target: Double): Boolean {
                    if (obstacle == null) return true

                    val buffer = atr * 0.10
                    return if (signal == "BUY") {
                        target <= obstacle + buffer
                    } else {
                        target >= obstacle - buffer
                    }
                }

                if (targetIsPlausible(target1)) tp1 = target1
                if (tp1 != null && targetIsPlausible(target2)) tp2 = target2
                if (tp2 != null && targetIsPlausible(target3)) tp3 = target3

                reasons += "SL menggunakan swing terdekat bila tersedia dan jarak minimum 1,2 ATR."
                reasons += "TP dihitung dari risiko entry ke SL: TP1 = RR 1:1, TP2 = RR 1:2, TP3 = RR 1:3."
                reasons += if (obstacle != null) {
                    "Target yang melewati resistance/support terdekat disaring. Target tidak dijamin tercapai."
                } else {
                    "Belum ada hambatan swing terdekat yang terdeteksi; target tetap bersifat estimasi."
                }

                if (tp1 == null) {
                    reasons += "TP1 tidak ditampilkan karena ruang harga menuju target belum memadai."
                }
                if (tp1 != null && tp2 == null) {
                    reasons += "TP2 tidak ditampilkan karena ruang harga menuju target belum memadai."
                }
                if (tp2 != null && tp3 == null) {
                    reasons += "TP3 tidak ditampilkan karena ruang harga menuju target belum memadai."
                }
            }
        }

        if (signal == "WAIT") {
            reasons += "WAIT: skor atau konfirmasi tren belum memenuhi syarat entry."
        }

        reasons += "Skor adalah nilai aturan teknikal, bukan probabilitas profit."
        reasons += "Harga entry, spread, slippage, dan biaya transaksi nyata dapat berbeda."
        reasons += "Sinyal menggunakan candle tertutup dan tidak mengeksekusi transaksi otomatis."

        return AnalysisResult(
            lastPrice = last,
            signal = signal,
            score = score,
            rsi = rsiValue,
            ema9 = e9,
            ema21 = e21,
            ema50 = e50,
            macd = macdValue,
            atr = atr,
            stopLoss = stop,
            takeProfit = tp2,
            reasons = reasons,
            entryPrice = entry,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            takeProfit3 = tp3
        )
    }

    private fun findPivotHighs(
        candles: List<Candle>,
        radius: Int = 2
    ): List<Pivot> {
        if (candles.size < radius * 2 + 1) return emptyList()

        val result = mutableListOf<Pivot>()

        for (i in radius until candles.size - radius) {
            val value = candles[i].high
            var isPivot = true

            for (j in (i - radius)..(i + radius)) {
                if (j != i && candles[j].high >= value) {
                    isPivot = false
                    break
                }
            }

            if (isPivot) result += Pivot(i, value)
        }

        return result
    }

    private fun findPivotLows(
        candles: List<Candle,
        radius: Int = 2
    ): List<Pivot> {
        if (candles.size < radius * 2 + 1) return emptyList()

        val result = mutableListOf<Pivot>()

        for (i in radius until candles.size - radius) {
            val value = candles[i].low
            var isPivot = true

            for (j in (i - radius)..(i + radius)) {
                if (j != i && candles[j].low <= value) {
                    isPivot = false
                    break
                }
            }

            if (isPivot) result += Pivot(i, value)
        }

        return result
    }

    private fun ema(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null

        val alpha = 2.0 / (period + 1)
        var value = values.take(period).average()

        for (x in values.drop(period)) {
            value = alpha * x + (1.0 - alpha) * value
        }

        return value
    }

    private fun rsi(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null

        var gain = 0.0
        var loss = 0.0

        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            if (change >= 0.0) gain += change else loss -= change
        }

        var averageGain = gain / period
        var averageLoss = loss / period

        for (i in (period + 1) until values.size) {
            val change = values[i] - values[i - 1]
            averageGain =
                (averageGain * (period - 1) + max(change, 0.0)) / period
            averageLoss =
                (averageLoss * (period - 1) + max(-change, 0.0)) / period
        }

        if (averageLoss == 0.0) return 100.0

        return 100.0 - 100.0 / (
            1.0 + averageGain / averageLoss
        )
    }

    private fun atr(
        candles: List<Candle>,
        period: Int
    ): Double? {
        if (candles.size < period + 1) return null

        val trueRanges = candles.drop(1).mapIndexed { i, candle ->
            max(
                candle.high - candle.low,
                max(
                    abs(candle.high - candles[i].close),
                    abs(candle.low - candles[i].close)
                )
            )
        }

        return trueRanges.takeLast(period).average()
    }
}
