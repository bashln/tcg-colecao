# TCG Coleção web

A coleção web guarda cartas no IndexedDB deste navegador. Ela ainda não sincroniza com o Android nem usa contas. Enquanto o app já estiver aberto, consultar e editar a coleção funciona sem rede; o app shell ainda precisa da rede para abrir ou recarregar a página. Em **Perfil**, baixe uma cópia JSON e restaure-a em outro navegador. A importação combina cartas da mesma variante e condição, ignora cópias já aplicadas e recusa conflitos sem alterar a coleção. Cópias acima de 50 MB são recusadas. O app também bloqueia a exportação de um arquivo maior que esse limite.

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
npm run build
```

O teste de navegador usa Chromium e intercepta o catálogo. Para testar WebKit, câmera e OCR no iPhone, ainda é necessário um aparelho real. `tesseract.js` executa o OCR no navegador, mas baixa os dados de idioma no primeiro uso. Se a leitura falhar, a tela abre a busca manual. A foto escolhida não é enviada ao TCGdex.

O catálogo usa a API pública do TCGdex. A aba **Faltam** só mostra ausências quando a API retorna exatamente a quantidade total declarada para a expansão. Em caso de erro ou resposta incompleta, o app mantém a coleção local visível.
