package com.speakerid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_VIDEO = 1001
        private const val REQ_SRT   = 1002
    }

    // UI
    private lateinit var tvVideoPath: TextView
    private lateinit var tvSrtPath: TextView
    private lateinit var tvSpeakerCount: TextView
    private lateinit var btnSpeakerMinus: Button
    private lateinit var btnSpeakerPlus: Button
    private lateinit var rbModelFast: RadioButton
    private lateinit var rbModelAccurate: RadioButton
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var cardProgress: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvLog: TextView
    private lateinit var cardResult: LinearLayout
    private lateinit var tvResult: TextView
    private lateinit var tvSrtPreview: TextView
    private lateinit var btnExport: Button
    private lateinit var btnShare: Button

    // Stato
    private var videoUri: Uri? = null
    private var srtUri: Uri? = null
    private var videoPath: String? = null
    private var srtPath: String? = null
    private var outputSrtPath: String? = null
    private var numSpeakers = 0

    private lateinit var engine: DiarizationEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initEngine()
        setupListeners()
    }

    private fun initViews() {
        tvVideoPath      = findViewById(R.id.tvVideoPath)
        tvSrtPath        = findViewById(R.id.tvSrtPath)
        tvSpeakerCount   = findViewById(R.id.tvSpeakerCount)
        btnSpeakerMinus  = findViewById(R.id.btnSpeakerMinus)
        btnSpeakerPlus   = findViewById(R.id.btnSpeakerPlus)
        rbModelFast      = findViewById(R.id.rbModelFast)
        rbModelAccurate  = findViewById(R.id.rbModelAccurate)
        btnStart         = findViewById(R.id.btnStart)
        btnStop          = findViewById(R.id.btnStop)
        cardProgress     = findViewById(R.id.cardProgress)
        tvStatus         = findViewById(R.id.tvStatus)
        progressBar      = findViewById(R.id.progressBar)
        tvProgressPercent= findViewById(R.id.tvProgressPercent)
        tvLog            = findViewById(R.id.tvLog)
        cardResult       = findViewById(R.id.cardResult)
        tvResult         = findViewById(R.id.tvResult)
        tvSrtPreview     = findViewById(R.id.tvSrtPreview)
        btnExport        = findViewById(R.id.btnExport)
        btnShare         = findViewById(R.id.btnShare)
    }

    private fun initEngine() {
        engine = DiarizationEngine(this)

        engine.onProgress = { progress, message ->
            runOnUiThread {
                progressBar.progress = progress
                tvProgressPercent.text = "$progress%"
                tvStatus.text = message
                appendLog(message)
            }
        }

        engine.onComplete = { segments ->
            runOnUiThread {
                onDiarizationComplete(segments)
            }
        }

        engine.onError = { error ->
            runOnUiThread {
                onDiarizationError(error)
            }
        }
    }

    private fun setupListeners() {
        // Selezione video
        findViewById<Button>(R.id.btnSelectVideo).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, REQ_VIDEO)
        }

        // Selezione SRT
        findViewById<Button>(R.id.btnSelectSrt).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/x-subrip",
                    "text/plain",
                    "text/x-ssa",
                    "*/*"
                ))
            }
            startActivityForResult(intent, REQ_SRT)
        }

        // Controlli speaker count
        btnSpeakerMinus.setOnClickListener {
            if (numSpeakers > 0) {
                numSpeakers--
                tvSpeakerCount.text = numSpeakers.toString()
            }
        }

        btnSpeakerPlus.setOnClickListener {
            if (numSpeakers < 10) {
                numSpeakers++
                tvSpeakerCount.text = numSpeakers.toString()
            }
        }

        // Avvio
        btnStart.setOnClickListener { startProcessing() }

        // Stop
        btnStop.setOnClickListener {
            engine.stop()
            btnStop.visibility = View.GONE
            btnStart.isEnabled = true
            updateStatus("⏹ Elaborazione fermata")
        }

        // Export
        btnExport.setOnClickListener { exportSrt() }

        // Share
        btnShare.setOnClickListener { shareSrt() }
    }

    // ─────────────────────────────────────────
    // SELEZIONE FILE
    // ─────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        val uri = data?.data ?: return

        when (requestCode) {
            REQ_VIDEO -> {
                videoUri = uri
                val name = getFileName(uri)
                tvVideoPath.text = name
                tvVideoPath.setTextColor(getColor(android.R.color.white))
                // Copia in cache per accesso diretto
                CoroutineScope(Dispatchers.IO).launch {
                    videoPath = copyUriToCache(uri, name ?: "video.mp4")
                }
            }
            REQ_SRT -> {
                srtUri = uri
                val name = getFileName(uri)
                tvSrtPath.text = name
                tvSrtPath.setTextColor(getColor(android.R.color.white))
                CoroutineScope(Dispatchers.IO).launch {
                    srtPath = copyUriToCache(uri, name ?: "subtitles.srt")
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) {
                name = cursor.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun copyUriToCache(uri: Uri, fileName: String): String {
        val cacheFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile.absolutePath
    }

    // ─────────────────────────────────────────
    // ELABORAZIONE
    // ─────────────────────────────────────────

    private fun startProcessing() {
        val vPath = videoPath
        val sPath = srtPath

        if (vPath == null) {
            Toast.makeText(this, getString(R.string.err_no_video), Toast.LENGTH_SHORT).show()
            return
        }
        if (sPath == null) {
            Toast.makeText(this, getString(R.string.err_no_srt), Toast.LENGTH_SHORT).show()
            return
        }

        // Aggiorna UI
        btnStart.isEnabled = false
        btnStop.visibility = View.VISIBLE
        cardProgress.visibility = View.VISIBLE
        cardResult.visibility = View.GONE
        tvLog.text = ""

        val modelType = if (rbModelAccurate.isChecked) "accurate" else "fast"

        updateStatus("🎵 Estrazione audio dal video...")
        appendLog("Video: ${File(vPath).name}")
        appendLog("SRT: ${File(sPath).name}")
        appendLog("Speaker: ${if (numSpeakers == 0) "auto" else numSpeakers.toString()}")
        appendLog("Modello: $modelType")
        appendLog("─────────────────────")

        // Estrai audio con FFmpegKit
        extractAudioAndDiarize(vPath, sPath, modelType)
    }

    private fun extractAudioAndDiarize(
        videoPath: String,
        srtPath: String,
        modelType: String
    ) {
        val audioPath = File(cacheDir, "audio_mono.wav").absolutePath

        // Comando ffmpeg: estrai audio mono 16kHz
        val cmd = "-y -i \"$videoPath\" -vn -acodec pcm_s16le -ar 16000 -ac 1 \"$audioPath\""

        appendLog("Estrazione audio (ffmpeg)...")

        FFmpegKit.executeAsync(cmd) { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                appendLog("✅ Audio estratto: ${File(audioPath).length() / 1024}KB")
                updateStatus("🎙️ Avvio diarizzazione...")

                // Avvia diarizzazione
                engine.startDiarization(
                    audioPath = audioPath,
                    srtPath = srtPath,
                    numSpeakers = numSpeakers,
                    modelType = modelType
                )
            } else {
                runOnUiThread {
                    onDiarizationError("Errore estrazione audio: ${session.failStackTrace}")
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // RISULTATI
    // ─────────────────────────────────────────

    private fun onDiarizationComplete(segments: List<DiarizationEngine.SpeakerSegment>) {
        btnStart.isEnabled = true
        btnStop.visibility = View.GONE

        // Conta speaker unici
        val speakers = segments.map { it.speakerId }.toSet()
        val speakerCount = speakers.size

        // Trova SRT arricchito
        val sPath = srtPath ?: return
        outputSrtPath = sPath.replace(".srt", "_speakers.srt")

        // Mostra risultato
        cardResult.visibility = View.VISIBLE

        tvResult.text = buildString {
            appendLine("✅ Diarizzazione completata!")
            appendLine()
            appendLine("🎭 Speaker identificati: $speakerCount")
            speakers.sorted().forEach { spk ->
                val count = segments.count { it.speakerId == spk }
                appendLine("   $spk → $count segmenti")
            }
            appendLine()
            appendLine("📄 SRT arricchito salvato")
        }

        // Mostra anteprima SRT
        val outputFile = File(outputSrtPath!!)
        if (outputFile.exists()) {
            val preview = outputFile.readLines().take(20).joinToString("\n")
            tvSrtPreview.text = preview
        }

        appendLog("─────────────────────")
        appendLog("✅ COMPLETATO!")
        appendLog("Speaker trovati: $speakerCount → ${speakers.sorted()}")
        appendLog("Output: ${File(outputSrtPath!!).name}")
    }

    private fun onDiarizationError(error: String) {
        btnStart.isEnabled = true
        btnStop.visibility = View.GONE
        updateStatus("❌ Errore: $error")
        appendLog("ERRORE: $error")
        Toast.makeText(this, "Errore: $error", Toast.LENGTH_LONG).show()
    }

    // ─────────────────────────────────────────
    // EXPORT / SHARE
    // ─────────────────────────────────────────

    private fun exportSrt() {
        val outPath = outputSrtPath ?: return
        val outFile = File(outPath)
        if (!outFile.exists()) {
            Toast.makeText(this, "File non trovato", Toast.LENGTH_SHORT).show()
            return
        }

        // Salva in Downloads
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-subrip"
            putExtra(Intent.EXTRA_TITLE, outFile.name)
        }
        startActivityForResult(intent, 9999)

        Toast.makeText(this, "SRT salvato: ${outFile.name}", Toast.LENGTH_LONG).show()
    }

    private fun shareSrt() {
        val outPath = outputSrtPath ?: return
        val outFile = File(outPath)
        if (!outFile.exists()) {
            Toast.makeText(this, "File non trovato", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            outFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "SRT con speaker tags")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Condividi SRT"))
    }

    // ─────────────────────────────────────────
    // UI HELPERS
    // ─────────────────────────────────────────

    private fun updateStatus(message: String) {
        runOnUiThread { tvStatus.text = message }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val current = tvLog.text.toString()
            val newText = if (current.isEmpty()) message
                          else "$current\n$message"
            tvLog.text = newText
        }
    }
}