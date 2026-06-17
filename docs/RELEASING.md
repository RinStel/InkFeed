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

Before publishing, install the signed APK on an API 26+ device and verify feed
sync, page navigation, database upgrade, SAF output, and EPUB generation.

Also verify:

- The release APK starts on at least one vendor E Ink device running Android 11 or below.
- `aapt dump badging app\build\outputs\apk\release\app-release.apk` shows `sdkVersion:'26'`.
- `apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk` reports V2 and V3 as `true`.
- The final APK size is reasonable after R8/resource shrinking, and startup still works with obfuscation enabled.

## Release Recommendations

- Use `v0.1.1` as the first public tag if you are shipping the current compatibility fixes.
- Publish the signed `app-release.apk` directly on GitHub Releases.
- In release notes, explicitly state `Android 8.0+`, `HTTP/HTTPS feeds supported`, and `optimized for E Ink devices`.
- Attach the APK as a release asset; InkFeed's in-app update prompt opens the first `.apk` asset from the latest GitHub Release.
- Mention that the app is tested on both a mainstream Android phone and a vendor E Ink reader before marking it as non-pre-release.
