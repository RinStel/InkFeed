# Release Checklist

This document is for project maintainers.

## Signing

Create and permanently back up the release keystore:

```powershell
keytool -genkeypair -v `
  -keystore release/inkfeed-release.jks `
  -alias inkfeed `
  -keyalg RSA -keysize 4096 -validity 10000
```

Copy `signing.properties.example` to `signing.properties` and fill in the real
keystore path and passwords. Neither file may be committed.

The build also accepts:

- `INKFEED_STORE_FILE`
- `INKFEED_STORE_PASSWORD`
- `INKFEED_KEY_ALIAS`
- `INKFEED_KEY_PASSWORD`

## Verification

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```

Before publishing, install the signed APK on an API 30+ device and verify feed
sync, page navigation, database upgrade, SAF output, and EPUB generation.
