# Teacher Buddy — Agent Instructions

See [`CLAUDE.md`](./CLAUDE.md) for the full architectural guide and non-negotiables. Kept as a separate file so agent tooling looking for `AGENTS.md` finds it immediately.

## The Short Version

1. **New behavior requires a passing test in `tests/`.** If you add or modify logic in `src/`, add or update corresponding tests in `tests/`.
2. **Bug fixes require a regression test.** Ensure the test asserts the fix and would fail on the broken state.
3. **Execute the gate command before claiming you are done:**
   ```bash
   npm run check
   ```
   This runs TypeScript type checking (`tsc --noEmit`), the entire Vitest suite (40+ tests), and a full production build (`vite build`).
4. **Offline-First & Delta Sync invariants:**
   - Any database mutation in `localDatabase.ts` MUST update `isSynced: false`, `updatedAt: new Date().toISOString()`, and increment `version`.
   - Always invoke `this.notify()` after mutating state in `localDatabase.ts`.
   - Never break the $O(N)$ single-pass complexity in `earlyWarningService.ts`.
5. **Do not restyle code you were not asked to touch.** Avoid reformatting unchanged lines, reordering imports, or replacing functional dependencies without explicit user request.
6. **Honesty & Disclosure:** If you could not run tests or are unsure about a change, state it clearly in your summary. Name your specific model and identify which parts were written by AI and which require human testing.
7. **Changelog Maintenance & Semantic Versioning:**
   - **Consulta obligatoria de tag reciente:** Antes de escribir en [`CHANGELOG.md`](./CHANGELOG.md), verifica el tag más reciente (`git tag -l --sort=-v:refname` o `git describe --tags --abbrev=0`).
   - **Prohibido modificar versiones ya liberadas:** NUNCA escribas cambios nuevos bajo una versión o tag que ya exista o haya sido liberado en Git (por ejemplo, si el tag más reciente es `v0.4.0`, NO agregues cambios bajo `## [0.4.0]`).
   - **Uso estricto de placeholder superior (`Unreleased`):** Los cambios nuevos DEBEN registrarse siempre al inicio bajo `## [Unreleased] - [Versión Proyectada]`, diferenciando claramente el tipo de incremento semántico:
     - **Parches y Bugfixes (`x.y.Z+1`)**: Correcciones de errores, regresiones, fallos visuales o ajustes de pruebas sin nuevas funciones públicas (ej. `## [Unreleased] - [0.4.1]`).
     - **Funcionalidades Nuevas / Menor (`x.Y+1.0`)**: Nuevas características compatibles hacia atrás (ej. nuevos modales, respaldos cifrados, internacionalización) (ej. `## [Unreleased] - [0.5.0]`).
     - **Mayor (`X+1.0.0`)**: Cambios incompatibles o rediseño mayor de la base de datos/arquitectura.
   - **Cierre automatizado en scripts de release:** Los scripts de lanzamiento ([`release-patch.bat`](./release-patch.bat), [`release-minor.bat`](./release-minor.bat), [`release-major.bat`](./release-major.bat) o `npm version`) ejecutan automáticamente [`scripts/finalize-changelog.mjs`](./scripts/finalize-changelog.mjs), transformando el encabezado `## [Unreleased]` a la versión oficial `## [X.Y.Z] - YYYY-MM-DD` dentro del commit y Git tag que se suben a GitHub.
   - Organiza siempre el contenido en las secciones estándar: `### Added`, `### Changed`, `### Fixed`, `### Removed`.
8. **Obligatoriedad de Internacionalización (i18n):** Todo texto, diálogo, botón, etiqueta, mensaje de error, placeholder o notificación visible para el usuario DEBE residir obligatoriamente en [`src/locales/es.json`](./src/locales/es.json) y con su correspondiente traducción en [`src/locales/en.json`](./src/locales/en.json). Está estrictamente prohibido introducir texto directo o quemado en componentes TSX/TS. Debe consumirse siempre a través de `useTranslation()` (`t('clave')`). Toda nueva clave añadida debe mantener estricta paridad entre ambos idiomas.
