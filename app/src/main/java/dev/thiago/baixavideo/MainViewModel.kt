package dev.thiago.baixavideo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

sealed interface UiState {
    data object Empty : UiState
    data object Identifying : UiState
    data class Ready(val meta: VideoMeta, val thumb: Bitmap?) : UiState
    data class Downloading(val meta: VideoMeta, val thumb: Bitmap?, val progress: Float) : UiState
    data class Done(val fileName: String) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _link = MutableStateFlow("")
    val link: StateFlow<String> = _link.asStateFlow()

    private val _state = MutableStateFlow<UiState>(UiState.Empty)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        override fun onLost(network: Network) {
            _online.value = false
        }
    }

    private var job: Job? = null

    init {
        _online.value = connectivity.activeNetwork
            ?.let { connectivity.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        connectivity.registerDefaultNetworkCallback(networkCallback)
        // Extrai o Python/yt-dlp já na abertura para o primeiro link ser rápido.
        viewModelScope.launch { runCatching { Downloader.prepare(app) } }
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
    }

    /** Texto vindo do "Compartilhar", da área de transferência ou digitado. */
    fun receive(text: String) {
        if (_state.value is UiState.Downloading) return
        val url = extractUrl(text)
        _link.value = url ?: text.trim()
        if (url != null) identify(url) else if (text.isBlank()) reset()
    }

    fun onLinkTyped(text: String) {
        _link.value = text
        val url = extractUrl(text)
        if (url != null) identify(url) else {
            job?.cancel()
            _state.value = UiState.Empty
        }
    }

    fun retry() {
        extractUrl(_link.value)?.let(::identify)
    }

    fun clear() {
        _link.value = ""
        reset()
    }

    private fun reset() {
        job?.cancel()
        _state.value = UiState.Empty
    }

    private fun identify(url: String) {
        if (_state.value is UiState.Downloading) return
        job?.cancel()
        _state.value = UiState.Identifying
        job = viewModelScope.launch {
            _state.value = try {
                val meta = Downloader.identify(getApplication(), url)
                UiState.Ready(meta, loadThumb(meta.thumbnailUrl))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                UiState.Error(identifyError(e))
            }
        }
    }

    fun download() {
        val ready = _state.value as? UiState.Ready ?: return
        val url = extractUrl(_link.value) ?: return
        job?.cancel()
        _state.value = UiState.Downloading(ready.meta, ready.thumb, 0f)
        job = viewModelScope.launch {
            _state.value = try {
                val name = Downloader.download(getApplication(), url, ready.meta.title) { p ->
                    _state.value = UiState.Downloading(ready.meta, ready.thumb, p)
                }
                UiState.Done(name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                UiState.Error(
                    if (!_online.value) "Sem internet. Conecte-se e tente de novo."
                    else "Falha ao baixar o vídeo. Tente de novo."
                )
            }
        }
    }

    private fun identifyError(e: Exception): String {
        val msg = e.message.orEmpty().lowercase()
        return when {
            !_online.value -> "Sem internet. Conecte-se e tente de novo."
            "login" in msg || "private" in msg || "privado" in msg ->
                "Este vídeo é privado ou exige login. Só é possível baixar vídeos públicos."
            "unsupported url" in msg -> "Este link não é de um vídeo do Facebook."
            else -> "Não foi possível identificar o vídeo. Confira o link e tente de novo."
        }
    }

    private suspend fun loadThumb(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url == null) return@withContext null
        runCatching {
            URL(url).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }.getInputStream().use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        private val FB_HOSTS = Regex("""(^|\.)(facebook\.com|fb\.watch|fb\.com|fb\.gg)$""")

        /** Pega o primeiro link do Facebook dentro de um texto qualquer. */
        fun extractUrl(text: String): String? = URL_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }
            .firstOrNull { link ->
                val host = runCatching { URL(link).host.lowercase() }.getOrNull() ?: return@firstOrNull false
                FB_HOSTS.containsMatchIn(host)
            }
    }
}
