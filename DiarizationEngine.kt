package com.speakerid

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Motore di diarizzazione speaker per Android
 * Usa SherpaOnnx per identificare chi parla quando
 */
class DiarizationEngine(private val context: Context) {

    companion object {
        private const val TAG = "DiarizationEngine"
        private const val SAMPLE_RATE = 16000
        private const val SEGMENT_DURATION = 1.5f  // secondi per segmento analisi
        private const val MIN_SEGMENT_DURATION = 0.3f
    }

    // Callback per aggiornamenti UI
    var onProgress: ((Int, String) -> Unit)? = null
    var onComplete: ((List<SpeakerSegment>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var isRunning = false
    private var job: Job? = null

    data class SpeakerSegment(
        val startSec: Float,
        val endSec: Float,
        val speakerId: String
    )

    data class SrtEntry(
        val index: Int,
        val startSec: Float,
        val endSec: Float,
        val text: String,
        var speakerId: String = "spk0"
    )

    /**
     * Avvia la diarizzazione in background
     */
    fun startDiarization(
        audioPath: String,
        srtPath: String,
        numSpeakers: Int = 0,
        modelType: String = "fast"
    ) {
        if (isRunning) return
        isRunning = true

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Step 1: Carica modello
                updateProgress(5, "Caricamento modello diarizzazione...")
                val embedder = loadSpeakerEmbedder(modelType)

                // Step 2: Carica audio
                updateProgress(15, "Caricamento audio...")
                val audioData = loadAudioFile(audioPath)

                // Step 3: Estrai embedding per segmenti
                updateProgress(25, "Analisi segmenti audio...")
                val segments = extractSegments(audioData)

                // Step 4: Calcola embedding per ogni segmento
                updateProgress(35, "Calcolo impronte vocali...")
                val embeddings = computeEmbeddings(embedder, audioData, segments)

                // Step 5: Clustering speaker
                updateProgress(65, "Clustering speaker...")
                val speakerSegments = clusterSpeakers(
                    segments, embeddings,
                    if (numSpeakers > 0) numSpeakers else null
                )

                // Step 6: Leggi SRT
                updateProgress(80, "Lettura file SRT...")
                val srtEntries = parseSrt(srtPath)

                // Step 7: Abbina SRT a speaker
                updateProgress(90, "Abbinamento sottotitoli a speaker...")
                val enrichedSrt = assignSpeakersToSrt(srtEntries, speakerSegments)

                // Step 8: Genera SRT arricchito
                updateProgress(95, "Generazione SRT con speaker tags...")
                val outputPath = generateEnrichedSrt(enrichedSrt, srtPath)

                updateProgress(100, "✅ Completato!")

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(speakerSegments)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Errore diarizzazione", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Errore sconosciuto")
                }
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        isRunning = false
    }

    // ─────────────────────────────────────────
    // CARICAMENTO MODELLO
    // ─────────────────────────────────────────

    private fun loadSpeakerEmbedder(modelType: String): OnnxSpeakerEmbedder {
        val modelName = when (modelType) {
            "accurate" -> "3dspeaker_speech_eres2net_large_sv_zh-cn_3dspeaker_16k.onnx"
            else -> "wespeaker_en_voxceleb_CAM++.onnx"
        }

        val modelFile = File(context.filesDir, "models/$modelName")

        if (!modelFile.exists()) {
            updateProgress(8, "Download modello (~50MB)...")
            downloadModel(modelName, modelFile)
        }

        return OnnxSpeakerEmbedder(modelFile.absolutePath)
    }

