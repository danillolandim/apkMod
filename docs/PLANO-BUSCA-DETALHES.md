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
- [ ] Etapa A — busca global · [ ] Etapa B — detalhes e marcação de assistidos
