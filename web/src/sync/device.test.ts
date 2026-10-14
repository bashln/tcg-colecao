import assert from 'node:assert/strict'
import { test } from 'node:test'
import { browserDeviceId, type DeviceIdStorage } from './device.ts'

function fakeStorage(initial: Record<string, string> = {}): DeviceIdStorage & { data: Record<string, string> } {
  const data = { ...initial }
  return {
    data,
    getItem: (key) => data[key] ?? null,
    setItem: (key, value) => { data[key] = value },
  }
}

test('browserDeviceId persists and reuses a valid stored id', () => {
  const storage = fakeStorage({ 'tcg-colecao-device-id': '20000000-0000-4000-8000-000000000001' })
  const first = browserDeviceId(storage)
  const second = browserDeviceId(storage)
  assert.equal(first, '20000000-0000-4000-8000-000000000001')
  assert.equal(second, first)
})

test('browserDeviceId generates and stores an id when none exists', () => {
  const storage = fakeStorage()
  const created = browserDeviceId(storage)
  assert.match(created, /^[a-zA-Z0-9-]{8,80}$/)
  assert.equal(storage.data['tcg-colecao-device-id'], created)
  assert.equal(browserDeviceId(storage), created)
})

test('browserDeviceId replaces an invalid stored value', () => {
  const storage = fakeStorage({ 'tcg-colecao-device-id': 'x' })
  const replaced = browserDeviceId(storage)
  assert.notEqual(replaced, 'x')
  assert.match(replaced, /^[a-zA-Z0-9-]{8,80}$/)
  assert.equal(storage.data['tcg-colecao-device-id'], replaced)
})

test('browserDeviceId stays stable when storage access fails', () => {
  const throwing: DeviceIdStorage = {
    getItem: () => { throw new Error('blocked') },
    setItem: () => { throw new Error('blocked') },
  }
  const first = browserDeviceId(throwing)
  const second = browserDeviceId(throwing)
  assert.match(first, /^[a-zA-Z0-9-]{8,80}$/)
  assert.equal(second, first)
})
