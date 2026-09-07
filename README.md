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
- Debug logging for integration troubleshooting.

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

## Use With Your Own Clan Website

The plugin has no hard-coded website domain. Set `Website base URL` in RuneLite to the public HTTPS root of your clan site, for example:

```
https://clan.example.com
```

The plugin appends its integration paths to that URL. Do not include a trailing `/api` path or an endpoint filename in the setting.

### Recommended Setup

1. Build the website endpoints listed below in your own application and connect them to your roster, Bingo, Wise Old Man, and Discord systems as needed.
2. Deploy the integration over HTTPS and configure its database, Wise Old Man group, and any Discord credentials on the server.
3. Install this plugin and set every player's `Website base URL` to your deployed site.
4. Enable only the plugin features your website supports. For example, leave Bingo drops disabled until your site has the Bingo endpoints and claim workflow in place.
5. Test using a non-production account before opening claims or location sharing to the clan.

The plugin does not include website code. Build the server-side integration required for the features you choose to enable.

### Server Requirements

- Authenticate and authorize every submission server-side. The RuneLite client and all submitted RSNs, timestamps, item IDs, coordinates, and screenshots are untrusted input.
- Match a logged-in RSN to an active clan roster before returning private panel, Bingo, location, or relay data.
- Treat `eventId` values as idempotency keys and reject duplicate claims instead of awarding or storing them twice.
- Validate uploaded images, set upload-size limits, strip metadata, and store files outside publicly executable paths.
- Verify Bingo rules, item eligibility, ownership requirements, and completion state on the server. Never approve a claim solely because the plugin submitted it.
- Keep location sharing player-controlled. The plugin suppresses Wilderness locations before building a request; the server should enforce the same rule and retain only data your clan needs.
- Return safe JSON errors. Do not expose stack traces, database errors, tokens, Discord secrets, or roster data to the plugin.

### Build Locally

```powershell
cd BingoDropCompanion
.\gradlew.bat compileJava
```

The project targets Java 11 and uses RuneLite's external-plugin APIs.
