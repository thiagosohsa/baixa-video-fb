package dev.thiago.baixavideo

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Blue = Color(0xFF1877F2)
private val Green = Color(0xFF1B8A3A)
private val Red = Color(0xFFC62828)
private val Ink = Color(0xFF111111)

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
        )
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Blue,
                    background = Color.White,
                    surface = Color.White,
                    onBackground = Ink,
                    onSurface = Ink,
                ),
            ) {
                Screen(vm, onPaste = ::pasteFromClipboard)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        ).joinToString(" ")
        if (text.isNotBlank()) vm.receive(text)
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        vm.receive(text.orEmpty())
    }
}

@Composable
private fun Screen(vm: MainViewModel, onPaste: () -> Unit) {
    val link by vm.link.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Baixar vídeo do Facebook",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.Center,
            lineHeight = 36.sp,
        )

        if (!online) {
            Message("Sem internet. Conecte ao Wi-Fi ou aos dados móveis.", Red)
        }

        OutlinedTextField(
            value = link,
            onValueChange = vm::onLinkTyped,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, color = Ink),
            placeholder = { Text("Cole o link do vídeo aqui", fontSize = 20.sp) },
            singleLine = true,
            enabled = state !is UiState.Downloading,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                disabledContainerColor = Color.White,
            ),
        )

        when (val s = state) {
            UiState.Empty -> {
                BigButton("COLAR LINK", Blue, enabled = online, onClick = onPaste)
                Hint("No Facebook, toque em Compartilhar e escolha este app.")
            }

            UiState.Identifying -> {
                CircularProgressIndicator(modifier = Modifier.height(64.dp), color = Blue, strokeWidth = 6.dp)
                Message("Identificando o vídeo…", Ink)
            }

            is UiState.Ready -> {
                VideoCard(s.meta, s.thumb)
                BigButton("BAIXAR VÍDEO", Green, enabled = online, onClick = vm::download)
            }

            is UiState.Downloading -> {
                VideoCard(s.meta, s.thumb)
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    color = Green,
                    trackColor = Color(0xFFE6E6E6),
                )
                Message("Baixando… ${(s.progress * 100).toInt()}%", Ink)
            }

            is UiState.Done -> {
                Message("Pronto! Vídeo salvo em Downloads.", Green)
                Hint(s.fileName)
                BigButton("BAIXAR OUTRO", Blue, onClick = vm::clear)
            }

            is UiState.Error -> {
                Message(s.message, Red)
                BigButton("TENTAR DE NOVO", Blue, enabled = online, onClick = vm::retry)
                TextButton(onClick = vm::clear) { Text("Limpar", fontSize = 20.sp, color = Blue) }
            }
        }
    }
}

@Composable
private fun VideoCard(meta: VideoMeta, thumb: android.graphics.Bitmap?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        Text(
            meta.title,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (meta.height > 0) Hint("Qualidade: ${meta.height}p")
    }
}

@Composable
private fun BigButton(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = color, textAlign = TextAlign.Center)
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 18.sp, color = Color(0xFF555555), textAlign = TextAlign.Center)
}
