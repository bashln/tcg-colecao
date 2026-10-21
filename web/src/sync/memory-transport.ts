import { applyOperation, type CollectionOperation, type SyncState } from './policy.ts'

export interface SequencedOperation {
  sequence: number
  operation: CollectionOperation
}

export interface PushOutcome {
  kind: 'applied' | 'already-applied' | 'conflict'
  reason?: Extract<ReturnType<typeof applyOperation>, { kind: 'conflict' }>['reason']
  sequence?: number
  opId?: string
}

export interface MemoryTransportOptions {
  failNextPush?: boolean
}

export interface MemoryTransport {
  push(identity: string, operations: readonly CollectionOperation[]): Promise<PushOutcome[]>
  pull(identity: string, afterSequence: number): Promise<{ operations: SequencedOperation[]; highWaterMark: number }>
}

export function createMemoryTransport(options: MemoryTransportOptions = {}): MemoryTransport {
  let failNextPush = options.failNextPush ?? false
  const accounts = new Map<string, { state: SyncState; sequence: number; log: SequencedOperation[]; operationSequence: Map<string, number> }>()

  return {
    async push(identity, operations) {
      if (failNextPush) {
        failNextPush = false
        throw new Error('Transporte indisponível')
      }
      const account = getAccount(identity)

      return operations.map((operation): PushOutcome => {
        const result = applyOperation(account.state, operation)
        if (result.kind === 'conflict') return { kind: 'conflict', reason: result.reason, opId: operation.opId }
        if (result.kind === 'already-applied') {
          const priorSequence = account.operationSequence.get(operation.opId)
          return { kind: 'already-applied', opId: operation.opId, ...(priorSequence === undefined ? {} : { sequence: priorSequence }) }
        }

        account.state = result
        account.sequence += 1
        account.operationSequence.set(operation.opId, account.sequence)
        account.log.push({ sequence: account.sequence, operation: cloneOperation(operation) })
        return { kind: 'applied', opId: operation.opId, sequence: account.sequence }
      })
    },
    async pull(identity, afterSequence) {
      const account = getAccount(identity)
      const operations = account.log.filter((entry) => entry.sequence > afterSequence).map((entry) => ({
        sequence: entry.sequence,
        operation: cloneOperation(entry.operation),
      }))
      return { operations, highWaterMark: account.sequence }
    },
  }

  function getAccount(identity: string) {
    let account = accounts.get(identity)
    if (account === undefined) {
      account = { state: { rows: [], appliedOperations: new Map() }, sequence: 0, log: [], operationSequence: new Map() }
      accounts.set(identity, account)
    }
    return account
  }
}

function cloneOperation(operation: CollectionOperation): CollectionOperation {
  return operation.kind === 'delete'
    ? { ...operation }
    : { ...operation, item: { ...operation.item, pricePaid: operation.item.pricePaid === null ? null : { ...operation.item.pricePaid } } }
}