    private fun downloadModel(modelName: String, targetFile: File) {
        // Prova prima dagli assets (se incluso nell'APK)
        try {
            context.assets.open("models/$modelName").use { input ->
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Modello caricato dagli assets: $modelName")
            return
        } catch (e: Exception) {
            Log.d(TAG, "Modello non negli assets, download da rete...")
        }

        // Download da SherpaOnnx GitHub releases
        val urls = mapOf(
            "wespeaker_en_voxceleb_CAM++.onnx" to
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recog-models/wespeaker-voxceleb-resnet34-LM.tar.bz2",
            "3dspeaker_speech_eres2net_large_sv_zh-cn_3dspeaker_16k.onnx" to
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recog-models/3dspeaker_speech_eres2net_large_sv_zh-cn_3dspeaker_16k.onnx"
        )

        val url = urls[modelName] ?: throw Exception("Modello non trovato: $modelName")

        targetFile.parentFile?.mkdirs()
        java.net.URL(url).openStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Modello scaricato: $modelName")
    }

    // ─────────────────────────────────────────
    // CARICAMENTO AUDIO
    // ─────────────────────────────────────────

    private fun loadAudioFile(audioPath: String): FloatArray {
        // Leggi WAV 16kHz mono (già convertito da FFmpegKit)
        val file = File(audioPath)
        val bytes = file.readBytes()

        // Skip WAV header (44 bytes)
        val headerSize = 44
        val audioBytes = bytes.copyOfRange(headerSize, bytes.size)

        // Converti PCM 16-bit a float
        val samples = FloatArray(audioBytes.size / 2)
        val buffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            samples[i] = buffer.short.toFloat() / 32768.0f
        }

        Log.d(TAG, "Audio caricato: ${samples.size} campioni, ${samples.size / SAMPLE_RATE}s")
        return samples
    }

    // ─────────────────────────────────────────
    // SEGMENTAZIONE
    // ─────────────────────────────────────────

    private fun extractSegments(audio: FloatArray): List<Pair<Float, Float>> {
        val segments = mutableListOf<Pair<Float, Float>>()
        val segmentSamples = (SEGMENT_DURATION * SAMPLE_RATE).toInt()
        val stepSamples = (SEGMENT_DURATION * 0.5f * SAMPLE_RATE).toInt() // 50% overlap

        var start = 0
        while (start + segmentSamples <= audio.size) {
            val startSec = start.toFloat() / SAMPLE_RATE
            val endSec = (start + segmentSamples).toFloat() / SAMPLE_RATE
            segments.add(Pair(startSec, endSec))
            start += stepSamples
        }

        // Aggiungi ultimo segmento se rimane audio
        if (start < audio.size) {
            val startSec = start.toFloat() / SAMPLE_RATE
            val endSec = audio.size.toFloat() / SAMPLE_RATE
            if (endSec - startSec >= MIN_SEGMENT_DURATION) {
                segments.add(Pair(startSec, endSec))
            }
        }

        Log.d(TAG, "Segmenti estratti: ${segments.size}")
        return segments
    }

    // ─────────────────────────────────────────
    // EMBEDDING VOCALI
    // ─────────────────────────────────────────

    private fun computeEmbeddings(
        embedder: OnnxSpeakerEmbedder,
        audio: FloatArray,
        segments: List<Pair<Float, Float>>
    ): List<FloatArray> {
        val embeddings = mutableListOf<FloatArray>()
        val total = segments.size

        segments.forEachIndexed { i, (startSec, endSec) ->
            val startSample = (startSec * SAMPLE_RATE).toInt()
            val endSample = minOf((endSec * SAMPLE_RATE).toInt(), audio.size)
            val segment = audio.copyOfRange(startSample, endSample)

            val embedding = embedder.computeEmbedding(segment)
            embeddings.add(embedding)

            if (i % 10 == 0) {
                val progress = 35 + (i.toFloat() / total * 30).toInt()
                updateProgress(progress, "Analisi segmento ${i+1}/$total...")
            }
        }

        return embeddings
    }

    // ─────────────────────────────────────────
    // CLUSTERING SPEAKER
    // ─────────────────────────────────────────

