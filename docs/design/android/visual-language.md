# Identidade visual Android

O app usa uma paleta adaptativa com indigo-violeta como ação principal, terracota como apoio e verde profundo como terciário. O tema claro mantém superfícies quase brancas com fundo levemente violeta. O tema escuro usa grafite para deixar a arte das cartas em destaque. A referência Collectr ajudou a identificar o contraste escuro e a hierarquia de cartas, mas o app mantém seus próprios nomes, ícone, componentes e organização.

`ui/theme/Color.kt` é a fonte dos tokens Compose. `ThemeContractTest` exige superfícies distintas e fundo claro e escuro coerentes. `ColorContrastTest` mede as combinações de texto e superfície dos dois esquemas usando a luminância sRGB da WCAG, com limite de 4,5:1 para texto normal.

Os cantos usam `TcgShapes`, de 8 dp nos controles compactos a 28 dp nas superfícies grandes. A tipografia usa pesos semibold e bold para títulos e mantém os estilos Material 3 para corpo. Cartões de coleção combinam superfície elevada, borda de 1 dp e ícones em cápsula; o banner de valor usa gradiente entre a cor primária e a terciária.

O ícone adaptativo e o fundo da janela em `res/values*` seguem o indigo do tema. A seleção Cartas/Expansões usa abas de largura integral para expor com clareza os dois modos da Coleção.

A web mantém a própria identidade escura com carvão e turquesa, documentada em `docs/design/web/collection-design.md`. As duas plataformas compartilham princípios de contraste, toque e hierarquia, não a mesma paleta. Unificar a marca entre Android e web exige uma decisão de produto e uma troca coordenada dos dois sistemas de tokens.
