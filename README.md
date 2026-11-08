# Index Paint

Index Paint is an Android app for two people to draw together on one shared canvas. It is built with Kotlin and currently connects nearby devices over Bluetooth Classic. Strokes and session data go directly to the connected peer; the app has no drawing server.

The original idea is a shared, turn-based drawing game: one person starts a picture and the other continues it. **Fold Game** makes that handoff visible: the drawer chooses where to fold, can leave optional drawn or typed notes outside the final picture, and yields the turn. The next person sees the revealed portion and continues. Finishing is acknowledged by both people. **Free Draw** shares a canvas without the fold rules.

Other tools include color and brush selection, erasing, undo and redo, movable text, A4/Letter paper, zoom, and saving the canvas as a PNG. The drawing protocol is separated from the Bluetooth transport so another transport could be added later; Bluetooth is the only transport implemented today.

## Project story

I started this project in Android's very early years—probably around 2008 or 2009, though I no longer remember the exact year. It stayed unfinished for a long time. In 2026 I came back to the original idea, moved the app to Kotlin, and streamlined it. It is still a work in progress.

## Build

### Requirements

- JDK 17
- Android SDK Platform 36
- Android SDK Command-line Tools (or Android Studio's SDK Manager)

With `sdkmanager` available, install the platform with:

```sh
sdkmanager "platforms;android-36"
```

The Gradle wrapper downloads the project's Gradle version automatically. Android Studio can open this repository directly, or build it from a terminal:

```sh
git clone https://github.com/ede73/IndexPaint.git
cd IndexPaint
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected Android device or emulator:

```sh
./gradlew installDebug
```

For emulator-to-emulator play, start two Android emulators, pair them in Android Settings, open Index Paint on both, and connect from one app. Both people must choose the same session mode before the game starts. Bluetooth behavior depends on the emulator image and host setup; physical Android devices are the intended use.

### Release builds

See [RELEASE.md](RELEASE.md) for the Mac mini workflow, signing setup, build and publishing commands, artifact download, and current release limitations.

## Screenshots

Fold Game across two Bluetooth-connected emulators: the drawer adds a handoff note beside the adjustable fold, while the peer sees the revealed drawing and waits for their turn. The note is guidance for the next drawer; it stays outside the finished picture.

![Index Paint Fold Game handoff: sender adding a note and peer viewing the fold reveal](screenshots/indexpaint-fold-game.png)

## Privacy

Index Paint has no account system, advertising, analytics SDK, or developer-operated server. When connected, drawing and session content is sent directly to the Bluetooth peer. Drawings stay on the device unless the user saves or shares them.

## License

Index Paint's original project code is licensed under the MIT License; see [LICENSE](LICENSE). Portions adapted from the Android Open Source Project BluetoothChat sample and the included Gradle wrapper retain their Apache License 2.0 terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LICENSE-APACHE-2.0](LICENSE-APACHE-2.0). Existing source-file notices remain in place.
