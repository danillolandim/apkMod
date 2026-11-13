# Plano — busca global e telas de detalhe no tema novo

Pedido do usuário em 12/11/2026 (após validar o modo TV ao vivo na v109). Branch: `feat/tv-ao-vivo`.

## Problema
- A busca atual (`ChannelListActivity.openSearch`) só procura dentro da seção/categoria aberta e usa o teclado do
  sistema, que cobre a tela.
- `DetailActivity` (filme) e `SeriesActivity` (série) ainda estão no visual antigo (azul/branco).
- Na série não fica claro, de relance, o que já foi assistido, o que está pela metade e qual é o próximo.

## Etapa A — busca global
Tela própria (`SearchActivity`), pensada para controle remoto a 3 m.
- **Entrada:** teclado na própria tela, à esquerda (grade A–Z, 0–9, espaço, apagar, limpar), no padrão dos apps de
  TV: é mais rápido no D-pad e não cobre os resultados. O texto digitado aparece grande no topo. Teclado físico
  também funciona (letras, Backspace). Se o aparelho tiver reconhecimento de voz, um botão "Falar" (e a tecla
  SEARCH/microfone) usa `RecognizerIntent`; sem suporte, o botão não aparece.
- **Resultados:** à direita, atualizando enquanto digita (espera de ~250 ms), em fileiras horizontais por tipo, nesta
  ordem: **Filmes**, **Séries**, **Canais**, cada uma com a contagem ("Filmes · 124"). Cartões de cartaz iguais aos
  da grade (título, ano/categoria, nota); canais com logo e programa atual quando houver em cache.
- **Abrangência:** o catálogo inteiro (todas as categorias), não a seção aberta. Dublado/legendado e qualidades do
  mesmo título contam como um item, como nas listas.
- **Casamento:** sem acento e sem diferenciar maiúsculas; várias palavras = todas precisam aparecer; ordem dos
  resultados: título começa com o texto → alguma palavra começa com o texto → contém; desempate por nota e por
  mais recente. Lógica pura com testes JVM.
- **Desempenho:** ~47 mil itens. Índice de títulos normalizados montado uma vez em segundo plano e reaproveitado;
  busca fora da thread da tela, com cancelamento da busca anterior; no máximo 40 cartões por fileira.
- **Estados:** antes de digitar = buscas recentes (até 8, apagáveis) e uma dica curta; buscando; sem resultado
  ("Nada encontrado para "…"" + sugestão de conferir a grafia); catálogo ainda carregando.
- **Ações:** OK no filme abre o detalhe; na série abre a tela da série; no canal abre o player ao vivo com a
  `LiveSession` completa. Voltar: sai do campo de resultados para o teclado; do teclado, sai da busca.
- **Entradas:** ícone de lupa na Home (ao lado da engrenagem), tecla SEARCH/amarelo nas telas de lista, e o ponto
  onde a busca antiga era aberta. A busca antiga por categoria sai.

## Etapa B — detalhe de filme e série no tema Landimflix
Seguir `DESIGN.md` (tokens `lf_*`, Rajdhani/Nunito Sans, anel de foco sem escala, um só botão roxo cheio por tela).
- **Fundo:** arte horizontal do TMDB quando houver chave e `tmdb_id`; senão o próprio cartaz desfocado e escurecido.
- **Filme:** cartaz com brilho, título, linha de dados (ano • gênero • duração • nota em pílula), sinopse, botão
  principal "Assistir" ou "Continuar de 32:10" (com barra de progresso), e secundários em anel: recomeçar,
  favoritar, marcar como visto/não visto, dublado/legendado quando existir. Filme já visto mostra "Assistido" com
  ícone.
- **Série:**
  - Topo: título, dados, sinopse e o botão principal "Continuar T2 · E4" (ou "Assistir T1 · E1" / "Rever").
  - Resumo lúdico do progresso: "Você viu 7 de 10 episódios" com barra; temporada completa ganha selo "Concluída".
  - Temporadas em pílulas com a contagem ("T1 · 10/10 ✓", "T2 · 3/8").
  - Episódios em lista: número, título, duração, sinopse curta e miniatura quando o provedor mandar. Estado sempre
    com ícone + texto + cor (nunca só cor): **Assistido** (✓, linha mais apagada), **Em andamento** (barra +
    "Restam 12 min"), **A seguir** (destaque) e não visto.
  - OK longo ou MENU no episódio: marcar como visto/não visto; na pílula da temporada: marcar a temporada inteira.
  - Abre com o foco no próximo episódio a assistir, na temporada certa.
