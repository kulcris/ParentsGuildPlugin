# ParentsGuild

RuneLite external plugin for two ParentsGuild workflows:

- submit screenshot-backed bingo drop claims to the ParentsGuild website
- show active Wise Old Man group events with in-plugin leaderboards and end-of-event refresh warnings
- show an in-game bingo overlay for matched active boards

## Features

- Watches NPC and player loot events.
- Captures the next rendered frame as a PNG proof screenshot.
- Submits multipart form-data to `/api/integrations/bingo-drop.php`.
- Sends the raw UTC capture timestamp with every bingo drop submission.
- Shows a RuneLite overlay with the active bingo name in green plus the matched team and event time in white.
- Loads the WOM group ID from the ParentsGuild website and shows active competitions for that group.
- Displays a leaderboard for each active competition inside the plugin sidebar.
- Warns players to refresh WOM hiscores shortly before an event ends.

## Plugin Config

- `Website base URL`: example `https://theparentsguild.org`
- `Enable bingo drops`
- `Bingo drop token`: use the per-board token shown on the bingo host controls page
- `Show bingo overlay`
- `Enable WOM event tracking`
- `WOM refresh interval`
- `WOM warning minutes`
- `Leaderboard rows`
- `Debug logging`

## Build

This project targets Java 11.

```powershell
$env:JAVA_HOME='C:\Users\kulcr\.jdks\temurin-11.0.30'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat test
.\gradlew.bat shadowJar
```

The packaged jar is written to `build\libs\`.
