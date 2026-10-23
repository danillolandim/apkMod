# Player Mod — debug do APK e player próprio

Resultado do debug do `P2P_BinStream_Exclusive.apk` e um projeto de player
para Android TV que **reproduz e melhora** o player daquele app, integrando
com o mesmo motor P2P pela API pública dele.

---

## 1. O que é o APK (debug)

| Item | Valor |
|------|-------|
| Pacote | `io.tvs.android.one` |
| Versão | 11.6.0 (versionCode 1160) |
| Launcher | `org.bitspark.android.Spark` (landscape, LEANBACK — Android TV) |
| Player | ExoPlayer / **Media3 1.6.1** |
| Decoders | FFmpeg SW embutido (`libffmpegJNI.so`) + `libijkcodec/ijkplayer` |
| Motor P2P | **TVBus / BitSpark** — `libtvcore.so` + `libgojni.so` (núcleo em Go) |
| Legenda | `SubtitleView` + `DefaultTrackSelector` (só faixas internas hoje) |

### Como o vídeo chega até o player (o achado principal)

O conteúdo **não é uma URL remota**. O motor P2P resolve o endereço e **sobe um
servidor HTTP local** e o ExoPlayer toca dele. Confirmado em dois pontos:

- `com.tvbus.engine.TVService` chama `setPlayPort(8902)`.
- Strings dentro de `libtvcore.so`:
  - `http://127.0.0.1:%d/%u/index.m3u8`
  - `http://127.0.0.1:%d/main.m3u8`
  - `http://127.0.0.1:%d/mpegts_live`

Ou seja, o pipeline real é:

```
endereço P2P  ──TVCore.start()──►  motor resolve (peers)  ──►  HLS local
                                                   http://127.0.0.1:8902/<id>/index.m3u8
                                                                   │
                                                          ExoPlayer toca ◄──┘
```

O callback `TVListener.onPrepared(json)` / `onStart(json)` devolve um JSON com
as URLs locais (chaves `hls`, `http`, `port`, `url`).

### Por que NÃO dá pra "modar o APK direto"

O APK está protegido com **`com.nmmedit.protect.NativeUtil`**. ~367 classes
(incluindo toda a lógica do player em `Spark.java` e `ne/o.java`) têm o corpo
dos métodos **removido do dex** e restaurado em runtime a partir de
`libijkplayer.so`:

```java
public final native void V0(String str);        // monta o MediaItem e dá prepare()
static { NativeUtil.classes2Init0(78); }         // injeta o bytecode real
```

Consequência: **patch via smali/baksmali não cola** — você editaria um stub
vazio que o `.so` sobrescreve (ou crasha na verificação). Reempacotar aquele
binário proprietário não é o caminho; construir o seu player no mesmo motor é.

---

## 2. O player próprio (este projeto)

App Android TV em Kotlin + Media3, que faz o que o original faz — tocar o HLS
local do motor P2P — com uma lista grande de melhorias:

**Qualidade de imagem**
- **Sem trava de SD** (o original forçava `setMaxVideoSizeSd()`) + nunca
  descarta a melhor rendição por restrição de tela/perf.
- **Começa em HD**, não em SD (`setInitialBitrateEstimate` alto).
- **ABR agressivo**: sobe de qualidade em 6s, resiste a cair 30s, usa 80% da banda.
- **Seleção manual de qualidade** (1080p/720p… ou travar na máxima).

**TV**
- **Frame-rate matching**: casa o Hz da tela com o fps do vídeo (tira judder de
  filme 24fps em TV 60Hz).
- **Tunneling** (melhor A/V sync, alivia CPU de box fraca).
- **Keep screen on**, wake lock de rede, pausa ao desconectar HDMI/fone.

**Áudio e legenda**
- **Legendas externas por URL** (`.srt`/`.vtt`/`.ass`) somadas às internas.
- **Estilo de legenda legível** (contorno) + tamanho ajustável.
- **Troca dublado ↔ original** por faixa de áudio.

**Confiabilidade**
- **Retry automático** com backoff (2s/4s/8s) e **troca para endereço de
  backup** (`address_backup` do painel) em falha.
- **Modos LIVE/VOD**: buffer de baixa latência p/ zapping vs buffer folgado p/
  filme; back-buffer p/ rewind instantâneo em live; start mais rápido.

**Extras**
- **"Continuar assistindo"** (salva/retoma posição por conteúdo).
- **HUD de diagnóstico** (botão INFO/azul): resolução, bitrate, codec, rede,
  buffer e frames perdidos ao vivo.

**Polimento de produto**
- **Indicador de qualidade** (3s) ao iniciar, ao mudar rendição/faixa e ao
  fazer seek: `1080p HEVC • 6.2 Mbps • 24fps`.
- **Picture-in-Picture** (botão verde, ou automático ao apertar HOME tocando).
- **Ciclo de aspecto** (vermelho): Ajustar / Preencher / Zoom / 16:9 / 4:3 —
  resolve canal 4:3 esticado.
