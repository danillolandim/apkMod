# Plano — modo TV ao vivo

Aprovado pelo usuário em 12/11/2026. Branch: `feat/tv-ao-vivo` (a partir de `feat/xtream-api`).

## Problema
O ao vivo roda no player de filme. Consequências medidas no código:
- `PlayerActivity` recebe só a URL de um canal: não há lista para zapear e `CHANNEL_UP/DOWN` não são tratadas.
- No ao vivo, ←/→ tentam seek ("Ao vivo"), ↑ mostra a barra, OK abre o dock e ↓ abre os controles de filme
  do Media3 (`use_controller=true`), que roubam o foco.
- Trocar de canal = fechar a tela e criar outro `ExoPlayer`.

## Resultado esperado
Experiência de TV por assinatura: troca de canal pelo controle, faixa com logo/número/programa, lista de canais
com EPG por cima do vídeo e preview do canal em foco. Um único `ExoPlayer` reaproveitado (uma conexão só:
contas Xtream costumam ter `max_connections = 1`, então **nunca** criar um segundo player para preview).

## Mapa de teclas (ao vivo, tela cheia)
| Tecla | Ação |
|---|---|
| CH+ / ↑ / PAGE_UP | Canal seguinte (número +1, dá a volta no fim) |
| CH− / ↓ / PAGE_DOWN | Canal anterior |
| OK | Lista de canais por cima do vídeo (canal único, sem lista: abre o dock) |
| ← / → | Nada (consumidas; sem seek no ao vivo) |
| MENU / amarelo / OK longo | Dock de opções (qualidade, áudio, legenda, aspecto, timer, PiP) |
| 0–9 | Número do canal; confirma sozinho após 1,5 s ou com OK |
| LAST_CHANNEL | Volta ao canal anterior |
| INFO / azul | Faixa do canal; toque longo liga o HUD de diagnóstico |
| Voltar | Fecha lista/dock; a faixa só fecha antes de sair se estiver presa por um erro (a faixa em auto-hide não segura o Voltar: um toque sai); se nada aberto, sai |
VOD (filme/série) **não muda**: tudo acima vale só quando `isLive`.
Ao vivo não lê nem grava posição de retomada e sempre (re)começa na borda ao vivo (`C.TIME_UNSET`).

## Regras de desenho
- Seguir `DESIGN.md`: tokens `lf_*` de `res/values/colors.xml`, foco = anel roxo fino sem escala, fontes Rajdhani
  (títulos) e Nunito Sans (corpo), textos 12–16sp de corpo, tela lógica ~960×540dp, margem segura 28–56dp.
- Estados: carregando, sem EPG ("Sem informação de programação"), erro de canal (faixa continua visível com a
  mensagem), lista vazia.
- Acessibilidade: `contentDescription` nos elementos focáveis, anúncio TalkBack ao trocar de canal, status não
  depender só de cor.
- Sem dados do provedor (host, usuário, senha) em código, testes, docs ou commits.

## Etapas (um commit por etapa; build + testes verdes antes de cada commit)

### Etapa 1 — zapping no player
1. `playlist/LiveSession.kt` (novo): `object` em memória com a lista de canais ao vivo da sessão.
   - `data class LiveChannel(name, logo, tvgId, apiId, group, userAgent, referer, variantUrls, variantLabels)`.
   - `class LiveZapper(size)`: lógica pura e testável — `current`, `next()`, `prev()` (com volta), `jumpTo(i)`,
     `last()` (alterna com o canal anterior), `byNumber(n)` (número = índice + 1; fora da faixa → null).
   - Testes JVM em `app/src/test/.../playlist/LiveZapperTest.kt` (volta nas pontas, last, número inválido, lista
     de 1 item, lista vazia).
2. `ChannelListActivity.playLive`: antes de abrir o player, preencher `LiveSession` com a lista ao vivo
   **atualmente exibida** (já colapsada por qualidade; variantes vêm de `liveVariants`) e o índice do canal
   escolhido. Extras atuais continuam indo (compatibilidade); o player usa `LiveSession` se estiver preenchida e
   o canal bater.
3. `PlayerActivity` (ao vivo):
   - `playerView.useController = false` (e continuar falso ao sair do PiP).
   - `onKeyDown` conforme o mapa de teclas. OK longo: usar `event.startTracking()` + `onKeyLongPress`.
   - `zapTo(index)`: atualiza a faixa **na hora** e sintoniza com atraso de 300 ms (cancelável: segurar CH+
     percorre os canais sem abrir conexão a cada um). `tune()` zera `retries`, `triedCompat`, `usingBackup`,
     atualiza `directUrl`, `resumeKey`, `title`, `logoUrl`, `tvgId`, variantes e cabeçalhos HTTP, e chama
     `pc.play(url)` no **mesmo** player.
   - `PlayerController.setHttpHeaders` hoje não limpa o Referer do canal anterior: corrigir (sempre definir).
   - Entrada numérica: mostra os dígitos no badge; confirma após 1,5 s.
