# TCG Coleção verification guide

Use this guide to drive Web workflows with Playwright and run Android's local gates. Read every feature page touched by a change. Mark each route verified or blocked; do not generalize a passing route to another entry point.

## Run the Web verifier

Use Node 26 or newer. From `web/`, install dependencies once with `npm ci` when needed. Then check prerequisites:

```sh
node --input-type=module -e 'import net from "node:net"; import fs from "node:fs"; import { chromium } from "@playwright/test"; const major=Number(process.versions.node.split(".")[0]); if(major<26) throw new Error(`Node 26+ required; found ${process.version}`); if(!fs.existsSync("node_modules/.bin/playwright")) throw new Error("Run npm ci in web/"); if(!fs.existsSync(process.env.CHROMIUM_EXECUTABLE_PATH ?? "/usr/bin/chromium")) throw new Error("Chromium executable missing"); void chromium; const server=net.createServer(); server.once("error",error=>{console.error(`Port 4175 unavailable: ${error.message}`);process.exitCode=1}); server.listen(4175,"127.0.0.1",()=>server.close(()=>console.log("Web verification ready: dependencies and Chromium found; port 4175 is free")))'
```

Run the focused collection journey:

```sh
CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on tests/collection.spec.ts --grep 'a saved card survives reload'
```

The Playwright config starts Vite at `http://127.0.0.1:4175`, launches Chromium, and stops the server on exit. `CI=1` prevents Playwright from reusing an unknown server. Each test gets an isolated browser context and IndexedDB. If the port is occupied, stop and identify its owner. Do not attach to that process.

Use the full local gates when a change spans the Web package:

```sh
npm run test:unit
CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on
npm run build
```

Run Android's JVM and build gates from the repository root:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

These Android tasks do not install the app. Do not run connected tests on a device that may contain a personal collection. Use an isolated emulator for runtime-only checks.

## Keep evidence

Playwright writes traces under `web/test-results/`. With `--trace on`, traces record actions, DOM snapshots, network activity, and screenshots. Inspect the trace path printed by Playwright with:

```sh
npx playwright show-trace test-results/<test-directory>/trace.zip
```

Keep traces after the test process exits. The Web tests mock TCGdex and Supabase at their network boundaries. They prove the browser flow and mocked service outcomes, not live catalog availability, hosted RLS, or a real Android-to-Web sync. For sync, also verify visible status and side effects such as RPC order, local identity binding, pending outbox, and cursor. Never use a personal account or collection as a fixture.

Playwright owns the browser and Vite process it starts. Let it clean them up. Do not kill processes by name. If a run is interrupted, identify the process that owns port `4175` and stop only the server started by that run. Do not clear browser profiles, IndexedDB, app data, or proof files.

## Feature map

- [Collection and catalog](./collection-and-catalog.md) covers search, add, edit, removal, and missing cards.
- [Backup and recovery](./backup-and-recovery.md) covers JSON export, restore, merge, and conflict refusal.
- [Photo scan](./photo-scan.md) covers image selection, recognition, and manual fallback.
- [Accounts and sync](./accounts-and-sync.md) covers signup, link preview, sync triggers, conflicts, and logout.
- [Android Profile and updates](./android-profile-and-updates.md) covers account controls and the OTA updater.
