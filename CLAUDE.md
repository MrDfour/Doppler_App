# Teacher Buddy — Agent Instructions & Technical Reference

This document is the single source of truth for AI agents (Antigravity, Gemini, Claude, Cursor, Copilot) and developers contributing to **Teacher Buddy**.

---

## 1. Non-Negotiable Rules

1. **New behavior requires a test in `tests/` that passes.** Adding or changing logic in `src/db/`, `src/services/`, or `src/components/` without updating `tests/` is unacceptable.
2. **Bug fixes require a regression test.** Write or locate the regression test and verify that it locks in the fix permanently.
3. **Run the quality gate before reporting done:**
   ```bash
   npm run check
   ```
4. **Preserve line endings and avoid style thrashing.** Do not reformat files you were not asked to edit. Avoid gratuitous import reordering or whitespace churn that obscures the real git diff.
5. **Changelog Maintenance & Semantic Versioning:**
   - **Check latest tag first:** Before modifying [`CHANGELOG.md`](./CHANGELOG.md), always query the most recent git tag (`git tag -l --sort=-v:refname | head -n 1` or `git describe --tags --abbrev=0`).
   - **Never retroactively append to released versions:** NEVER document new work under a version/tag that has already been released or tagged in git (e.g., if `v0.4.0` is already tagged, do NOT append new changes under `## [0.4.0]`).
   - **Use superior `[Unreleased]` placeholder:** Always place new changes at the top under `## [Unreleased] - [Projected Version]`, following Semantic Versioning:
     - **Bugfixes / Patches (`x.y.Z+1`)**: Bug fixes, minor regressions, test adjustments without new public features (e.g., `## [Unreleased] - [0.4.1]`).
     - **Features / Minor (`x.Y+1.0`)**: Backwards-compatible new features, new UI modules, encryption/backup additions, internationalization (e.g., `## [Unreleased] - [0.5.0]`).
     - **Breaking / Major (`X+1.0.0`)**: Backwards-incompatible schema changes or fundamental architecture overhauls.
   - **Automated release finalization:** Release scripts ([`release-patch.bat`](./release-patch.bat), [`release-minor.bat`](./release-minor.bat), [`release-major.bat`](./release-major.bat) or `npm version`) automatically run [`scripts/finalize-changelog.mjs`](./scripts/finalize-changelog.mjs) during the `version` lifecycle hook, converting `## [Unreleased]` into the official `## [X.Y.Z] - YYYY-MM-DD` header inside the release commit and Git tag pushed to GitHub.
   - Categorize items cleanly under `### Added`, `### Changed`, `### Fixed`, or `### Removed`.
6. **Mandatory Internationalization (i18n):** Any user-visible dialogue, button, title, placeholder, alert, or toast MUST be placed in [`src/locales/es.json`](./src/locales/es.json) with its corresponding translation in [`src/locales/en.json`](./src/locales/en.json). Hardcoded text in TSX/TS files is strictly prohibited. All user-facing strings must be retrieved via `useTranslation()` (`t('key')`). Translation keys must maintain strict parity between `es.json` and `en.json`.

---

## 2. Verification Gate & Modular Testing

| Command | Scope & Purpose |
| :--- | :--- |
| **`npm run check`** | **CI Gate**: Typecheck (`lint`) + Full Tests (`test`) + Production Build (`build`). |
| `npm run lint` | TypeScript typecheck (`tsc --noEmit`). |
| `npm run test` | Run entire Vitest suite once. |
| `npm run test:watch` | Interactive Vitest test runner. |
| `npm run test:db` | Unit tests for `localDatabase` (CRUD, reactividad, LWW, deltas). |
| `npm run test:services` | Unit tests for `earlyWarningService` & `syncService`. |
| `npm run test:components` | Integration tests for React components (`Navbar`, `AttendanceScreen`, `GradebookScreen`). |
| `npm run dev` | Local development server on port 3000. |
| `npm run build` | Vite production bundle compilation to `dist/`. |

---

