# AI Market Analyzer Live — Android Starter

## Fitur versi 0.2.0
- Harga candle crypto publik melalui Binance REST API + WebSocket.
- BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT.
- Timeframe candle 1m, 5m, 15m, 1h.
- Candlestick chart live.
- Sinyal BUY / SELL / WAIT otomatis berdasarkan EMA 9/21/50, RSI, dan MACD line sederhana.
- ATR-based stop/target estimates.
- Reconnect saat koneksi WebSocket gagal.

## Batasan penting
- Versi ini hanya menghubungkan crypto spot publik; forex, saham, indeks belum dihubungkan.
- Data harga berasal dari Binance; akses dapat tergantung konektivitas, lokasi, atau kebijakan layanan.
- Ini adalah mesin aturan teknikal, bukan model AI machine-learning yang dilatih.
- Sinyal dihitung dari candle tertutup, sehingga tidak berubah-ubah setiap tick; harga chart tetap diperbarui saat candle berjalan.
- Tidak ada eksekusi order otomatis.
- Jangan memakai sinyal/level ini sebagai jaminan profit. Uji dengan backtest dan paper trading terlebih dahulu.

## Cara menjalankan di Windows / macOS / Linux
1. Unduh dan ekstrak ZIP.
2. Instal Android Studio stable dan JDK 17.
3. Android Studio → Open → pilih folder `AI_Market_Analyzer_Live`.
4. Tunggu Gradle Sync; pastikan internet aktif.
5. Jalankan di emulator atau perangkat Android.
6. Untuk membuat APK: Build → Build Bundle(s) / APK(s) → Build APK(s).
7. APK debug umumnya berada di `app/build/outputs/apk/debug/app-debug.apk`.

## Troubleshooting
- Jika Gradle Sync gagal, cek koneksi, JDK 17, dan versi Android Studio.
- Jika status OFFLINE, cek internet dan coba Reconnect / Refresh data.
- Jika API tidak dapat diakses dari jaringan Anda, provider bisa tidak tersedia; jangan menganggap harga terakhir masih live.
- Untuk rilis publik, tambahkan indikator waktu update terakhir, batas umur data (stale-data guard), pengujian unit, dan penanganan batas permintaan.

## Struktur kode
- `MainActivity.kt`: UI Compose dan candlestick.
- `LiveMarketRepository.kt`: REST candle history + WebSocket updates.
- `AnalysisEngine.kt`: EMA, RSI, MACD line, ATR, sinyal.
