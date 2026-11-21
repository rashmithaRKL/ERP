# Verification record

## Interface motion — 30 October 2026

Executed against the local React workspace and the running PostgreSQL-backed API.

| Check | Result |
|---|---|
| Clean dependency installation | `npm ci --offline --cache .local/npm-cache --include=dev --no-audit --no-fund` passed |
| TypeScript | `npm run typecheck` passed after the final source changes |
| Production web build | `npm run build` passed; app, chart and motion chunks are each below 500 kB before compression |
| Navigation/page transitions | Opened manufacturing, BOM, administration and overview through the application |
| Manufacturing filters | Switching to Draft showed 2 matching records; switching to Active showed 6, with a moving selection indicator |
| Modal entry and keyboard focus | New manufacturing-order dialog focused its product selector; Escape dismissed it through the animated exit |
| Command palette | Opened with focus on the search field; Escape dismissed it |
| Production drawer and nested dialog | Opened `MO-2026-2231E0`, then its output form; the form focused quantity entry |
| Nested labels and focus restoration | Two open dialogs had distinct heading IDs; cancelling output left the drawer open and restored focus to “Record output” |
| BOM component transitions | Added a second material row, removed it and cancelled the draft editor |
| Reduced-motion preference | Selected Reduce motion, reloaded, and confirmed the saved choice and “Reduced motion is active” state |
| Restoring standard motion | Selected Follow device preference; the page reported `data-motion="full"`; the three-stage preview replayed |
| Mobile layout | At 390 × 844, navigation opened, a route selection closed it, the hidden sidebar was inert, and document width equalled the 390 px viewport |
| Browser console | No error entries captured at the end of these checks |

Screenshots: `docs/screenshots/motion-settings-desktop.jpg` and `docs/screenshots/motion-mobile.jpg`. Temporary viewport override was reset after mobile checks. The demo returns to device-controlled motion, not a forced reduced-motion preference.

Scope limits: the device-level reduced-motion branch is implemented but the operating-system preference was not changed during testing. No formal FPS, cross-browser, screen-reader certification or accessibility audit was performed. Current browser checks exercise presentation and cancellation; no new production completion or inventory posting was needed for this interface update.

## Manufacturing foundation baseline — 29 October 2026

The prior implementation ran **21 PostgreSQL integration tests, 0 failures, 0 errors**, including concurrency-safe reservations, idempotent completion, partial WIP reconciliation, immutable BOM approval, exact ratio rounding, cross-tenant denial, restricted self-approval, held-lot dispatch denial, incoming rejection reversal, closed-period rollback, account creation/login, and transactional migration failure rollback. These tests were not rerun for the presentation-only motion update; the backend was unchanged.

The prior browser workflow created `MO-2026-F10B71` for 1,000 units, independently approved it, reserved/issued materials, recorded 400 then 600 good units, passed both final inspections, closed the order and delivered all 1,000 units on `SO-2026-7EA8C4` using two delivery references. After the first posting, WIP was Rs. 10,146 and finished output value Rs. 6,764; after final posting, WIP was zero and cumulative finished output value Rs. 17,910. The final sales row showed 1,000/1,000 delivered.

The backend JAR exists at `services/erp-api/target/erp-api-0.1.0.jar`. Docker deployment, recovery drills, cloud synchronization and commercial Windows installation remain unverified or planned as recorded in the roadmap. Artifact existence is not a production-readiness claim.
