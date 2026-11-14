# Plano: Landimflix no celular, com botão para espelhar na TV

## Pedido

Pediram um app como o Landimflix que rode num celular Android e tenha um botão para espelhar na TV. Muita gente
não tem Android TV, mas tem smart TV comum (Samsung, LG) ou Chromecast, que recebem espelhamento de tela.

## Decisões (14/11/2026, Danillo)

- **Mesmo app.** Um só APK e um só código: no celular abre a interface de toque, na TV nada muda.
- **Espelhar a tela** é a primeira versão do botão. Chromecast "de verdade" (a TV tocando sozinha) fica para
  depois, só se o teste com os canais reais mostrar que funciona (formato MPEG-TS e limite de uma conexão do
  provedor são riscos conhecidos).
- TVs de destino: smart TV comum e Chromecast.

## Regra de ouro

**Nada pode mudar na TV.** Toda mudança de comportamento fica atrás de `Device.isTv(context)`. Layouts novos
entram por qualificador de recurso ou por ajuste em código só no celular. Antes e depois de cada etapa:
`gradle :app:testDebugUnitTest :app:assembleDebug --offline` (JDK 21, ver `docs/DESENVOLVIMENTO.md`).

## Estado atual (o que já ajuda e o que atrapalha)

- O manifesto já tem `LAUNCHER` além de `LEANBACK_LAUNCHER`, e `touchscreen`/`leanback` não são obrigatórios:
  o app já instala e abre em celular.
- Todas as telas estão travadas em `landscape`. **Fica assim no celular nesta versão**: é o formato do vídeo e o
  que espelha melhor na TV. Retrato fica fora do escopo.
- Os layouts foram feitos para 960×540dp. Celular em paisagem tem cerca de 780–900 × 360–410dp: a altura é o
  problema. Cada tela precisa caber em 360dp de altura sem cortar nada (rolagem onde for preciso).
- Comportamentos que só existem por tecla ou por foco e não acontecem com toque:
  - Player ao vivo: trocar de canal (setas/CH±), abrir a lista (OK), opções (MENU/OK longo), números.
    `playerView.useController` fica desligado no ao vivo, então hoje o toque não faz nada.
  - `LiveListPanel`: a categoria só mostra seus canais quando ganha **foco** (`onItemSelected`); com toque,
    clicar na categoria não troca a lista. A pré-sintonia por foco também não existe no toque (não precisa).
  - `SeriesActivity` e `ChannelListActivity`: ações em OK longo / MENU por tecla (marcar visto, favoritar).
  - `SettingsActivity`: seção mostra as opções ao ganhar foco.
  - `SearchActivity`: teclado próprio na tela (funciona com toque, mas no celular o natural é o teclado do sistema).

## Etapas (um commit por etapa)

### 1. `Device` e botão "Transmitir"

- `Device.kt`: `isTv(context)` = `UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION` **ou**
  `hasSystemFeature(FEATURE_LEANBACK)`. Resultado guardado em memória.
- `CastScreen.kt` com a parte pura testável: `candidates(): List<IntentSpec>` na ordem de tentativa e
  `firstAvailable(candidates, canResolve)`. Ordem:
  1. `android.settings.CAST_SETTINGS` (painel Transmitir do Android; em Samsung/Xiaomi abre o equivalente do
     fabricante);
  2. `android.settings.WIFI_DISPLAY_SETTINGS`;
  3. Samsung Smart View: ação `com.samsung.android.smartmirroring.CAST_SETTINGS`;
  4. `Settings.ACTION_WIRELESS_SETTINGS` como último recurso.
  Sem nenhum disponível: caixa de diálogo dizendo para abrir "Transmitir"/"Smart View" nos atalhos rápidos.
  No manifesto, `<queries>` para as ações acima (Android 11+).
- Botão "Transmitir" (ícone novo `ic_cast`, vetor próprio, no estilo dos outros ícones) na Home, ao lado da lupa,
  **só no celular**. Na primeira vez mostra uma caixa curta: "A TV e o celular precisam estar no mesmo Wi‑Fi.
  Escolha a TV na próxima tela e volte para o app." (guardar em `AppSettings` que já foi vista).
