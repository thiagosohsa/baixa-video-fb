package dev.thiago.baixavideo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class VideoMeta(
    val title: String,
    val thumbnailUrl: String?,
    val height: Int,
)

/**
 * Envolve o yt-dlp (via youtubedl-android). Tudo aqui bloqueia, então roda em Dispatchers.IO.
 */
object Downloader {

    // Até 720p; se o vídeo não tiver 720p, pega a maior resolução abaixo disso.
    private const val FORMAT_SORT = "res:720,ext:mp4:m4a"
    private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val initLock = Mutex()
    private var ready = false

    suspend fun prepare(context: Context) = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (ready) return@withLock
            val app = context.applicationContext
            YoutubeDL.init(app)
            FFmpeg.init(app)
            ready = true
            updateIfDue(app)
        }
    }

    /** O Facebook muda com frequência; mantém o extrator do yt-dlp atualizado sem incomodar o usuário. */
    private fun updateIfDue(context: Context) {
        val prefs = context.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_update", 0L)
        if (System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return
        runCatching { YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
            .onSuccess { prefs.edit().putLong("last_update", System.currentTimeMillis()).apply() }
    }

    suspend fun identify(context: Context, url: String): VideoMeta = withContext(Dispatchers.IO) {
        prepare(context)
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("-S", FORMAT_SORT)
        }
        val info = YoutubeDL.getInfo(request)
        VideoMeta(
            title = info.title?.takeIf { it.isNotBlank() } ?: "Vídeo do Facebook",
            thumbnailUrl = info.thumbnail,
            height = info.height,
        )
    }

    /** Baixa para o cache do app e depois copia para a pasta pública Downloads. */
    suspend fun download(
        context: Context,
        url: String,
        title: String,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        prepare(context)
        val workDir = File(context.cacheDir, "download").apply {
            deleteRecursively()
            mkdirs()
        }
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-S", FORMAT_SORT)
            addOption("--merge-output-format", "mp4")
            addOption("-o", File(workDir, "video.%(ext)s").absolutePath)
        }
        YoutubeDL.execute(request, null) { progress, _, _ ->
            if (progress >= 0f) onProgress(progress / 100f)
        }

        val file = workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.maxByOrNull { it.length() }
            ?: error("Arquivo baixado não encontrado")

        try {
            saveToDownloads(context, file, fileName(title, file.extension))
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun saveToDownloads(context: Context, file: File, name: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "video/${file.extension.ifBlank { "mp4" }}")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Não foi possível criar o arquivo em Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // O MediaStore pode renomear (ex.: "nome (1).mp4"); devolve o nome final.
        return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: name
    }

    private fun fileName(title: String, ext: String): String {
        val clean = title
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "video_facebook" }
        return "$clean.${ext.ifBlank { "mp4" }}"
    }
}
