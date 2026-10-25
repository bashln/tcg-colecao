# Hosted auth and two-account sync verification

Use this runbook to unblock the hosted Supabase project and prove that two isolated accounts can sync without seeing each other's collection.

The script uses only the publishable key. It signs in two existing accounts, pushes one test item as account A, proves A can pull it, proves B cannot, then deletes the test item as A. It never creates or deletes accounts and never prints tokens or passwords.

## Current blocker

As of 2026-10-25 this checkout has no Supabase CLI, no hosted publishable key, and no confirmed test accounts. The hosted run therefore cannot execute here. Everything else is ready.

## Option 1. Closed beta, no email delivery

Use this when the goal is to prove sync with a small group, not to open public signup.

1. Open the Supabase dashboard for the isolated project `fwzzwqakrvogafqiatgo`.
2. Go to **Authentication > Sign In / Providers > Email**.
3. Turn off **Confirm email**.
4. Keep signup rate limits and the default Site URL for now.

Tradeoff: anyone who reaches signup can create an account with an address they do not own. Use this only while the app is private. Public release needs Option 2.

With this setting, signup returns a session immediately. The web and Android clients already handle that response and sign the person in.

## Option 2. Production email delivery

Use this before public release.

1. Configure custom SMTP in **Authentication > Emails > SMTP Settings**. Do not put the SMTP password in the repository or in client environment variables.
2. Set **Site URL** to the deployed web origin, for example `https://collection.example.com`.
3. Add that origin to **Redirect URLs**. The web confirmation handler reads the session from the URL fragment at the app root.
4. Keep **Confirm email** on.
5. Send one real signup to a test mailbox and confirm the link lands on the web app and stores the session.

The Android app has no confirmation deep link. Its email link opens the web callback, and the person signs in on Android with the same email and password after confirming.

## Environment variables

Web build:

```sh
VITE_SUPABASE_URL=https://<project-ref>.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_<public-key>
```

Android build:

```sh
TCG_SUPABASE_URL=https://<project-ref>.supabase.co
TCG_SUPABASE_PUBLISHABLE_KEY=sb_publishable_<public-key>
```

Harness:

```sh
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_<public-key>
HOSTED_EMAIL_A=<test-account-a>
HOSTED_PASSWORD_A=<password-a>
HOSTED_EMAIL_B=<test-account-b>
HOSTED_PASSWORD_B=<password-b>
```

`TCG_SUPABASE_URL` and `TCG_SUPABASE_PUBLISHABLE_KEY` are accepted as aliases. Never use a `service_role` or secret key here.

## Create two isolated accounts

Use dedicated mailboxes, or plus-addressing if the provider supports it. Do not use a personal collection.

1. Sign up for account A and account B through the web or Android app.
2. Confirm both emails if Option 2 is active.
3. Leave both collections empty. The harness writes one temporary item and removes it.

## Run the harness

First check the auth settings without signing in:

```sh
node scripts/verify-hosted-sync.mjs --settings-only
```

Then run the full proof:

```sh
node scripts/verify-hosted-sync.mjs
```

The JSON summary lists seven checks.

- `settings`: `mailerAutoconfirm`, signup disablement, and enabled external providers.
- `accounts-distinct`: the two logins resolve to different user ids.
- `push-applied`: account A's create operation was applied.
- `pull-own`: account A can pull its operation.
- `isolated`: account B cannot pull account A's operation.
- `cleanup-applied`: the delete operation was applied.
- `cleanup-pulled`: account A can pull the delete.

Exit code 0 means every check passed. Any failed check or typed error exits 1.

If sign-in fails with `email-not-confirmed`, choose Option 1 or finish Option 2. The message repeats this instruction.

### Run from GitHub Actions

The `hosted-sync` workflow runs the same two commands from a manual dispatch. Add these repository secrets first.

- `HOSTED_SUPABASE_URL`
- `HOSTED_SUPABASE_PUBLISHABLE_KEY`
- `HOSTED_EMAIL_A`, `HOSTED_PASSWORD_A`
- `HOSTED_EMAIL_B`, `HOSTED_PASSWORD_B`

Dispatch **Hosted sync** from the Actions tab. The workflow never echoes a secret. It has no schedule and does not run on pull requests.

## What this proves and what it does not

This proves hosted Auth with real accounts, the authenticated RPC path, RLS isolation between two users, and cleanup of the test item.

It does not prove Android instrumentation, physical devices, SMTP deliverability, email templates, account deletion, or the Android-to-web round-trip through the UI. Those stay in `docs/verification/accounts-and-sync.md`.

## Cleanup

The harness deletes its test item. The two test accounts remain because account deletion needs an admin path. Remove them in the dashboard after the proof if they are not needed, and revoke any sessions you created.

## Security notes

- Use only the publishable key.
- Do not paste passwords or tokens into shell history on a shared machine. Prefer an env file outside the repository with mode 600.
- Do not commit any `.env` file.
- Do not run this against a project that holds another person's collection.
