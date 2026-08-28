# Graffiti Hunter Android demo

The demo starts automatically from the existing `SampleScene`.

## On the phone

1. Allow camera, precise location, and microphone permissions.
2. Point the red box at graffiti.
3. Say **“graffiti”** or tap **CAPTURE GRAFFITI**.
4. The app saves a timestamped folder under its private Android app storage:
   `GraffitiReports/<UTC timestamp>/`

Each report contains:

- a JPEG with the red targeting box;
- `report.json` with phone GPS, accuracy, compass bearing, time, and estimated target;
- `map.url`, an OpenStreetMap link to the estimated point.

GPS plus compass cannot determine the wall's exact position without a distance/depth
measurement. For this first demo, the target point is explicitly marked as an estimate
15 metres ahead of the phone. A later version can add ARCore depth/raycasting or a
user-confirmed distance before integration with San Diego Get It Done/311.

## Build

Use **Graffiti Hunter > Build Demo APK** in Unity, or run Unity in batch mode with:

`-executeMethod GraffitiHunter.Editor.BuildGraffitiHunter.BuildDemoApk`
