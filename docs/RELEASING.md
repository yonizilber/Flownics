# Releasing Flownics

How a version gets from this repository to a friend's phone. The app has no server and no
accounts, so a release is just a signed APK.

## Once: the signing key

Android only installs an update when it is signed with the same key as the version already on
the phone. Lose the key and nobody can update; they would have to uninstall (losing their data
unless they made a backup in the app) and install again.

1. Make the key, outside the project folder (here `C:\Users\Yonatan\keys`), with the JDK that
   comes with Android Studio. It asks for a password and a name:

   ```
   "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v ^
     -keystore C:\Users\Yonatan\keys\flownics-release.jks -alias flownics ^
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` in the project folder (it is in `.gitignore`; never commit it):

   ```
   storeFile=C:/Users/Yonatan/keys/flownics-release.jks
   storePassword=...
   keyAlias=flownics
   keyPassword=...
   ```

3. Back up the `.jks` file and the password together, somewhere other than this computer (a
   password manager holds both).

Android Studio can do steps 1–2 too: Build > Generate Signed App Bundle or APK > Create new.

## Every release

1. Raise `versionCode` (by one; Android refuses an update with the same or a lower one) and
   `versionName` in `app/build.gradle.kts`.
2. Build and test:

   ```
   gradlew.bat testDebugUnitTest assembleRelease
   ```

   The APK is `app/build/outputs/apk/release/app-release.apk`. If it is called
   `app-release-unsigned.apk`, `keystore.properties` was not found.
3. Install it on your own phone over the old version first (`adb install -r`), and check that
   your data is still there: an update keeps data only when the signature matches.
4. Tag and publish on GitHub, with the APK attached:

   ```
   git tag v0.6.0
   git push origin v0.6.0
   gh release create v0.6.0 app/build/outputs/apk/release/app-release.apk#Flownics-0.6.0.apk ^
     --title "Flownics 0.6.0" --notes "What changed..."
   ```

## What a friend does

See "Installing" in the [README](../README.md#installing): download the APK from the Releases
page, allow the browser (or file manager) to install apps, open it. Updates: the same, over the
installed app. Each person's data stays on their phone; the app's own backup (Me > Settings >
  Backup and sharing)
is how it moves to a new phone.

## Before going wider than friends

- Google Play needs a developer account, a privacy policy page, the Data safety form, and a
  justification for the `specialUse` foreground service (the text is in the manifest).
- Garmin's personal API key is "not intended for commercial applications serving multiple end
  users". Each friend would use their own key in their own app; ask Garmin before offering this
  more widely.
- The name "Flownics" is also a Korean flow-meter company (flownics.kr).
