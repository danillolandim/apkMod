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
  mesmo em qualquer categoria. CH±/número/último canal seguem zapeando com ela aberta e a seleção acompanha; ↑/↓ só
  navegam. (Superado pelo teste na TV: a etapa 3 passou a usar sempre a seção inteira e o prazo de inatividade para
  30 s.)

### Etapa 3 — seção ao vivo inteira, entrada pela Home e preview (ajustada pelo retorno da TV)
Escopo original: "modo guia" com a lista em tela cheia e o vídeo numa janela reduzida no canto. Trocado, depois do
teste na TV (ver "Retorno do usuário"), por: a mesma lista lateral da etapa 2 sobre o vídeo em tela cheia, agora
sempre com a seção inteira, aberta direto pela Home e com preview do canal em foco. Sem janela de vídeo reduzida.
- `playlist/LiveCatalog.kt` (novo): monta a seção ao vivo a partir da API da conta (`XtreamCatalog`) ou da lista M3U
  em memória. Parte pura e testada em JVM: `build` (junta por qualidade com a mesma regra do `collapseLive`; "Todos" =
  todos os canais, na ordem da lista), `groupsOf` ("Todos", "Favoritos" se houver, e as categorias na ordem do menu),
  chave estável do canal (`keyOf`) e achar canal por URL. `openSession` é a cola Android usada pela Home.
  - Categorias: as do painel (sem o prefixo "TV | ", na ordem do painel); sem API, as do group-title se houver mais de
    uma (ordem alfabética, como o menu); senão as deduzidas do nome (`LiveGroups`). Um canal aparece em toda categoria
    em que alguma qualidade dele está; a "principal" (a que o painel abre) é a do melhor irmão.
  - Numeração = posição em "Todos". "Favoritos" vem logo após "Todos": canais ao vivo cuja URL (de qualquer
    qualidade) está nos favoritos da tela de canais (`PlaylistStore.favorites`, por URL).
- `ChannelListActivity.playLive`: a sessão passa a ser sempre a seção inteira, de qualquer categoria, busca ou
  Favoritos; posiciona no canal clicado (achado pela URL). `secOf` usa `LiveCatalog.groupKind` (mesma regra).
- Home → "TV ao vivo": `LiveCatalog.openSession` em segundo plano (o botão mostra "Carregando canais…"), sintoniza o
  último canal (`AppSettings.lastLiveChannel`, gravado a cada sintonia; se não existir mais, o primeiro) e abre o
  `PlayerActivity` com `EXTRA_LIVE_HOME`: a lista abre sozinha depois do primeiro layout. Sem como montar o catálogo
  (sem API e sem M3U em memória, ou falha), cai na `ChannelListActivity` com um aviso. A tela de canais continua nas
  abas das outras telas (busca de canais fica lá).
- Painel: abre na categoria do canal que está tocando, com o canal em foco. Canal em foco por 800 ms → o MESMO player
  sintoniza (cada movimento do foco reinicia a espera; o foco é lido na hora de disparar e só vale com o foco nos
  canais, não nas categorias). OK fecha (sintoniza na hora se ainda não sintonizou); Voltar fecha e mantém o canal
  que está tocando. Fecha sozinho após 30 s sem tecla; aberto pela Home, o prazo só começa na primeira tecla. A faixa
  do canal fica escondida com o painel aberto.

- Correção pós-teste (v108): a Home caía na tela de canais em vez de abrir o player. Causa provável, não provada por
  leitura: clique antes de o app terminar a carga da abertura (`PlaylistHolder.preloadDone`), quando a lista M3U ainda
  não está em memória e a conta pode não ser derivável. Agora `openSession` espera a carga (até 30 s, com o
  "Carregando canais…") e devolve `Opening.Ready` ou `Opening.Failed(motivo)`; o motivo (curto, sem endereço nem conta)
  aparece numa caixa antes de abrir a lista completa. `assemble` (a decisão de fonte) é pura e tem testes.

### Etapa 4 — programação sob demanda pela conta
Formato confirmado na documentação (Context7, "Xtream Codes API"): `GET player_api.php?...&action=get_short_epg&stream_id=ID&limit=N`
(padrão 4) devolve `{"epg_listings":[{"title","description","start_timestamp","stop_timestamp","now_playing",...}]}`; nos painéis
reais o título e a descrição costumam vir em base64 (a documentação mostra texto puro: o parser aceita os dois).
- `XtreamApi.shortEpg(acc, ua, streamId, limit)` + `parseShortEpg` (puro, JVM): tolera campo ausente, título que não é
  base64, lista vazia, timestamp como texto ou em milissegundos, objeto indexado no lugar de lista, BOM e resposta que não é
  JSON. Base64 só é aceito com o preenchimento canônico ("=") e sem caracteres de controle, e a decisão vale para a
  resposta toda (palavra solta como "Rede" ou "OK" não vira lixo). Pede `limit=6` (atual + 3 seguintes com folga).
- `meta/Guide.kt`: `ProgramCache` (TTL 10 min; falha vale 1 min e não apaga o dado bom; no máximo uma consulta por canal)
  e o `Guide`, interface única para a faixa e a lista: `peek` (o que se sabe agora, sem rede), `request` (consulta em
  segundo plano, 2 threads, `Net.client` com timeouts de 8/10 s), `nowText` (linha da lista, só do cache) e `nowLine`.
  Ordem das fontes: XMLTV (`Epg.listFor`) se a URL está configurada e o arquivo tem programa útil para o canal; senão a
  API da conta (`XtreamCatalog.accountFor`, mesmo cálculo do catálogo, + `apiId` do canal). Sem conta e sem URL: igual
  a antes ("Sem informação de programação").
- Estados: "Carregando programação…" (consulta possível e sem dado fresco), "Sem informação do programa atual" (só há
  programas futuros) e "Sem informação de programação".
- Quando consulta: o canal sintonizado (ao sintonizar e ao mostrar a faixa) e o canal em foco na lista depois de 350 ms
  parado. Linhas da lista só leem o cache. Quando a resposta chega, `Guide.listener` redesenha a faixa ou a lista.
- Nada em `Net` segura essa requisição durante a reprodução: `Net.playbackActive` só barra quem chama `Net.awaitIdle()`
  (catálogo e TMDB em segundo plano); o `Guide` não chama.

## Retorno do usuário (TV real, v107, etapas 1 e 2)
1. CH+/CH− e ↑/↓ trocam de canal: OK.
2. OK abre a lista, mas ela só mostrava o nível em que ele estava (entrando por uma categoria, só aquela): ele quer
   SEMPRE a seção TV ao vivo inteira, com todas as categorias.
3. "TV ao vivo" (Home) deve já abrir um canal tocando, com a lista completa na lateral, como no menu do OK.
4. Faixa e lista mostravam "Sem informação de programação". Causa: `Epg` só carrega se houver `PlaylistStore.epgUrl`
   digitada em Configurações; com o login por usuário e senha ela fica vazia, e a falha é silenciosa. Resolvido na
   etapa 4 (programação sob demanda pela conta).

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
- [x] Etapa 1 · [x] Etapa 2 · [x] Etapa 3 · [x] Etapa 4

Etapas 1 e 2 testadas pelo usuário na TV (v107). Etapas 3 e 4 implementadas, com testes JVM e build verdes; o
comportamento na TV (inclusive se o painel responde ao `get_short_epg` durante a reprodução) ainda não foi validado
(sem emulador).
