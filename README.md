# LanChat

Chat between nearby Android phones with no internet, router or account. Messages hop from phone to phone over Bluetooth and Wi-Fi, so a group can stay in touch where there is no signal.

Status: early preview (v0.1.0). The JVM unit tests pass (29 of 29) and the debug build compiles. It has not been tested on a range of real devices, and its encryption design has not been independently reviewed. Do not rely on it where your safety depends on it.

## What it does

- Finds nearby phones and relays messages across several hops, using Google Nearby Connections.
- Uses Ed25519 signatures and encrypts messages with keys agreed over X25519 and AES-GCM, through the Tink library.
- Verify a contact by scanning their QR code.
- Voice notes, image transfer in chunks, voice calls and a compass view of nearby peers. These are in the code; how well they work on real hardware is not yet measured.

## Limits

- Needs Google Play Services (for Nearby). It will not work on phones without it.
- Needs Bluetooth, Nearby Wi-Fi, camera, microphone, and on older Android versions location permission.
- Your identity keys are stored in the app's private storage and are not yet wrapped by the Android Keystore. Backup of app data is turned off.
- Android only.

## Build

Open in Android Studio (JDK 21) and run the app configuration, or use Gradle 9.3.1: `gradle :app:assembleDebug`. Run the unit tests with `gradle :app:testDebugUnitTest`. Release signing reads KEYSTORE_PATH, STORE_PASSWORD and KEY_PASSWORD from the environment. CI builds are debug builds for testing only.

## License

Apache License 2.0, see LICENSE. Bundled fonts (Inter, Plus Jakarta Sans, JetBrains Mono) are under the SIL Open Font License 1.1, see THIRD_PARTY_NOTICES.md.
