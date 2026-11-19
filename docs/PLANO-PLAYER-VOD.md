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

> **Parte 2 (mais abaixo) muda ↓, ↑, OK e Voltar na TV:** o ↓ não abre mais o dock, desce para uma fileira de botões.
> A tabela abaixo vale para o modo "linha"; a da Parte 2 é a que manda.

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
1. [x] **`VodScrub` + testes.** Sem mudar tela. (`569aca0`; 36 testes depois da etapa 3)
2. [x] **Barra nova na TV.** (`868f13a`) Layout `vod_bar`, `VodTimeBar`, atualização a cada 500 ms enquanto visível (posição, buffer,
   duração), ícone central, `useController = false` no VOD (onCreate e `onPictureInPictureModeChanged`), novo mapa de
   teclas do VOD no `onKeyDown` conforme a tabela, remover `wireOptionsGear` do VOD (a engrenagem do Media3 deixa de
   existir) e o `showBadge` de seek (a barra mostra o tempo). Badge de qualidade/legenda continua.
3. [x] **Celular.** (`2406d38`) Toque mostra/esconde a barra, botões ⏪10 ▶/❚❚ 10⏩, linha arrastável, `touch_bar` atrelada à barra,
   `syncTouchLayer` cobrindo o VOD.
4. [x] **Fechamento.** `CHANGELOG.md` ("Esta versão"), marcar este plano, lista de testes para o Danillo (abaixo).

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

## Como ficou (19/11/2026) e desvios do plano
- `VodScrub` ganhou `jump(deltaMs)` (teclas ⏪ ⏩ ⏮ ⏭ passam pelo mesmo alvo) e `moveTo(ms)` (arrastar com o dedo).
- `fmtTime` **não** foi movido: o ao vivo ainda o usa na faixa "+60s • 05:03 / 20:00" e não podia mudar. O `VodScrub.fmt`
  é novo e sem zero à esquerda ("5:03", "1:02:15", como no desenho).
- Durante um scrub, OK / ⏯ só **confirmam o pulo** (não alternam pausa na mesma tecla): quem pausou continua pausado no
  novo ponto, quem estava tocando continua tocando. PLAY e PAUSE explícitos confirmam o pulo e depois agem.
- OK e ENTER juntos: além de só agir em `repeatCount == 0`, ignora um segundo OK em menos de 250 ms (caixa que manda os dois).
- Tecla segurada: o scrub ignora repetições mais rápidas que 100 ms, para a aceleração (10 s, 30 s, 60 s) não depender da
  taxa de repetição de cada controle.
- Ícone pequeno à esquerda da linha: ▶ quando pausado, ❚❚ quando tocando (o que o OK faz). O pulso grande no centro mostra
  o estado novo (❚❚ ao pausar, ▶ ao continuar). No celular o botão do centro faz esse papel e o pulso não aparece.
- MENU, amarelo e CC abrem o dock no filme (antes abriam o menu em lista); o menu em lista continua acessível pelo
  dock aberto + MENU, como já era.
- O aviso "Próximo em N" sobe 132dp enquanto a barra está na tela, para não ficar embaixo do tempo restante.
- Celular: o número em ⏪N / N⏩ é o "Pulo das setas" das Configurações (10 s por padrão), toques seguidos somam num pulo só.
- Não foi feito o toque duplo nas laterais (opcional no plano). `wireOptionsGear` foi apagada por inteiro (no ao vivo ela já
  era inócua, o controlador nunca aparece).

## Testes para fazer (sem emulador: só na TV e no celular de verdade)
TV, filme:
1. Abrir um filme: toca sem barra. **OK** pausa: ❚❚ grande some em ~0,6 s e a barra fica na tela, com o botão ▶/❚❚ da
   fileira mostrando ▶. **OK** de novo: continua (o botão volta a ❚❚) e a barra some 4 s depois. Trocar por ⏯ do controle
   (se tiver) e conferir o mesmo.
2. **→** (e **←**): a linha anda na hora com o balão do tempo; ~0,7 s depois da última tecla o vídeo pula. Segurar →
   acelera (10 s, 30 s, 1 min). Ao pular aparece "Carregando…" ao lado do tempo até voltar a tocar.
