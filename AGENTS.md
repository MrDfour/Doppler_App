# Sandman Doppler App — Agent Instructions

See [`CLAUDE.md`](./CLAUDE.md) for the full architectural guide and conventions. This file is the short version for agent tooling.

## The Short Version

1. **New behavior requires a passing test in `android/app/src/test/`.** If you add or modify logic in repository/api/services, add or update the Vitest-style JUnit tests and keep `DopplerProtocolTest` / `DopplerRepositoryTest` green.
2. **Bug fixes require a regression test.** The test must fail on the broken state.
3. **Run the quality gate before claiming you are done:**
   ```bash
   cd android && ./gradlew testDebugUnitTest && ./gradlew assembleDebug
   ```
   Both must report `BUILD SUCCESSFUL`. For web/`server.ts` changes also run `./node_modules/.bin/tsc --noEmit`.
4. **Standalone invariant:** the Android app talks directly to the clock over LAN (HTTPS port `5443`, two-tier nonce/`localKey` Bearer auth). No Home Assistant, no proxy server, no cloud dependency in the day-to-day path.
5. **Preserve the hardware concurrency constraint.** All clock requests are serialized through a coroutine `Mutex`/single scope in `DopplerLocalApi`/`DopplerRepository`. Never fan out parallel calls to the clock.
6. **Offline audit fields:** any state mutation in `DopplerRepository` must update `DopplerState` timestamps when relevant and roll back via optimistic-update rollback on API failure.
7. **Do not restyle code you were not asked to touch.** Avoid gratuitous import reordering, reformatting, or dependency swaps.
8. **Honesty & disclosure:** state which parts were verified by automated tests vs. requiring human hardware testing, and name your model.
