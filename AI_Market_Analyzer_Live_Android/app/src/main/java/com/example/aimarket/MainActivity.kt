package com.example.aimarket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private val Bg = Color(0xFF080D18)
private val Panel = Color(0xFF111A2A)
private val Muted = Color(0xFF9AA8BD)
private val Green = Color(0xFF36D399)
private val Red = Color(0xFFFF657A)
private val Amber = Color(0xFFFFC857)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LiveAnalyzerApp() }
    }
}

@Composable
private fun LiveAnalyzerApp() {
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var interval by remember { mutableStateOf("1m") }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var connected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Menghubungkan…") }
    var refreshKey by remember { mutableIntStateOf(0) }

    DisposableEffect(symbol, interval, refreshKey) {
        val repo = LiveMarketRepository { data, live, message ->
            candles = data
            connected = live
            status = message
        }
        repo.start(symbol, interval)
        onDispose { repo.stop() }
    }
    val result = remember(candles) { AnalysisEngine.analyze(candles) }
    val lastPrice = candles.lastOrNull()?.close
    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Green,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("MARKET LAB", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("AI Market Analyzer", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(color = if (connected) Color(0xFF17382F) else Color(0xFF49351A), shape = RoundedCornerShape(50)) {
                        Text(if (connected) "● LIVE" else "● OFFLINE", Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            color = if (connected) Green else Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                PanelCard {
                    Text("CRYPTO • DATA PUBLIK", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT").forEach {
                            Chip(it, symbol == it) { symbol = it }
                        }
                    }
                    Text("Timeframe candle", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("1m", "5m", "15m", "1h").forEach { Chip(it, interval == it) { interval = it } }
                    }
                }

                PanelCard {
                    Text(symbol, color = Muted, fontSize = 13.sp)
                    Text(lastPrice?.let(::price) ?: "Menunggu data…", fontSize = 29.sp, fontWeight = FontWeight.Bold)
                    Text(status, color = if (connected) Green else Amber, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    CandleChart(Modifier.fillMaxWidth().height(145.dp), candles.takeLast(70))
                    Text("Harga live • sinyal memakai candle yang sudah tutup", color = Muted, fontSize = 11.sp)
                }

                PanelCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("AUTO SIGNAL", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Surface(color = when (result?.signal) {
                            "BUY" -> Color(0xFF17382F)
                            "SELL" -> Color(0xFF48212A)
                            else -> Color(0xFF49351A)
                        }, shape = RoundedCornerShape(10.dp)) {
                            Text(result?.signal ?: "ANALYZING", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = when (result?.signal) { "BUY" -> Green; "SELL" -> Red; else -> Amber },
                                fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    Text(if (result == null) "Mengumpulkan minimal 52 candle tertutup untuk analisis." else "Skor aturan teknikal: ${result.score}/100",
                        color = Muted, fontSize = 12.sp)
                    if (result != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("RSI 14", result.rsi?.let { "%.1f".format(it) } ?: "—")
                            Metric("EMA 9", result.ema9?.let(::price) ?: "—")
                            Metric("EMA 21", result.ema21?.let(::price) ?: "—")
                            Metric("EMA 50", result.ema50?.let(::price) ?: "—")
                        }
                        HorizontalDivider(color = Color(0xFF283448))
                        Text("Alasan sinyal", fontWeight = FontWeight.Bold)
                        result.reasons.forEach { Text("• $it", color = Muted, fontSize = 12.sp, lineHeight = 17.sp) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("ATR 14", result.atr?.let(::price) ?: "—")
                            Metric("Stop loss*", result.stopLoss?.let(::price) ?: "—")
                            Metric("Target*", result.takeProfit?.let(::price) ?: "—")
                        }
                        Text("*Estimasi berbasis ATR; bukan instruksi order. Periksa spread, biaya, dan likuiditas.", color = Amber, fontSize = 10.sp)
                    }
                }

                Button(onClick = { refreshKey++ }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF17382F))) {
                    Text("Reconnect / Refresh data", color = Green)
                }
                Text("Versi ini live untuk crypto spot publik. Forex, saham, dan indeks belum diaktifkan. Tidak melakukan transaksi otomatis.",
                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF17382F), selectedLabelColor = Green,
            containerColor = Panel, labelColor = Muted
        ))
}
@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}
@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
@Composable
private fun CandleChart(modifier: Modifier, data: List<Candle>) {
    Canvas(modifier) {
        if (data.isEmpty()) return@Canvas
        val lo = data.minOf { it.low }
        val hi = data.maxOf { it.high }
        val range = max(hi - lo, 1e-9)
        val slot = size.width / data.size
        val body = max(2f, slot * 0.55f)
        fun y(v: Double) = size.height - (((v - lo) / range).toFloat() * size.height)
        data.forEachIndexed { i, c ->
            val x = i * slot + slot / 2
            val color = if (c.close >= c.open) Green else Red
            drawLine(color, Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = 1.2f)
            val top = min(y(c.open), y(c.close))
            val bottom = max(y(c.open), y(c.close))
            drawRect(color, Offset(x - body / 2, top), androidx.compose.ui.geometry.Size(body, max(2f, bottom - top)))
        }
    }
}
private fun price(v: Double): String = if (v >= 1000) "%,.2f".format(v) else if (v >= 1) "%.4f".format(v) else "%.6f".format(v)
