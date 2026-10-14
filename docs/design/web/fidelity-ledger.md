# Web concept fidelity ledger

Comparison uses the accepted PNG concepts in `docs/design/web/concepts/` and Chromium screenshots captured on 2026-10-10. Mobile checks use a 390 by 844 CSS-pixel viewport. Desktop checks use a 1440 by 1000 CSS-pixel viewport.

| State | Concept | Rendered evidence | Result |
|---|---|---|---|
| Empty collection, desktop | `collection-empty.png` | `/tmp/tcg-web-desktop.png` | Sidebar, title, search, Tenho/Faltam switch, filters, and two add actions match the concept hierarchy. |
| Empty collection, mobile | `collection-mobile.png` and `collection-empty.png` | `/tmp/tcg-web-mobile.png` | Four-item bottom navigation and stacked content match. Initial layout hid the catalog action and clipped filters; the CSS now wraps filters and moves both actions above navigation. |
| Populated collection, desktop | `collection-desktop.png` | `/tmp/tcg-web-collection-desktop.png` | Search, mode switch, filters, summary, and card grid match. The screenshot fixture has no card image, so it shows the intentional unavailable-image state. |
| Populated collection, mobile | `collection-mobile.png` | `/tmp/tcg-web-collection-mobile.png` | List card layout and bottom navigation match. The original two-column summary overlapped at this width; it now stacks into two rows. |
| Scan and confirmation | `scanner-camera.png` and `scan-confirmation.png` | Not captured | The scan view has photo and camera actions, guidance, manual search, and a back action. Verify real camera permissions and OCR on iPhone before closing this row. |
| Exact-item editor | `collection-item-edit.png` | Not captured | Form fields, market quote, save, confirmed removal, and keyboard focus behavior are implemented. Capture a rendered comparison before public release. |

Catalog images and live quote layouts remain unverified because browser tests intercept TCGdex and the fixture uses `image: null`. Do not replace catalog art with concept art or generated placeholders.
