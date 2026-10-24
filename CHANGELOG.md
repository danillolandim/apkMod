# Novidades

## Esta versão
- Carregamento: zapping do ao vivo começa mais rápido (primeiro quadro em ~0,6s), splash mais ágil na abertura, e a indexação do TMDB prioriza a seção que você abriu (os cartazes/gêneros do que está na tela preenchem primeiro).
- Desempenho: a reclassificação Filme/Série roda no máximo a cada 8s (antes, a cada 200 itens indexados) e o mapeamento de grupo→seção é memoizado — menos CPU/GC em 2º plano durante a indexação, sem travar a navegação.
- Classificação Filme × Série pelo TMDB: o tipo (filme/série) do TMDB passa a mandar na hora de separar as seções, corrigindo itens que a lista pôs no grupo errado — nos dois sentidos. Vai se ajustando conforme o catálogo é indexado.
- Autoplay de séries: ao terminar um episódio, emenda sozinho no próximo (mesma versão dublada/legendada), mostrando "A seguir…".
- "▶ Continuar" em Filmes e Séries: fileira no topo com o que você começou a assistir, pra retomar de onde parou com um clique. Some da fileira quando o conteúdo termina.
- Guia de programação (EPG) no ao vivo: lê o XMLTV da URL de EPG (Configurações; aceita .xml e .xml.gz) e mostra "agora / a seguir" na barra do canal e o programa atual sob cada canal na lista. Carrega em 2º plano; some sozinho se a lista não tiver EPG.
- Configurações: "Pulo das setas" — escolha quanto a seta avança/volta no filme/série (10s, 15s, 30s ou 1min). Padrão 10s.
- Player: setas = avançar/voltar 10s (e mostram posição/duração); o OK abre o dock de ações (qualidade, áudio, legenda…) tanto no ao vivo quanto no filme/série. A barra de tempo não captura mais as setas, então navegar até a qualidade não "esfrega" mais o vídeo.
- Corrigido filme aparecendo na lista de Séries: só movemos um item de Filmes pra Séries quando o nome tem um marcador forte de episódio (S01E02/T01E02); o formato frouxo "1x02" casava com números em nomes de filme.
- Nova categoria "Populares" (em alta) em Filmes e Séries: as mais vistas pelo nº de votos do TMDB, da mais popular pra menos. E "Aclamadas" agora exige menos votos, então mostra mais títulos.
- Dock de ações no player (estilo dock do Mac): a engrenagem (ou OK no ao vivo) abre uma barra que sobe de baixo com Qualidade, Áudio, Legenda, Aspecto, Velocidade, Timer e PiP — cada botão alterna o valor na hora.
- Dublado + Legendado juntos: filme "X" e "X (LEG)" viram um cartaz só; no detalhe aparecem os botões "Dublado" e "Legendado".
- Série: tela redesenhada estilo Apple TV — alternar Dublado/Legendado, abas de temporada (1ª, 2ª…) e a lista de episódios abaixo; versões dub/leg da mesma série agora são a mesma série.
- Zapping mais rápido: conexões mantidas vivas (reuso ao trocar de canal no mesmo servidor), DNS da lista pré-resolvido ao carregar, e cache do DNS-over-HTTPS (não re-consulta a cada troca).
- Performance: cache de cartazes em disco (300 MB, persistente) e memória maior + RGB_565 — a grade carrega muito mais rápido e não re-baixa pôster.
- Performance: indexação do TMDB mais rápida (8 tarefas), ABR/buffer afinados para rede com fio (sobe pra alta qualidade antes, sem rebuffer) e opção "Cabo de rede" nas Configurações.
- Visual Apple TV no app inteiro: Configurações (campos, listas, caixas), menus/diálogos e a tela de carregamento agora seguem o mesmo estilo escuro com destaque azul.
- Menus no estilo do app (escuro, cantos arredondados, destaque azul) — antes usavam o diálogo cinza padrão do Android.
- Botão de qualidade no player ao vivo: mostra a atual e abre um menu suspenso compacto ali mesmo.
- Nova categoria "Aclamadas" (bem avaliadas no TMDB, com votos suficientes) em Filmes e Séries, ordenada pela nota.
- Séries agora têm categorias de gênero (Ação, Comédia, Terror…) como os Filmes, além de Tudo/Lançamentos/A-Z.
- Cartaz do filme/série é o mesmo no grid e na tela de detalhes (pôster do TMDB), em vez da capa trocada/antiga da lista.
- Menu do player compacto: uma linha por tipo (Qualidade, Áudio, Legenda…) mostrando o valor atual; cada uma abre um submenu curto.
- Menu único no player: a engrenagem na tela (ou OK no ao vivo) abre todas as opções do app.
- No player ao vivo, o botão OK abre esse menu direto, sem depender da tecla ≡.
- Canais ao vivo juntados por qualidade: "Globo HD/FHD/SD" vira um só; a troca de qualidade fica no player (OK ou ≡).
- Seleção de categoria instantânea em Filmes e Séries (gênero e lançamentos pré-calculados em 2º plano).
- "Lançamentos" por ANO de lançamento, do mais novo pro mais antigo — em Filmes e Séries.
- TV ao vivo agrupada por tema (Esportes, Notícias, Documentários, Infantil…); a lista troca ao mover o foco, sem clicar.
- Tela de abertura (splash) + lista pré-carregada na memória: abrir cada seção é instantâneo.
- Categoria "Novidades": compara a lista a cada atualização e mostra o que entrou de novo.

## Anteriores
- Filtro de gênero via TMDB, indexando em 2º plano e salvando em disco (só busca o que é novo).
- Episódios de série caindo em "Filmes" agora voltam para Séries (detecção pelo nome).
- Diagnóstico do item (tecla INFO): mostra a linha crua da lista (#EXTINF).
- Filmes e séries tocam como VOD (barra de avançar/retroceder, "continuar de onde parou"), não como "ao vivo".
- DNS-over-HTTPS: resolve domínios que a TV bloqueia (ex.: .sbs).
- Barra de navegação por seções; séries por temporada/episódio; pôster, nota e sinopse do TMDB.
- Estética Apple TV (foco branco, sem estourar); papel de parede; atualização dentro do app (sem Downloader).