- **Grade de cartazes (Filmes/Séries e busca):** selo "✓" em título assistido e barra fina de progresso em título
  pela metade.
- **Dados:** reaproveitar `ResumeStore` (posição, vistos) — conferir o que ele já guarda antes de criar algo novo. A
  contagem por temporada e a escolha do "próximo episódio" são lógica pura com testes JVM.
- **Estados:** carregando episódios, sem sinopse, sem cartaz, erro ao buscar a série (com "Tentar de novo").

## Regras
- VOD e ao vivo continuam tocando como hoje; favoritos e "Continuar" (chaveados pela URL) não podem se perder.
- Acessibilidade: `contentDescription`, estado anunciado ("Episódio 4, assistido"), contraste dos tokens do
  `DESIGN.md`, alvo de foco ≥ 40dp.
- Sem dependências novas sem necessidade; nada de dados do provedor em código, testes, docs ou commits.

## Verificação
`ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home gradle :app:testDebugUnitTest :app:assembleDebug --offline`
Não há emulador: o comportamento na TV é validado pelo usuário.

## Status
- [x] Etapa A — busca global · [x] Etapa B — detalhes e marcação de assistidos (código e testes JVM prontos; as duas etapas faltam validar na TV)

## Retorno do usuário (TV real, v110)
1. Busca: abre a tela, mas ao digitar as primeiras letras o app fecha e volta à tela de carga. Causa (achada por
   leitura do bytecode do leanback 1.0.0, não reproduzida em aparelho): `CardAdapter.onCreateViewHolder` trocava os
   LayoutParams do cartão por `RecyclerView.LayoutParams`; o `GridLayoutManager` do leanback não sobrescreve
   `checkLayoutParams` e faz cast para `GridLayoutManager.LayoutParams` → `ClassCastException` no primeiro layout dos
   resultados. Corrigido ajustando só largura/altura dos params criados pelo inflate. O índice não era o problema.
   Além disso, exceção na thread da busca agora vira mensagem na tela ("A busca falhou" + motivo).
   Validado pelo usuário na v111: a busca funciona.
2. Atualização (v111): o app não verificava ao abrir e o botão de atualizar às vezes pedia dois cliques. Causas no
   código: a verificação automática só rodava no `SplashActivity.onCreate` (na Google TV o processo fica vivo e o app
   reabre sem passar pela abertura) e, mesmo na abertura fria, a resposta chegava depois de a Splash fechar e era
   descartada; o download só mostrava um aviso de 3 s e não impedia um segundo download. Agora o `Updater` é ligado no
   `App` (`attach`), verifica a cada volta do app para a frente (no máximo a cada 10 min), mostra o aviso na tela da
   frente (não na abertura nem no player), com Instalar / Depois / Pular esta versão (`AppSettings.skippedUpdate`), e o
   download tem janela de progresso, arquivo temporário e conferência de tamanho. Decisões puras em `UpdateLogic`
   (testes JVM). O "dois cliques" não foi reproduzido: a explicação é a mais provável pelo código.
3. Detalhes e grades de Filmes/Séries (v112): aprovados pelo usuário. Um filme aberto em "Assistir legendado" tocou
   dublado. Pela leitura, o detalhe manda a URL certa (a do item marcado LEG). Causa possível no app: o player prefere
   áudio "pt" (`PlayerController`), então um arquivo legendado com dois áudios toca em português. Corrigido com
   `EXTRA_SUBTITLED` (detalhe e série mandam; `SubtitledAudio`, puro e testado, escolhe o áudio não português e liga a
   legenda pt uma vez por arquivo). NÃO confirmado: se o arquivo do servidor só tiver áudio em português, é erro do
   provedor e o app não tem o que fazer (conferir em opções do player → áudio).
4. TV ao vivo (etapas 3 e 4): ainda sem retorno.

