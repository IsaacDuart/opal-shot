# OpalShot

Aplicativo Android pessoal para organizar capturas de tela logo depois que elas
são criadas.

Ao tirar um print pelo botão normal do celular, o OpalShot mostra um card sobre
o aplicativo atual com três ações:

| Ação | Resultado |
|---|---|
| **Temporário e compartilhar** | Copia o print para o cache privado, remove o original da galeria e abre o compartilhamento do Android. |
| **Manter na galeria** | Mantém o arquivo original e fecha o card. |
| **Excluir** | Remove o arquivo e seu registro do `MediaStore`. |

O aplicativo foi feito para uso pessoal e instalação direta por APK. Ele não é
destinado à publicação na Play Store.

## Compatibilidade

- Android 11 ou mais recente;
- compatível com Android 14/15 e Redmi 14C com HyperOS;
- não exige root;
- não usa MediaProjection ou AccessibilityService;
- o monitoramento precisa ser iniciado manualmente após reiniciar o celular.

Configuração Android atual:

```text
applicationId: com.example.opalshot
minSdk:         30 (Android 11)
targetSdk:      35 (Android 15)
compileSdk:     35
```

## Compilar pelo terminal, sem Android Studio

O projeto possui Gradle Wrapper, portanto o Android Studio não é obrigatório.

Neste computador, execute:

```bash
cd '/home/isaac/Área de trabalho/opalshot'

chmod +x gradlew

export ANDROID_HOME='/home/isaac/Android/Sdk'
export ANDROID_SDK_ROOT='/home/isaac/Android/Sdk'

./gradlew assembleDebug
```

O APK será criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Para apagar compilações antigas e gerar tudo novamente:

```bash
cd '/home/isaac/Área de trabalho/opalshot'

export ANDROID_HOME='/home/isaac/Android/Sdk'
export ANDROID_SDK_ROOT='/home/isaac/Android/Sdk'

./gradlew clean assembleDebug
```

Para executar também a verificação do Android Lint:

```bash
./gradlew lintDebug assembleDebug
```

O relatório do lint fica em:

```text
app/build/reports/lint-results-debug.html
```

## Instalar por USB com ADB

No celular, ative **Opções do desenvolvedor > Depuração USB**, conecte o cabo e
confirme a autorização exibida na tela.

Depois execute:

```bash
export OPALSHOT_ADB='/home/isaac/Android/Sdk/platform-tools/adb'

"$OPALSHOT_ADB" start-server
"$OPALSHOT_ADB" devices

"$OPALSHOT_ADB" install -r \
  '/home/isaac/Área de trabalho/opalshot/app/build/outputs/apk/debug/app-debug.apk'
```

Para abrir o aplicativo pelo terminal:

```bash
"$OPALSHOT_ADB" shell am start -n com.example.opalshot/.MainActivity
```

## Primeira configuração

1. Abra o **OpalShot**.
2. Toque em **Conceder permissões**.
3. Autorize notificações.
4. Autorize fotos e imagens. No Android 14/15, escolha **Permitir todas**.
5. Na tela especial do Android, habilite **Acesso a todos os arquivos**.
6. Habilite **Exibir sobre outros apps** para permitir que o card apareça sobre
   WhatsApp, Instagram e outros aplicativos.
7. Volte ao OpalShot e confirme estes estados:

```text
Permissões de mídia/notificação: concedidas
Acesso a todos os arquivos: autorizado
Exibir sobre outros apps: autorizado
```

8. Toque em **Iniciar monitoramento**.
9. Confirme que apareceu a notificação discreta **OpalShot ativo**.

Essa notificação permanente é obrigatória pelo Android para manter o serviço em
primeiro plano. A interação com cada novo print aparece no card flutuante, não
em outra notificação.

## Evitar que o HyperOS encerre o serviço

No Redmi, abra:

```text
Configurações > Apps > Gerenciar apps > OpalShot > Economia de bateria
```

Selecione **Sem restrições**. Se o aparelho oferecer as opções abaixo, habilite
também:

- atividade em segundo plano;
- início automático;
- fixar o OpalShot na tela de aplicativos recentes.

## Como usar

1. Inicie o monitoramento.
2. Saia do OpalShot normalmente, sem tocar em **Parar monitoramento**.
3. Tire um print pelos botões do celular.
4. Aguarde o card **Novo print detectado**.
5. Escolha uma das três ações.

Ao selecionar **Temporário e compartilhar**, o botão muda para
**Preparando…**. Em seguida, o seletor do Android permite escolher WhatsApp,
Instagram, Telegram ou outro aplicativo compatível.

