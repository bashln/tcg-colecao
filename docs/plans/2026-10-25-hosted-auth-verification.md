# Hosted auth unblock and two-account verification

**Decision.** Keep Supabase as the sync backend. Do not build WhatsApp-style phone-as-server pairing. QR remains a possible login shortcut or one-shot transfer later, not the sync engine.

**Why.** The server, RLS, RPC contract, client outboxes, conflict handling, and automatic triggers already exist and pass local gates. The remaining blocker for production is proving the hosted project with two real accounts. A live QR channel would add a relay, encryption, foreground service, and reconnect work while keeping the same offline-conflict problem.

## Current state (2026-10-25)

- Eight migrations are applied to the isolated hosted project `fwzzwqakrvogafqiatgo`.
- Web and Android both handle signup with and without email confirmation. When Supabase returns a session, the client signs in. When it does not, the client shows the awaiting-verification state.
- Hosted two-account proof is still missing. The recorded blockers are custom SMTP, a production callback domain, and isolated confirmed accounts.
- This checkout has no Supabase CLI, hosted publishable key, test accounts, or organization credentials. Remote dashboard changes and the hosted run are therefore blocked here.

## Goal

Make the hosted proof executable and repeatable, and document the exact dashboard change needed to unblock auth.

## Non-goals

- No QR live sync, WebRTC relay, or phone-as-server transport.
- No account deletion, billing change, release, or deploy.
- No service-role key in any client, script, or tracked file.
- No automatic account creation on a shared hosted project.

## Units

### U1. Hosted verification harness

**Status:** done 2026-10-25. `node --test scripts/verify-hosted-sync.test.mjs` passes 8 tests. The CLI was also exercised against a loopback fake server and printed seven passing checks.

**Deliverable:** `scripts/verify-hosted-sync.mjs` plus `scripts/verify-hosted-sync.test.mjs`.

The script signs in two pre-existing accounts with the publishable key, probes `/auth/v1/settings`, pushes one create operation as account A, proves account A can pull it, proves account B cannot see it, then deletes the test item as A. It prints a JSON summary without tokens or passwords.

TDD order:

1. Write the test with a fake Supabase HTTP boundary. Run it and record the failure for the missing module.
2. Implement the smallest client that satisfies the fake.
3. Add RED/GREEN cases for an isolation leak, an unconfirmed email, missing configuration, and idempotent cleanup.
4. Re-run the test and the script `--help`.

**Acceptance:** `node --test scripts/verify-hosted-sync.test.mjs` passes; no secret appears in output; a leak or auth failure exits non-zero with an actionable message.

### U2. Auth settings probe

**Status:** done 2026-10-25, covered by the tested `probeAuthSettings` export and the `--settings-only` flag.

The same script reports `mailer_autoconfirm`, signup disablement, and enabled external providers. If sign-in fails with an unconfirmed email, the message names the two fixes: disable email confirmation for the closed beta, or configure custom SMTP plus a callback domain.

**Acceptance:** a fake settings response is parsed and printed without leaking keys.

### U3. Runbook

**Status:** done 2026-10-25 at `docs/verification/hosted-auth.md`.

**Deliverable:** `docs/verification/hosted-auth.md`.

Cover:

- Which Supabase dashboard settings unblock the closed beta.
- The environment variables for web, Android, and the harness.
- How to create two isolated accounts without using a personal collection.
- How to run the harness and read the result.
- What the harness does not prove: Android instrumentation, physical devices, SMTP deliverability, and account deletion.
- Cleanup and the fact that test accounts remain.

### U4. Hosted run

**Status:** blocked in this environment. Requires the hosted publishable key, two confirmed test accounts, and either confirmation disabled or working SMTP. No credential is present in this checkout. The runbook and harness make the step a single command once those exist.

## Security constraints

- Use only the publishable key. Never use `service_role`.
- Do not print access tokens, refresh tokens, passwords, or full auth responses.
- Use a unique item id and a dedicated verification device id per run.
- Delete the pushed test item at the end. Test accounts stay because account deletion needs an admin path.
- Do not touch another user's collection or any existing production data.

## Follow-ups, not in this plan

- QR device login that mints a Supabase session after phone approval.
- One-shot QR or local transfer of the JSON backup for people who refuse an account.
- Offline cold start for the web shell through a service worker.
