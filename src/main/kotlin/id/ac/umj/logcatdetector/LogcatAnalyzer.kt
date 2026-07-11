package id.ac.umj.logcatdetector

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LogcatAnalyzer {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val gson = Gson()

    // URL server Hugging Face kamu
    private val flaskUrl = "https://faizenith0145-logcat-detector.hf.space/predict"

    // --- 🛠️ TAMBAHAN VARIABEL UNTUK ANTI-SPAM ---
    private var lastSentLog = ""
    private var lastSentTime = 0L
    private val MIN_INTERVAL_MS = 500L // Jeda minimal 500ms antar pengiriman data
    // --------------------------------------------

    // Data class untuk request ke Flask
    data class PredictRequest(
        val log_text: String,
        val package_name: String = "Android-App",
        val timestamp: String = java.time.LocalDateTime.now().toString()
    )

    // Data class untuk menerima respon dari Flask
    data class PredictResponse(
        val category: String,
        val recommendation: String,
        val status: String
    )

    /**
     * Fungsi untuk mengirim log ke AI di Flask (Sudah dilengkapi proteksi Anti-Spam)
     */
    fun analyzeWithAI(logText: String): PredictResponse? {

        // 1. SENSOR LEVEL LOG: Hanya proses log yang mengandung Error (" E/"), FATAL, atau Exception
        if (!logText.contains(" E/") && !logText.contains("FATAL") && !logText.contains("Exception")) {
            return null // Langsung abaikan log normal (Info, Debug, Verbose)
        }

        // 2. PEMBERSIH TEKS: Ambil pesan intinya saja untuk akurasi deteksi duplikat
        // Mengubah "E/ApplicationHelper(1977): Fail to get..." menjadi "Fail to get..."
        val cleanLog = if (logText.contains("):")) logText.substringAfter("):").trim() else logText.trim()

        // 3. SENSOR DUPLIKAT BERUNTUN: Jika pesan error sama persis dengan yang barusan dikirim, abaikan
        if (cleanLog == lastSentLog) {
            return null
        }

        // 4. SENSOR KECEPATAN (Rate Limiter): Jika log masuk terlalu rapat (< 500 milidetik), abaikan
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSentTime < MIN_INTERVAL_MS) {
            return null
        }

        // 🎉 Lolos semua sensor? Update data pelacak dan izinkan pengiriman ke Hugging Face
        lastSentLog = cleanLog
        lastSentTime = currentTime

        return try {
            val requestBody = gson.toJson(PredictRequest(log_text = logText))

            val request = HttpRequest.newBuilder()
                .uri(URI.create(flaskUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                gson.fromJson(response.body(), PredictResponse::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            println("❌ Error koneksi ke Flask: ${e.message}")
            null
        }
    }
}