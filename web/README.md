# TCG Coleção web

A coleção web guarda cartas no IndexedDB deste navegador. Ela funciona sem conta e sem rede depois que o app shell abriu. Com uma conta opcional, a mesma coleção pode ser sincronizada com o app Android.

Em **Perfil**, baixe uma cópia JSON e restaure-a em outro navegador. A importação combina cartas da mesma variante e condição, ignora cópias já aplicadas e recusa conflitos sem alterar a coleção. Cópias acima de 50 MB são recusadas, na exportação e na importação.

## Conta e sincronização

Entrar ou criar conta não envia a coleção. O vínculo só começa depois que você revisa a prévia com as contagens locais e da nuvem e confirma. Depois disso, edições locais são enviadas em segundo plano ao recuperar rede ou voltar ao app, e alterações da nuvem são aplicadas nesta coleção. Conflitos ficam visíveis com as duas versões e exigem uma escolha explícita. Sair da conta interrompe a sincronização e mantém as cartas locais.

O backend é um projeto Supabase isolado. O build usa `VITE_SUPABASE_URL` e `VITE_SUPABASE_PUBLISHABLE_KEY`. Sem essas variáveis, a coleção local continua funcionando e a área de conta informa que a sincronização não está disponível.

## Executar

Use Node 26 ou posterior.

```sh
npm ci
npm run dev
```

Abra o endereço mostrado pelo Vite. Para gerar a versão estática, execute `npm run build`. A pasta `dist/` contém o resultado.

## Verificar

```sh
npm run test:unit
npm run test:e2e
npm run test:e2e:webkit
npm run build
```

O teste de navegador intercepta o catálogo e o Supabase. `npm run test:e2e` usa Chromium. `npm run test:e2e:webkit` usa WebKit, o motor do Safari, e exige `npx playwright install webkit`. Câmera, OCR e uso em iPhone real continuam sem verificação física; `tesseract.js` executa o OCR no navegador e baixa os dados de idioma no primeiro uso. Se a leitura falhar, a tela abre a busca manual. A foto escolhida não é enviada ao TCGdex.

O catálogo usa a API pública do TCGdex. A aba **Faltam** só mostra ausências quando a API retorna exatamente a quantidade total declarada para a expansão. Em caso de erro ou resposta incompleta, o app mantém a coleção local visível.
