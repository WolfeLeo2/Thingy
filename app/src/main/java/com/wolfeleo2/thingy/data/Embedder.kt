package com.wolfeleo2.thingy.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tflite.java.TfLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.InterpreterApi
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel

/**
 * On-device text embeddings for semantic search. Runs the all-MiniLM-L6-v2 sentence-transformer
 * (a `.tflite`) through LiteRT — the model is downloaded once on first enable (kept out of the APK
 * to keep it small). Indexing and querying both run offline: no network, no key.
 *
 * MiniLM is a raw model (no MediaPipe metadata), so we do the tokenization ourselves: a WordPiece
 * tokenizer over the bundled BERT-uncased vocab, then mean-pool the token embeddings and L2-normalize
 * (the standard sentence-transformers recipe). Vectors are unit length, so cosine == dot product.
 *
 * Dimension-agnostic: we store whatever vector length the model emits and cosine over it.
 */
class Embedder(private val context: Context) {

    private val modelFile: File
        get() = File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_NAME)

    fun isReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    // InterpreterApi isn't thread-safe and TFLite init is async — this mutex guards both the one-time
    // setup and every run (classification calls embed concurrently).
    private val lock = Mutex()
    @Volatile private var interp: InterpreterApi? = null

    /** Must be called while holding [lock]. Initializes the Play Services runtime + interpreter once. */
    private suspend fun ensureInterpreter(): InterpreterApi {
        interp?.let { return it }
        TfLite.initialize(context).await() // provisions the TFLite runtime from Play Services
        val buf = FileInputStream(modelFile).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length()) }
        val options = InterpreterApi.Options().setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
        return InterpreterApi.create(buf, options).also { interp = it }
    }

    // ---- WordPiece tokenizer (BERT uncased) over the bundled vocab ----

    private val vocab: Map<String, Int> by lazy {
        HashMap<String, Int>(30600).also { m ->
            context.assets.open(VOCAB_ASSET).bufferedReader().useLines { lines ->
                lines.forEachIndexed { i, line -> m[line.trimEnd()] = i }
            }
        }
    }
    private val clsId get() = vocab["[CLS]"] ?: 101
    private val sepId get() = vocab["[SEP]"] ?: 102
    private val unkId get() = vocab["[UNK]"] ?: 100

    /** Split on whitespace + separate punctuation (BERT basic tokenizer), lowercased. */
    private fun basicSplit(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text.lowercase()) {
            when {
                c.isWhitespace() -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                !c.isLetterOrDigit() -> { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }; out.add(c.toString()) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Greedy longest-match WordPiece → token ids (no special tokens). */
    private fun tokenize(text: String): List<Int> {
        val ids = ArrayList<Int>()
        for (word in basicSplit(text)) {
            if (word.length > 100) { ids.add(unkId); continue }
            var start = 0
            val sub = ArrayList<Int>()
            var bad = false
            while (start < word.length) {
                var end = word.length
                var cur: Int? = null
                while (start < end) {
                    val piece = if (start > 0) "##" + word.substring(start, end) else word.substring(start, end)
                    val id = vocab[piece]
                    if (id != null) { cur = id; break }
                    end--
                }
                if (cur == null) { bad = true; break }
                sub.add(cur); start = end
            }
            if (bad) ids.add(unkId) else ids.addAll(sub)
        }
        return ids
    }

    // ---- Download / embed / rank ----

    /**
     * Download the model once. [onProgress] reports (downloadedBytes, totalBytes) as it streams
     * (totalBytes is 0 if the server didn't send a length). Returns true if present afterwards.
     */
    suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext true
        runCatching {
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 30_000; readTimeout = 60_000; connect()
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            val tmp = File(modelFile.parentFile, "$MODEL_NAME.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            conn.disconnect()
            tmp.renameTo(modelFile)
        }.onFailure { Log.w("Thingy", "embedder model download failed", it) }
        isReady()
    }

    /** Embed text → L2-normalized vector, or null if the model isn't ready / text is blank. */
    suspend fun embed(text: String): List<Double>? = withContext(Dispatchers.Default) {
        if (!isReady() || text.isBlank()) return@withContext null
        runCatching {
            val ids = (listOf(clsId) + tokenize(text).take(MAX_LEN - 2) + listOf(sepId))
            val seq = ids.size
            lock.withLock {
                val itp = ensureInterpreter()
                itp.resizeInput(0, intArrayOf(1, seq))
                itp.resizeInput(1, intArrayOf(1, seq))
                itp.allocateTensors()
                val in0 = buildInput(itp.getInputTensor(0).dataType(), ids)                 // input_ids
                val in1 = buildInput(itp.getInputTensor(1).dataType(), List(seq) { 1 })      // attention_mask
                val outShape = itp.getOutputTensor(0).shape()
                val hidden = outShape.last()
                if (outShape.size == 3) {
                    // token embeddings [1, seq, hidden] → mean-pool (mask is all 1s, no padding)
                    val out = Array(1) { Array(outShape[1]) { FloatArray(hidden) } }
                    itp.runForMultipleInputsOutputs(arrayOf(in0, in1), mapOf(0 to out))
                    val pooled = DoubleArray(hidden)
                    for (t in out[0].indices) for (k in 0 until hidden) pooled[k] += out[0][t][k]
                    for (k in 0 until hidden) pooled[k] /= out[0].size
                    normalize(pooled)
                } else {
                    // already a pooled sentence embedding [1, hidden]
                    val out = Array(1) { FloatArray(hidden) }
                    itp.runForMultipleInputsOutputs(arrayOf(in0, in1), mapOf(0 to out))
                    normalize(DoubleArray(hidden) { out[0][it].toDouble() })
                }
            }
        }.onFailure { Log.w("Thingy", "embed failed", it) }.getOrNull()
    }

    /** Feeds token ids to the interpreter in whatever integer type the model declares. */
    private fun buildInput(dtype: DataType, values: List<Int>): Any = when (dtype) {
        DataType.INT64 -> Array(1) { LongArray(values.size) { values[it].toLong() } }
        else -> Array(1) { IntArray(values.size) { values[it] } } // INT32
    }

    private fun normalize(v: DoubleArray): List<Double> {
        var norm = 0.0
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        return if (norm > 0) v.map { it / norm } else v.toList()
    }

    /**
     * Embed every ready item that isn't indexed at the CURRENT model's dimension. Idempotent, and
     * self-healing across a model swap — items embedded by a previous model (different vector length)
     * are re-embedded rather than left as dead, unmatchable vectors.
     */
    suspend fun backfill(items: ItemRepository) = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext
        val dim = embed("thingy")?.size ?: return@withContext // probe the current model's dimension
        items.snapshotReadyItems(500)
            .filter { it.embedding?.size != dim }
            .forEach { item -> embed(item.embedText())?.let { runCatching { items.updateEmbedding(item.id, it) } } }
    }

    companion object {
        private const val MODEL_NAME = "minilm.tflite"
        private const val VOCAB_ASSET = "minilm_vocab.txt"
        private const val MAX_LEN = 128 // MiniLM's trained sequence length

        // all-MiniLM-L6-v2, quantized (~22 MB), sentence-tuned → good semantic ranking. Swap this URL
        // for a stronger LiteRT sentence model (same tokenizer family) without touching callers.
        const val MODEL_URL =
            "https://huggingface.co/Nihal2000/all-MiniLM-L6-v2-quant.tflite/resolve/main/all-MiniLM-L6-v2-quant.tflite"

        // ponytail: hand-tuned relevance knobs — MIN_SCORE is the absolute floor; RELATIVE_GAP keeps
        // only items close to the best hit. MiniLM cosines run low for short queries (device log:
        // matches ~0.2–0.3, noise <0.15), so the floor is low. Tune from the "semantic \"…\":" logcat line.
        const val MIN_SCORE = 0.2
        const val RELATIVE_GAP = 0.1

        /** Cosine similarity of two L2-normalized vectors (== dot product). -1 if incomparable. */
        fun cosine(a: List<Double>, b: List<Double>): Double {
            if (a.size != b.size || a.isEmpty()) return -1.0
            var dot = 0.0
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }
    }
}
