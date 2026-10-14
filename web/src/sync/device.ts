const STORAGE_KEY = 'tcg-colecao-device-id'
const ID_PATTERN = /^[a-zA-Z0-9-]{8,80}$/

export interface DeviceIdStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

let fallbackId: string | null = null

/**
 * Stable per-browser sync device id. Persisted in localStorage when available and
 * kept in memory otherwise, so private-mode or storage failures never change the
 * identity a server has already seen mid-session.
 */
export function browserDeviceId(storage?: DeviceIdStorage): string {
  const store = storage ?? safeLocalStorage()
  if (store === null) {
    fallbackId ??= newDeviceId()
    return fallbackId
  }
  try {
    const stored = store.getItem(STORAGE_KEY)
    if (stored !== null && ID_PATTERN.test(stored)) return stored
    const created = newDeviceId()
    store.setItem(STORAGE_KEY, created)
    return created
  } catch {
    fallbackId ??= newDeviceId()
    return fallbackId
  }
}

function newDeviceId(): string {
  const generated = crypto.randomUUID()
  return ID_PATTERN.test(generated) ? generated : `web-${generated}`
}

function safeLocalStorage(): DeviceIdStorage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}
