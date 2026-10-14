# Photo scan

The scan flow accepts a camera photo or image, tries local recognition, and offers manual catalog search when recognition cannot identify a card.

## Sub-features

- `scan-open` opens the scan screen from navigation.
- `scan-photo` accepts an image file or camera capture.
- `scan-recognition` shows candidates for confirmation.
- `scan-fallback` opens manual search when recognition is unavailable or uncertain.

## How to get to it (user POV)

- Choose `Escanear` in the navigation.
- Choose `Fotografar` to use the camera or `Escolher foto` to select an image.
- Choose `Buscar manualmente` when recognition does not find the card.

## Driving it with Playwright

Preconditions:

- Run from `web/` with `CI=1` and Chromium available.
- Use test fixtures or intercepted network boundaries. Do not capture or upload a person's photo.
- Check whether the test exercises a real image before treating it as OCR proof.

- **Open scan.** Run `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on tests/collection.spec.ts --grep 'mobile empty state'`. The test chooses `Escanear` and checks the `Escanear carta` heading.
- **Check recognition logic.** Run `npm run test:unit` and inspect scan recognition test results.
- **Use manual fallback.** From the scan screen choose `Buscar manualmente`. The textbox `Nome ou número da carta` appears.
- **Proof.** Keep the Playwright trace and unit-test output. Mark camera permissions, physical lighting, orientation, and iPhone Safari unverified unless tested on those devices.

## Gotchas

- OCR downloads language assets on first use and depends on browser and network conditions.
- Playwright does not prove physical camera focus, permissions, lighting, or orientation.
- A recognized candidate is a suggestion. The person confirms the card before adding it.
- Do not report mocked recognition as a physical photo test.
