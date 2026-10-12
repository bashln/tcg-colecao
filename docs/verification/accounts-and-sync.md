# Accounts and sync

Accounts are optional. Initial linking requires a server preview and explicit confirmation. Logout, account changes, network loss, and conflicts must preserve the local collection.

## Sub-features

- `account-signup` reports email confirmation without uploading collection data.
- `account-callback` verifies confirmation tokens before storing a session.
- `sync-preview` shows the server preview before initial upload.
- `sync-confirm` commits only after explicit confirmation.
- `sync-automatic` sends local changes after edits, foreground return, and network recovery.
- `sync-conflict` keeps conflicting edits visible and pending.
- `sync-logout` stops remote access and preserves local cards.
- `sync-account-switch` requires a fresh preview and keeps account identities separate.

## How to get to it (user POV)

- Open `Perfil` and create an account or sign in.
- Choose `Revisar vínculo`, inspect local and cloud counts, then choose `Confirmar vínculo` or `Cancelar prévia`.
- Edit an item while linked. The app shows sync status without a manual sync action.
- Choose `Sair da conta` to stop sync and keep local cards.

## Driving it with Playwright

Preconditions:

- Use the fake Supabase values and RPC routes in the E2E harness. Do not use a hosted or personal account.
- Run commands from `web/` with `CI=1` and Chromium available.
- Mocked RPC tests prove client behavior, not hosted RLS or Android-to-Web round-trip.

- **Signup without upload.** Run `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on tests/account.spec.ts`. Verify the confirmation message and absence of collection RPC calls.
- **Preview before commit.** Run `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on tests/link.spec.ts`. Preview appears before `commit_collection_link`; cancel and conflict cases do not commit.
- **Automatic sync and recovery.** Run `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on tests/sync-lifecycle.spec.ts`. Check local edit push, network recovery, foreground return, logout preservation, and account switching.
- **Proof.** Inspect trace action history and verify RPC order, local binding, pending outbox, cursor, and local rows. Keep traces under `web/test-results/`.
- **Hosted round-trip.** Mocked tests do not prove hosted isolation or cross-platform sync. Use isolated test accounts and devices as required by `docs/plans/2026-10-11-complete-account-sync.md`.

## Gotchas

- Signup and confirmation do not link a collection.
- Never send an outbox operation under another account identity.
- Do not merge ID collisions or duplicate configurations silently.
- Hosted round-trip and RLS need dedicated isolated-account tests.
- Custom SMTP and a production callback domain are not configured in this checkout.
