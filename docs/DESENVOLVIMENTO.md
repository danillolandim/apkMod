# Notas de desenvolvimento — Player Mod

Memória do projeto: o que é, como funciona e por quê. Serve para retomar o
contexto em qualquer sessão futura sem perder o fio.

## O que é

App de IPTV/M3U genérico para Android TV (estilo Apple TV), focado na TCL C6K
(Google TV). O usuário fornece a URL da lista M3U; o app carrega, organiza e
reproduz. É um player genérico (categoria VLC/Kodi), **não** um cliente de um
serviço específico.

### Escopo e limites (decisão importante)
- Não descompilar/repackaging de APK protegido de terceiros, nem remover travas.
- Não usar chave de API/engine proprietária de painel de terceiros.
- O usuário informa a URL M3U; o app é neutro quanto à fonte.

## Build e distribuição
- GitHub Actions (`.github/workflows/build.yml`) compila o APK e publica na
  release "latest": `player-mod.apk`, `version.txt` (= GITHUB_RUN_NUMBER) e
  `changelog.txt` (cópia de `CHANGELOG.md`).
- Atualização dentro do app (`Updater.kt`) compara `version.txt` e mostra o
  bloco "## Esta versão" do changelog.
- Keystore de debug fixo (`app/debug.keystore`) para assinatura estável
  (atualização sem conflito de assinatura).

## Arquitetura (arquivos-chave)
- `SplashActivity` — abertura: anima enquanto a lista é preparada; vai pra Home.
- `App.onCreate` — pré-carrega a lista do cache no `PlaylistHolder` assim que o
  processo sobe (abrir seções fica instantâneo).
- `MainActivity` — Home: TV ao vivo / Filmes / Séries + engrenagem (Configurações).
- `playlist/ChannelListActivity` — tela principal: seções, subcategorias à
  esquerda, conteúdo à direita. Filtro em 2º plano com `filterGen` (cancela
  filtros antigos). Partição por seção (`secLists`) para filtrar rápido.
- `playlist/M3uParser` — parser de #EXTINF. `isLive` por URL (/movie/, /series/,
  extensão) e não só pela duração -1.
- `playlist/SeriesGrouper` — agrupa episódios (SxxExx/1x02/T01E02) em séries.
- `playlist/LiveGroups` — categoriza canais ao vivo por palavra-chave no nome.
- `playlist/ChannelQuality` — detecta sufixo HD/FHD/SD/4K para juntar canais.
- `playlist/NewItems` — detecta "Novidades" comparando listas entre atualizações.
- `meta/Tmdb` — busca TMDB (gênero, ano, nota, cartaz, sinopse).
- `meta/VodIndex` — índice persistente (gênero+ano) por título, em disco
  (`vod_index.json`); `keyOf` memoizado (cleanTitle é caro).
- `PlayerActivity` / `PlayerController` — ExoPlayer/Media3 (DoH via `net/Net`,
  legendas externas, qualidade do canal por variantes, PiP, aspecto, retry).

## Como as categorias funcionam
- **TV ao vivo**: se a lista tem subgrupos reais (>1), usa-os; senão deduz o
  tema do nome (`LiveGroups`). Canais HD/FHD/SD do mesmo nome são juntados
  (`ChannelQuality`); a qualidade vira opção no player.
- **Filmes**: a lista só traz "FILMES (BR)" (sem gênero/ano). Gênero e ano vêm
  do TMDB, indexados em 2º plano (`VodIndex`) e salvos em disco. Subcategorias:
  Tudo, Lançamentos (por ano, mais novo primeiro), Novidades, gêneros.
  Gênero/Lançamentos pré-calculados (`filmesByGenre`/`filmesLancamentos`) para
  seleção instantânea.
- **Séries**: agrupadas por SeriesGrouper; também indexadas (uma busca por
  série) para ter ano em "Lançamentos".

## Performance (o que já foi resolvido)
- Lista de 47k itens: adapters reciclados, filtro em 2º plano, cache instantâneo.
- Indexação TMDB: executor próprio, pausa durante a reprodução, contagem de
  gênero incremental, progresso a cada 200.
- Filtro rápido: partição por seção + atalhos pré-calculados de Filmes +
  memoização de `cleanTitle`.

## Pendências / próximos passos
- EPG (agora/próximo) usando o XMLTV do usuário.
- Ajuste fino das regras de `LiveGroups` conforme a lista real.
- Avaliar "Novidades" e Lançamentos conforme os dados do provedor.