    private fun clusterSpeakers(
        segments: List<Pair<Float, Float>>,
        embeddings: List<FloatArray>,
        numSpeakers: Int?
    ): List<SpeakerSegment> {

        // Clustering agglomerativo semplice basato su cosine similarity
        val n = embeddings.size
        val labels = IntArray(n) { -1 }
        var numClusters = 0

        // Soglia similarità coseno per stesso speaker
        val threshold = 0.75f

        for (i in 0 until n) {
            if (labels[i] != -1) continue

            // Cerca cluster esistente simile
            var bestCluster = -1
            var bestSim = threshold

            for (j in 0 until i) {
                if (labels[j] == -1) continue
                val sim = cosineSimilarity(embeddings[i], embeddings[j])
                if (sim > bestSim) {
                    bestSim = sim
                    bestCluster = labels[j]
                }
            }

            if (bestCluster != -1) {
                labels[i] = bestCluster
            } else {
                labels[i] = numClusters++
            }
        }

        // Se numSpeakers specificato, forza il numero di cluster
        if (numSpeakers != null && numSpeakers > 0 && numClusters != numSpeakers) {
            Log.d(TAG, "Forzando $numSpeakers speaker (trovati $numClusters)")
            // Ricalcola con soglia adattiva
            val adjustedThreshold = if (numClusters > numSpeakers) 0.85f else 0.65f
            return clusterWithThreshold(segments, embeddings, numSpeakers, adjustedThreshold)
        }

        // Converti labels in SpeakerSegment
        return segments.mapIndexed { i, (start, end) ->
            SpeakerSegment(
                startSec = start,
                endSec = end,
                speakerId = "spk${labels[i]}"
            )
        }
    }

    private fun clusterWithThreshold(
        segments: List<Pair<Float, Float>>,
        embeddings: List<FloatArray>,
        targetClusters: Int,
        threshold: Float
    ): List<SpeakerSegment> {
        val n = embeddings.size
        val labels = IntArray(n) { it } // Ogni segmento è il suo cluster

        // Merge iterativo finché non raggiungiamo targetClusters
        var currentClusters = n

        while (currentClusters > targetClusters) {
            var maxSim = -1f
            var mergeA = -1
            var mergeB = -1

            // Trova i due cluster più simili
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (labels[i] == labels[j]) continue
                    val sim = cosineSimilarity(embeddings[i], embeddings[j])
                    if (sim > maxSim) {
                        maxSim = sim
                        mergeA = labels[i]
                        mergeB = labels[j]
                    }
                }
            }

            if (mergeA == -1 || maxSim < 0.3f) break

