package id.ac.umj.logcatdetector

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IShellOutputReceiver
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class MyToolWindowFactory : ToolWindowFactory {

    @Volatile
    private var isMonitoring = false
    private var monitoringThread: Thread? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        val analyzer = LogcatAnalyzer()

        val statusArea = JTextArea("Status: Siap.\n").apply {
            isEditable = false
            rows = 3
            font = Font("Monospaced", Font.BOLD, 12)
        }

        val resultArea = JTextArea("=== LOGCAT AI DETECTOR UMJ READY ===\n").apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font("Monospaced", Font.PLAIN, 12)
        }

        val startButton = JButton("MULAI MONITORING")
        val stopButton = JButton("STOP MONITORING").apply { isEnabled = false }
        val clearButton = JButton("HAPUS HASIL")

        startButton.addActionListener {
            if (isMonitoring) return@addActionListener
            isMonitoring = true
            startButton.isEnabled = false
            stopButton.isEnabled = true
            resultArea.append("\n[SISTEM] Menghubungkan ke ADB & Server Flask...\n")

            try {
                BrowserUtil.browse("https://faizenith0145-logcat-detector.hf.space/dashboard")
                resultArea.append("[SISTEM] Membuka Web Dashboard di Browser...\n")
            } catch (e: Exception) {
                resultArea.append("[PERINGATAN] Gagal membuka browser otomatis: ${e.message}\n")
            }

            monitoringThread = Thread {
                try {
                    if (AndroidDebugBridge.getBridge() == null) AndroidDebugBridge.initIfNeeded(false)
                    val bridge = AndroidDebugBridge.getBridge() ?: AndroidDebugBridge.createBridge()

                    var attempts = 0
                    while (!bridge.hasInitialDeviceList() && attempts < 50) {
                        if (!isMonitoring) return@Thread
                        Thread.sleep(200)
                        attempts++
                    }

                    val devices = bridge.devices
                    if (devices.isEmpty()) throw Exception("Device tidak ditemukan. Pastikan USB Debugging aktif.")
                    val device = devices[0]

                    SwingUtilities.invokeLater {
                        statusArea.text = "Status: ✅ MONITORING AI AKTIF\nDevice: ${device.name}\n"
                    }

                    // Bersihkan log lama
                    device.executeShellCommand("logcat -c", NullReceiver())

                    // Ambil log baru secara real-time (Hanya level Error ke atas)
                    device.executeShellCommand("logcat *:E -v brief", object : IShellOutputReceiver {
                        private val buffer = StringBuilder()
                        private val logHistory = mutableListOf<String>()

                        override fun addOutput(data: ByteArray?, offset: Int, length: Int) {
                            if (!isMonitoring) return

                            val text = String(data!!, offset, length, StandardCharsets.UTF_8)
                            buffer.append(text)
                            val lines = buffer.toString().split("\n")

                            // PERBAIKAN UTAMA: Amankan sisa potongan baris terakhir yang belum lengkap sebelum masuk loop
                            buffer.setLength(0)
                            if (lines.isNotEmpty()) {
                                buffer.append(lines.last())
                            }

                            // Proses baris-baris log yang sudah dipastikan utuh sempurna
                            for (i in 0 until lines.size - 1) {
                                val currentLine = lines[i].trim()

                                if (currentLine.isNotEmpty()) {
                                    logHistory.add(currentLine)
                                    if (logHistory.size > 20) logHistory.removeAt(0)
                                }

                                val isTriggered = currentLine.contains("Exception", ignoreCase = true) ||
                                        currentLine.contains("FATAL", ignoreCase = true) ||
                                        currentLine.contains("Error", ignoreCase = true) ||
                                        currentLine.contains("ANR", ignoreCase = false) ||
                                        currentLine.contains("timeout", ignoreCase = true) ||
                                        currentLine.contains("failed", ignoreCase = true) ||
                                        currentLine.contains("leak", ignoreCase = true) ||
                                        currentLine.contains("denied", ignoreCase = true)

                                if (isTriggered) {
                                    val contextText = logHistory.takeLast(10).joinToString("\n")
                                    val aiResult = analyzer.analyzeWithAI(contextText)

                                    if (aiResult != null && aiResult.status == "success") {
                                        SwingUtilities.invokeLater {
                                            resultArea.append("\n==================================\n")
                                            resultArea.append("🚨 AI DETECTED: ${aiResult.category.uppercase()}\n")
                                            resultArea.append("----------------------------------\n")
                                            resultArea.append("STACKTRACE KONTEKS:\n")
                                            resultArea.append(currentLine)
                                            resultArea.append("\n----------------------------------\n")
                                            resultArea.append("REKOMENDASI AI:\n")
                                            resultArea.append(aiResult.recommendation)
                                            resultArea.append("\n==================================\n")
                                            resultArea.caretPosition = resultArea.document.length

                                            NotificationGroupManager.getInstance()
                                                .getNotificationGroup("LogcatDetector Alerts")
                                                .createNotification("Bug Terdeteksi!", "Kategori AI: ${aiResult.category}", NotificationType.ERROR)
                                                .notify(project)
                                        }
                                    }
                                }
                            }
                        }

                        override fun flush() {}

                        // Sakelar otomatis untuk memutus streaming data ADB logcat saat monitoring dihentikan
                        override fun isCancelled(): Boolean = !isMonitoring
                    }, 0)

                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        statusArea.text = "Status: Error\n${e.message}"
                    }
                } finally {
                    // Blok pembersihan otomatis jika terjadi error koneksi perangkat di awal
                    if (!isMonitoring || monitoringThread?.isInterrupted == true) {
                        SwingUtilities.invokeLater {
                            startButton.isEnabled = true
                            stopButton.isEnabled = false
                        }
                    }
                }
            }.also { it.start() }
        }

        stopButton.addActionListener {
            isMonitoring = false
            monitoringThread?.interrupt()
            monitoringThread = null
            startButton.isEnabled = true
            stopButton.isEnabled = false
            statusArea.text = "Status: 🛑 Monitoring Berhenti.\n"
            resultArea.append("[SISTEM] Monitoring dimatikan.\n")
        }

        clearButton.addActionListener {
            resultArea.text = "=== LOGCAT AI DETECTOR UMJ READY ===\n"
        }

        // Susun tata letak UI sesuai blueprint tombol awal Anda
        val buttonPanel = JPanel(BorderLayout(5, 5))
        buttonPanel.add(startButton, BorderLayout.WEST)
        buttonPanel.add(stopButton, BorderLayout.CENTER)
        buttonPanel.add(clearButton, BorderLayout.EAST)

        panel.add(JBScrollPane(statusArea), BorderLayout.NORTH)
        panel.add(JBScrollPane(resultArea), BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private class NullReceiver : IShellOutputReceiver {
        override fun addOutput(data: ByteArray?, offset: Int, length: Int) {}
        override fun flush() {}
        override fun isCancelled(): Boolean = false
    }
}
