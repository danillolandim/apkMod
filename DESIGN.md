# DESIGN.md — Landimflix

Perfil visual de referência: Figma Community "Gerenciador de Filmes" (nó 3:376),
https://www.figma.com/design/k8bpT4yyYaGwsv5k18jAee/Gerenciador-de-Filmes--Community-?node-id=3-376

> Valores **medidos nos pixels dos screenshots** (Listagem e Detalhe do Filme). As variáveis e os
> estilos de texto do Figma não puderam ser lidos (limite de uso do plano Starter). Onde está
> "estimado" ou "a confirmar", conferir quando o Figma liberar ou com o usuário.

## Marca
- Nome exibido: **Landimflix** (o Figma usa "ab filmes").
- Logo: **recebida** (símbolo "L" facetado em roxo + "LAND​IM" branco e "FLIX" roxo, sobre grafite).
  As fontes originais são JPG com o fundo desenhado; recortei por saturação/brilho. Se o usuário tiver
  SVG ou PNG com transparência real, trocar: as bordas ficam mais nítidas.
- Arquivos gerados em `res/drawable-nodpi/`: `logo_symbol.png`, `logo_full.png`, `logo_short.png`
  (L + FLIX, composta por mim a partir do recorte), `ic_launcher_fg.png` (camada adaptativa),
  `app_banner.png` (TV, 1280×720). Ícone adaptativo em `drawable-v26/ic_launcher.xml`.
- Feito: nome "Landimflix" (`app_name` e títulos), ícone e banner. `applicationId` intacto.
- **Não mudar `applicationId`** (`com.seupainel.player`): quebra a atualização por cima e perde dados.
  Muda só `app_name`, rótulo do launcher, banner e ícone.

## Cores (medidas)
| Token | Hex | Uso | Contraste sobre o fundo |
|---|---|---|---|
| `lf_bg` | `#0F0F1A` | fundo de tela e navbar | — |
| `lf_surface` | `#131320` | cards de comentário, painéis, divisor da navbar | — |
| `lf_surface_hi` | `#1A1B2D` | aba ativa (pílula), campos em foco | — |
| `lf_primary` | `#892CCD` | botão principal, tag "você" | texto branco ≈ 6,3:1 |
| `lf_accent` | `#A85FDD` | estrelas, aba ativa (texto), foco | ≈ 4,8:1 |
| `lf_logo` | `#A13CD2` | logo (degradê roxo → magenta) | — |
| `lf_text` | `#E4E5EC` | títulos | alto |
| `lf_text_2` | `#B5B6C9` | texto de apoio (categoria, ano, sinopse) | alto |
| `lf_text_3` | `#7A7B9F` | texto discreto, aba inativa, placeholder | ≈ 4,6:1 (no limite; não usar menor que 18sp) |
- Selo de nota: pílula escura translúcida sobre o cartaz, "4,5 / 5 ★" (estrela branca na listagem,
  roxa no detalhe).
- Cartaz: degradê escuro de baixo para cima sob título e "Gênero • Ano".
- Detalhe: capa com brilho (glow) e o próprio cartaz desfocado e escurecido como fundo da tela.
- Contrastes calculados por script (WCAG): texto discreto 4,68:1 no fundo e 4,52:1 no `lf_surface`
  (passa por pouco: só para texto de apoio, nunca para ações), acento 4,84:1, branco sobre `lf_primary` 6,35:1.

## Tipografia (confirmada no guia de estilo do Figma)
- **Display** (títulos grandes, ex.: "Explorar"): Rammetto One. **Títulos** (cartões, rótulos, abas):
  Rajdhani (Medium/SemiBold/Bold). **Corpo**: Nunito Sans (Regular/Bold). Todas Google Fonts (OFL),
  embutidas em `res/font` (a TV é offline-first). Licenças em `docs/LICENCAS-FONTES.md`.
- Rajdhani é mais estreita que a fonte do sistema: pede ~1–2sp a mais no mesmo papel.

## Medidas do desenho (desktop 1366×768, referência de proporção)
- Navbar 80 · margem lateral 88 · campo e botão 48 de altura · avatar 48.
- Card de filme 280×360, espaço de 24, 4 por linha. Capa do detalhe 381×490.
- Raio dos cards ≈ 16 (estimado) · botões e campos ≈ 8–10 (estimado) · selo em pílula.
- Ícones: família Phosphor (FilmSlate, Popcorn, Star, MagnifyingGlass, SignOut).

## Tradução para Android TV (interface a 3 m, controle remoto)
- **Tamanho lógico da tela: ~960×540dp (estimado)**, deduzido do layout antigo (grade de 6 colunas
  com ~116dp e cartões de 240dp); a TV 4K parece usar densidade 640dpi. **Confirmar por print da TV.**
  Por isso: larguras por peso (nunca 300dp fixo para 3 itens), textos de corpo 12–16sp, títulos
  de seção 20–38sp, alvo de foco ≥ 40dp, margem de segurança ~28–56dp.
- Sem hover: **foco é tudo**. Foco = **anel roxo fino (`lf_accent`, 2–3dp) sobre fundo escuro, sem
  preenchimento**, para o texto nunca ficar escondido (o roxo cheio com texto branco foi descartado:
  feedback do usuário). **Roxo cheio só no botão de ação principal** (um por tela).
  Item ativo de lista = fundo `lf_surface_hi` + barra roxa + texto claro. **Nada de escala no foco** (o aumento é recortado pelo container e corta a borda: problema clássico do Android);
  o anel é desenhado dentro do próprio botão e todo container ao redor fica com `clipChildren=false` e folga.
- Grade: 6 por linha (rolagem ajustada), cartão ~109dp × 172dp com cartaz inteiro, degradê embaixo,
  selo de nota no canto e título + categoria sobre o cartaz.
- Estados em toda tela: padrão, carregando, vazio (ícone + frase), erro, foco.

## Mapa Figma → app
| Figma | App |
|---|---|
| Navbar com abas (Explorar / Meus filmes) | Barra superior com abas: TV ao vivo · Filmes · Séries · Favoritos (também na Home) |
| (sem equivalente) | **Home simples**: saudação + 3 botões grandes (TV ao vivo, Filmes, Séries) com ícone e contagem. Home "Stage" com fileiras fica como ideia futura (ver NuvioTV no plano) |
| Explorar (grade + busca) | `ChannelListActivity` (grade de cartazes, busca, menu de categorias) |
| Meus filmes | "▶ Continuar" e Favoritos |
| Detalhe do Filme | `DetailActivity` e `SeriesActivity` (fundo desfocado, capa, sinopse, botão "Assistir" roxo) |
| Login / Cadastro | Tela de **servidor + usuário + senha** (login estilo IPTV Smarters, planejado) |
| Sem resultados / Vazio | Estados vazios com ícone FilmSlate/Popcorn |
| Avaliar filme, comentários | Não se aplica (sem avaliações de usuários) |
- Sem referência no Figma (derivar dos mesmos tokens): TV ao vivo (lista com EPG), Player,
  Configurações, Splash.

## Pendências
1. (feito) Logo. 2. Raios exatos (Figma). 3. Conferir a licença do arquivo Community
(muitos pedem crédito ao autor). 4. Validar foco e leitura na TV real: não há emulador neste Mac.
