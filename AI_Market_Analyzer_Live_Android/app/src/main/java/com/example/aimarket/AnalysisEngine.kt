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
        val stable = candles
            .filter { it.closed && isValidCandle(it) }
            .distinctBy { it.time }
            .sortedBy { it.time }
            .takeLast(300)

        if (stable.size < 52) return null

        val closes = stable.map { it.close }
        val lastCandle = stable.last()
        val last = lastCandle.close

        val e9 = ema(closes, 9) ?: return null
        val e21 = ema(closes, 21) ?: return null
        val e50 = ema(closes, 50) ?: return null
        val rsiValue = rsi(closes, 14)
        val atrValue = atr(stable, 14)?.takeIf { it > 0.0 && it.isFinite() }
        val macdValue = (ema(closes, 12) ?: last) -
            (ema(closes, 26) ?: last)

        if (!last.isFinite() || last <= 0.0 ||
            !e9.isFinite() || !e21.isFinite() || !e50.isFinite() ||
            !macdValue.isFinite()
        ) return null

        val reasons = mutableListOf<String>()
        var score = 50

        val bullishTrend = e9 > e21 && e21 > e50
        val bearishTrend = e9 < e21 && e21 < e50
        val bullishMomentum = e9 > e21 && macdValue > 0.0
        val bearishMomentum = e9 < e21 && macdValue < 0.0

        when {
            bullishTrend -> {
                score += 15
                reasons += "Tren naik selaras: EMA 9 > EMA 21 > EMA 50."
            }
            bearishTrend -> {
                score -= 15
                reasons += "Tren turun selaras: EMA 9 < EMA 21 < EMA 50."
            }
            e9 > e21 -> {
                score += 7
                reasons += "EMA 9 di atas EMA 21; momentum jangka pendek positif."
            }
            e9 < e21 -> {
                score -= 7
                reasons += "EMA 9 di bawah EMA 21; momentum jangka pendek negatif."
            }
            else -> reasons += "EMA jangka pendek berdekatan; tren belum tegas."
        }

        if (rsiValue != null) {
            when {
                rsiValue in 52.0..68.0 -> {
                    score += 7
                    reasons += "RSI ${fmt(rsiValue)} mendukung momentum bullish."
                }
                rsiValue in 32.0..48.0 -> {
                    score -= 7
                    reasons += "RSI ${fmt(rsiValue)} mendukung momentum bearish."
                }
                rsiValue > 78.0 -> {
                    score -= 4
                    reasons += "RSI sangat tinggi; risiko mengejar harga meningkat."
                }
                rsiValue < 22.0 -> {
                    score += 4
                    reasons += "RSI sangat rendah; waspadai potensi pantulan."
                }
                else -> reasons += "RSI ${fmt(rsiValue)} netral atau belum memberi konfirmasi kuat."
            }
        }

        if (macdValue > 0.0) {
            score += 5
            reasons += "MACD line positif."
        } else if (macdValue < 0.0) {
            score -= 5
            reasons += "MACD line negatif."
        }

        val body = abs(lastCandle.close - lastCandle.open)
        val bullishCandle = lastCandle.close > lastCandle.open
        val bearishCandle = lastCandle.close < lastCandle.open

        if (atrValue != null && body >= atrValue * 0.55) {
            when {
                bullishCandle -> {
                    score += 6
                    reasons += "Candle bullish memiliki body relatif kuat."
                }
                bearishCandle -> {
                    score -= 6
                    reasons += "Candle bearish memiliki body relatif kuat."
                }
            }
        }

        val highs = findPivotHighs(stable)
            .filter { it.index < stable.lastIndex - 1 }
        val lows = findPivotLows(stable)
            .filter { it.index < stable.lastIndex - 1 }

        val previousHigh = highs.lastOrNull()
        val previousLow = lows.lastOrNull()

        val bullishBreakout = previousHigh != null &&
            lastCandle.close > previousHigh.price && bullishCandle

        val bearishBreakout = previousLow != null &&
            lastCandle.close < previousLow.price && bearishCandle

        when {
            bullishBreakout -> {
                score += 11
                reasons += "Breakout bullish menembus swing high dengan candle tertutup."
            }
            bearishBreakout -> {
                score -= 11
                reasons += "Breakout bearish menembus swing low dengan candle tertutup."
            }
            else -> reasons += "Belum ada breakout swing terkonfirmasi; setup tren tetap dinilai."
        }

        val lastTwoHighs = highs.takeLast(2)
        val lastTwoLows = lows.takeLast(2)

        if (lastTwoHighs.size == 2 && lastTwoLows.size == 2) {
            val hh = lastTwoHighs[1].price > lastTwoHighs[0].price
            val hl = lastTwoLows[1].price > lastTwoLows[0].price
            val lh = lastTwoHighs[1].price < lastTwoHighs[0].price
            val ll = lastTwoLows[1].price < lastTwoLows[0].price

            when {
                hh && hl -> {
                    score += 7
                    reasons += "Struktur HH/HL mendukung bias bullish."
                }
                lh && ll -> {
                    score -= 7
                    reasons += "Struktur LH/LL mendukung bias bearish."
                }
                else -> reasons += "Struktur swing belum sepenuhnya searah."
            }
        }

        val recentVolumes = stable.takeLast(21)
            .dropLast(1)
            .map { it.volume }
            .filter { it > 0.0 && it.isFinite() }

        if (recentVolumes.size >= 10 && lastCandle.volume > 0.0) {
            val averageVolume = recentVolumes.average()

            if (averageVolume > 0.0 &&
                lastCandle.volume >= averageVolume * 1.35
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

        val support = lows.lastOrNull { it.price < last }?.price
        val resistance = highs.lastOrNull { it.price > last }?.price
        val proximity = atrValue?.times(0.65) ?: last * 0.002

        if (support != null && last - support <= proximity) {
            score += 3
            reasons += "Harga dekat support swing; pantau reaksi harga."
        }

        if (resistance != null && resistance - last <= proximity) {
            score -= 3
            reasons += "Harga dekat resistance swing; risiko penolakan meningkat."
        }

        if (atrValue != null) {
            val extension = abs(last - e21) / atrValue

            if (extension > 2.8) {
                score += if (last > e21) -5 else 5
                reasons += "Harga terlalu jauh dari EMA21 dibanding ATR; hindari mengejar candle."
            }
        }

        score = score.coerceIn(0, 100)

        // Konfirmasi fleksibel: tidak semua indikator wajib searah.
        val bullishSetup = bullishTrend ||
            bullishMomentum ||
            (e9 > e21 && bullishBreakout)

        val bearishSetup = bearishTrend ||
            bearishMomentum ||
            (e9 < e21 && bearishBreakout)

        val signal = when {
            score >= 66 && bullishSetup && !bearishBreakout -> "BUY"
            score <= 34 && bearishSetup && !bullishBreakout -> "SELL"
            else -> "WAIT"
        }

        var entry: Double? = null
        var stop: Double? = null
        var tp1: Double? = null
        var tp2: Double? = null
        var tp3: Double? = null

        if ((signal == "BUY" || signal == "SELL") && atrValue != null) {
            val isBuy = signal == "BUY"
            val direction = if (isBuy) 1.0 else -1.0
            entry = last

            val structuralStop = if (isBuy) {
                support?.takeIf { it < last }?.minus(atrValue * 0.10)
            } else {
                resistance?.takeIf { it > last }?.plus(atrValue * 0.10)
            }

            val minRisk = atrValue * 1.15
            val maxRisk = atrValue * 3.2

            val rawStop = if (isBuy) {
                min(last - minRisk, structuralStop ?: (last - minRisk))
            } else {
                max(last + minRisk, structuralStop ?: (last + minRisk))
            }

            val risk = abs(last - rawStop).coerceAtMost(maxRisk)
            val calculatedStop = last - direction * risk

            if (risk > 0.0 && risk.isFinite() && calculatedStop.isFinite()) {
                stop = calculatedStop
                tp1 = last + direction * risk
                tp2 = last + direction * risk * 2.0

                val obstacle = if (isBuy) {
                    resistance?.takeIf { it > last }
                } else {
                    support?.takeIf { it < last }
                }

                val target3 = last + direction * risk * 3.0
                val target3HasRoom = obstacle == null || if (isBuy) {
                    target3 <= obstacle + atrValue * 0.15
                } else {
                    target3 >= obstacle - atrValue * 0.15
                }

                if (target3HasRoom && target3.isFinite()) {
                    tp3 = target3
                }

                reasons += "SL adaptif: minimal 1,15 ATR, mempertimbangkan swing, maksimal 3,2 ATR."
                reasons += "TP1 RR 1:1 dan TP2 RR 1:2 dihitung dari risiko entry ke SL."

                if (obstacle != null && abs(obstacle - last) < risk * 2.0) {
                    reasons += "Peringatan: support/resistance dekat dapat menghambat TP2."
                }

                if (tp3 == null) {
                    reasons += "TP3 tidak ditampilkan karena ruang target belum cukup."
                }
            }
        }

        if (signal == "WAIT") {
            reasons += "WAIT: skor atau konfirmasi arah belum cukup; tidak semua indikator wajib searah."
        }

        reasons += "Skor adalah gabungan aturan teknikal, bukan probabilitas profit."
        reasons += "Sinyal menggunakan candle tertutup; spread, slippage, dan biaya belum dimodelkan."

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

    private fun fmt(value: Double): String = "%.1f".format(value)

    private fun isValidCandle(c: Candle): Boolean =
        c.time > 0L &&
            c.open.isFinite() &&
            c.high.isFinite() &&
            c.low.isFinite() &&
            c.close.isFinite() &&
            c.volume.isFinite() &&
            c.open > 0.0 &&
            c.high > 0.0 &&
            c.low > 0.0 &&
            c.close > 0.0 &&
            c.high >= max(c.open, c.close) &&
            c.low <= min(c.open, c.close) &&
            c.high >= c.low &&
            c.volume >= 0.0

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
        candles: List<Candle>,
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

        return value.takeIf { it.isFinite() }
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

        if (!averageGain.isFinite() || !averageLoss.isFinite()) return null
        if (averageLoss == 0.0) return 100.0

        val value = 100.0 - 100.0 / (1.0 + averageGain / averageLoss)
        return value.takeIf { it.isFinite() }
    }

    private fun atr(candles: List<Candle>, period: Int): Double? {
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

        return trueRanges.takeLast(period)
            .average()
            .takeIf { it > 0.0 && it.isFinite() }
    }
}