- Testes JVM para `firstAvailable`.

### 2. Player com toque (o coração da versão)

Só no celular:
- Ao vivo: um toque na tela mostra por 5 s uma barra de controles sobreposta (nova view em `activity_player.xml`,
  `visibility=gone`, nunca exibida na TV) com: **Canais** (abre a lista), **◀ canal** e **canal ▶** (zapStep),
  **Opções** (dock) e **Transmitir**. Alvos de toque de pelo menos 48dp. Tocar fora esconde.
  Deslizar para cima/baixo na metade direita da tela = próximo/anterior canal (opcional; só se ficar simples).
- Filmes/séries: o controlador do PlayerView já funciona com toque; acrescentar o botão **Transmitir** e conferir
  que o dock de opções abre por um botão visível.
- `LiveListPanel`: clique na categoria chama `showGroup(position)` (hoje só o foco chama). Na TV o clique
  continua levando o foco aos canais.
- A faixa do canal mostra dicas de tecla ("OK: lista de canais • MENU: opções"): no celular trocar por
  "Toque na tela para ver os controles".
- Durante a reprodução no celular: `FLAG_KEEP_SCREEN_ON` (conferir se já existe) e modo imersivo
  (esconder barras do sistema), para a imagem espelhada ocupar a TV inteira.

### 3. Telas cabendo no celular

Não existe qualificador de recurso "menor que", então a separação é pela **altura disponível**: a TV tem 540dp
(ou mais) e o celular em paisagem tem 360–410dp.

- As medidas críticas de cada layout saem do XML para `dimens`. O valor de hoje vai, idêntico, para
  `values-h500dp/dimens.xml` (é o que a TV e os tablets carregam); `values/dimens.xml` recebe o valor do celular.
  Assim a TV continua lendo exatamente os números atuais. Não usar `-television`: TV box barata nem sempre se
  declara como televisão.
- Não duplicar layouts inteiros: só `dimens` e, onde não der, ajuste em código atrás de `Device.isTv`.
- Para cada tela, somar as alturas fixas e conferir contra 360dp; onde não couber, rolagem.
  Telas: Home (`activity_main`), Login, Lista (`activity_channels`), Busca, Detalhes, Série, Configurações, Splash.
- Itens de lista com altura mínima de 48dp no toque.
- Margens laterais de TV (até 88dp) caem para 16–24dp no celular.

### 4. Ações que só existem por tecla

- `SeriesActivity` / `ChannelListActivity`: garantir `setOnLongClickListener`/`setOnItemLongClickListener`
  equivalentes ao OK longo e ao MENU (marcar visto, favoritar, opções).
- `SettingsActivity`: tocar na seção mostra as opções dela.
- `SearchActivity` no celular: campo de texto de verdade com o teclado do sistema e o microfone; a grade de
  teclas fica escondida. Na TV continua como está.
- Textos de ajuda que citam teclas ("Segure OK…") ganham versão de toque ("Toque e segure…") no celular.

### 5. Fechamento

- `CHANGELOG.md` (seção "Esta versão") em português, em linguagem de usuário.
- `docs/PLANO-CELULAR.md`: marcar o que foi feito e o que ficou pendente.
- Lista do que o Danillo precisa testar no celular e na TV (regressão).

## Fora do escopo desta versão

- Retrato.
- Chromecast "de verdade" (Cast SDK) e DLNA.
- Publicação em loja.

## Riscos e como saber

- **Não há emulador nem celular ligado ao Mac**: nada de visual é verificado antes do Danillo instalar. Cada
  etapa precisa compilar e passar nos testes; o que for lógica (escolha do intent, detecção) tem teste JVM.
- O painel de espelhamento varia por fabricante; por isso a lista de tentativas e a caixa de ajuda.
- Conteúdo com proteção não existe aqui (streams abertos), então o espelhamento não deve dar tela preta; se der
  em algum aparelho, é limitação do aparelho.
