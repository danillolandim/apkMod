# Plano — Configurações simples e no tema Landimflix

Pedido do usuário em 13/11/2026 (v115): "pensando no menu e no que já temos no app, simplifique o menu
configurações e deixe com a cara do restante do app". Branch: `feat/tv-ao-vivo`.

## Problema
`SettingsActivity` + `activity_settings.xml` são uma rolagem única com ~25 controles no visual antigo (fundo
`#101418`, rótulos azuis `#3DA9FC`, `Spinner`, `CheckBox`, `Button` padrão, botão "Salvar"). Boa parte virou
desnecessária para o uso normal: o login por usuário e senha configura a lista, a programação vem da conta, a chave do
TMDB pode vir de fábrica, o app já salva sozinho ao sair.

## Resultado esperado
Uma tela no padrão do `DESIGN.md` (tokens `lf_*`, Rajdhani nos títulos e rótulos, Nunito Sans no corpo, anel de foco
fino sem escala, tela lógica ~960×540dp, margem segura), em **duas colunas**:
- **Esquerda (~30%)**: título "Configurações" e a lista de seções. Seção ativa = fundo `lf_surface_hi` + barra roxa +
  texto claro (padrão de item ativo do `DESIGN.md`; ver como `ChannelListActivity`/`item_group_bg` fazem).
- **Direita**: as linhas da seção em foco, com rolagem. Mover o foco entre seções troca o conteúdo da direita na hora
  (sem precisar de OK); → entra nas linhas; ← na linha volta para a seção; Voltar: da direita vai para a seção, da
  esquerda sai da tela.

Cada linha: rótulo (Rajdhani semibold, `lf_text`), descrição curta opcional (Nunito, `lf_text_2`, 12–13sp) e o valor
atual à direita (`lf_accent`). Altura ≥ 48dp, fundo `card_bg`/anel de foco já usados no app. Tipos de linha:
- **Escolha**: OK abre um diálogo com as opções (lista de escolha única, tema `Theme.Player.Dialog`), marca a atual.
- **Liga/desliga**: OK alterna; o valor mostra "Ligado"/"Desligado" (texto, não só cor).
- **Texto**: OK abre um diálogo com um campo (`TvEditText`), botões "Salvar" e "Cancelar" e, se houver valor, "Apagar".
  O valor na linha aparece resumido (URL: só o host; chave: "Definida"/"De fábrica"/"Não definida"). **Nunca mostrar
  usuário, senha, chave ou URL completa da lista na linha.**
- **Ação**: OK executa (abrir login, testar, verificar atualização, recarregar, limpar cache).
- **Informação**: não focável, texto de apoio.

**Tudo é gravado na hora** em que muda (sem botão "Salvar", sem depender do `onPause`).

## Seções e linhas
Ordem fixa. Entre parênteses, de onde vem o valor.

1. **Conta**
   - Ação "Conta" — valor: "Conectada" se `store.playlistUrl` ou `store.configUrl` existir, senão "Não configurada";
     abre `LoginActivity` (como o botão atual).
   - Ação "Testar conexão" — mesmo teste do botão "Testar API do provedor" (mover o código atual; a linha mostra
     "Testando…" enquanto roda; resultado no mesmo diálogo).
   - Informação — o texto de `statusText()` atual (última atualização e itens em cache).
2. **Reprodução**
   - Escolha "Áudio preferido" (`app.audioLanguage`, `AppSettings.LANG_OPTIONS`).
   - Escolha "Legenda preferida" (`app.subtitleLanguage`, `LANG_OPTIONS`).
   - Escolha "Tamanho da legenda" (`app.subtitleSizeSp`, `SUBTITLE_SIZES`).
   - Escolha "Pulo das setas" (`app.seekStepSec`, `SEEK_STEPS`).
   - Liga/desliga "Continuar de onde parou" (`app.resumeEnabled`).
3. **Aparência**
   - Escolha "Papel de parede" (`app.wallpaper`, `AppSettings.WALLPAPERS`). Ao escolher "Imagem personalizada (URL)",
     abrir em seguida o diálogo de texto da URL (`app.wallpaperUrl`).
   - Texto "Imagem personalizada" (`app.wallpaperUrl`) — só aparece quando o papel de parede é o personalizado.
4. **Atualização**
   - Informação "Versão instalada: `BuildConfig.VERSION_NAME`".
   - Liga/desliga "Avisar quando houver versão nova" (`app.autoUpdate`).
   - Ação "Verificar agora" (`Updater.check(this, silent = false)`, com o toast "Verificando…").
