package com.example.aimarket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HigherTimeframeFilterTest {

    private val interval15m = 15L * 60_000L

    /**
     * Membuat candle timeframe 15 menit.
     * nowMillis disediakan secara tetap agar test konsisten.
     */
    private fun createCandles(
        count: Int,
        rising: Boolean,
        closed: Boolean = true
    ): List<Candle> {
        return (0 until count).map { index ->
            val direction = if (rising) 1.0 else -1.0
            val close = if (rising || !rising) {
                100.0 + direction * index * 0.5
            } else {
                100.0
            }

            val open = close - direction * 0.2

            Candle(
                time = (index + 1L) * interval15m,
                open = open,
                high = maxOf(open, close) + 0.3,
                low = minOf(open, close) - 0.3,
                close = close,
                volume = 100.0,
                closed = closed
            )
        }
    }

    private fun fixedNow(count: Int): Long {
        return (count + 1L) * interval15m
    }

    @Test
    fun timeframeMappingIsCorrect() {
        assertEquals(
            "15m",
            HigherTimeframeFilter.higherTimeframeFor("1m")
        )

        assertEquals(
            "15m",
            HigherTimeframeFilter.higherTimeframeFor("5m")
        )

        assertEquals(
            "1h",
            HigherTimeframeFilter.higherTimeframeFor("15m")
        )

        assertEquals(
            "4h",
            HigherTimeframeFilter.higherTimeframeFor("1h")
        )

        assertNull(
            HigherTimeframeFilter.higherTimeframeFor("4h")
        )
    }

    @Test
    fun bullishHigherTimeframeAllowsBuy() {
        val input = createCandles(
            count = 100,
            rising = true
        )

        val result = HigherTimeframeFilter.evaluate(
            candles = input,
            timeframe = "1m",
            nowMillis = fixedNow(100)
        )

        assertEquals(
            HigherTimeframeFilter.Bias.BULLISH,
            result.bias
        )

        assertTrue(result.canBuy)
        assertFalse(result.canSell)
        assertTrue(result.isReady)
        assertEquals("15m", result.timeframe)
    }

    @Test
    fun bearishHigherTimeframeAllowsSell() {
        val input = createCandles(
            count = 100,
            rising = false
        )

        val result = HigherTimeframeFilter.evaluate(
            candles = input,
            timeframe = "5m",
            nowMillis = fixedNow(100)
        )

        assertEquals(
            HigherTimeframeFilter.Bias.BEARISH,
            result.bias
        )

        assertFalse(result.canBuy)
        assertTrue(result.canSell)
        assertTrue(result.isReady)
        assertEquals("15m", result.timeframe)
    }

    @Test
    fun flatMarketProducesNeutralBias() {
        val input = (0 until 100).map { index ->
            Candle(
                time = (index + 1L) * interval15m,
                open = 100.0,
                high = 100.3,
                low = 99.7,
                close = 100.0,
                volume = 100.0,
                closed = true
            )
        }

        val result = HigherTimeframeFilter.evaluate(
            candles = input,
            timeframe = "1m",
            nowMillis = fixedNow(100)
        )

        assertEquals(
            HigherTimeframeFilter.Bias.NEUTRAL,
            result.bias
        )

        assertFalse(result.canBuy)
        assertFalse(result.canSell)
        assertFalse(result.isReady)
    }

    @Test
    fun insufficientCandlesReturnsUnavailable() {
        val input = createCandles(
            count = 51,
            rising = true
        )

        val result = HigherTimeframeFilter.evaluate(
            candles = input,
            timeframe = "1m",
            nowMillis = fixedNow(51)
        )

        assertEquals(
            HigherTimeframeFilter.Bias.UNAVAILABLE,
            result.bias
        )

        assertFalse(result.canBuy)
        assertFalse(result.canSell)
        assertFalse(result.isReady)
    }

    @Test
    fun unclosedCandlesAreNotUsed() {
        val closedCandles = createCandles(
            count = 51,
            rising = true
        )

        val unclosedCandle = Candle(
            time = 52L * interval15m,
            open = 125.0,
            high = 126.0,
            low = 124.0,
            close = 125.5,
            volume = 100.0,
            closed = false
        )

        val input = closedCandles + unclosedCandle

        val result = HigherTimeframeFilter.evaluate(
            candles = input,
            timeframe = "1m",
            nowMillis = fixedNow(52)
        )

        assertEquals(
            HigherTimeframeFilter.Bias.UNAVAILABLE,
            result.bias
        )
    }

    @Test
    fun staleHigherTimeframeDataReturnsUnavailable() {
        val input = createCandles(
            count = 100,
            rising = true
        )

        val latest = input.last()

        val staleNow = latest.time + interval15m * 4L

        val result = HigherTimeframeFilter.evaluate(
            candles = input,
            timeframe = "1m",
            nowMillis = staleNow
        )

        assertEquals(
            HigherTimeframeFilter.Bias.UNAVAILABLE,
            result.bias
        )

        assertFalse(result.isReady)
    }

    @Test
    fun unsupportedTimeframeReturnsUnavailable() {
        val input = createCandles(
            count = 100,
            rising = true
        )

        val result = HigherTimeframeFilter.evaluate(
            candles = input,
            timeframe = "4h",
            nowMillis = fixedNow(100)
        )

        assertEquals(
            HigherTimeframeFilter.Bias.UNAVAILABLE,
            result.bias
        )

        assertFalse(result.canBuy)
        assertFalse(result.canSell)
    }

    @Test
    fun resultAlwaysContainsReason() {
        val input = createCandles(
            count = 100,
            rising = true
        )

        val result = HigherTimeframeFilter.evaluate(
            candles = input,
            timeframe = "1m",
            nowMillis = fixedNow(100)
        )

        assertNotNull(result.reason)
        assertTrue(result.reason.isNotBlank())
    }
}
