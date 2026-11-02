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

    @Test
    fun insufficientCandlesReturnsNull() {
        assertNull(AnalysisEngine.analyze(candles(51)))
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
        assertTrue(result.signal in setOf("BUY", "SELL", "WAIT"))
    }

    @Test
    fun sameInputProducesSameResult() {
        val input = candles(100)

        assertEquals(
            AnalysisEngine.analyze(input),
            AnalysisEngine.analyze(input)
        )
    }
}