- **Timer de desligar** (15/30/60/90 min), **velocidade** (VOD) e **tamanho
  de legenda** no menu.
- **Acessibilidade**: respeita o estilo/tamanho de legenda configurado no
  sistema (Acessibilidade → Legendas) e anuncia mudanças via TalkBack.

**Controle remoto**

| Tecla | Ação |
|---|---|
| OK | mostra os controles |
| MENU / amarelo / legendas | opções: qualidade, áudio, legenda, tamanho, velocidade, timer |
| INFO / azul | HUD de diagnóstico |
| vermelho | cicla o aspecto |
| verde | picture-in-picture |
| ◀ ▶ (controles escondidos) | −10s / +10s |
| ⏪ ⏩ | −60s / +60s |
| ⏮ ⏭ | −10min / +10min |
| ⏯ | play/pause |
| BACK | fecha controles; de novo, sai |

### Estrutura

```
app/src/main/java/com/seupainel/player/
├── App.kt                     # inicializa o motor P2P 1x no processo
├── PlayerActivity.kt          # activity de TV; MENU/amarelo abre áudio+legenda
├── PlayerController.kt         # ExoPlayer: subs externas, troca de áudio, sem SD cap
└── engine/P2pEngine.kt        # wrapper do TVCore: start() -> URL local 127.0.0.1
app/src/main/java/com/tvbus/engine/
├── TVCore.java                # CONTRATO (assinaturas extraídas do APK)
└── TVListener.java            # CONTRATO
```

### Motor P2P: reaproveitado deste APK

Os binários do motor **já foram extraídos do próprio APK** e colocados em
`app/src/main/jniLibs/` (não precisa de SDK externo):

- `libtvcore.so` — ponte JNI + motor (Go estático, autocontido)
- `libgojni.so`  — componente Go auxiliar
- `libc++_shared.so`

A ligação é por **símbolos JNI padrão** (`Java_com_tvbus_engine_TVCore_*`),
então a classe `com.tvbus.engine.TVCore` (reconstruída fielmente em
`app/src/main/java/com/tvbus/engine/`) liga direto neles. **Não foi usada** a
camada protegida (`libijkplayer.so`/nmmedit) — ela não é necessária, porque o
player aqui é código nosso em Kotlin.

> Se o seu provedor tiver um **SDK oficial** (`.so` + doc atualizados), é só
> trocar os arquivos em `jniLibs/`. Enquanto não tiver, o motor deste APK
> funciona.

### O que VOCÊ informa (config dinâmica do painel)

Confirmado no debug: **não há authUrl/broker/usuário fixos no APK** — essa
config é dinâmica, vem do seu painel em runtime. Então preencha:

1. **Credenciais do motor** em `App.kt` → `BuildConfigValues`
   (`authUrl`, `username`, `password`, `mkBroker`, `domainSuffix`).
2. **Endereços de conteúdo + legendas** — o `address` (P2P) e a lista
   `subtitles` (URL/idioma/tipo) que o seu painel entrega por item. O modelo
   `PanelSource`/`PanelSubtitle` espelha exatamente o do APK
   (`Source`/`Subtitles`), então é só mapear a resposta da sua API para ele.

### Build (no PC)

1. Instale o **Android Studio** (ele traz SDK + JDK). Abra a pasta
   `tvplayer-mod` com *File → Open*. Na primeira vez ele gera o Gradle wrapper
   e baixa as dependências (Media3 etc.).
2. **Build → Build Bundle(s)/APK(s) → Build APK(s)**. O APK sai em
   `app/build/outputs/apk/debug/app-debug.apk`.

Ou, pela linha de comando (com SDK instalado): `gradle wrapper && ./gradlew :app:assembleDebug`.

### Sem PC: compilar na nuvem e instalar pelo Downloader

O projeto traz `.github/workflows/build.yml`. Com ele o GitHub compila o APK
pra você e publica num link fixo:

1. Crie um repositório no GitHub (pode ser privado) e envie a pasta
   `tvplayer-mod` para a branch `main`.
2. No repo: *Settings → Actions → General → Workflow permissions* → marque
   **Read and write permissions** → Save.
3. Aba *Actions* → aguarde o job "Build APK" terminar (~5 min na primeira vez).
4. O APK fica em **Releases → latest → `player-mod.apk`**. A URL direta é:
   `https://github.com/SEU_USUARIO/SEU_REPO/releases/download/latest/player-mod.apk`
   (num repo privado a URL exige login; use repo público ou baixe e hospede
   no Drive/Dropbox).
5. Na TV, abra o **Downloader** e cole essa URL. Se quiser um **código**,
   gere um em `aftv.news` (o próprio Downloader oferece o encurtador) a
   partir da mesma URL.

Dica: no Drive use o link de download direto; no Dropbox troque `dl=0` por
`dl=1` no fim do link.

### Instalar e testar na TCL C6K (com PC)

**Ativar ADB na TV (uma vez):**
1. Configurações → Sistema → Sobre → toque 7× em **"Build do Android TV OS"**
   até liberar as *Opções do desenvolvedor*.
