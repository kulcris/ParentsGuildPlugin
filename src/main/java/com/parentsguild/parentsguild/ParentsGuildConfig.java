package com.parentsguild.parentsguild;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("parentsguild")
public interface ParentsGuildConfig extends Config
{
    @ConfigSection(
        name = "Bingo",
        description = "Bingo drop submission and overlay settings.",
        position = 1
    )
    String bingoSection = "bingo";

    @ConfigSection(
        name = "WOM events",
        description = "Wise Old Man event tracking and leaderboard settings.",
        position = 2
    )
    String womSection = "wom";

    @ConfigItem(
        keyName = "websiteBaseUrl",
        name = "Website base URL",
        description = "The ParentsGuild website base URL, such as https://theparentsguild.org.",
        warning = "This plugin submits screenshots and account data to a 3rd party website not controlled or verified by the RuneLite Developers.",
        position = 0
    )
    default String websiteBaseUrl()
    {
        return "";
    }

    @ConfigItem(
        keyName = "enableBingoDrops",
        name = "Enable bingo drops",
        description = "Capture loot drops and submit them to the ParentsGuild bingo drop endpoint.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
        section = bingoSection,
        position = 0
    )
    default boolean enableBingoDrops()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showBingoOverlay",
        name = "Show bingo overlay",
        description = "Display the active bingo name, team name, and event time in a RuneLite overlay when this account is on the active board.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
        section = bingoSection,
        position = 1
    )
    default boolean showBingoOverlay()
    {
        return false;
    }

    @ConfigItem(
        keyName = "enableBingoMetricTracking",
        name = "Enable bingo metric tracking",
        description = "Track local XP, kill count, and clue metric progress for active bingo metric tiles.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
        section = bingoSection,
        position = 2
    )
    default boolean enableBingoMetricTracking()
    {
        return false;
    }

    @ConfigItem(
        keyName = "redactChatboxProofScreenshots",
        name = "Hide chat in proof screenshots",
        description = "Black out the chatbox area before bingo drop proof screenshots are uploaded.",
        section = bingoSection,
        position = 3
    )
    default boolean redactChatboxProofScreenshots()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enableWomEventTracking",
        name = "Enable WOM event tracking",
        description = "Show active WOM group competitions and leaderboard progress inside RuneLite.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
        section = womSection,
        position = 0
    )
    default boolean enableWomEventTracking()
    {
        return false;
    }

    @ConfigItem(
        keyName = "submitWomRefreshOnLogout",
        name = "Submit WOM refresh on logout",
        description = "Automatically request a WOM player update when you log out or hop worlds.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
        section = womSection,
        position = 1
    )
    default boolean submitWomRefreshOnLogout()
    {
        return false;
    }

    @Range(min = 30, max = 3600)
    @ConfigItem(
        keyName = "womRefreshSeconds",
        name = "WOM refresh interval",
        description = "How often to refresh active WOM competitions, in seconds.",
        section = womSection,
        position = 2
    )
    default int womRefreshSeconds()
    {
        return 180;
    }

    @Range(min = 1, max = 120)
    @ConfigItem(
        keyName = "womWarningMinutesBeforeEnd",
        name = "WOM warning minutes",
        description = "Warn to refresh WOM hiscores when an active event is near its end.",
        section = womSection,
        position = 3
    )
    default int womWarningMinutesBeforeEnd()
    {
        return 15;
    }

    @Range(min = 3, max = 25)
    @ConfigItem(
        keyName = "womLeaderboardSize",
        name = "Leaderboard rows",
        description = "How many leaderboard rows to show for each active WOM event.",
        section = womSection,
        position = 4
    )
    default int womLeaderboardSize()
    {
        return 10;
    }

    @ConfigItem(
        keyName = "debug",
        name = "Debug logging",
        description = "Log transport details and quiet outcomes to the RuneLite log.",
        position = 3
    )
    default boolean debug()
    {
        return false;
    }
}
