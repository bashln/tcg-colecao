# Política de privacidade do TCG Coleção

Última atualização: 13 de novembro de 2026.

Este documento descreve como o aplicativo TCG Coleção, para Android, e a versão web tratam dados pessoais. Ele reflete o que o código faz hoje. Se o comportamento mudar, este arquivo muda no mesmo commit.

## Resumo

- A coleção fica no seu aparelho. Usar o app sem conta não envia a coleção para nenhum servidor.
- A foto da carta não sai do aparelho. A leitura do texto acontece no próprio dispositivo.
- Não há anúncio, rastreamento, perfilamento nem relatório automático de erro.
- A conta é opcional e serve para sincronizar a mesma coleção entre Android e navegador.
- Você pode exportar a coleção em JSON a qualquer momento, com ou sem conta.

## Quem responde pelos dados

O projeto é mantido por um desenvolvedor individual, sem empresa. O canal de contato para qualquer pedido relacionado a dados pessoais é a página de issues do repositório, em https://github.com/bashln/tcg-colecao/issues. Não abra uma issue com dados pessoais ou com o arquivo de backup. Descreva o pedido sem anexar a coleção e combine o envio por outro meio se for necessário.

## O que fica no seu aparelho

- A coleção: carta, variante, condição, quantidade, preço pago, listas e observações.
- O histórico de preços e as cotações mais recentes que o app já buscou.
- Preferências de tema e de uso.
- Com conta, o token da sessão. Ele é cifrado com uma chave que fica no Android Keystore e não é legível fora do aparelho desbloqueado.
- A senha do backup, quando você ativa a criptografia. Ela é cifrada com a mesma chave do Keystore e nunca sai do aparelho.
- A cópia temporária da última importação, apagada quando a importação termina.

Na versão web, esses dados ficam no IndexedDB do navegador. Limpar os dados do site apaga a coleção local.

O app desliga o backup automático do Google. A única cópia fora do aparelho é a que você exporta em Perfil, e ela vai para onde você escolher.

## O que sai do aparelho

O app só faz pedidos de rede para as finalidades abaixo.

| Destino | O que é enviado | Para quê |
| --- | --- | --- |
| TCGdex (`api.tcgdex.net`) | Texto lido na carta ou termo que você digita, e identificadores de carta | Identificar a carta, buscar o catálogo e obter preços de referência |
| Supabase, só com conta | E-mail, senha e os itens da coleção | Criar a conta e sincronizar a coleção entre Android e navegador |
| Liga Pokémon (`ligapokemon.com.br`) | Nome, identificador e número da carta | Ler a cotação pública em reais |
| CDN da TCGplayer (`tcgplayer-cdn.tcgplayer.com`) | Pedido da imagem da carta | Mostrar a arte da carta |
| GitHub (`api.github.com`) | Pedido HTTPS padrão da checagem de versão | Avisar quando existe uma versão nova |
| jsDelivr (`cdn.jsdelivr.net`), só na versão web | Pedido do arquivo de idioma do OCR no primeiro uso | Ler o texto da foto no navegador |

Esses pedidos carregam o endereço IP do seu aparelho, como qualquer acesso à internet. Os serviços registram esse dado conforme as políticas deles.

Não enviamos: sua coleção sem conta, suas fotos, sua localização, seus contatos, identificadores de publicidade ou o conteúdo de outros apps.

## Fotografias

A câmera e a galeria servem para ler a carta. No Android, o texto é reconhecido pelo ML Kit no próprio aparelho. Na versão web, o reconhecimento roda no navegador com a biblioteca de OCR local. A foto não é enviada ao TCGdex nem a qualquer outro servidor. Apenas o texto reconhecido segue para a busca.

O app não mantém a foto depois da leitura. O backup exportado guarda os dados da coleção, não as fotos.

## Conta e sincronização

Sem conta, o app não tem cadastro e não identifica você. Com conta:

- O e-mail identifica a conta e recebe a confirmação de cadastro. Hoje não há redefinição de senha dentro do app; quem perder a senha precisa pedir ajuda pelo canal de contato.
- A senha é armazenada pelo Supabase com hash. O app não guarda a senha.
- A coleção sincronizada inclui os itens e o preço pago de cada um.
- O acesso aos dados na nuvem é restrito ao dono da conta por políticas de segurança no banco.
- Sair da conta interrompe a sincronização e mantém a coleção local.

Hoje não existe botão para excluir a conta dentro do app. Para excluir a conta e os dados na nuvem, abra uma issue e o pedido é atendido manualmente. A coleção local continua no aparelho até você desinstalar o app ou limpar os dados.

## Compartilhamento

Não vendemos, alugamos nem cedemos dados pessoais. O compartilhamento se limita aos serviços da tabela acima, que atuam como fornecedores de infraestrutura ou como fontes públicas de catálogo e preço. Alguns desses servidores ficam fora do Brasil, o que caracteriza transferência internacional nos termos da LGPD.

## Retenção

- Dados locais: até você desinstalar o app ou limpar os dados. Não temos cópia.
- Dados na nuvem: enquanto a conta existir, ou até você pedir a exclusão.
- Backups exportados: arquivos que você guarda onde escolher. Não temos acesso a eles.

## Seus direitos

A Lei Geral de Proteção de Dados (Lei 13.709/2018) garante a você, entre outros:

- confirmação de tratamento e acesso aos dados;
- correção de dados incompletos ou desatualizados;
- portabilidade, atendida pela exportação em JSON;
- eliminação dos dados tratados com consentimento;
- informação sobre compartilhamento;
- revogação do consentimento, que no app corresponde a sair da conta e apagar os dados locais.

Para exercer um direito, abra uma issue em https://github.com/bashln/tcg-colecao/issues. Pedidos que dependam de confirmação de identidade podem exigir contato adicional. Não há cobrança para atender um pedido.

## Segurança

Os pedidos de rede usam HTTPS. O token de sessão no Android é cifrado com uma chave do Android Keystore. O acesso aos dados sincronizados é restrito à conta dona por políticas de segurança no banco. Nenhum sistema é imune a falha. Se você encontrar uma falha de segurança, relate em uma issue sem incluir dados pessoais, ou peça um canal privado na issue.

## Crianças e adolescentes

O app não coleta idade e não é direcionado a crianças. Se um responsável identificar dados de uma criança no app, peça a remoção pelo canal de contato acima.

## Alterações nesta política

Uma mudança de comportamento que afete dados pessoais atualiza este documento no mesmo commit da mudança. O histórico do repositório mostra cada versão.