3. Durante o scrub: **OK** confirma na hora; **Voltar** cancela e a bolinha volta ao lugar (a barra fica).
4. Barra escondida: **←/→** já mostram a barra e movem a linha; **↑** só mostra a barra; **↓** mostra a barra **já na
   fileira, com o ▶/❚❚ roxo**. **MENU** abre o dock mesmo com a barra escondida. Dock aberto: setas/OK navegam nele e
   **Voltar** fecha.
4b. Fileira de botões (com a barra na linha, **↓** desce): o botão escolhido fica roxo e os outros cinza-claro, **sem anel e
   sem fundo**. **←/→** andam entre os botões e param nas pontas (não dão a volta). **OK** no ▶/❚❚ pausa e continua (o
   ícone troca); em **Áudio e legendas** abre o dock com o foco em Áudio; em **Mais opções** abre o dock em Qualidade.
   **↑** ou **Voltar** leva de volta à linha (a bolinha volta a responder a ←/→). Navegar nos botões mantém a barra na tela;
   4 s sem tecla tocando, ela some e, ao voltar com qualquer tecla, está em modo linha. Conferir se o roxo do botão
   escolhido é fácil de ver de longe.
5. **Voltar**: na fileira volta para a linha; na linha esconde a barra; sem barra, sai do player. Pausado, a barra não some
   sozinha.
6. Se a TV box mandar OK duas vezes (DPAD_CENTER + ENTER): o OK deve pausar/continuar **uma** vez só.
7. Teclas ⏪ ⏩ ⏮ ⏭ (se existirem): andam 1 min / 10 min pela mesma linha.
8. Série: título e "T1 · E3" no topo; a fileira mostra **Próximo episódio** (e no último episódio ele não aparece); **OK**
   nele pula para o próximo e o título muda. Nos últimos 10 s o "Próximo em N" aparece acima da barra, **OK** pula,
   **Voltar** cancela. Trocar de episódio pelo autoplay atualiza o título e a fileira. Em filme, a fileira tem só 3 botões.
9. PiP (verde): a barra some e nada reage. Ao voltar da PiP, a barra continua escondida.
10. Foto da barra **pausada** e da barra **no meio de um scrub** (para conferir o visual).
11. **Ao vivo: nada mudou** (OK abre a lista, CH+/CH−, número, MENU/OK longo abrem o dock). No celular a fileira não
    aparece e a barra é a de antes.

Celular:
12. Toque no vídeo mostra a barra com Opções/Transmitir no alto, ⏪10 ▶/❚❚ 10⏩ no centro e a linha embaixo; novo toque esconde.
13. Arrastar a linha com o dedo move o balão; soltar pula. Tocar ⏪/⏩ várias vezes seguidas soma num pulo só.
14. Tocar ▶/❚❚ pausa e continua; a barra não some pausada. Opções abre o dock; Transmitir esconde a barra.
15. Com TalkBack: ⏪, ▶/❚❚ e ⏩ têm nome falado ("Voltar 10 segundos", "Pausar"…) e o estado é anunciado ("Pausado"/"Reproduzindo").
16. Animações do sistema desligadas (Opções do desenvolvedor, escala 0): a barra entra e sai sem se mover.

---

# Parte 2: fileira de botões estilo Netflix (TV) — pedido 19/11/2026

## Pedido
"Monta a fileira de botão igual a Netflix": na barra do filme/série, uma fileira de botões embaixo da linha, que se
navega pelo controle.

## Desenho
```
  Título · T1 · E3
  ━━━━━━━━━━━━━━━●──────────────────────────────  -1:02:15
  32:10
  [ ❚❚ ]   [ Áudio e legendas ]   [ Próximo episódio ]   [ Mais opções ]
```
- Botões: **▶/❚❚** (só ícone, ~56×48dp), **Áudio e legendas** (`ic_subtitles` + texto), **Próximo episódio** (`ic_skip_next`
  + texto; só em série com próximo item: `pc.player.hasNextMediaItem()`; atualizado em `onMediaItemTransition` e a cada
  500 ms da barra), **Mais opções** (engrenagem `ic_gear` + texto). Altura 48dp, espaço 12dp.