2. Configurações → Sistema → Opções do desenvolvedor → ative **Depuração USB**
   e **Depuração de rede** (ou "ADB pela rede").
3. Anote o IP da TV (Configurações → Rede e Internet).

**Instalar pelo PC (mesma rede):**
```bash
adb connect IP_DA_TV:5555      # aceite o aviso que aparece na TV
adb install -r app-debug.apk
adb shell monkey -p com.seupainel.player 1   # abre o app
```

**Sem ADB:** copie o `app-debug.apk` num pendrive e abra com um gerenciador de
arquivos na TV (precisa permitir "fontes desconhecidas" para esse app), ou use
o app *Send files to TV*.

**Ver o log enquanto testa** (útil para me mandar se algo falhar):
```bash
adb logcat -s ExoPlayerImpl MediaCodecVideoRenderer AudioTrack TVCore AndroidRuntime
```

### Testando na TV

O app abre numa **tela de teste**. Use os **streams públicos** (Apple/Mux) —
eles têm várias qualidades, áudios e legendas, então servem para validar tudo:

| O que testar | Como |
|---|---|
| ABR / começa em HD | Abra o preset Apple 4K e ligue o HUD (azul): `RES` deve subir para 1080p/2160p em poucos segundos |
| Menu de faixas | Amarelo → troque áudio e legenda; o indicador no canto confirma |
| Legenda externa | Cole uma URL `.srt` no campo "Legenda externa" antes de tocar |
| Aspecto | Vermelho cicla Ajustar/Preencher/Zoom/16:9/4:3 |
| PiP | Verde, ou aperte HOME com o vídeo tocando |
| Seek | Setas ±10s, ⏪⏩ ±60s, ⏮⏭ ±10min |
| Frame-rate | Com "Combinar taxa de quadros" ligado na TV, filme 24fps não deve ter judder |
| Áudio passthrough | Com soundbar em Bitstream, o preset Apple fMP4 deve sair como AC3/EAC3 |

O modo **"Tocar via P2P"** só funciona depois de preencher a config do motor
em `App.kt` (`BuildConfigValues`); sem isso ele avisa e não tenta.

### Como chamar o player

Mapeie a resposta da sua API para `PanelSource` e lance:

```kotlin
val source = PanelSource(
    address = item.address,                 // endereço P2P (o mesmo do app oficial)
    title   = item.title,
    subtitles = item.subtitles.map {        // legendas que o painel já devolve
        PanelSubtitle(url = it.url, language = it.code, type = it.type, name = it.name)
    },
    startPositionMs = ultimaPosicaoMs,      // retomar de onde parou
)
startActivity(PlayerActivity.intent(this, source))
```

## Melhorias opcionais (precisam de dependência extra + teste no device)

Estas duas eu deixei **documentadas** em vez de embutir sem testar, porque
mexem em dependência pesada e não dá pra validar sem rodar no aparelho:

### 1. Upscaling / sharpening (maquiar conteúdo SD esticado)
Media3 permite shaders via `player.setVideoEffects(...)` (dep
`androidx.media3:media3-effect:1.6.1`). **Mas na sua TCL C6K eu NÃO recomendo:**
o processador de imagem da própria TV (upscaler 4K + nitidez) é melhor que um
shader no app, e fazer no app ainda disputa GPU com a decodificação. O certo na
C6K é o app entregar a **maior rendição da fonte limpa** (já faz) e deixar a TV
escalar para 4K. Mexa antes na nitidez/clareza nas Configurações de imagem da TV.

### 2. Fallback libVLC (robustez máxima + legenda ASS)
Arquitetura: uma interface `PlaybackEngine` com duas implementações —
ExoPlayer (padrão) e libVLC — e troca automática quando o ExoPlayer dá
`PlaybackException` num stream problemático. libVLC engole quase tudo e
renderiza ASS com `libass`. Requer:
```kotlin
implementation("org.videolan.android:libvlc-all:3.6.0")  // ~30 MB
```
e gerenciamento de uma segunda surface. É um mini-projeto; posso montar se
você topar o APK maior.

---

No controle: **MENU / amarelo / legendas** → áudio + legenda + qualidade.
**INFO / azul** → HUD de diagnóstico.

---

## Ajustes na própria TCL C6K (fora do app)

Pra tirar o máximo, no Google TV:
- **Configurações → Tela e som → Avançado → "Combinar taxa de quadros do
  conteúdo": Automático** — deixa o frame-rate matching do player funcionar
  (filme 24fps sem judder).
- **Resolução de saída:** 4K 60Hz (ou a nativa do painel).
- **Modo de imagem:** evite "Vívido"; para filme, "Cinema/Filmmaker". Ajuste
  **Nitidez** com cuidado — nitidez alta em SD cria halo/artefato.
- Se usar soundbar/receiver: **saída de áudio = Passthrough/Bitstream** para o
  app mandar AC3/EAC3/DTS/Atmos sem decodificar (o app já permite isso).
