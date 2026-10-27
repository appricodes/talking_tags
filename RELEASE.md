# Releasing Talking Tags to Google Play

Same setup as the other apps on this machine (see Stylopia's `RELEASE.md`).

## Build a signed bundle

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab` (with `mapping/release/mapping.txt`
beside it, which the upload tool sends too).

Signing reads `keystore.properties` in the project root (git-ignored). The upload key is
`C:\Users\amiri\AndroidStudioProjects\data\voicetag-upload.p12`, alias `upload`. Keep a backup of
that file and `keystore.properties` off this machine. Without the properties file the release build
is left unsigned. Check before uploading:

```bash
jarsigner -verify -verbose:summary -certs app/build/outputs/bundle/release/app-release.aab
```

You want `CN=Mohammad Amiri Moalla, O=Appricodes`.

| | |
|---|---|
| Application ID | `com.appricodes.voicetag` |
| Upload key | RSA 2048, created 2026-10-27, valid to 2054, SHA-256 `0B:68:60:60:77:7B:36:A8:CE:10:FA:C6:43:DA:5E:AC:3F:A9:E1:33:EC:01:60:38:2A:D9:64:92:A6:B8:E8:E8` |

The app is signed by Google (Play App Signing); this key is only the *upload* key. The earlier
upload key was lost, so this one was registered through **App integrity → Request upload key
reset** with `upload_certificate.pem`. Play accepts bundles signed with it only after Google
approves the reset.

Bump `versionCode` in `app/build.gradle.kts` before every upload.

## Publishing

Uploads use the shared tool `C:\Users\amiri\projects\PlayStore` (its `README.md` is the full guide)
and its shared service account `play-release@memovision-app.iam.gserviceaccount.com`. This project
only adds `playstore.json` and the listing in `store/listings/<language>/` (pulled from Play on
2026-10-27).

Unlike the other apps, for Talking Tags this account **may also release to production** (granted
2026-10-27 at the owner's request), so `--track production` is not refused. Double-check the track
before every push.

```bash
play --pull-listing
play --check-listing
play --push-listing --dry-run
play --aab app/build/outputs/bundle/release/app-release.aab --track internal --notes "what changed"
play --version-code 102 --track closed --notes "what changed"
```

(`play` is `C:\Users\amiri\projects\PlayStore\play.cmd`.)
