# Backup and recovery

The Profile lets people export a JSON copy of the local collection and restore it in another browser or after local data loss.

## Sub-features

- `backup-export` downloads a valid JSON backup.
- `backup-restore` restores a backup into an empty collection.
- `backup-merge` merges matching copies idempotently.
- `backup-conflict` refuses inconsistent data without changing the collection.
- `backup-limit` enforces the 50 MB size limit.

## How to get to it (user POV)

- Open `Perfil` and choose `Baixar cópia da coleção` to export.
- Choose `Restaurar ou combinar cópia` to select a JSON backup.

## Driving it with Playwright

Preconditions:

- Use an isolated Playwright page and browser context.
- Run from `web/` with `CI=1` and Chromium available.
- The backup tests generate fixtures without reading personal files.

- **Export.** Add a fixture card through the UI, open `Perfil`, and choose `Baixar cópia da coleção`. The test parses the downloaded JSON and checks its format, card, and quantity.
- **Restore and merge.** Run `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on tests/backup-import.spec.ts`. The tests restore a fixture, merge matching copies, and check the collection after reload.
- **Refuse a conflict.** The suite imports inconsistent data, checks the error, and verifies that the original collection remains unchanged.
- **Proof.** Keep the trace under `web/test-results/` and the JSON assertions in the test output.

## Gotchas

- Restore combines quantity only for the same card, variant, and condition.
- A visible error alone does not prove safety. Check that the collection stayed unchanged.
- Do not overwrite or reuse personal backup files as test fixtures.
- Keep trace files during cleanup.