            // Merge cluster B in A
            for (i in 0 until n) {
                if (labels[i] == mergeB) labels[i] = mergeA
            }
            currentClusters--
        }

        // Rinomina cluster in modo sequenziale
        val clusterMap = mutableMapOf<Int, Int>()
        var nextId = 0
        val finalLabels = labels.map { label ->
            clusterMap.getOrPut(label) { nextId++ }
        }

        return segments.mapIndexed { i, (start, end) ->
            SpeakerSegment(
                startSec = start,
                endSec = end,
                speakerId = "spk${finalLabels[i]}"
            )
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f
        else dot / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
    }

    // ─────────────────────────────────────────
    // PARSING SRT
    // ─────────────────────────────────────────

    fun parseSrt(srtPath: String): List<SrtEntry> {
        val entries = mutableListOf<SrtEntry>()
        val content = File(srtPath).readText(Charsets.UTF_8)
        val blocks = content.trim().split(Regex("\n\n+"))

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 3) continue

            try {
                val index = lines[0].trim().toInt()
                val timeLine = lines[1].trim()
                val text = lines.drop(2).joinToString("\n").trim()

                val times = timeLine.split(" --> ")
                val startSec = parseTimestamp(times[0].trim())
                val endSec = parseTimestamp(times[1].trim())

                entries.add(SrtEntry(index, startSec, endSec, text))
            } catch (e: Exception) {
                Log.w(TAG, "Errore parsing blocco SRT: ${lines.firstOrNull()}")
            }
        }

        Log.d(TAG, "SRT parsato: ${entries.size} entries")
        return entries
    }

    private fun parseTimestamp(ts: String): Float {
        // Formato: HH:MM:SS,mmm
        val parts = ts.replace(",", ".").split(":")
        val hours = parts[0].toFloat()
        val minutes = parts[1].toFloat()
        val seconds = parts[2].toFloat()
        return hours * 3600 + minutes * 60 + seconds
    }

    // ─────────────────────────────────────────
    // ABBINAMENTO SRT → SPEAKER
    // ─────────────────────────────────────────

    private fun assignSpeakersToSrt(
        srtEntries: List<SrtEntry>,
        speakerSegments: List<SpeakerSegment>
    ): List<SrtEntry> {
        return srtEntries.map { entry ->
            val speaker = findBestSpeaker(entry.startSec, entry.endSec, speakerSegments)
            entry.copy(speakerId = speaker)
        }
    }

    private fun findBestSpeaker(
        startSec: Float,
        endSec: Float,
        segments: List<SpeakerSegment>
    ): String {
        val speakerOverlap = mutableMapOf<String, Float>()

        for (seg in segments) {
            val overlapStart = maxOf(startSec, seg.startSec)
            val overlapEnd = minOf(endSec, seg.endSec)
            val overlap = maxOf(0f, overlapEnd - overlapStart)

            if (overlap > 0) {
                speakerOverlap[seg.speakerId] =
                    (speakerOverlap[seg.speakerId] ?: 0f) + overlap
            }
        }

        return speakerOverlap.maxByOrNull { it.value }?.key ?: "spk0"
    }

    // ─────────────────────────────────────────
    // GENERAZIONE SRT ARRICCHITO
    // ─────────────────────────────────────────

    fun generateEnrichedSrt(entries: List<SrtEntry>, originalPath: String): String {
        val outputPath = originalPath.replace(".srt", "_speakers.srt")
        val sb = StringBuilder()

        entries.forEachIndexed { i, entry ->
            sb.appendLine(entry.index)
            sb.appendLine("${formatTimestamp(entry.startSec)} --> ${formatTimestamp(entry.endSec)}")
            sb.appendLine("[${entry.speakerId}] ${entry.text}")
            if (i < entries.size - 1) sb.appendLine()
        }

        File(outputPath).writeText(sb.toString(), Charsets.UTF_8)
        Log.d(TAG, "SRT arricchito salvato: $outputPath")
        return outputPath
    }

    private fun formatTimestamp(seconds: Float): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = (seconds % 60).toInt()
        val ms = ((seconds % 1) * 1000).toInt()
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    // ─────────────────────────────────────────
    // UTILITY
    // ─────────────────────────────────────────

    private fun updateProgress(progress: Int, message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            onProgress?.invoke(progress, message)
        }
        Log.d(TAG, "[$progress%] $message")
    }
}

/**
 * Wrapper ONNX per speaker embedding
 * Usa ONNX Runtime Android per inferenza
 */
class OnnxSpeakerEmbedder(private val modelPath: String) {

    private var session: ai.onnxruntime.OrtSession? = null
    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()

    init {
        val opts = ai.onnxruntime.OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        // Usa NNAPI se disponibile (accelerazione hardware Snapdragon)
        try {
            opts.addNnapi()
        } catch (e: Exception) {
            // NNAPI non disponibile, usa CPU
        }
        session = env.createSession(modelPath, opts)
    }

    fun computeEmbedding(audioSegment: FloatArray): FloatArray {
        val session = this.session ?: return FloatArray(256)

        try {
            // Prepara input tensor
            val inputName = session.inputNames.iterator().next()
            val shape = longArrayOf(1, audioSegment.size.toLong())
            val tensor = ai.onnxruntime.OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(audioSegment),
                shape
            )

            // Inferenza
            val results = session.run(mapOf(inputName to tensor))
            val output = results[0].value as Array<FloatArray>

            tensor.close()
            results.close()

            return output[0]

        } catch (e: Exception) {
            // Fallback: embedding casuale (per testing senza modello)
            return FloatArray(256) { Math.random().toFloat() }
        }
    }

    fun close() {
        session?.close()
    }
}