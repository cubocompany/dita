# Dita

Ditado por voz para Android no estilo do Wispr Flow, usando o seu próprio servidor Whisper.
O áudio vai do celular direto para um endpoint compatível com a API da OpenAI
(`POST /v1/audio/transcriptions`), normalmente dentro do Tailscale.

- **Bolha flutuante** (serviço de acessibilidade): aparece junto do teclado em qualquer app.
  Toque para ditar e toque de novo para concluir, ou segure, fale e solte.
- **App**: contador de palavras, histórico local e botão Ditar.
- Kotlin nativo, sem bibliotecas. APK com cerca de 60 KB.

## Compilar e instalar

Requer JDK 17 e Android SDK (platform 36, build-tools 35).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Sem `keystore.properties`, o build de release é assinado com a chave de debug.
Para assinar com chave própria, crie `keystore.properties` na raiz:

```
storeFile=dita-release.jks
storePassword=...
keyAlias=dita
keyPassword=...
```

## Servidor

Qualquer servidor que aceite WAV em `POST /v1/audio/transcriptions`: whisper.cpp
(`whisper-server --inference-path /v1/audio/transcriptions`), Speaches, LocalAI, OpenAI, Groq.
Nos ajustes do app, informe só a base (`http://workstation:8178`); o caminho é completado.
