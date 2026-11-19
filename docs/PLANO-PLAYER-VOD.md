# Plano: controles do player de filmes e séries (estilo Netflix/Prime)

## Pedido (19/11/2026, Danillo)
"Não consigo dar pause depois de colocar um filme. Quero algo como Netflix/Amazon Prime: play, pause e uma linha de
progresso que anda conforme você age."

## Causa do problema atual
- No VOD o **OK abre o dock** de opções (`PlayerActivity.onKeyDown`, ramo `DPAD_CENTER`), não pausa. Só a tecla física
  ⏯ pausa, e muitos controles de TV box não têm.
- O controlador do Media3 (`useController = true`) briga com o nosso `onKeyDown`: a primeira tecla só o mostra
  (o `PlayerView` consome), o foco cai num botão pouco visível, as setas ora movem o foco ora fazem seek, e ele some em
  4 s. Dois sistemas disputando as mesmas teclas.

## Decisões (19/11/2026, Danillo)
- O VOD deixa de usar o controlador do Media3 (`useController = false` sempre). Controle próprio, uma camada só.
- **OK / ⏯ = play/pause.** **↓ e MENU = opções** (o dock atual). A seta ↓ só abre o dock com a barra na tela; com a
  barra escondida, ↓ só mostra a barra.
- **← →: a linha anda na hora e o vídeo só pula ~700 ms depois da última tecla** (scrub com confirmação adiada).
  Segurar acelera.
- O ao vivo **não muda** (mapa de teclas, faixa do canal, lista, toque).

## Comportamento (TV)
| Tecla | Barra escondida | Barra na tela |
|---|---|---|
| OK, ⏯, PLAY, PAUSE | alterna play/pause e mostra a barra | alterna play/pause (durante scrub: confirma o pulo já) |
| ← → | mostra a barra e começa o scrub | move o alvo do scrub |
| ↓, MENU | mostra a barra | abre o dock (MENU abre direto, mesmo com a barra escondida) |
| ↑ | mostra a barra | nada |
| Voltar | sai do player (como hoje) | durante scrub: cancela e volta a bolinha; senão esconde a barra |
| ⏩ ⏪ ⏭ ⏮ | continuam como hoje, mas passam pelo mesmo scrub (alvo + pulo adiado) e mostram a barra |

- Barra some 4 s depois da última ação **se estiver tocando**. Pausado, fica na tela.
- Próximo episódio: com o aviso "Próximo em N" na tela, OK continua pulando para o próximo e Voltar cancela (prioridade
  sobre a barra, como hoje).
- Dock aberto: setas e OK navegam no dock, Voltar fecha. A barra se esconde ao abrir o dock.
- PiP: barra escondida e nada dela reage.
- Ícone central grande (❚❚ ou ▶) aparece por ~600 ms com fade ao alternar, como Netflix. Pausado, fica um ▶ menor
  sobre a barra indicando o estado (o estado também está em texto para o TalkBack: "Pausado", "Reproduzindo").

## Visual (overlay `vod_bar` em `activity_player.xml`)
```
 ┌──────────────────────────────────────────────────────────────┐
 │ (degradê escuro no topo)  Título · T1:E3                      │
 │                                                               │
 │                           ❚❚   (pulso central ao alternar)    │
 │                                                               │
 │ (degradê escuro embaixo)                                      │
 │  ▶  ━━━━━━━━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  -1:02:15 │
 │     32:10   (durante o scrub: balão com o tempo alvo acima da bolinha) │
 │     ↓ Áudio, legenda e mais                                   │
 └──────────────────────────────────────────────────────────────┘
```
- Degradês (`scrim` escuro de baixo para cima e de cima para baixo), sem caixa sólida.
- Linha: trilho `#33FFFFFF`, buffer `#66FFFFFF`, progresso `lf_primary`, bolinha `lf_accent` 14dp (cresce para 18dp
  durante o scrub). Altura do trilho 4dp (6dp durante o scrub). View própria `VodTimeBar` (desenho em `onDraw`) ou
  `SeekBar` estilizada **não focável**; na TV o foco nunca entra nela.
