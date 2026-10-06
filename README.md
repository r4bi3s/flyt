# Flyt

Flyt is a calm, one-handed Android launcher. **Fast by default. Quiet when idle. Nothing between intent and action.**

From Home there are three ways forward:

- **Radial** — start where your thumb rests (or from a fixed spot) and drag in a direction to open a learned app or action. Directions can hold tags whose members appear as a second ring.
- **Search** — find an app, contact, action or app shortcut by typing.
- **Apps** — a predictable app list with local, overlapping tags.

Radial geometry, handedness and bindings adapt to your hand. Themes can be picked from the bundled palettes and the default Krets theme, created from your own photos, imported from a ZIP or installed from an [Omarchy](https://omarchy.org) theme on GitHub when you ask for it.

Flyt has no account, ads or analytics, and no background service. Network access is used only when you install a theme from a GitHub link (HTTPS, fixed hosts). Contacts are read only after you enable contact results.

## Build

Requires JDK 17 and Android SDK 36 (`ANDROID_HOME` or `local.properties`):

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` also verifies the merged manifest against a permission and component allowlist.

## Verifying the APK

Official APKs on GitHub Releases are signed with this certificate (SHA-256):

```
02:18:7F:EB:E5:09:58:A7:AF:E8:97:7F:E9:BB:75:8D:95:E4:19:32:C4:2C:6D:EE:85:7D:32:FB:C9:61:BF:B9
```

Check with `apksigner verify --print-certs flyt-<version>.apk`, or compare the `.sha256` file published with each release.

## License

Copyright (C) 2026 Heimlager.

Flyt is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see [LICENSE](LICENSE). SPDX-License-Identifier: `GPL-3.0-or-later`.

Bundled third-party theme palettes keep their own MIT licenses; see [the theme notice](app/src/main/assets/themes/NOTICE.txt). The license covers the code and does not grant rights to present a modified build as the official app or to use its name and icon for that purpose.
