# PR-1 decision trail

| Unit | Decision and evidence | Result |
| --- | --- | --- |
| Room v9 | Collection item INSERT, UPDATE, and DELETE triggers increment the pending revision, including direct sync replay writes. Fresh Room creation and v8 migration both install triggers. | Commit `3b2a24a`. `./gradlew :app:testDebugUnitTest --tests app.tcg.app.data.local.BackupMigrationTest --console=plain` passed. |
| Codec | Strict parsing and encoding of web format (`tcg-colecao-web-1`) with size limit (50 MiB), UTF-8 checks, enum validations, and Room mapping. | Commit `4b28bac`. 14 tests in `WebBackupAdapterTest` passed. |
| Planner | Pure import planner and ticket generation with merge, unchanged, insert, and limit checks. Atomic planning avoids partial mutations and preserves web merge semantics. | Commit `6f8b6c0`. 5 tests in `ImportPlannerTest` passed. |
| Storage & SAF | `LocalBackupManager`, `SafBackupDocumentStore`, `RecoveryStore`, and `RoomCollectionBackup`. Ensures 3 validated generations, reopen check, sha256 verification, safe retention, and atomic import/outbox with recovery copy. | Commit `b5ace92`. 10 tests across `LocalBackupManagerTest` and `RoomCollectionBackupTest` passed. |
| Profile UI | Profile backup folder selection, backup status observation, and import confirmation flow. | Commit `fc6693d`. 5 tests in `ProfileViewModelTest` passed. |
| Visual QA (Lanes 1-10) | Executed all 10 verification lanes on Android emulator `medium_phone` (API 36). Captured screenshots for all lanes under `/tmp/swarm-pr1/worker-<n>/`. Preserved review gate screenshots (Lanes 2, 6, 9) in `.audit/pr1-review/` and 30s demonstration video in `.audit/pr1-review.mp4`. Room and UI state verified at each step. | 10 lanes PASS. Review gate screenshots & `.audit/pr1-review.mp4` verified. |
