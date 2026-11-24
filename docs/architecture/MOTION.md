# Interface motion

Implemented 30 October 2026. This is a code-native Figma-style interaction system; no Figma reference or exported Figma design is claimed.

## Shared configuration

`apps/erp-web/src/motion/motion.tsx` owns spring/easing constants, motion preferences, page transitions, filter tabs and the interactive settings preview. `motion/motion.css` owns CSS timings, focus/hover feedback and reduced-motion overrides. `MotionProvider` wraps the workspace in `main.tsx`. The production bundler separates Motion into its own chunk.

| Interaction | Behavior |
|---|---|
| Page change | 90 ms outgoing fade, 220 ms incoming fade with 8 px vertical travel; outgoing content is inert |
| Page loading | Pages are lazy chunks. A preloaded page renders inside the incoming fade with no extra step; otherwise the routed area (inside the same page transition, below the heading and context bar) shows the shared skeleton, which fades in over 220 ms after a 140 ms delay so quick loads never flash it. The `/api/state` load uses the same skeleton |
| Active navigation | Shared selection background and edge marker move with a nearly critically damped spring |
| Filter views | Shared underline moves between selected tabs; data filtering happens immediately. Tabs follow the WAI-ARIA tabs pattern (Arrow Left/Right, Home, End); on phones a long tab strip scrolls sideways |
| KPI cards | 300 ms reveals staggered by 45 ms; the actual numeric value is never interpolated |
| Operational cards | Small, pointer-only lift/shadow feedback; informational panels reveal once when entering view |
| Modal dialogs | Short spring entrance and 160 ms exit; native dialog focus trapping remains active |
| Production drawer | Horizontal entrance/exit using the same timing family |
| Progress bars | Spring width transitions only when the underlying completion value changes; accessible values update immediately |
| Tables | Existing rows interpolate position after sorting/filtering; removed records are not retained as actionable rows |
| BOM components | 200 ms expand/collapse; exiting inputs are disabled and inert, so they cannot block native validation or accept input |
| Notifications | Short entrance/exit, with existing live-region semantics |
| Controls | Restrained press, hover, focus, favorite and lifecycle feedback |

## Accessibility and transaction boundaries

Administration → Interface motion offers **Follow device preference** and **Reduce motion**, persisted under the local presentation-only `dex-motion` key. A device request for reduced motion always wins. Reduced mode disables spatial/layout animations and CSS transitions, shortens opacity feedback, and removes preview staggering. With reduced motion the loading skeleton appears immediately, without the delayed fade.

Dialog closing prevents the browser's default immediate dismissal so the exit can finish. Cleanup closes the native dialog before removing it, then restores focus to its initiating control when that control still exists and another modal has not taken focus. Each dialog has its own accessible heading ID. Exiting dialogs/pages are inert; animations never postpone API requests or simulate successful transactions. All quantities, costs and statuses remain server-controlled values.

The settings preview is an explicitly decorative, manually replayed three-stage illustration. It contains no invented operational numbers, synchronization state or telemetry.

## Verification

See `docs/VERIFICATION.md` for executed browser checks, build results and limits. Performance was visually checked in the available desktop browser and at a 390 px viewport; no measured frame-rate or cross-browser performance guarantee is claimed.

Implementation references: [Motion layout animation](https://motion.dev/docs/react-layout-animations), [AnimatePresence](https://motion.dev/docs/react-animate-presence), and [reduced motion](https://motion.dev/docs/react-accessibility).