- Visual (decisão do Danillo, 19/11/2026, **no lugar do anel do DESIGN.md**): **sem anel e sem fundo, em nenhum estado**.
  O botão escolhido só muda de cor: ícone + texto `lf_accent` (opacidade total); os outros `lf_text_2` a 70% (`lf_text_2_dim`).
  Sem escala. Lista de cores `res/color/vod_row_content.xml` com `state_selected` (texto e `tint` do ícone); o
  `isSelected` vai no contêiner do botão e passa para o ícone e o texto.
- A dica "↓ Áudio, legenda e mais" sai; "Carregando…" aparece **ao lado do tempo decorrido** (mesma linha, para a barra não
  mudar de altura) e fica escondido quando não está carregando.
- Celular: a fileira **não aparece** (já tem ⏪ ▶/❚❚ ⏩ e a touch_bar com Opções). Tudo atrás de `!phone`.

## Foco "virtual" (decisão técnica)
Nada na barra recebe foco do Android (continua `blocksDescendants`). A Activity guarda o modo e o índice:
`vodRowIdx = -1` → modo **linha**; `0..n-1` → modo **fileira**, botão selecionado (`isSelected = true` só nele, e
`announce()` do nome para o TalkBack). Evita a briga de foco que derrubou o controlador do Media3.

## Teclas (filme/série, TV)
| Tecla | Barra escondida | Modo linha | Modo fileira |
|---|---|---|---|
| OK | play/pause + mostra a barra (linha) | play/pause (no scrub: confirma) | aciona o botão selecionado |
| ⏯ PLAY PAUSE | play/pause | play/pause | play/pause |
| ← → | mostra + scrub | scrub | anda entre os botões (para nas pontas, sem dar a volta) |
| ↓ | mostra a barra **já na fileira, no ▶/❚❚** | vai para a fileira, no ▶/❚❚ | nada |
| ↑ | mostra a barra (linha) | nada | volta para a linha |
| MENU / amarelo / CC | dock | dock | dock |
| Voltar | sai do player | no scrub: cancela; senão esconde | volta para a linha |
- Ao esconder a barra (4 s sem ação tocando, Voltar, dock, PiP), o modo volta para linha.
- No modo fileira as teclas também contam como ação (a barra não some enquanto o usuário navega).
- "Próximo em N" na tela: OK e Voltar continuam com a prioridade deles (pular / cancelar), como hoje.
- Ações: ▶/❚❚ = `vodSetPlaying(null)` (o ícone do botão segue o estado); Áudio e legendas = abre o dock com o foco em
  `dock_audio` (`openDock(focus = dockAudio)`); Próximo episódio = `hideNextEpBar(); seekToNextMediaItem()`;
  Mais opções = `openDock()`.
- O ícone pequeno ▶/❚❚ à esquerda da linha sai na TV (o botão da fileira mostra o estado). No celular já está escondido.

## Etapas
5. [x] Fileira + foco virtual + mapa de teclas + CHANGELOG ("Esta versão") + atualizar a lista de testes acima. Um commit.

## Como ficou e desvios do desenho (Parte 2)
- Lógica pura em `VodRow.kt` (quais botões existem, ← → parando nas pontas, `remap` quando o "Próximo episódio" aparece ou
  some e o botão escolhido tem de continuar o mesmo) com `VodRowTest.kt`.
- Teclas ⏪ ⏩ ⏮ ⏭ na fileira: saem da fileira (voltam ao modo linha) e movem o scrub, para a seleção e o alvo do pulo não
  aparecerem juntos. ← → só andam na primeira batida (segurar não faz nada a mais).
- ↓ na linha com um pulo pendente confirma o pulo antes de descer. ⏯ / PLAY / PAUSE na fileira não saem dela.
- Se o botão escolhido some (último episódio começa com "Próximo episódio" escolhido), a escolha fica no índice mais próximo
  (Mais opções).
- O aviso "Próximo em N" sobe 170dp na TV (a barra ficou mais alta com a fileira); no celular continua 132dp.
- O ícone pequeno de estado à esquerda da linha agora é `gone` no layout; a linha começa na borda, alinhada ao tempo.
- No celular ↓ continua abrindo o dock com a barra na tela, como antes (a fileira não existe lá).

## Regras
As mesmas da parte 1: ao vivo intocado, celular intocado (exceto não mostrar a fileira), build/testes verdes com JDK 21.
