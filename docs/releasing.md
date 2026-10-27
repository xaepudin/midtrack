# Releasing

How to build, sign and publish Midtrack. The app is distributed as a signed
APK on GitHub Releases; the same AAB can be uploaded to Google Play later.

## Versioning

`versionCode` and `versionName` live in `app/build.gradle.kts`. For a release:

```kotlin
versionCode = 1
versionName = "1.0.0"
```

Bump both, commit, then tag the commit with the same version prefixed by `v`:

```bash
git tag v1.0.0
git push origin main
git push origin v1.0.0
```

Add the repository secrets below before pushing the tag; the release job fails
rather than publishing an unsigned package.

The version is also shown in the app footer through `BuildConfig.VERSION_NAME`,
so there is only one place to edit.

## Signing key

The GitHub release must be signed with a stable key. The key is **not** in this
repository. This checkout uses:

```
~/.android/keystores/midtrack-release.keystore
alias: midtrack
```

The certificate (SHA-256) is:

```
FF:A3:23:4E:9F:36:72:A0:3E:D6:F6:61:F8:ED:AB:43:CA:91:FC:02:7E:09:A9:55:B6:28:BE:D9:A2:54:3E:F8
```

Back up the keystore and its password somewhere safe, offline. Losing it means
existing installs cannot receive an update signed by the same identity. Never
commit the keystore or `keystore.properties`.

### Create a new key (only if starting over)

```bash
keytool -genkeypair -v \
  -keystore ~/.android/keystores/midtrack-release.keystore \
  -storetype PKCS12 -storepass 'CHANGE_ME' -keypass 'CHANGE_ME' \
  -alias midtrack -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Midtrack, OU=Android, O=xaepudin, L=Bandung, ST=West Java, C=ID"
```

Then create `keystore.properties` in the repository root (gitignored):

```properties
storeFile=/absolute/path/to/midtrack-release.keystore
storePassword=…
keyAlias=midtrack
keyPassword=…
```

## Local release build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew testDebugUnitTest lintDebug assembleRelease bundleRelease

# Verify the signature before publishing
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties`, `assembleRelease` still runs, but the APK is
left unsigned rather than silently signed with the debug key. The release
workflow fails instead of publishing an unsigned artifact.

R8 minifies and shrinks release builds. The mapping file is written to
`app/build/outputs/mapping/release/mapping.txt`; keep it with the release so
crash reports can be deobfuscated.

## GitHub Actions

### CI (`.github/workflows/ci.yml`)

Runs on every push and pull request:

- JDK 21, Gradle wrapper cache
- `./gradlew testDebugUnitTest lintDebug assembleDebug`
- uploads the debug APK and lint report

### Release (`.github/workflows/release.yml`)

Runs on tags matching `v*`, and manually from the Actions tab. A tag push:

1. checks out the tag;
2. decodes the keystore from repository secrets;
3. builds and tests the signed APK and AAB;
4. verifies the APK signature with `apksigner`;
5. names the files `midtrack-<version>.apk` and `midtrack-<version>.aab`;
6. generates SHA-256 checksums;
7. uploads the R8 `mapping.txt` as a workflow artifact;
8. creates a GitHub Release with the files attached and generated notes.

A manual run only uploads workflow artifacts; it does not create a release.

### Repository secrets

Add these in **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The keystore, base64-encoded (single line) |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword` |
| `ANDROID_KEY_ALIAS` | `midtrack` |
| `ANDROID_KEY_PASSWORD` | `keyPassword` |

With the GitHub CLI:

```bash
base64 -i ~/.android/keystores/midtrack-release.keystore | tr -d '\n' > /tmp/keystore.b64
gh secret set ANDROID_KEYSTORE_BASE64 < /tmp/keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS --body midtrack
gh secret set ANDROID_KEY_PASSWORD
rm /tmp/keystore.b64
```

On Linux use `base64 -w0 file` instead of `base64 -i file | tr -d '\n'`.

## Publishing the GitHub release

The repository is <https://github.com/xaepudin/midtrack> and `origin` is
already configured. The release workflow lives on `main`; add the repository
secrets first, then push the tag:

```bash
git push -u origin main
git push origin v1.0.0
```

Watch the run:

```bash
gh run list --workflow release.yml
gh run watch
gh release view v1.0.0
```

## Google Play later

The release workflow also produces a signed `.aab`. See
[play-store.md](play-store.md) for the store listing, data-safety answers, the
foreground-service declaration and the notification-access declaration. Upload
to an **internal testing** track first; the app is not on Play today.

## Post-release checklist

- [ ] `versionCode` increased and `versionName` matches the tag
- [ ] APK verifies against the expected certificate
- [ ] Release contains APK, AAB and SHA-256 checksums
- [ ] `mapping.txt` archived with the release
- [ ] README download link still resolves
- [ ] Fresh install on a clean device: notification access, notification
      permission, first track baseline, next track seek
