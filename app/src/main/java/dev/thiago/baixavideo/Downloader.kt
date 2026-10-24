package dev.thiago.baixavideo

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
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
    // Codec "no máximo H.264": evita AV1/VP9, que muitos players e o WhatsApp não abrem.
    private const val FORMAT_SORT = "res:720,vcodec:h264,ext:mp4:m4a"

    // H.264 até 720p via DASH, senão o MP4 progressivo "hd" do Facebook (H.264 + áudio).
    private const val H264_FORMAT = "bv*[vcodec^=avc][height<=720]+ba/b[vcodec^=avc][height<=720]/hd"

    // O Facebook prefixa o título com contadores: "1.9M views · 48K reactions | ...".
    private val COUNTERS_PREFIX = Regex(
        """^[^|]*\b(views|visualizações|reactions|reações)\b[^|]*\|\s*""",
        RegexOption.IGNORE_CASE,
    )
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
            title = info.title?.replace(COUNTERS_PREFIX, "")?.trim()?.takeIf { it.isNotBlank() }
                ?: "Vídeo do Facebook",
            thumbnailUrl = info.thumbnail,
            // "720p" = menor dimensão, assim vídeos verticais (Reels) também contam certo.
            height = listOf(info.width, info.height).filter { it > 0 }.minOrNull() ?: 0,
        )
    }

    /** Baixa para o cache do app e depois copia para a pasta pública Downloads. */
    suspend fun download(
        context: Context,
        url: String,
        meta: VideoMeta,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        prepare(context)
        val workDir = File(context.cacheDir, "download")
        try {
            // 1ª tentativa: H.264 (abre em qualquer lugar). O "hd" progressivo do Facebook não
            // informa a resolução, então ela é conferida depois de baixado.
            val h264 = runCatching { fetch(url, workDir, onProgress) { addOption("-f", H264_FORMAT) } }
                .getOrNull()
                ?.takeIf { meta.height <= 0 || shortSide(it) == meta.height }
            // 2ª: a melhor até 720p em qualquer codec (alguns vídeos só existem em AV1).
            val file = h264 ?: fetch(url, workDir, onProgress) { addOption("-S", FORMAT_SORT) }
            saveToDownloads(context, file, fileName(meta.title, file.extension))
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun fetch(
        url: String,
        workDir: File,
        onProgress: (Float) -> Unit,
        format: YoutubeDLRequest.() -> Unit,
    ): File {
        workDir.deleteRecursively()
        workDir.mkdirs()
        onProgress(0f)
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            format()
            addOption("--merge-output-format", "mp4")
            addOption("-o", File(workDir, "video.%(ext)s").absolutePath)
        }
        YoutubeDL.execute(request, null) { progress, _, _ ->
            if (progress >= 0f) onProgress(progress / 100f)
        }
        return workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.maxByOrNull { it.length() }
            ?: error("Arquivo baixado não encontrado")
    }

    /** Menor dimensão do vídeo (o "p" de 720p), lida do próprio arquivo. */
    private fun shortSide(file: File): Int = MediaMetadataRetriever().use { r ->
        r.setDataSource(file.absolutePath)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        minOf(w, h)
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