- Tempos: decorrido à esquerda embaixo, restante à direita ("-1:02:15"). Rajdhani SemiBold 18sp, `lf_text`.
- Título: Rajdhani SemiBold 22sp; subtítulo (episódio) `lf_text_2`.
- Dica "↓ Áudio, legenda e mais" em `lf_text_2` 14sp (no celular: some, o botão Opções está na `touch_bar`).
- Margens laterais 56dp na TV, 16–24dp no celular (via `Device.isTv` ou `dimens`).
- Movimento: barra entra/sai com fade + translateY 16dp em 180 ms; respeitar animações desligadas do sistema
  (`Settings.Global.ANIMATOR_DURATION_SCALE == 0` → sem animação).

## Celular (`Device.isTv = false`)
- Toque no vídeo mostra/esconde a barra (a `touch_layer` passa a valer também no VOD).
- Barra no celular tem, no centro da tela, **⏪10  ▶/❚❚  10⏩** (alvos ≥ 48dp) e a linha **arrastável com o dedo**
  (arrastar = scrub; soltar = pulo). Toque duplo à esquerda/direita = −/+10 s (opcional; só se sobrar tempo).
- A `touch_bar` (Opções e Transmitir) passa a acompanhar a barra nova em vez do controlador do Media3.

## Lógica testável (JVM) — `VodScrub.kt`
Classe pura, sem Android:
- `begin(positionMs, durationMs)`, `step(direction, repeatCount)` → novo alvo, limitado a `[0, duration]`.
- Aceleração por `repeatCount` (tecla segurada): 0–4 → passo base (Configurações `seekStepSec`, padrão 10 s);
  5–14 → 30 s; 15+ → 60 s.
- Duração desconhecida (`C.TIME_UNSET`/≤0): limita só em 0.
- `commit()` devolve o alvo e encerra; `cancel()` devolve a posição original.
- Formatação de tempo (`fmt(ms)` e `fmtRemaining(pos, dur)`), movendo o `fmtTime` atual para cá.
Testes em `app/src/test/.../VodScrubTest.kt`: limites, aceleração, cancelar, duração desconhecida, formatação.

## Etapas (um commit por etapa)
1. **`VodScrub` + testes.** Sem mudar tela.
2. **Barra nova na TV.** Layout `vod_bar`, `VodTimeBar`, atualização a cada 500 ms enquanto visível (posição, buffer,
   duração), ícone central, `useController = false` no VOD (onCreate e `onPictureInPictureModeChanged`), novo mapa de
   teclas do VOD no `onKeyDown` conforme a tabela, remover `wireOptionsGear` do VOD (a engrenagem do Media3 deixa de
   existir) e o `showBadge` de seek (a barra mostra o tempo). Badge de qualidade/legenda continua.
3. **Celular.** Toque mostra/esconde a barra, botões ⏪10 ▶/❚❚ 10⏩, linha arrastável, `touch_bar` atrelada à barra,
   `syncTouchLayer` cobrindo o VOD.
4. **Fechamento.** `CHANGELOG.md` ("Esta versão"), marcar este plano, lista de testes para o Danillo.

## Regras
- O ao vivo não pode mudar: todo ramo novo fica atrás de `!isLive`.
- Não mexer em `PlayerController`, retomada (`ResumeStore`), autoplay de episódio, legendas, recuperação de erro.
- `./gradlew`/`gradle :app:testDebugUnitTest :app:assembleDebug --offline` com JDK 21 verde a cada commit.

## Riscos e como saber
- Sem emulador: o visual só é conferido na TV/celular do Danillo. Pedir foto da barra pausada e no scrub.
- Seek adiado em HLS do P2P pode levar alguns segundos para retomar: a barra mostra o carregamento (bolinha parada no
  alvo e "Carregando…" no lugar da dica) até `STATE_READY`.
- Algumas TV box mandam `DPAD_CENTER` e `ENTER` juntos: tratar só `ACTION_DOWN` com `repeatCount == 0` para não
  alternar duas vezes.
