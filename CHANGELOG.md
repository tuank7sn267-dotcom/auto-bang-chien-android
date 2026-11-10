# Changelog

## 0.2.0
- Port the latest Python state-machine behavior into the Android engine.
- Replace the initial Kotlin pixel matcher with OpenCV Android template matching.
- Add red selected-deck check detection and wait-before-attack behavior.
- Add sequential Accessibility tap queue and queued-tap cancellation when leaving battle.
- Add screen-change waiting and reuse of the first recognized next frame.
- Add unknown-screen debug screenshots and stop-safe threshold.
- Add dry-run mode, battle limit, idle wait, visible log, and stop action in foreground notification.
- Add GitHub Actions workflow to build and upload a debug APK artifact.
