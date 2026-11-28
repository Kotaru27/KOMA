# KOMA — Native Android Manga Reader

Clean-room Android implementation: Java 17, AndroidX RecyclerView and ViewPager2, Storage Access Framework, no network permission, accounts, advertisements or analytics.

## 1.3 Sorting and completed-folder cleanup
- Seven library sort modes and independent ascending/descending chapter display, persisted separately. Reader traversal always stays in natural chapter order.
- Completion uses a new evidence namespace: a decoded page segment must be visible in consecutive observations while the reader is active. Earlier builds' completion certificates are not used for deletion.
- Automatic cleanup runs when leaving a fully completed manga (swiping to Library/History or opening another manga). Only the local primary external-storage provider with a persisted write grant and a strict descendant folder is eligible. Every direct child must be an identified, certified CBZ with known metadata; content digests and a fresh matching folder snapshot are required. Only one folder-level delete is requested. Unknown files, new chapters, missing metadata, alternate providers and failed checks preserve the folder.
- Android SAF has no atomic compare-and-delete: a concurrent external modification in the narrow interval after the final scan is a residual risk. Do not modify the folder during cleanup. Device-specific destructive testing on disposable folders is still required.

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
- Deletion safeguards use strict local folder identity, complete chapter evidence and a fresh matching snapshot; see 1.3 above.

## Verification status
This is a candidate implementation, not a production-certified release. Automated checks cover ordering, coverage, deletion policy, archive indexing, corrupt image handling and startup. Full device acceptance is still needed for first-run picker and nested SAF folders, exact resume after process death, rapid navigation, cross-chapter latency, very large archives, permission revocation, varied screen dimensions, high-refresh displays and deletion safety. Do not install a build as a production release merely because it compiles.