5. **Avançado** — descrição da seção: "Só mexa aqui se algo não estiver funcionando."
   - Escolha "Formato da imagem" (`app.aspectIndex`, `PlayerController.ASPECTS`).
   - Escolha "Velocidade da internet" (`app.connectionMbps`, `CONNECTION_OPTIONS`).
   - Liga/desliga "Sempre na qualidade máxima" (`app.forceMaxQuality`).
   - Liga/desliga "Reconectar sozinho quando falhar" (`app.autoRetry`).
   - Escolha "Atualizar o catálogo" (`store.refreshHours`): "A cada 6 horas" 6, "A cada 12 horas" 12, "Uma vez por
     dia" 24, "Só quando eu pedir" 0. Valor salvo fora dessas opções aparece como "A cada N horas" e é mantido até o
     usuário escolher outra.
   - Liga/desliga "Catálogo pela conta (API)" (`app.useXtream`) — descrição: "Desligado usa a lista M3U."
   - Escolha "Identificação do player" (`store.userAgent`, `AppSettings.UA_PRESETS`); valor que não é um dos prontos
     aparece como "Personalizado". A opção "— Personalizado / vazio —" abre o diálogo de texto.
   - Texto "Chave do TMDB" (`app.userTmdbApiKey`) — valor: "Definida" / "De fábrica" (vazia e
     `BuildConfig.DEFAULT_TMDB_KEY` não vazia) / "Não definida".
   - Texto "Guia de programação (URL XMLTV)" (`store.epgUrl`) — descrição: "Opcional: a programação já vem da conta."
   - Texto "Lista M3U (URL)" (`store.playlistUrl`) e Texto "URL de configuração" (`store.configUrl`). Mudar qualquer
     uma das duas mantém o efeito atual: `PlaylistHolder.clear()` + `XtreamCatalog.clearCaches(applicationContext, store)`.
   - Ação "Recarregar o catálogo agora" — o que "Carregar agora" faz hoje (abre `ChannelListActivity` com
     `EXTRA_FROM_SETTINGS` e `EXTRA_FORCE_REFRESH`; sem lista nem configuração, avisa).
   - Ação "Limpar cache" — o que o botão faz hoje; pede confirmação num diálogo antes.

**Sai da tela** (o valor salvo não é apagado): "Abrir a lista automaticamente ao iniciar" (`store.autoLoad` não é lido
em lugar nenhum fora das Configurações) e o botão "Salvar".

## Regras
- Não mudar chaves nem formatos de `AppSettings`/`PlaylistStore`: quem já usa o app não pode perder configuração.
- Lógica de apresentação que dê para isolar (resumo de URL → host, rótulo do valor atual de uma escolha, rótulo do
  intervalo de atualização, estado da chave do TMDB) vai para um `object` puro com **testes JVM**.
- Acessibilidade: `contentDescription` da linha = "rótulo, valor" (liga/desliga: "…, ligado"); foco visível; nenhum
  estado só por cor; alvo ≥ 48dp de altura.
- Estados: seção sem linha não existe; diálogo de texto com campo vazio = "Apagar"; teste de conexão rodando.
- Sem dependências novas. Sem dados do provedor (host, usuário, senha, chave) em código, testes, docs ou commits.
- `LoginActivity` e `MainActivity` continuam abrindo `SettingsActivity` como hoje.
- Foco inicial: primeira seção (Conta).

## Verificação
`ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home gradle :app:testDebugUnitTest :app:assembleDebug --offline`
Não há emulador: o comportamento na TV é validado pelo usuário.

## Status
- [x] Tela nova · [x] testes JVM · [x] CHANGELOG ("## Esta versão") · [ ] validação na TV

## Decisões fora do plano
- **Papel de parede e URL.** A Home usa qualquer `wallpaperUrl` preenchida, qualquer que seja o tema escolhido
  (`MainActivity.applyWallpaper`). Com a linha "Imagem personalizada" escondida fora do modo personalizado, escolher
  um tema pronto não teria efeito se a URL continuasse salva. Por isso: (a) a escolha em vigor é "personalizado"
  quando há URL (ou a chave salva é `custom`); (b) escolher um tema pronto apaga a URL e avisa num toast; (c) escolher
  "Imagem personalizada (URL)" só grava depois de "Salvar" no diálogo (cancelar não muda nada); (d) "Apagar" a URL volta
  o tema para o padrão se estava em `custom`. Única exceção à regra de não apagar valor salvo. Alternativa não feita:
  mexer na `MainActivity` para só usar a URL com a chave `custom` (quebraria quem só colou a URL, o fluxo antigo).
- **Campos sigilosos abrem vazios.** Lista M3U, URL de configuração, guia XMLTV (a URL do guia da conta costuma levar
  usuário e senha) e chave do TMDB não pré-preenchem o diálogo, para o valor salvo nunca aparecer na tela. Nesses
  campos "Salvar" vazio mantém o valor; só "Apagar" remove. Nos demais (URL do papel de parede, identificação do player)
  o campo vem preenchido e vazio = apagar, como no plano.
- **Opções com o mesmo valor.** `AppSettings.CONNECTION_OPTIONS` tem "Automática (10 Mbps)" e "Boa (10 Mbps)" com o
  mesmo valor salvo; depois de salvar não há como saber qual foi escolhida. A lista da tela mostra só a primeira de cada
  valor (`SettingsText.uniqueByValue`); a constante não foi alterada.
- **Identificação do player vazia** aparece como "Padrão do app" (o app usa o seu próprio quando o campo está vazio);
  "Personalizado" fica para valores que não são um dos prontos.
- **Formato da imagem** é lido com `coerceIn`, igual ao player, para um índice salvo inválido nunca quebrar a tela.
- **Setas nas pontas.** ↑ na primeira linha e ↓ na última (e ↑/↓ entre as seções) são tratados no código: o foco por
  geometria do Android pulava para a outra coluna nas pontas. → na seção entra na primeira linha.
- **Teste da conexão** mostra só o tipo do erro quando o teste lança exceção (a mensagem de rede pode trazer o
  endereço com usuário e senha). O relatório normal é o mesmo de antes.
- O fundo da tela é fixo no tema Landimflix (como a Busca); o papel de parede escolhido só aparece na Home.