4. Faixa do canal (`channel_bar` em `activity_player.xml`): número, logo, nome, programa atual com horário e
   barra de progresso, "A seguir". `Epg` ganha `progress(tvgId): Float?` (0..1) — com teste JVM se a lógica
   puder ser isolada de `System.currentTimeMillis()` (passar `now` por parâmetro).
5. Atualizar `CHANGELOG.md` (bloco "## Esta versão").

### Etapa 2 — lista de canais por cima do vídeo
- Painel à esquerda (≈ 45% da largura) sobre o vídeo, que continua tocando: coluna de categorias + coluna de
  canais (número, logo, nome, programa atual). À direita embaixo: programação do canal em foco (atual +
  próximos 3).
- `LiveSession` passa a carregar os grupos (categoria → canais) montados pela `ChannelListActivity`.
- OK no canal sintoniza e fecha; ←/→ alternam entre categorias e canais; Voltar fecha. Abre com o foco no canal
  atual. OK (tela cheia) passa a abrir a lista; dock fica em MENU / OK longo.
- `ListView` com holder reciclado (padrão do projeto), logos via Coil já usado no app.
- Como ficou: `LiveSession.groups` = "Todos" (a lista da sessão) + uma categoria por `LiveChannel.group` (a tela de
  canais grava aí o nome da categoria do menu), com índices sempre na lista "Todos", então o número do canal é o
  mesmo em qualquer categoria. A lista fecha sozinha após 15 s sem tecla. CH±/número/último canal seguem zapeando
  com ela aberta e a seleção acompanha; ↑/↓ só navegam. Se a sessão foi aberta de uma categoria ou busca, "Todos" é
  essa lista filtrada (não a seção inteira).

### Etapa 3 — guia com preview
- "TV ao vivo" abre direto o player em **modo guia**: lista em tela cheia e o vídeo numa janela (canto superior
  direito). Canal em foco por 700 ms → a janela sintoniza (mesmo player). OK expande para tela cheia sem
  reconectar; Voltar na tela cheia volta ao guia.
- Requer extrair de `ChannelListActivity` a montagem da lista ao vivo (seção, `collapseLive`, grupos) para algo
  reutilizável (`playlist/LiveCatalog.kt`), sem mudar o comportamento de Filmes/Séries.
- Abre no último canal assistido (salvar em `AppSettings`).

### Etapa 4 — EPG sob demanda
- `XtreamApi.shortEpg(acc, streamId)` via `get_short_epg` (títulos em base64) com cache em memória por canal
  (TTL 10 min) e parser testado em JVM. Usado pela faixa e pela lista quando a conta é Xtream; o XMLTV continua
  como alternativa quando há URL de EPG configurada.
- Confirmar o formato da resposta com `ctx7`/documentação antes de implementar.

## Verificação
- `ANDROID_HOME=~/Library/Android/sdk gradle :app:testDebugUnitTest` e `gradle :app:assembleDebug`.
- Neste Mac o JDK padrão (26) quebra o build do Android antes de compilar (`jlink`/`JdkImageTransform`). Comando que
  funciona (JDK 21; não mexer em versões do `build.gradle.kts`):
  `ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home gradle :app:testDebugUnitTest :app:assembleDebug --offline`.
- Não há emulador neste Mac: comportamento na TV é validado pelo usuário (prints ajudam). Dizer isso no relato.

## Riscos
- Tunneling + troca rápida de canal pode falhar em algumas TVs: o fallback `triedCompat` precisa valer por canal.
- `↑/↓` para zapear conflita com o costume de "↓ abre algo": se o usuário estranhar, trocar por lista no ↑/↓ é
  uma mudança de uma linha no mapa de teclas.
- `disableTunneling()` é permanente na sessão do player: se o fallback de compatibilidade disparar num canal, os
  seguintes seguem sem tunneling (seguro, mas pode custar qualidade/sincronia em TVs que aguentariam). O
  `triedCompat` é por canal, então o fallback pode tentar de novo. Reativar o tunneling a cada troca de canal é
  possível (`setTunnelingEnabled(true)`), mas repetiria a falha nas TVs que realmente não suportam; fica como está.
- A lista ao vivo em memória morre com o processo: se o player for recriado sem `LiveSession`, cair no modo de
  canal único (extras), sem travar.

## Status
- [x] Etapa 1 · [x] Etapa 2 · [ ] Etapa 3 · [ ] Etapa 4

Etapas 1 e 2 implementadas, com testes JVM e build verdes; o comportamento na TV ainda não foi validado (sem emulador).
