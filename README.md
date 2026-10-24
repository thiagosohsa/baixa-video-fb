# Baixar Vídeo FB

App Android (Kotlin + Jetpack Compose) que baixa vídeos públicos do Facebook direto na pasta **Downloads**.

## Como usar

1. No Facebook, toque em **Compartilhar** no vídeo e escolha **Baixar Vídeo FB**
   (ou copie o link e toque em **COLAR LINK** no app).
2. O app identifica o vídeo sozinho e mostra título e capa.
3. Toque em **BAIXAR VÍDEO**. O arquivo `.mp4` vai para `Downloads/`.

Sem perguntas e sem pedidos de permissão. A qualidade é sempre **720p**, ou a maior
disponível abaixo disso quando o vídeo não tem 720p. Precisa de internet (Wi-Fi ou dados móveis).

## Como funciona

- Extração e download: [yt-dlp](https://github.com/yt-dlp/yt-dlp) embarcado via
  [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), com ffmpeg para
  juntar áudio e vídeo. Seleção de formato: `-S res:720,ext:mp4:m4a`.
- O yt-dlp se atualiza sozinho em segundo plano (no máximo uma vez por dia), porque
  o Facebook muda o site com frequência.
- O arquivo é baixado no cache do app e copiado para Downloads via `MediaStore`
  (Android 10+), o que dispensa permissão de armazenamento.

## Compilar

```sh
export JAVA_HOME=/opt/android-studio/jbr
./gradlew assembleRelease
```

Gera um APK por arquitetura em `app/build/outputs/apk/release/`:

| APK | Para |
| --- | --- |
| `app-arm64-v8a-release.apk` | quase todos os celulares atuais (use este) |
| `app-armeabi-v7a-release.apk` | celulares antigos de 32 bits |
| `app-x86_64-release.apk` | emulador |

O release é assinado com a chave de debug, então dá para instalar direto
(`adb install app/build/outputs/apk/release/app-arm64-v8a-release.apk`).

Requisitos: Android 10 ou superior.
