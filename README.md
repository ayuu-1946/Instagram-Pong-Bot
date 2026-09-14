# Instagram Pong Bot

Android MVP that watches the screen with MediaProjection, detects the Pong emoji and bottom paddle, predicts the next paddle intercept with horizontal wall-bounce reflection, and moves the paddle through AccessibilityService gestures.

## Use
1. Enable **Instagram Pong Bot** in Android Accessibility settings.
2. Open the bot and tap **Start Auto Play**.
3. Approve Android's screen-capture prompt.
4. Switch to Instagram and open the DM Pong game.
5. Stop from the bot app when finished.

No PC, ADB, or root is required.

## MVP notes
The detector uses dependency-free color/geometry heuristics tuned to the supplied recording. Instagram can change the game's artwork/layout, so device-specific calibration and adaptive segmentation are planned hardening steps. The GitHub Actions workflow builds a debug APK and uploads it as an Actions artifact.
