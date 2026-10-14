# Android Profile and updates

The Android Profile provides account, sync, and app update controls while keeping the collection local and available.

## Sub-features

- `android-account` supports signup, sign-in, verification, explicit link confirmation, and logout.
- `android-update-check` checks GitHub Releases for a compatible version.
- `android-update-download` validates and downloads an APK before system installer handoff.
- `android-update-safety` rejects invalid package, signature, digest, or version combinations.

## How to get to it (user POV)

- Open the `Perfil` tab from Android bottom navigation.
- Choose `Conta` to open account and sync controls.
- Choose `Atualizações` to check for an update and hand an approved APK to Android's package installer.

## Driving it with Android local tests

Preconditions:

- Run from the repository root.
- Use JVM tests and `assembleDebug` for routine checks. They do not install the app on a device.
- Do not run connected tests on a physical device that may contain the user's collection.

- **Account states.** Run `./gradlew :app:testDebugUnitTest --tests 'app.tcg.app.ui.account.AccountViewModelTest' --tests 'app.tcg.app.ui.account.AccountScreenTest'`. Verify that login does not bind before confirmation and logout leaves collection data intact.
- **Update safety.** Run `./gradlew :app:testDebugUnitTest --tests 'app.tcg.app.ui.update.UpdateViewModelTest' --tests 'app.tcg.app.update.UpdateCoordinatorTest' --tests 'app.tcg.app.update.ApkValidatorTest'`. Invalid packages and signatures must not reach installer handoff.
- **Build.** Run `./gradlew :app:assembleDebug`. This produces the APK without installing it.
- **Device verification.** Use an isolated emulator and controlled test account for Compose navigation, camera permission, or installer behavior. Capture a screenshot or screen recording outside app data.

## Gotchas

- JVM and Robolectric tests do not prove physical camera behavior or the Android system installer screen.
- Never run `adb uninstall`, clear app storage, or install onto a personal profile during verification.
- The updater hands a validated APK to Android. It does not install silently or bypass Play Protect.
- Cross-platform round-trip is separate from Compose UI unit tests.
