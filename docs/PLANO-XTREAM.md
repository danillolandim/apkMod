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

## Requisito do usuário (11/11/2026)
Aclamadas e Populares são úteis ("muita coisa boa sem procurar") e NÃO podem sumir no modo API.
A API só traz nota arredondada, sem votos. Plano: voltar Aclamadas/Populares/Lançamentos/gêneros
pelo `tmdb_id` exato — listas do TMDB (`top_rated`, `popular`, `discover` por gênero; até 500
páginas cada) casadas com o catálogo por id, em vez de uma consulta por filme. Confirmar
endpoints/limites com `ctx7` antes de implementar. Séries: usar `get_series_categories` se vier
por plataforma (pendente: o usuário ainda não colou as categorias de séries).

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

## Login estilo IPTV Smarters (pedido do usuário, 11/11/2026)
- Configurações ganham 3 campos: servidor (DNS que o provedor fornece), usuário e senha. Têm
  prioridade sobre a conta derivada da URL da lista; a URL do M3U passa a ser montada
  (`get.php?username=…&password=…&type=m3u_plus&output=m3u8`) a partir deles.
- Rota alternativa: tentar o servidor informado e, se falhar, o da URL da lista (e vice-versa).
  Atenção: a URL de reprodução usa o host da conta escolhida; Favoritos e "Continuar assistindo"
  são chaveados pela URL, então trocar de host pode "esquecer" o que já estava salvo.
- Credenciais ficam só nas preferências do app (como a URL da lista hoje). Nunca no repositório.
- Hostname do provedor NÃO entra em código, testes, docs ou commits.

## DNS bloqueado
- `XtreamApi` e o download do M3U usam HttpURLConnection (DNS do sistema); o player usa
  `Net.client` (OkHttp + DoH). Se a API falhar com UnknownHostException, trocar `XtreamApi` para
  `Net.client` resolve sem precisar de outro DNS.

## Status (11/11/2026)
- Etapas 1-3 feitas e validadas pelo usuário em Filmes (API ✓, categorias e cartaz do painel).
- Causa do "API indisponível": UnknownHostException (DNS do sistema da TV). API passou a usar `Net.client`.
- Feito, aguardando teste no aparelho: Séries (`get_series` + `get_series_info` ao abrir), TV ao vivo
  (`get_live_streams`, URL copiada do formato do M3U), Aclamadas/Populares via `TmdbLists`
  (top_rated + popular casados por `tmdb_id`, cache 7 dias).
- Pendente: login Smarters (servidor/usuário/senha); download do M3U ainda usa HttpURLConnection
  (DNS do sistema) e pode falhar em silêncio; gêneros/Lançamentos por ano em modo API (os dados
  das listas TMDB já trazem `genre_ids` e ano); `get_vod_info` para detalhes.

## Próxima tarefa: visual + nome "Landimflix" (pedido do usuário, 11/11/2026)
- Referência visual: Figma "Gerenciador de Filmes (Community)", nó 3-376
  (https://www.figma.com/design/k8bpT4yyYaGwsv5k18jAee/Gerenciador-de-Filmes--Community-?node-id=3-376).
- Renomear o app para **Landimflix** com a logo que o usuário vai enviar (ainda não recebida).
- NÃO mudar o `applicationId` (`com.seupainel.player`): trocar quebraria a atualização por cima
  do app instalado e perderia favoritos/"Continuar". Muda só o nome exibido (`app_name`, rótulo do
  launcher, banner da Android TV) e o ícone.
- Fazer em sessão nova: ler o Figma via MCP (`create_session` antes de tudo), conferir tokens
  (cores, tipografia, raios) e aplicar nos layouts XML sem quebrar foco por controle remoto/D-pad.
- Antes de começar, pedir a logo (SVG ou PNG com fundo transparente, de preferência 1024px) e
  confirmar o escopo (só Home + listas, ou o app todo).
