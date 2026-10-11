# Identidade visual Android

O app usa uma paleta adaptativa com teal como ação principal, azul como apoio e âmbar para metadados. O tema claro mantém superfícies quase brancas. O tema escuro usa grafite para deixar a arte das cartas em destaque. A referência Collectr ajudou a identificar o contraste escuro com teal, mas o app mantém seus próprios nomes, ícone, componentes e organização.

`ui/theme/Color.kt` é a fonte dos tokens Compose. As cores da janela e do ícone em `res/values*` seguem o mesmo teal e o fundo correspondente a cada modo. A seleção Cartas/Expansões usa abas de largura integral para expor com clareza os dois modos da Coleção.

`ColorContrastTest` mede as combinações de texto e superfície dos dois esquemas usando a luminância sRGB da WCAG. O limite é 4,5:1 para texto normal. Testes de layout em aparelho continuam necessários para avaliar espaçamento, recortes e hierarquia com arte real.
