# Plano — lista rápida via API Xtream

## Problema
- Indexar ~47 mil itens com uma busca TMDB por título leva de 30 min a horas.
- As listas aparecem na ordem do provedor; Filme × Série é adivinhado pelo nome do grupo.
- Os botões Filmes/Séries/TV só passam um filtro: a tela carrega a playlist inteira.

## Descoberta (painel do usuário)
`player_api.php` está liberada. `get_vod_streams` traz, por filme: `stream_id`, `tmdb_id`
exato, `stream_icon` (pôster TMDB), `rating`, `added` (unix), `category_id`,
`container_extension`. Não traz gênero. Mais novo visto: 28/06/2025.

## Etapas (um commit por etapa)
1. `XtreamApi`: conta a partir da URL M3U, URLs da API, parse em streaming, testes de URL.
2. Filmes pela API: cache em disco, `Channel` ganha `added/rating/tmdbId`, fallback para M3U.
3. Ordem padrão por `added` (mais novo primeiro); cartaz e nota vêm da API (sem busca TMDB
   por célula); "Novidades" por `added`.
4. Séries por `get_series` (séries já agrupadas); episódios via `get_series_info` ao abrir.
5. Gêneros por `tmdb_id` (`/movie/{id}`, sem busca fuzzy), só sob demanda e em segundo plano.
6. (Avaliar depois) TV ao vivo por `get_live_streams`.

## Riscos
- Favoritos e "Continuar assistindo" usam a URL do item como chave: a URL montada pela API
  precisa ser idêntica à do M3U (esquema/host/porta). Calibrar pelo prefixo `/movie/` do M3U.
- Se a API falhar ou vier vazia, cair no caminho M3U atual sem perder a lista.
- Credenciais nunca entram em log, nota ou commit.

## Depois do teste (ideias, em ordem)
- Trocar o HttpURLConnection do `XtreamApi` por OkHttp (já é dependência) e usar parser testável
  no JVM (Moshi ou kotlinx.serialization). Verificar versões com `ctx7` antes de adotar.
- `get_vod_info`/`get_series_info` (gênero, sinopse, tmdb_id do provedor) no lugar do TMDB.
- `get_short_epg` por canal em foco no lugar do XMLTV completo; `user_info` (vencimento,
  `max_connections`); catch-up via `tv_archive` (confirmar no painel).
- WorkManager para atualizar o catálogo em segundo plano.
- Só se medir lentidão: Room + FTS + Paging 3; grade em RecyclerView/Leanback; coroutines/Flow.