## Decisões fora da spec (Etapa A)
- **Séries da API não juntam dublado/legendado.** Cada `series_id` abre uma tela de episódios própria e a tela não sabe
  fundir duas séries; então "Dark" e "Dark (LEG)" aparecem como dois itens, como nas listas. Filmes e episódios da lista
  M3U juntam, como nas listas.
- **Cartaz na busca:** itens da API usam o cartaz e a nota do painel; itens só da lista M3U usam o cartaz do TMDB já em
  cache (`PosterCache`) ou a capa da lista. A busca não consulta o TMDB por título (seriam dezenas de pedidos por tecla).
- **Programa atual do canal:** só o que já está em cache (XMLTV ou `get_short_epg` já pedido); a busca não pede
  programação à rede. Sem cache, o cartão mostra a categoria.
- **Contagem:** o título da fileira mostra o total ("Filmes · 124") e, se passar de 40, "(primeiros 40)".
- **Botão "Buscar" das telas de lista** e as teclas SEARCH/amarelo abrem a busca global; o campo de busca por categoria
  foi removido (layout e código). A lógica de listas de `ChannelListActivity` não foi alterada além disso.
- **Canal aberto pela busca** toca com a sessão ao vivo completa (zapping e painel de canais iguais aos da Home), mas sem
  abrir a lista de canais por cima.
- **Buscas recentes** ficam num arquivo de preferências próprio (`search`); favoritos, "Continuar" e vistos não mudam.

## Decisões fora da spec (Etapa B)
- **Dados do TMDB intactos** (pedido do usuário: a carga de capa, sinopse, ano e nota já funciona bem): continua a mesma
  busca por título (`Tmdb.fetch`). Por isso a linha de dados mostra **ano e nota** (nota do painel até o TMDB responder);
  **gênero e duração ficaram de fora**, pois exigiriam outro pedido ao TMDB por título. A arte de fundo horizontal sai
  do campo `backdrop_path` da mesma resposta (sem pedido extra); sem ela, o fundo é o próprio cartaz muito desfocado
  (`SoftBackdrop`, desfoque de caixa sobre uma miniatura de 64×96, sem dependência nova).
- **Filme concluído passa a ser marcado como visto** (`PlayerActivity.persistResume`, uma condição a mais): até aqui só
  episódio de série era marcado, então "Assistido" no filme e o selo da grade não teriam como existir. Filmes já
  assistidos antes desta versão não aparecem como vistos até serem vistos de novo, ou marcados à mão.
- **Em andamento vence "visto":** episódio com ponto salvo conta como em andamento mesmo já tendo sido marcado antes
  (quem revê); só volta a contar como visto ao terminar de novo. Assim o botão "Continuar" e a contagem não se contradizem.
- **Próximo episódio** = o em andamento (o mexido por último); senão o seguinte ao último visto (não o primeiro não
  visto: quem pulou o E1 e viu o E2 vai para o E3); sem nada visto, o primeiro; tudo visto: "Rever T1 · E1".
- **Selo de série na grade** vem de um resumo guardado em arquivo novo (`series_summary`) que a tela da série atualiza a
  cada abertura/volta do player (a lista não tem os episódios na mão: na API eles só chegam ao abrir a série). Série que
  nunca foi aberta não mostra selo. Filme usa `ResumeStore` direto.
- **Série da API busca os episódios dentro da `SeriesActivity`** (antes a lista buscava e só então abria): o estado de
  carregando e o erro com "Tentar de novo" ficam na própria tela. `SeriesLauncher` (código sem uso) virou o ponto único
  de abertura, usado pelas listas e pela busca.
- **Dados do episódio** (título, sinopse, duração, miniatura) vêm de `get_series_info`, que antes eram descartados; a
  sinopse é cortada em 280 caracteres no Intent (limite de tamanho). Série da lista M3U não tem nada disso e a linha do
  episódio funciona sem.
- **Marcação em lote** (temporada) é uma única escrita (`ResumeStore.setWatched`); marcar como visto também apaga o ponto
  salvo e a entrada em "Continuar". Chaves e formatos já salvos (posição, recentes, vistos, favoritos) não mudaram.
- **Layout da série em duas colunas** (dados e botão à esquerda, temporadas e episódios à direita) em vez de uma coluna:
  na tela lógica de ~960×540dp uma coluna deixaria só 3 episódios visíveis.
