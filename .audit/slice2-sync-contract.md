# Contrato de correção do sync, slice 2

Autorizado pelo operador em 2026-10-20. As duas plataformas implementam as
mesmas regras. Os vetores compartilhados ficam em
`testdata/sync-wire-vectors.json` e são a fonte da verdade cross-platform.

Cada regra vale para Android e web. Onde o texto cita um arquivo, é o ponto de
partida, não a única posição permitida.

## C1. Lote de push

O servidor aceita no máximo 100 operações por chamada. O push rejeita o lote
inteiro com `invalid-request` quando recebe mais.

Cada coordenador divide as operações pendentes em blocos consecutivos de no
máximo 100 e faz o push de um bloco por vez, na ordem. As respostas de um bloco
são aplicadas antes do push do bloco seguinte.

Se o transporte lançar ao enviar um bloco, o coordenador para ali, não envia os
blocos seguintes e devolve o resultado de erro ou offline correspondente. As
operações dos blocos seguintes continuam pendentes.

## C2. Atribuição por opId

Toda resposta de push traz o `opId` que o servidor devolveu. O coordenador casa
cada resposta com a operação enviada pelo `opId`.

Resposta sem `opId` é violação de protocolo. O coordenador para, não confirma
nada do bloco e devolve erro.

Resposta com `opId` que não está entre as operações enviadas é violação de
protocolo, com o mesmo tratamento.

Operação enviada que não ganhou resposta fica pendente. Não é confirmada.

O processamento das respostas segue a ordem das operações enviadas, não a
ordem das respostas.

## C3. Rejected é conflito

Nos dois clientes, uma resposta `rejected` produz um detalhe de conflito com
origem `push`, com o motivo e o id de conflito do servidor. A operação não é
confirmada.

## C4. Operações bloqueadas

O conjunto de operações bloqueadas para push é a união, sobre todos os
conflitos de origem `push`, de `pushConflict.opId` e de
`pushConflict.rejectedOperation.opId`.

A regra é a mesma nos dois clientes.

## C5. Dinheiro

Uma conversão por plataforma, usada por todo codificador e todo decodificador.

`toMinorUnits(amount)`:

1. Rejeita valor não finito.
2. Rejeita valor negativo.
3. `scaled = amount * 100`.
4. `rounded` arredonda meio para cima, afastando do zero.
5. Rejeita quando `abs(scaled - rounded) > 1e-7`.
6. Rejeita quando `abs(rounded) > 9007199254740991`.
7. Devolve `rounded`.

`fromMinorUnits(minor)` devolve `minor / 100.0`.

Valores que essa regra produz, nos dois clientes, com os mesmos bytes:

| major | minor |
| --- | --- |
| 12.34 | 1234 |
| 19.99 | 1999 |
| 0.29 | 29 |
| 1.15 | 115 |
| 205 | 20500 |
| 90071992547409.9 | 9007199254740991 |

Valores que a regra rejeita nos dois clientes: `12.345` e `-1`.

No Android, o conversor estrito por BigDecimal sai de uso. O digest, o push, o
item de nuvem do conflito e o payload local de conflito usam a mesma função.

## C6. Unidades do payload local no Android

No `RoomSyncLocalStore`, o payload local de conflito grava `pricePaid` em
unidades menores e o decodificador divide por 100. O item de nuvem do conflito
usa `toMinorUnits` no lugar de truncar com `toLong`.

## C7. Testes

Cada plataforma ganha testes de comportamento que falham antes da correção.

Android, em `app/src/test/kotlin/app/tcg/app/data/sync/`:

- Push de 250 operações pendentes chama o transporte 3 vezes, com 100, 100 e 50
  operações, e confirma as 250.
- Push de 250 operações onde o segundo bloco falha deixa 150 pendentes: as 100
  do bloco que falhou e as 50 do bloco que não foi enviado.

Respostas devolvidas fora de ordem são atribuídas pelo `opId` certo.
- Resposta sem `opId` devolve erro e não confirma nada.
- Resposta `rejected` vira conflito de origem `push` e não confirma a operação.
- Operação bloqueada por `rejectedOperation.opId` não é reenviada.
- `toMinorUnits` rejeita `12.345` e `-1`.
- `decodeSyncItem` devolve o preço em unidades maiores a partir de um payload
  remoto em unidades menores.
- Os casos de `roundingCases` do arquivo de vetores produzem as unidades
  esperadas.

Web, em `web/src/sync/`:

- Push de 250 operações pendentes chama o transporte 3 vezes, com 100, 100 e 50
  operações, e confirma as 250.
- Push de 250 operações onde o segundo bloco falha deixa 150 pendentes: as 100
  do bloco que falhou e as 50 do bloco que não foi enviado.
- Respostas devolvidas fora de ordem são atribuídas pelo `opId` certo.
- Resposta sem `opId` devolve erro e não confirma nada.
- Resposta `rejected` vira conflito de origem `push` e não confirma a operação.
- Operação bloqueada por `rejectedOperation.opId` não é reenviada.
- `toMinorUnits` rejeita `12.345` e `-1`.
- Os casos de `roundingCases` do arquivo de vetores produzem as unidades
  esperadas.

Os dois clientes leem `testdata/sync-wire-vectors.json` e validam
`priceVector.rejected` e `roundingCases`.

## Fora do escopo

Nenhuma mudança de migration no Supabase. Nenhuma mudança de API pública do
transporte além do necessário para a atribuição por `opId`. Nenhuma mudança de
UX.