## Prints temporários

Os arquivos temporários ficam no cache privado:

```text
/data/user/0/com.example.opalshot/cache/temp_shots/
```

No código, o diretório é criado como:

```text
cacheDir/temp_shots/
```

Características desse diretório:

- não aparece na galeria;
- não é acessível normalmente por gerenciadores de arquivos;
- é compartilhado por uma URI `content://` através do `FileProvider`;
- pode ser limpo pelo próprio Android quando faltar espaço;
- o arquivo não é apagado imediatamente ao abrir o compartilhamento.

O serviço verifica a pasta a cada 60 segundos. Um arquivo que completou 10
minutos é removido na próxima verificação, normalmente entre 10 e 11 minutos
após sua criação.

A limpeza também acontece:

- quando o serviço é iniciado;
- ao tocar em **Limpar temporários** — nesse caso, todos são removidos;
- quando o Android decide limpar o cache do aplicativo.

Se o serviço estiver parado, a verificação periódica fica suspensa até o
próximo início.

## Teste recomendado

1. Confirme todas as permissões.
2. Inicie o monitoramento.
3. Tire um print com WhatsApp ou outro aplicativo aberto.
4. Confirme que o card aparece sobre o aplicativo.
5. Escolha **Manter na galeria** e verifique que a imagem continua lá.
6. Tire outro print, escolha **Excluir** e confira que ele desapareceu.
7. Tire outro print e escolha **Temporário e compartilhar**.
8. Selecione o WhatsApp e confirme que a tela de envio abre com a imagem.
9. Pare o monitoramento e confirme que a notificação permanente desaparece.

## Arquivos principais

```text
app/src/main/
├── AndroidManifest.xml
├── assets/
│   └── branding/opalshot_source.png
├── java/com/example/opalshot/
│   ├── MainActivity.kt
│   └── ScreenshotWatcherService.kt
└── res/
    ├── drawable/
    │   ├── ic_launcher_foreground.xml
    │   ├── ic_opalshot.xml
    │   └── overlay_card_background.xml
    ├── drawable-nodpi/opalshot_launcher_foreground.png
    ├── layout/
    │   ├── activity_main.xml
    │   └── overlay_screenshot_card.xml
    ├── mipmap-*/
    ├── values/
    │   ├── colors.xml
    │   ├── strings.xml
    │   └── styles.xml
    └── xml/file_paths.xml
```

Onde alterar cada parte:

| Alteração | Arquivo |
|---|---|
| Lógica de detecção, card, exclusão e compartilhamento | `ScreenshotWatcherService.kt` |
| Tela principal e fluxo de permissões | `MainActivity.kt` |
| Organização visual do card | `overlay_screenshot_card.xml` |
| Fundo, borda e cantos do card | `overlay_card_background.xml` |
| Textos e nome do aplicativo | `strings.xml` |
| Tema e aparência geral | `styles.xml` e `colors.xml` |
| Permissões e componentes Android | `AndroidManifest.xml` |
| Versão, SDK e dependências | `app/build.gradle.kts` |
| Imagem original da marca | `assets/branding/opalshot_source.png` |

Não edite manualmente arquivos dentro de:

```text
.gradle/
build/
app/build/
dist/
```

Essas pastas contêm caches, relatórios e APKs gerados.

## Solução de problemas

### O card não aparece

- confirme **Exibir sobre outros apps: autorizado**;
- confirme que o serviço está ativo;
- desative a otimização de bateria do OpalShot;
- pare e inicie o monitoramento novamente.

### O compartilhamento abre, mas o aplicativo escolhido não inicia

- instale a versão mais recente do OpalShot;
- reinicie o monitoramento após atualizar o APK;
- confirme que o arquivo temporário ainda existe e que o serviço está ativo.

### O ícone antigo continua aparecendo

O launcher do HyperOS pode manter o ícone anterior em cache. Remova o atalho da
tela inicial e adicione novamente. Se necessário, reinicie o aparelho.

### O Play Protect informa que o app veio de fonte desconhecida

Isso pode acontecer porque o APK foi instalado diretamente e não veio da Play
Store. Confirme que você compilou o projeto localmente e está instalando o APK
correto antes de continuar.

## Segurança e privacidade

- os prints não são enviados automaticamente para nenhum servidor;
- nenhuma biblioteca de rede é usada;
- temporários ficam no cache privado do aplicativo;
- o compartilhamento usa `FileProvider` e URI `content://`;
- outros aplicativos recebem somente permissão temporária de leitura;
- o original só é removido quando o usuário escolhe **Excluir** ou
  **Temporário e compartilhar**.
