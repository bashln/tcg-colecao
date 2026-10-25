# Web concept fidelity ledger

Comparison uses the accepted PNG concepts in `docs/design/web/concepts/` and Chromium screenshots captured on 2026-10-10. Mobile checks use a 390 by 844 CSS-pixel viewport. Desktop checks use a 1440 by 1000 CSS-pixel viewport.

| State | Concept | Rendered evidence | Result |
|---|---|---|---|
| Empty collection, desktop | `collection-empty.png` | `/tmp/tcg-web-desktop.png` | Sidebar, title, search, Tenho/Faltam switch, filters, and two add actions match the concept hierarchy. |
| Empty collection, mobile | `collection-mobile.png` and `collection-empty.png` | `/tmp/tcg-web-mobile.png` | Four-item bottom navigation and stacked content match. Initial layout hid the catalog action and clipped filters; the CSS now wraps filters and moves both actions above navigation. |
| Populated collection, desktop | `collection-desktop.png` | `/tmp/tcg-web-collection-desktop.png` | Search, mode switch, filters, summary, and card grid match. The screenshot fixture has no card image, so it shows the intentional unavailable-image state. |
| Populated collection, mobile | `collection-mobile.png` | `/tmp/tcg-web-collection-mobile.png` | List card layout and bottom navigation match. The original two-column summary overlapped at this width; it now stacks into two rows. |
| Scan and confirmation | `scanner-camera.png` and `scan-confirmation.png` | `/tmp/tcg-web-scan-fixed.png` and `/tmp/tcg-web-confirm-final.png` | Scan guidance, photo and camera actions, manual search, and the back action match. Confirmation shows the selected candidate, fields, primary action, and manual fallback. Subpages hide the bottom navigation and brand header, so the primary action is visible at 390 by 844. Real camera permissions and OCR on iPhone remain unverified. |
| Exact-item editor | `collection-item-edit.png` | `/tmp/tcg-web-edit-final.png` | Form fields, market quote, save, confirmed removal, and the subpage header match. The mobile currency select shows the full ISO code after widening it at 390 px. Keyboard focus and removal behavior are covered by Playwright. |

Catalog images and live quote layouts remain unverified because browser tests intercept TCGdex and the fixture uses `image: null`. Do not replace catalog art with concept art or generated placeholders.

WebKit runs the same Playwright suite in CI and passes 40 of 40 tests as of 2026-10-25, including the mobile empty-state clearance and the subpage chrome change. Physical iPhone, camera, OCR, keyboard, and screen reader remain unverified.
