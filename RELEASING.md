# Releasing Flyt

Flyt ships the same app, package `no.heimflyt.launcher`, through two channels: a free signed APK on GitHub Releases and a paid listing on Google Play. Both are signed with **one app signing key**, so users can move between channels without reinstalling and losing their setup.

Gradle builds release outputs **unsigned**; no key or password ever enters the build. Signing happens afterwards with `release/sign.sh`, run by the maintainer, which fetches the key from Bitwarden into memory (`/dev/shm`) and deletes it when done. Automated agents prepare builds and text but never sign.

## Keys

| Key | Used for | Stored |
|---|---|---|
| App signing key (`flyt-app-signing`) | GitHub APKs; given to Play App Signing once | Bitwarden «Lunni Flyt - Android app signing key» + offline backup. Never in CI secrets. |
| Upload key (`flyt-upload`) | Signing bundles uploaded to Play | Bitwarden «Lunni Flyt - Android upload key» |

Each Bitwarden item: username = key alias, password = keystore password, the `.jks` file as attachment. Public certificates live in `~/lunni-keys/`.

Losing the app signing key ends GitHub updates for existing installs (Play keeps working, since Google holds a copy). A leaked app signing key lets others sign APKs that install over Flyt. Keep the offline backup and treat the key accordingly. A lost upload key can be reset through Play support.

## One-time setup

1. `release/make-keys.sh` — creates both keystores in `/dev/shm` and exports the public certificates. Choose the passwords in Bitwarden.
2. Create the two Bitwarden items above and copy `flyt-app-signing.jks` to the offline backup.
3. In Play Console, create Flyt as a **paid** app (it can never become paid later). Under *Test and release → App integrity → Play App Signing* choose to use your own key: *Export and upload a key from Java keystore*. Download `pepk.jar` and the encryption key, then run the PEPK command the console shows against `flyt-app-signing.jks` (alias `flyt-app-signing`). Upload the resulting zip and `~/lunni-keys/flyt-upload.pem` as the upload certificate.
4. Delete the `/dev/shm/flyt-keys.*` folder.
5. Publish the app signing certificate SHA-256 (from `release/sign.sh apk` output or Play Console) in the README so users can verify APKs.

## Each release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`; commit and tag `v<versionName>`.
2. `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease`
3. `export BW_SESSION=$(bw unlock --raw)`, then `release/sign.sh apk` and `release/sign.sh aab`. Outputs go to `dist/` (ignored by Git).
4. Play: upload `dist/flyt-<version>.aab` (the first build must be uploaded in Play Console). Keep `app/build/outputs/mapping/release/mapping.txt` with the release for crash deobfuscation.
5. GitHub: create a release for the tag with `dist/flyt-<version>.apk`, its `.sha256` and the release notes.

Store submission and public release are separate maintainer decisions.