## 3. Traps in this Codebase

### ⚠️ Trap 1: Mutations without Offline Audit & Delta Sync Fields
In `src/db/localDatabase.ts`, every mutation that adds or modifies an entity (asistencias, calificaciones, criterios, tareas, alumnos) **MUST**:
- Set `isSynced: false`.
- Set `updatedAt: new Date().toISOString()`.
- Increment `version: (old.version || 0) + 1`.

*Why:* If an entity is modified without `isSynced: false`, the delta sync engine (`getUnsyncedPayload()`) will not see it, and offline edits will never reach the backend.

### ⚠️ Trap 2: Missing `this.notify()` on Database Mutations
`LocalDatabase` implements an in-memory Observer pattern for local reactivity. Any method mutating state must call `this.notify()`.
- `this.notify()` calls `this.saveToStorage()` and triggers all active subscribers.
- Failing to call `this.notify()` causes React components not to re-render and mutations to be lost on page reload.

### ⚠️ Trap 3: Last-Write-Wins (LWW) Remote Reconciliation
In `localDatabase.applyRemoteDeltas()`, remote deltas are reconciled using timestamps:
- The remote record **only wins if `remoteDate > localDate`**.
- If `remoteDate <= localDate`, the local record is preserved (`skipped++`).
- When matching records, match by entity `id` (e.g. `cal.id`), not just composite fields.

### ⚠️ Trap 4: Early Warning Engine $O(N)$ Complexity
`earlyWarningService.ts` evaluates risks in real-time during attendance taking and grade calculations.
- Must remain strictly $O(N)$ single-pass over attendance history.
- Thresholds:
  - Absenteeism $> 15\% \to$ Alerta Preventiva (`NivelRiesgo.medio`).
  - Absenteeism $> 20\% \to$ Riesgo Crítico (`NivelRiesgo.alto`).
  - Academic risk $\to$ weighted average $< 70$ (`NivelRiesgo.alto`) or $\ge 2$ missing assignments (`NivelRiesgo.medio`).

### ⚠️ Trap 5: Global Keyboard Shortcuts (Desktop 1-4)
In `AttendanceScreen.tsx`, keys `1`, `2`, `3`, `4` capture attendance states (Presente, Retardo, Falta Justificada, Falta Injustificada).
- **Never** trigger keyboard shortcuts when the event target is an `<input>`, `<textarea>`, or `<select>`. This is guarded by checking `e.target instanceof HTMLInputElement`.

### ⚠️ Trap 6: Hardcoded User-Facing Text (Missing i18n)
Never hardcode raw user-visible strings (such as modal titles, confirmation dialogs, error messages, button labels, or notifications) in JSX or component logic.
- Always declare the strings in [`src/locales/es.json`](./src/locales/es.json) and provide the English counterpart in [`src/locales/en.json`](./src/locales/en.json).
- Access them in components via `useTranslation()`: `const { t } = useTranslation(); t('namespace.key')`.
- Ensure parity: every key added to `es.json` MUST exist in `en.json`.

---

## 4. Code Style & Architecture Conventions

- **React 19 & TypeScript**: Strict typing, functional components, hooks (`useState`, `useEffect`, `useRef`).
- **Tailwind CSS v4**: Utility-first styling via `@tailwindcss/vite`.
- **Icons**: Use `lucide-react` icons.
- **Animations & Micro-interactions**: `motion` and `canvas-confetti` for celebratory actions.
- **Comments**: Comment *why*, not *what*. Explain constraints, algorithm choices, and rejected alternatives.

---

## 5. Honesty & Disclosure

When you complete a task or report results:
1. **Identify yourself**: State your model name (e.g., Gemini 3.7 Flash, Claude 3.5 Sonnet).
2. **Be precise about coverage**: Clearly distinguish which parts were verified by automated tests (`npm run check`) and which parts require manual human testing (e.g. visual CSS adjustments or native device interactions).
3. **Never guess**: If a command or test failed, explain the exact error and the fix applied.
