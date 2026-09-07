# ParentsGuild

RuneLite external plugin for clan website workflows:

- submit screenshot-backed bingo drop claims to the configured clan website
- show active Wise Old Man group events with in-plugin leaderboards and end-of-event refresh warnings
- show an in-game bingo overlay for matched active boards

## Features

- Configurable clan website base URL; no hard-coded production domain.
- Roster-matched clan profile, rank, points, announcements, and quick links in the RuneLite sidebar.
- Upcoming clan events with localized dates/times, status, compact timeline, locations, and Wise Old Man event links.
- Active Wise Old Man competition cards with leaderboards, player rank, gap to next place, compact `k`/`m` gains, and full-value hover text.
- Optional Wise Old Man update request when logging out or hopping worlds.
- Automated Bingo item-drop detection from eligible loot sources.
- PNG screenshot capture for automatic drop claims and manual tile proof submissions.
- Chatbox redaction option for Bingo proof screenshots.
- UTC proof timestamps, idempotent event IDs, item IDs, quantities, source information, and location data submitted with claims.
- Active Bingo overlay showing game, team, and event timing.
- In-game Bingo board popup with tile images, backgrounds, completion state, requirements, descriptions, and restrictions.
- Local XP, kill-count, clue, and supported metric tracking for Bingo metric tiles.
- Optional Lifetime Loot submissions for supported non-PvP loot.
- Player-controlled live location sharing with a server-configured cadence.
- Privacy gate for location sharing: player must be logged in, in clan chat, have Private Chat set to On/Friends, and be outside the Wilderness.
- Clan chat relay to Discord, including clan guests and clan achievement broadcasts.
- Discord-to-clan-chat relay with clan-rank icons, configurable Discord message color, duplicate suppression, and no backlog replay after login.
- Player display preferences for DD/MM/YY dates and 24-hour time.

## Plugin Config

- `Website base URL`
- `Enable bingo drops`
- `Show bingo overlay`
- `Enable bingo metric tracking`
- `Hide chat in proof screenshots`
- `Send Lifetime Loot`
- `Enable WOM event tracking`
- `Submit WOM refresh on logout`
- `WOM refresh interval`
- `WOM warning minutes`
- `Leaderboard rows`
- `Use DD/MM/YY dates`
- `Use 24-hour time`
- `Discord chat text color`
- `Share live location`
- `Debug logging`