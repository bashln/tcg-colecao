import assert from 'node:assert/strict'
import test from 'node:test'
import { isCollectionBackupSizeAllowed, MAX_COLLECTION_BACKUP_BYTES } from './backup.ts'

test('backup export and import share the same inclusive size limit', () => {
  assert.equal(isCollectionBackupSizeAllowed(0), true)
  assert.equal(isCollectionBackupSizeAllowed(MAX_COLLECTION_BACKUP_BYTES), true)
  assert.equal(isCollectionBackupSizeAllowed(MAX_COLLECTION_BACKUP_BYTES + 1), false)
  assert.equal(isCollectionBackupSizeAllowed(-1), false)
})
