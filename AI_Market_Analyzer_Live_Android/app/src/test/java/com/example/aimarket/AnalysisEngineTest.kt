package com.example.aimarket

import org.junit.Assert.*
import org.junit.Test

class AnalysisEngineTest {

    private fun candles(count: Int): List<Candle> =
        (0 until count).map { i ->
            val close = 100.0 + i * 0.5

            Candle(
                time = (i + 1L) * 60_000L,
                open = close - 0.2,
                high = close + 0.5,
                low = close - 0.5,
                close = close,
                volume = 100.0,
                closed = true
            )
        }

    /**
     * Membuat tren kuat dengan candle bullish/bearish
     * yang valid dan ATR stabil.
     */
    private fun trendingCandles(
        count: Int,
        rising: Boolean
    ): List<Candle> =
        (0 until count).map { i ->
            val direction = if (rising) 1.0 else -1.0
            val close = 100.0 + direction * i * 0.5
            val open = close - direction * 1.0

            Candle(
                time = (i + 1L) * 60_000L,
                open = open,
                high = maxOf(open, close) + 0.2,
                low = minOf(open, close) - 0.2,
                close = close,
                volume = 100.0,
                closed = true
            )
        }

    @Test
    fun insufficientCandlesReturnsNull() {
        assertNull(
            AnalysisEngine.analyze(candles(51))
        )
    }

    @Test
    fun unclosedCandlesAreNotUsed() {
        val input = candles(51) + Candle(
            time = 52L * 60_000L,
            open = 126.0,
            high = 127.0,
            low = 125.0,
            close = 126.5,
            volume = 100.0,
            closed = false
        )

        assertNull(AnalysisEngine.analyze(input))
    }

    @Test
    fun enoughValidCandlesProduceValidResult() {
        val result = AnalysisEngine.analyze(candles(100))

        assertNotNull(result)
        assertTrue(result!!.lastPrice.isFinite())
        assertTrue(result.score in 0..100)
        assertTrue(
            result.signal in setOf("BUY", "SELL", "WAIT")
        )
    }

    @Test
    fun sameInputProducesSameResult() {
        val input = candles(100)

        assertEquals(
            AnalysisEngine.analyze(input),
            AnalysisEngine.analyze(input)
        )
    }

    @Test
    fun bullishTrendProducesBuyWithValidTargets() {
        val result = AnalysisEngine.analyze(
            trendingCandles(100, rising = true)
        )

        assertNotNull(result)
        val analysis = result!!

        assertEquals("BUY", analysis.signal)
        assertNotNull(analysis.entryPrice)
        assertNotNull(analysis.stopLoss)
        assertNotNull(analysis.takeProfit1)
        assertNotNull(analysis.takeProfit2)
        assertNotNull(analysis.atr)

        val entry = analysis.entryPrice!!
        val sl = analysis.stopLoss!!
        val tp1 = analysis.takeProfit1!!
        val tp2 = analysis.takeProfit2!!
        val atr = analysis.atr!!

        assertTrue(sl < entry)
        assertTrue(tp1 > entry)
        assertTrue(tp2 > tp1)

        assertEquals(
            1.15,
            (entry - sl) / atr,
            1e-6
        )

        assertEquals(
            1.0,
            (tp1 - entry) / (entry - sl),
            1e-6
        )

        assertEquals(
            2.0,
            (tp2 - entry) / (entry - sl),
            1e-6
        )
    }

    @Test
    fun bearishTrendProducesSellWithValidTargets() {
        val result = AnalysisEngine.analyze(
            trendingCandles(100, rising = false)
        )

        assertNotNull(result)
        val analysis = result!!

        assertEquals("SELL", analysis.signal)
        assertNotNull(analysis.entryPrice)
        assertNotNull(analysis.stopLoss)
        assertNotNull(analysis.takeProfit1)
        assertNotNull(analysis.takeProfit2)
        assertNotNull(analysis.atr)

        val entry = analysis.entryPrice!!
        val sl = analysis.stopLoss!!
        val tp1 = analysis.takeProfit1!!
        val tp2 = analysis.takeProfit2!!
        val atr = analysis.atr!!

        assertTrue(sl > entry)
        assertTrue(tp1 < entry)
        assertTrue(tp2 < tp1)

        assertEquals(
            1.15,
            (sl - entry) / atr,
            1e-6
        )

        assertEquals(
            1.0,
            (entry - tp1) / (sl - entry),
            1e-6
        )

        assertEquals(
            2.0,
            (entry - tp2) / (sl - entry),
            1e-6
        )
    }
}
