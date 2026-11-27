# KOMA — Native Android Manga Reader

Clean-room Android implementation: Java 17, AndroidX RecyclerView and ViewPager2, Storage Access Framework, no network permission, accounts, advertisements or analytics.

## 1.2 Reference APK layout
- Compact text-first library and history rows; no cover decoding during library navigation. Search, all three sorting modes and chapter ordering retained.
- Centered reader empty state with explicit Open library / Select Folder actions, compact outlined buttons, dark surfaces and separate chapter and page navigation.
- System sans-serif and monospace fonts approximate the supplied visual reference without importing its binaries or changing the reader architecture.
- The reference layout is recreated in Android Views; this is not a pixel-identical Compose port.

## 1.1 Interface refresh (superseded visually)
- Responsive local manga cover grid, searchable manga and chapters, A–Z/recently read/recently added sorting (first indexed date), chapter list and cover-based history.
- Reader is the initial screen even after selecting a folder; tap controls fade in/out. Cover generation uses a separate cancellable worker and bounded local caches, with a placeholder for unsupported or oversized archives.
- Reading controls update only when visible and changed; redundant position saves are skipped. Physical-device refresh-rate and frame pacing validation remain required.

## Build
Use JDK 17, Gradle 8.9 and Android SDK 35. Run `gradle testDebugUnitTest lintDebug assembleDebug` and `gradle connectedDebugAndroidTest` with an emulator attached. GitHub Actions runs these checks, verifies the APK signature, and publishes a run-specific debug APK only on success. Debug application IDs are unique per CI run/attempt; production application ID is `com.koma.reader`. Release signing requires a persistent private keystore supplied via KOMA_KEYSTORE, KOMA_STORE_PASSWORD, KOMA_KEY_ALIAS and KOMA_KEY_PASSWORD. Never commit a keystore or credentials.

## Implementation and safety
- Folder picker and persisted SAF grants; recursive library index and natural ordering.
- Reader-centered swipe navigation; width-fit continuous pages; lazy chapter indexing and asynchronous decoding with bounded bitmap cache and disk-staged ZIPs.
- Per-manga resume position, per-chapter page coverage and content digest.
- Deletion policy evaluates root exclusion, local provider, write grant, recognized direct children, metadata and fresh chapter digests. **Permanent deletion is intentionally disabled**: SAF does not offer an atomic compare-and-delete guarantee. Preserve all files until storage-specific safety and end-to-end deletion tests establish an acceptable contract. No individual chapter is deleted.

## Verification status
This is a candidate implementation, not a production-certified release. Automated checks cover ordering, coverage, deletion policy, archive indexing, corrupt image handling and startup. Full device acceptance is still needed for first-run picker and nested SAF folders, exact resume after process death, rapid navigation, cross-chapter latency, very large archives, permission revocation, varied screen dimensions, high-refresh displays and deletion safety. Do not install a build as a production release merely because it compiles.
