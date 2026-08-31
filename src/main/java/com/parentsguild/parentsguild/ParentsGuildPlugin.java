package com.parentsguild.parentsguild;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
    name = "ParentsGuild",
    description = "ParentsGuild companion for bingo drops and WOM event tracking.",
    tags = {"parentsguild", "bingo", "drops", "wom", "events", "leaderboard"}
)
public class ParentsGuildPlugin extends Plugin
{
    private static final MediaType PNG = MediaType.parse("image/png");
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String WISE_OLD_MAN_PLAYER_API = "https://api.wiseoldman.net/v2/players/";
    private static final DateTimeFormatter CAPTURED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SERVER_UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneOffset.UTC);
    private static final long RECENT_EVENT_TTL_MILLIS = 30_000L;
    private static final long CONFIG_WARNING_COOLDOWN_MILLIS = 60_000L;
    private static final long DROP_TILE_ELIGIBILITY_CACHE_MILLIS = 10_000L;
    private static final long COMPLETED_DROP_DENY_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long WOM_HTTP_TIMEOUT_WARNING_MILLIS = 20_000L;
    private static final long NPC_INVENTORY_REWARD_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(12);
    private static final int BINGO_STATUS_REFRESH_SECONDS = 60;
    private static final int[] LOGIN_SYNC_DELAYS_SECONDS = {1, 3, 6};
    private static final Map<Integer, String> BINGO_REWARD_CONTAINER_NAMES = Map.of(
        InventoryID.TRAWLER_REWARDINV, "Reward chest: Fishing trawler reward",
        InventoryID.TRAIL_REWARDINV, "Reward chest: Barrows reward",
        InventoryID.MACRO_CERTER, "Reward chest: Drift net fishing reward",
        InventoryID.RAIDS_REWARDS, "Reward chest: Chambers of xeric chest",
        InventoryID.TOB_CHESTS, "Reward chest: Theatre of blood chest",
        InventoryID.LOOT_INV_ACCESS, "Reward chest: Wilderness loot chest",
        InventoryID.TOA_CHESTS, "Reward chest: Toa reward chest",
        InventoryID.PMOON_REWARDINV, "Reward chest: Lunar chest",
        InventoryID.COLOSSEUM_REWARDS, "Reward chest: Fortis colosseum reward chest"
    );
    private static final Set<Integer> BINGO_REWARD_CONTAINER_IDS = new HashSet<>(BINGO_REWARD_CONTAINER_NAMES.keySet());
    private static final Set<String> INVENTORY_LOOT_CHEST_NAMES = Set.of(
        "brimstone chest",
        "crystal chest",
        "larran's big chest",
        "larran's small chest"
    );

    @Inject
    private ParentsGuildConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private DrawManager drawManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Gson gson;

    @Inject
    private Notifier notifier;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private OverlayManager overlayManager;

    private final Map<String, Long> recentDropEventIds = new ConcurrentHashMap<>();
    private final Map<String, Long> seenSubmissionNotificationIds = new ConcurrentHashMap<>();
    private final Map<String, Long> seenCompletionNotificationIds = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> bingoBoardImageCache = new ConcurrentHashMap<>();
    private final Map<Integer, Long> completedDropItemDenyCache = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Integer>> rewardContainerSnapshots = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> inventorySnapshot = new LinkedHashMap<>();
    private final Map<Integer, Long> womWarningMarkers = new ConcurrentHashMap<>();
    private final Map<String, Long> metricLocalGains = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastSkillXpByMetricKey = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastAbsoluteMetricCountByKey = new ConcurrentHashMap<>();
    private final Set<String> metricWomUpdateReminderTileIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean womRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean bingoStatusRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean bingoBoardRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean bingoBoardImageRefreshQueued = new AtomicBoolean(false);
    private volatile long lastConfigWarningAtMillis = 0L;
    private volatile long dropTileEligibilityCacheAtMillis = 0L;
    private volatile Set<Integer> dropTileEligibilityCache = new HashSet<>();
    private volatile Instant pluginStartedAt = Instant.EPOCH;
    private volatile String lastLoggedInRsn = "";
    private volatile NpcInventoryRewardInteraction pendingNpcInventoryRewardInteraction;
    private volatile boolean inventorySnapshotInitialized;
    private volatile WomPanelState womPanelState = WomPanelState.message("Loading WOM events...", "Waiting for first refresh.");
    private volatile BingoOverlayState bingoOverlayState = BingoOverlayState.hidden();
    private volatile BingoBoardState bingoBoardState = BingoBoardState.hidden();
    private volatile boolean bingoBoardOverlayEnabled = false;
    private ScheduledExecutorService womExecutor;
    private ScheduledFuture<?> womRefreshTask;
    private ScheduledFuture<?> bingoStatusTask;
    private ScheduledFuture<?> bingoBoardTask;
    private ParentsGuildPanel womPanel;
    private NavigationButton womNavigationButton;
    private ParentsGuildBingoOverlay bingoOverlay;
    private ParentsGuildBoardPopup bingoBoardPopup;

    // Lifecycle

    @Provides
    ParentsGuildConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ParentsGuildConfig.class);
    }

    @Override
    protected void startUp()
    {
        recentDropEventIds.clear();
        seenSubmissionNotificationIds.clear();
        seenCompletionNotificationIds.clear();
        bingoBoardImageCache.clear();
        completedDropItemDenyCache.clear();
        rewardContainerSnapshots.clear();
        inventorySnapshot.clear();
        pendingNpcInventoryRewardInteraction = null;
        inventorySnapshotInitialized = false;
        womWarningMarkers.clear();
        metricLocalGains.clear();
        metricWomUpdateReminderTileIds.clear();
        lastSkillXpByMetricKey.clear();
        lastAbsoluteMetricCountByKey.clear();
        lastConfigWarningAtMillis = 0L;
        dropTileEligibilityCacheAtMillis = 0L;
        dropTileEligibilityCache = new HashSet<>();
        pluginStartedAt = Instant.now();
        lastLoggedInRsn = "";
        womRefreshInFlight.set(false);
        bingoStatusRefreshInFlight.set(false);
        bingoBoardRefreshInFlight.set(false);
        bingoBoardImageRefreshQueued.set(false);
        womPanelState = WomPanelState.message("Loading WOM events...", "Waiting for first refresh.");
        bingoOverlayState = BingoOverlayState.hidden();
        bingoBoardState = BingoBoardState.hidden();
        bingoBoardOverlayEnabled = false;
        womExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "parentsguild-wom");
            thread.setDaemon(true);
            return thread;
        });
        womPanel = new ParentsGuildPanel(this);
        womNavigationButton = buildNavigationButton();
        clientToolbar.addNavigation(womNavigationButton);
        bingoOverlay = new ParentsGuildBingoOverlay(this);
        overlayManager.add(bingoOverlay);
        pushPanelState();
        rescheduleWomRefresh();
        rescheduleBingoStatusRefresh();
        rescheduleBingoBoardRefresh();
        requestWomRefresh(true);
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            requestLoginInitialSync();
        }
    }

    @Override
    protected void shutDown()
    {
        recentDropEventIds.clear();
        seenSubmissionNotificationIds.clear();
        seenCompletionNotificationIds.clear();
        rewardContainerSnapshots.clear();
        inventorySnapshot.clear();
        pendingNpcInventoryRewardInteraction = null;
        inventorySnapshotInitialized = false;
        womWarningMarkers.clear();
        metricLocalGains.clear();
        metricWomUpdateReminderTileIds.clear();
        lastSkillXpByMetricKey.clear();
        lastAbsoluteMetricCountByKey.clear();
        lastLoggedInRsn = "";
        womRefreshInFlight.set(false);
        bingoStatusRefreshInFlight.set(false);
        bingoBoardRefreshInFlight.set(false);
        bingoBoardImageRefreshQueued.set(false);
        bingoOverlayState = BingoOverlayState.hidden();
        bingoBoardState = BingoBoardState.hidden();
        bingoBoardOverlayEnabled = false;
        if (womRefreshTask != null)
        {
            womRefreshTask.cancel(true);
            womRefreshTask = null;
        }
        if (bingoStatusTask != null)
        {
            bingoStatusTask.cancel(true);
            bingoStatusTask = null;
        }
        if (bingoBoardTask != null)
        {
            bingoBoardTask.cancel(true);
            bingoBoardTask = null;
        }
        if (womExecutor != null)
        {
            womExecutor.shutdownNow();
            womExecutor = null;
        }
        if (womNavigationButton != null)
        {
            clientToolbar.removeNavigation(womNavigationButton);
            womNavigationButton = null;
        }
        if (bingoOverlay != null)
        {
            overlayManager.remove(bingoOverlay);
            bingoOverlay = null;
        }
        if (bingoBoardPopup != null)
        {
            bingoBoardPopup.dispose();
            bingoBoardPopup = null;
        }
        womPanel = null;
    }

    // RuneLite event hooks

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!"parentsguild".equals(event.getGroup()))
        {
            return;
        }

        rescheduleWomRefresh();
        rescheduleBingoStatusRefresh();
        rescheduleBingoBoardRefresh();
        requestWomRefresh(true);
        requestBingoStatusRefresh(true);
        requestBingoBoardRefresh(true);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            requestLoginInitialSync();
            lastLoggedInRsn = currentLocalPlayerName();
            return;
        }

        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            inventorySnapshot.clear();
            pendingNpcInventoryRewardInteraction = null;
            inventorySnapshotInitialized = false;
            final String playerRsn = cleanText(lastLoggedInRsn);
            if (!playerRsn.isEmpty() && config.submitWomRefreshOnLogout())
            {
                requestWomPlayerUpdate(playerRsn);
            }
            lastLoggedInRsn = "";
            bingoOverlayState = BingoOverlayState.hidden();
            bingoBoardState = BingoBoardState.hidden();
            updateBingoBoardPopup();
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        handleStatChanged(event);
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        handleMetricChatMessage(event);
    }

    private void handleStatChanged(StatChanged event)
    {
        if (!config.enableBingoMetricTracking() || client.getGameState() != GameState.LOGGED_IN || event == null)
        {
            return;
        }

        final String skillMetricKey = metricKeyForSkill(event.getSkill());
        if (skillMetricKey.isEmpty())
        {
            return;
        }

        final int currentXp = Math.max(0, event.getXp());
        final Integer previousXp = lastSkillXpByMetricKey.put(skillMetricKey, currentXp);
        if (previousXp == null || currentXp <= previousXp)
        {
            return;
        }

        final long gained = currentXp - previousXp;
        applyMetricDelta(skillMetricKey, gained, event.getSkill().getName() + " XP");
        applyMetricDelta("computed:overall_xp", gained, "Overall XP");
    }

    private void handleMetricChatMessage(ChatMessage event)
    {
        if (!config.enableBingoMetricTracking() || client.getGameState() != GameState.LOGGED_IN || event == null)
        {
            return;
        }
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
        {
            return;
        }

        final String message = cleanText(event.getMessage());
        final String normalized = normalizeName(message);
        if (normalized.startsWith("your ") && normalized.contains(" kill count is:"))
        {
            final int nameStart = "your ".length();
            final int nameEnd = normalized.indexOf(" kill count is:");
            final String bossName = normalized.substring(nameStart, nameEnd).trim();
            final int count = parseTrailingCount(normalized.substring(nameEnd + " kill count is:".length()));
            if (!bossName.isEmpty() && count > 0)
            {
                applyAbsoluteMetricCount("boss:" + metricKeySlug(bossName), count, bossName + " KC");
            }
            return;
        }

        if (normalized.startsWith("you have completed ") && normalized.contains(" treasure trail"))
        {
            final int count = parseFirstCount(normalized.substring("you have completed ".length()));
            final String tier = clueTierFromMessage(normalized);
            if (count > 0 && !tier.isEmpty())
            {
                applyAbsoluteMetricCount("activity:clue_scrolls_" + tier, count, tier + " clues");
            }
        }
    }

    private void applyAbsoluteMetricCount(String metricKey, int absoluteCount, String sourceLabel)
    {
        final String cleanedMetricKey = cleanText(metricKey);
        if (cleanedMetricKey.isEmpty() || absoluteCount <= 0)
        {
            return;
        }

        final Integer previousCount = lastAbsoluteMetricCountByKey.put(cleanedMetricKey, absoluteCount);
        final long gained = previousCount == null || absoluteCount <= previousCount ? 1L : absoluteCount - previousCount;
        applyMetricDelta(cleanedMetricKey, gained, sourceLabel);
    }

    private void applyMetricDelta(String metricKey, long gained, String sourceLabel)
    {
        if (gained <= 0L)
        {
            return;
        }

        final String playerRsn = currentLocalPlayerName();
        final String endpoint = resolveBingoMetricEndpoint();
        if (playerRsn.isEmpty() || endpoint.isEmpty())
        {
            return;
        }

        final List<BingoBoardCell> matchingTiles = activeMetricTiles(metricKey);
        if (matchingTiles.isEmpty())
        {
            return;
        }

        for (BingoBoardCell cell : matchingTiles)
        {
            final String tileId = cleanText(cell.getTileId());
            if (tileId.isEmpty() || cell.isCompleted() || cell.getTargetValue() <= 0L)
            {
                continue;
            }

            final long localGain = metricLocalGains.merge(tileId, gained, Long::sum);
            final long pluginProgress = cell.getRequiredCompletions() > 1
                ? localGain
                : Math.max(0L, cell.getProgressValue()) + localGain;
            submitMetricClaim(endpoint, playerRsn, cell, pluginProgress, gained, sourceLabel);
            maybeRemindWomUpdateRequired(cell, pluginProgress);
        }
    }

    private void maybeRemindWomUpdateRequired(BingoBoardCell cell, long pluginProgress)
    {
        if (cell == null || pluginProgress < cell.getTargetValue())
        {
            return;
        }

        final String tileId = cleanText(cell.getTileId());
        if (tileId.isEmpty() || !metricWomUpdateReminderTileIds.add(tileId))
        {
            return;
        }

        sendGameMessage("ParentsGuild: \"" + cell.getLabel() + "\" appears complete locally. Log out and back in so Wise Old Man can update and confirm it.");
    }

    private List<BingoBoardCell> activeMetricTiles(String metricKey)
    {
        final List<BingoBoardCell> tiles = new ArrayList<>();
        final String cleanedMetricKey = cleanText(metricKey);
        if (cleanedMetricKey.isEmpty() || !bingoBoardState.isVisible())
        {
            return tiles;
        }

        for (List<BingoBoardCell> row : bingoBoardState.getGrid())
        {
            for (BingoBoardCell cell : row)
            {
                if (cell != null
                    && "metric".equals(normalizeName(cell.getTileType()))
                    && cleanedMetricKey.equals(cleanText(cell.getMetricKey()))
                    && !cell.isCompleted())
                {
                    tiles.add(cell);
                }
            }
        }
        return tiles;
    }

    private void submitMetricClaim(String endpoint, String playerRsn, BingoBoardCell cell, long pluginProgress, long localGain, String sourceLabel)
    {
        final String achievedAtUtc = CAPTURED_AT_FORMAT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        final String eventId = buildMetricEventId(playerRsn, cell.getTileId(), cell.getMetricKey(), cell.getTargetValue(), achievedAtUtc);
        final JsonObject payload = new JsonObject();
        payload.addProperty("source", "runelite_plugin");
        payload.addProperty("eventId", eventId);
        payload.addProperty("playerRsn", playerRsn);
        payload.addProperty("tileId", cell.getTileId());
        payload.addProperty("metricKey", cell.getMetricKey());
        payload.addProperty("metricLabel", firstNonBlank(cell.getMetricLabel(), cell.getLabel(), sourceLabel));
        payload.addProperty("gainedValue", localGain);
        payload.addProperty("pluginProgressValue", pluginProgress);
        payload.addProperty("targetValue", cell.getTargetValue());
        payload.addProperty("achievedAtUtc", achievedAtUtc);

        final Request request = new Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(RequestBody.create(JSON, gson.toJson(payload)))
            .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Bingo metric submission failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response httpResponse = response)
                {
                    final String responseBody = httpResponse.body() != null ? httpResponse.body().string() : "";
                    if (!httpResponse.isSuccessful())
                    {
                        log.warn("Bingo metric endpoint returned HTTP {}: {}", httpResponse.code(), responseBody);
                        return;
                    }

                    final JsonObject responseJson = parseResponseJson(responseBody);
                    final String outcome = jsonString(responseJson, "outcome");
                    if ("approved".equals(outcome))
                    {
                        sendGameMessage("ParentsGuild: WOM confirmed \"" + firstNonBlank(jsonString(responseJson, "tileLabel"), cell.getLabel()) + "\".");
                        requestBingoStatusRefresh(true);
                        requestBingoBoardRefresh(true);
                        return;
                    }
                    if ("submitted".equals(outcome))
                    {
                        requestBingoStatusRefresh(true);
                        requestBingoBoardRefresh(true);
                        return;
                    }
                    debugLog("Quiet bingo metric outcome={} tile={} response={}", outcome, cell.getLabel(), responseBody);
                }
            }
        });
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        handleLoot(event.getNpc() != null ? event.getNpc().getName() : "", event.getItems());
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (event == null)
        {
            return;
        }

        final String menuOption = cleanText(Text.removeTags(event.getMenuOption()));
        final String npcName = cleanText(Text.removeTags(event.getMenuTarget()));
        final boolean npcRewardInteraction = "Talk-to".equalsIgnoreCase(menuOption) && !npcName.isEmpty();
        final boolean lootChestInteraction = "Open".equalsIgnoreCase(menuOption)
            && INVENTORY_LOOT_CHEST_NAMES.contains(normalizeName(npcName));
        if (npcRewardInteraction || lootChestInteraction)
        {
            captureInventorySnapshot();
            pendingNpcInventoryRewardInteraction = new NpcInventoryRewardInteraction(
                npcName,
                System.currentTimeMillis() + NPC_INVENTORY_REWARD_WINDOW_MILLIS
            );
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        final int containerId = event.getContainerId();
        if (containerId == net.runelite.api.InventoryID.INVENTORY.getId())
        {
            handleNpcInventoryReward(event.getItemContainer());
            return;
        }
        if (!BINGO_REWARD_CONTAINER_IDS.contains(containerId))
        {
            return;
        }

        handleRewardContainer(containerId, event.getItemContainer());
    }

    // Login sync

    private void requestLoginInitialSync()
    {
        requestLoginSyncAttempt();

        if (womExecutor == null || (!config.showBingoOverlay() && !config.enableBingoDrops() && !config.enableBingoMetricTracking()))
        {
            return;
        }

        for (int delaySeconds : LOGIN_SYNC_DELAYS_SECONDS)
        {
            womExecutor.schedule(() -> {
                if (client.getGameState() != GameState.LOGGED_IN)
                {
                    return;
                }

                requestLoginSyncAttempt();
            }, delaySeconds, TimeUnit.SECONDS);
        }
    }

    private void requestLoginSyncAttempt()
    {
        final String playerName = currentLocalPlayerName();
        if (playerName.isEmpty())
        {
            debugLog("Skipping login sync attempt because local player name is not available yet.");
            return;
        }
        lastLoggedInRsn = playerName;
        requestWomRefresh(true);
        requestBingoStatusRefresh(true);
        requestBingoBoardRefresh(true);
    }

    // WOM and clan panel refresh

    void requestWomRefresh(boolean manual)
    {
        if (womExecutor == null)
        {
            return;
        }

        if (!config.enableWomEventTracking())
        {
            womPanelState = WomPanelState.message("WOM event tracking is disabled.", "Enable it in the plugin config to load active competitions.");
            pushPanelState();
            return;
        }

        final String websiteBase = resolveEndpointBase(config.websiteBaseUrl());
        if (websiteBase.isEmpty())
        {
            womPanelState = WomPanelState.message("Set the website base URL.", "The plugin loads the WOM group ID from the ParentsGuild website.");
            pushPanelState();
            return;
        }

        if (!womRefreshInFlight.compareAndSet(false, true))
        {
            debugLog("Skipped WOM refresh because another refresh is already running.");
            return;
        }

        final WomPanelState currentState = womPanelState;
        womPanelState = currentState.withLoading(true, "Refreshing WOM events...");
        pushPanelState();
        final String playerRsn = currentLocalPlayerName();
        womExecutor.execute(() -> refreshWomState(playerRsn, manual));
    }

    private void rescheduleWomRefresh()
    {
        if (womRefreshTask != null)
        {
            womRefreshTask.cancel(false);
            womRefreshTask = null;
        }

        if (womExecutor == null || !config.enableWomEventTracking() || resolveEndpointBase(config.websiteBaseUrl()).isEmpty())
        {
            return;
        }

        final int intervalSeconds = Math.max(30, config.womRefreshSeconds());
        womRefreshTask = womExecutor.scheduleWithFixedDelay(
            () -> requestWomRefresh(false),
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
    }

    private void refreshWomState(String playerRsn, boolean manual)
    {
        final long startedAtMillis = System.currentTimeMillis();
        try
        {
            womPanelState = fetchClanPanelState(playerRsn);
            for (CompetitionView competitionView : womPanelState.getCompetitions())
            {
                maybeWarnCompetitionEndingSoon(competitionView, Instant.now());
            }

            pushPanelState();
            if (manual && config.debug())
            {
                log.debug("[ParentsGuild] clan panel refresh loaded {} active competitions in {}ms", womPanelState.getCompetitions().size(), System.currentTimeMillis() - startedAtMillis);
            }
        }
        catch (Exception ex)
        {
            log.warn("Failed to refresh ParentsGuild clan panel data", ex);
            final String detail = manual ? "Refresh failed. Check the website URL and roster RSN." : "Will retry automatically.";
            womPanelState = womPanelState.withFailure("Failed to load clan panel.", detail);
            pushPanelState();
            if (config.debug() || (System.currentTimeMillis() - startedAtMillis) > WOM_HTTP_TIMEOUT_WARNING_MILLIS)
            {
                log.debug("[ParentsGuild] clan panel refresh failed after {}ms", System.currentTimeMillis() - startedAtMillis);
            }
        }
        finally
        {
            womRefreshInFlight.set(false);
        }
    }

    private void maybeWarnCompetitionEndingSoon(CompetitionView competition, Instant now)
    {
        final long minutesRemaining = Duration.between(now, competition.getEndsAt()).toMinutes();
        if (minutesRemaining < 0 || minutesRemaining > Math.max(1, config.womWarningMinutesBeforeEnd()))
        {
            return;
        }

        final long marker = competition.getEndsAt().getEpochSecond();
        final Long existingMarker = womWarningMarkers.putIfAbsent(competition.getId(), marker);
        if (Objects.equals(existingMarker, marker))
        {
            return;
        }

        notifier.notify("ParentsGuild: refresh WOM hiscores, \"" + competition.getTitle() + "\" ends in " + Math.max(1, minutesRemaining) + " minute(s).");
    }

    // Bingo status overlay refresh

    void requestBingoStatusRefresh(boolean manual)
    {
        if (womExecutor == null)
        {
            return;
        }

        if (!config.showBingoOverlay() && !config.enableBingoDrops())
        {
            bingoOverlayState = BingoOverlayState.hidden();
            return;
        }

        final String endpoint = resolveBingoStatusEndpoint();
        final String playerRsn = currentLocalPlayerName();
        if (endpoint.isEmpty() || playerRsn.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
        {
            bingoOverlayState = BingoOverlayState.hidden();
            return;
        }

        if (!bingoStatusRefreshInFlight.compareAndSet(false, true))
        {
            return;
        }

        womExecutor.execute(() -> refreshBingoStatusState(endpoint, playerRsn, manual));
    }

    private void rescheduleBingoStatusRefresh()
    {
        if (bingoStatusTask != null)
        {
            bingoStatusTask.cancel(false);
            bingoStatusTask = null;
        }

        if (womExecutor == null)
        {
            return;
        }

        bingoStatusTask = womExecutor.scheduleWithFixedDelay(
            () -> requestBingoStatusRefresh(false),
            BINGO_STATUS_REFRESH_SECONDS,
            BINGO_STATUS_REFRESH_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void refreshBingoStatusState(String endpoint, String playerRsn, boolean manual)
    {
        try
        {
            final BingoOverlayState nextState = fetchBingoOverlayState(endpoint, playerRsn);
            bingoOverlayState = nextState;
        }
        catch (Exception ex)
        {
            bingoOverlayState = BingoOverlayState.hidden();
            if (config.debug() || manual)
            {
                log.warn("Failed to refresh bingo overlay state", ex);
            }
        }
        finally
        {
            bingoStatusRefreshInFlight.set(false);
        }
    }

    // Bingo board popup

    void toggleBingoBoardOverlay()
    {
        if (bingoBoardOverlayEnabled)
        {
            closeBingoBoardPopup();
            return;
        }

        bingoBoardOverlayEnabled = true;
        SwingUtilities.invokeLater(() -> {
            if (bingoBoardPopup == null)
            {
                bingoBoardPopup = new ParentsGuildBoardPopup(this);
            }
            bingoBoardPopup.updateBoardState(bingoBoardState);
            bingoBoardPopup.setVisible(true);
            bingoBoardPopup.toFront();
        });
        rescheduleBingoBoardRefresh();
        requestBingoBoardRefresh(true);
    }

    boolean isBingoBoardOverlayEnabled()
    {
        return bingoBoardOverlayEnabled;
    }

    void onBingoBoardPopupClosed()
    {
        if (!bingoBoardOverlayEnabled && bingoBoardPopup == null)
        {
            return;
        }

        bingoBoardOverlayEnabled = false;
        if (bingoBoardTask != null)
        {
            bingoBoardTask.cancel(false);
            bingoBoardTask = null;
        }
        bingoBoardRefreshInFlight.set(false);
        bingoBoardPopup = null;
        pushPanelState();
    }

    private void closeBingoBoardPopup()
    {
        bingoBoardOverlayEnabled = false;
        if (bingoBoardTask != null)
        {
            bingoBoardTask.cancel(false);
            bingoBoardTask = null;
        }
        bingoBoardRefreshInFlight.set(false);
        SwingUtilities.invokeLater(() -> {
            if (bingoBoardPopup != null)
            {
                bingoBoardPopup.dispose();
                bingoBoardPopup = null;
            }
            pushPanelState();
        });
    }

    void requestBingoBoardRefresh(boolean manual)
    {
        if (womExecutor == null)
        {
            return;
        }

        if (!bingoBoardOverlayEnabled && !config.enableBingoMetricTracking())
        {
            bingoBoardState = BingoBoardState.hidden();
            updateBingoBoardPopup();
            return;
        }

        final String endpoint = resolveBingoBoardEndpoint();
        final String playerRsn = currentLocalPlayerName();
        if (endpoint.isEmpty() || playerRsn.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
        {
            bingoBoardState = BingoBoardState.hidden();
            updateBingoBoardPopup();
            return;
        }

        if (!bingoBoardRefreshInFlight.compareAndSet(false, true))
        {
            return;
        }

        womExecutor.execute(() -> refreshBingoBoardState(endpoint, playerRsn, manual));
    }

    // Side-panel actions

    void openWebsiteBingoBoard()
    {
        openConfiguredWebsitePath("/bingo.php", "website bingo board");
    }

    void openWebsiteHome()
    {
        openConfiguredWebsitePath("/", "website");
    }

    void openWebsiteProfile(ClanProfileState profile)
    {
        openConfiguredWebsitePath(websiteProfilePath(profile), "website profile");
    }

    void openDiscordInvite(String inviteCode)
    {
        openFixedUrl(discordInviteUrl(inviteCode), "Discord invite");
    }

    void openWomGroup(int groupId)
    {
        openFixedUrl(womGroupUrl(groupId), "WOM group");
    }

    void openWomCompetition(int competitionId)
    {
        openFixedUrl(womCompetitionUrl(competitionId), "WOM event");
    }

    String lastSeenAnnouncementMarker()
    {
        final String value = configManager.getConfiguration("parentsguild", "lastSeenAnnouncementMarker");
        return value == null ? "" : value.trim();
    }

    void markAnnouncementSeen(String marker)
    {
        final String cleaned = cleanText(marker);
        if (!cleaned.isEmpty())
        {
            configManager.setConfiguration("parentsguild", "lastSeenAnnouncementMarker", cleaned);
        }
    }

    private void openConfiguredWebsitePath(String path, String label)
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        final String cleanedPath = cleanWebsitePath(path);
        if (base.isEmpty() || cleanedPath.isEmpty())
        {
            notifier.notify("ParentsGuild: set the website base URL before opening " + cleanText(label) + ".");
            return;
        }
        openFixedUrl(base + cleanedPath, label);
    }

    private void openFixedUrl(String url, String label)
    {
        final String cleanedUrl = cleanText(url);
        if (cleanedUrl.isEmpty())
        {
            notifier.notify("ParentsGuild: no " + cleanText(label) + " link is available.");
            return;
        }

        try
        {
            LinkBrowser.browse(cleanedUrl);
        }
        catch (IllegalArgumentException ex)
        {
            log.warn("Failed to open ParentsGuild link {}", cleanedUrl, ex);
            notifier.notify("ParentsGuild: failed to open " + cleanText(label) + ".");
        }
    }

    private static String websiteProfilePath(ClanProfileState profile)
    {
        if (profile == null || !profile.isAvailable() || profile.getMemberId() == null || profile.getMemberId().trim().isEmpty())
        {
            return "";
        }
        return "/member-profile.php?id=" + urlEncode(profile.getMemberId().trim());
    }

    private static String cleanWebsitePath(String path)
    {
        final String cleaned = cleanText(path);
        return cleaned.startsWith("/") && !cleaned.startsWith("//") ? cleaned : "";
    }

    static String discordInviteUrl(String inviteCode)
    {
        final String cleaned = cleanText(inviteCode);
        return cleaned.matches("[A-Za-z0-9-]{2,64}") ? "https://discord.gg/" + cleaned : "";
    }

    static String womGroupUrl(int groupId)
    {
        return groupId > 0 ? "https://wiseoldman.net/groups/" + groupId : "";
    }

    static String womCompetitionUrl(int competitionId)
    {
        return competitionId > 0 ? "https://wiseoldman.net/competitions/" + competitionId : "";
    }

    // Bingo board data

    private void rescheduleBingoBoardRefresh()
    {
        if (bingoBoardTask != null)
        {
            bingoBoardTask.cancel(false);
            bingoBoardTask = null;
        }

        if (womExecutor == null || (!bingoBoardOverlayEnabled && !config.enableBingoMetricTracking()))
        {
            return;
        }

        bingoBoardTask = womExecutor.scheduleWithFixedDelay(
            () -> requestBingoBoardRefresh(false),
            BINGO_STATUS_REFRESH_SECONDS,
            BINGO_STATUS_REFRESH_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void refreshBingoBoardState(String endpoint, String playerRsn, boolean manual)
    {
        try
        {
            bingoBoardState = fetchBingoBoardState(endpoint, playerRsn);
            updateBingoBoardPopup();
            pushPanelState();
        }
        catch (Exception ex)
        {
            bingoBoardState = BingoBoardState.hidden();
            updateBingoBoardPopup();
            pushPanelState();
            if (config.debug() || manual)
            {
                log.warn("Failed to refresh bingo board overlay state", ex);
            }
        }
        finally
        {
            bingoBoardRefreshInFlight.set(false);
        }
    }

    private void updateBingoBoardPopup()
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            final ParentsGuildBoardPopup popup = bingoBoardPopup;
            if (popup != null)
            {
                popup.updateBoardState(bingoBoardState);
            }
            return;
        }

        SwingUtilities.invokeLater(this::updateBingoBoardPopup);
    }

    // Drop detection

    private void handleRewardContainer(int containerId, ItemContainer itemContainer)
    {
        final Map<Integer, Integer> currentItems = aggregateContainerItems(itemContainer);
        final Map<Integer, Integer> previousItems = rewardContainerSnapshots.get(containerId);
        if (currentItems.isEmpty())
        {
            rewardContainerSnapshots.remove(containerId);
            return;
        }

        rewardContainerSnapshots.put(containerId, currentItems);

        final List<ItemStack> gainedItems = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : currentItems.entrySet())
        {
            final int itemId = entry.getKey();
            final int quantity = entry.getValue();
            final int previousQuantity = previousItems == null ? 0 : previousItems.getOrDefault(itemId, 0);
            final int gainedQuantity = quantity - previousQuantity;
            if (gainedQuantity > 0)
            {
                gainedItems.add(new ItemStack(itemId, gainedQuantity));
            }
        }

        if (!gainedItems.isEmpty())
        {
            handleLoot(rewardContainerSourceName(containerId), gainedItems);
        }
    }

    private void handleNpcInventoryReward(ItemContainer itemContainer)
    {
        final Map<Integer, Integer> currentItems = aggregateContainerItems(itemContainer);
        if (!inventorySnapshotInitialized)
        {
            inventorySnapshot.putAll(currentItems);
            inventorySnapshotInitialized = true;
            return;
        }

        final NpcInventoryRewardInteraction interaction = pendingNpcInventoryRewardInteraction;
        final boolean npcRewardWindowActive = interaction != null && interaction.getExpiresAtMillis() >= System.currentTimeMillis();
        if (interaction != null && !npcRewardWindowActive)
        {
            pendingNpcInventoryRewardInteraction = null;
        }

        final List<ItemStack> gainedItems = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : currentItems.entrySet())
        {
            final int gainedQuantity = entry.getValue() - inventorySnapshot.getOrDefault(entry.getKey(), 0);
            if (gainedQuantity > 0 && (npcRewardWindowActive || isUntradeableItem(entry.getKey())))
            {
                gainedItems.add(new ItemStack(entry.getKey(), gainedQuantity));
            }
        }
        if (!gainedItems.isEmpty())
        {
            handleLoot(
                npcRewardWindowActive ? "NPC reward: " + interaction.getNpcName() : "Untradable inventory reward",
                gainedItems
            );
        }

        inventorySnapshot.clear();
        inventorySnapshot.putAll(currentItems);
    }

    private boolean isUntradeableItem(int itemId)
    {
        try
        {
            final ItemComposition item = itemManager.getItemComposition(canonicalItemId(itemId));
            return item != null && !item.isTradeable();
        }
        catch (RuntimeException ex)
        {
            debugLog("Could not determine whether item {} is tradable: {}", itemId, ex.getMessage());
            return false;
        }
    }

    private void captureInventorySnapshot()
    {
        inventorySnapshot.clear();
        inventorySnapshot.putAll(aggregateContainerItems(client.getItemContainer(net.runelite.api.InventoryID.INVENTORY.getId())));
        inventorySnapshotInitialized = true;
    }

    private static Map<Integer, Integer> aggregateContainerItems(ItemContainer itemContainer)
    {
        final Map<Integer, Integer> items = new LinkedHashMap<>();
        if (itemContainer == null || itemContainer.getItems() == null)
        {
            return items;
        }

        for (Item item : itemContainer.getItems())
        {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
            {
                continue;
            }

            items.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        return items;
    }

    private static String rewardContainerSourceName(int containerId)
    {
        return BINGO_REWARD_CONTAINER_NAMES.getOrDefault(containerId, "Reward chest");
    }

    private void pushPanelState()
    {
        final ParentsGuildPanel panel = womPanel;
        if (panel == null)
        {
            return;
        }

        final WomPanelState state = womPanelState;
        SwingUtilities.invokeLater(() -> panel.updateState(state));
    }

    private void handleLoot(String sourceName, Collection<ItemStack> items)
    {
        final boolean bingoDropsEnabled = config.enableBingoDrops();
        final boolean lifetimeLootEnabled = config.enableLifetimeLoot();
        if (!bingoDropsEnabled && !lifetimeLootEnabled)
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        final String bingoEndpoint = bingoDropsEnabled ? resolveBingoDropEndpoint() : "";
        final String lifetimeEndpoint = lifetimeLootEnabled ? resolveLifetimeLootEndpoint() : "";
        final Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || items == null || items.isEmpty())
        {
            debugLog("Skipping loot submission because player/items were incomplete.");
            return;
        }
        if ((bingoDropsEnabled && !isConfiguredForSubmission(bingoEndpoint))
            || (lifetimeLootEnabled && !isConfiguredForSubmission(lifetimeEndpoint)))
        {
            warnConfigIncomplete("ParentsGuild: set the website URL in plugin config.");
            return;
        }

        final String playerRsn = cleanText(localPlayer.getName());
        final String cleanedSourceName = normalizeSourceName(sourceName);
        if (playerRsn.isEmpty())
        {
            debugLog("Skipping loot submission because the player RSN was blank.");
            return;
        }

        purgeExpiredRecentDropEvents();
        final List<PendingDrop> drops = new ArrayList<>();
        for (ItemStack item : items)
        {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
            {
                continue;
            }

            final int canonicalItemId = canonicalItemId(item.getId());
            if (canonicalItemId <= 0)
            {
                continue;
            }

            final ItemComposition composition = itemManager.getItemComposition(canonicalItemId);
            final String itemName = cleanText(composition != null ? composition.getName() : "");
            if (itemName.isEmpty())
            {
                continue;
            }

            final String capturedAtUtc = CAPTURED_AT_FORMAT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            final String eventId = buildEventId(cleanedSourceName, playerRsn, canonicalItemId, item.getQuantity(), capturedAtUtc);
            if (recentDropEventIds.putIfAbsent(eventId, System.currentTimeMillis()) != null)
            {
                debugLog("Skipping duplicate in-memory loot event {} for {}", eventId, itemName);
                continue;
            }

            final long unitValue = Math.max(0L, itemManager.getItemPrice(canonicalItemId));
            drops.add(new PendingDrop(eventId, canonicalItemId, item.getQuantity(), itemName, unitValue, capturedAtUtc));
        }

        if (drops.isEmpty())
        {
            debugLog("No eligible item stacks found for loot event from {}", cleanedSourceName);
            return;
        }

        if (womExecutor == null)
        {
            debugLog("Skipping loot submissions because the plugin executor is unavailable.");
            return;
        }

        womExecutor.execute(() -> {
            if (lifetimeLootEnabled)
            {
                submitLifetimeLoot(lifetimeEndpoint, playerRsn, cleanedSourceName, drops);
            }
            if (bingoDropsEnabled)
            {
                submitEligibleDropScreenshots(bingoEndpoint, playerRsn, cleanedSourceName, drops);
            }
        });
    }

    private void submitEligibleDropScreenshots(String dropEndpoint, String playerRsn, String sourceName, List<PendingDrop> drops)
    {
        final List<PendingDrop> eligibleDrops = filterDropsForIncompleteBingoTiles(playerRsn, drops);
        if (eligibleDrops.isEmpty())
        {
            debugLog("Skipping bingo drop screenshot because no dropped items matched incomplete drop tiles.");
            return;
        }

        requestPrivacyAwareScreenshot((Image image) -> {
            try
            {
                if (image == null)
                {
                    log.warn("RuneLite did not supply a frame for bingo drop capture.");
                    return;
                }

                final byte[] screenshotBytes = toPngBytes(image);
                for (PendingDrop drop : eligibleDrops)
                {
                    submitDrop(dropEndpoint, playerRsn, sourceName, drop, screenshotBytes);
                }
            }
            catch (IOException ex)
            {
                log.warn("Failed to encode bingo drop screenshot", ex);
            }
        });
    }

    private List<PendingDrop> filterDropsForIncompleteBingoTiles(String playerRsn, List<PendingDrop> drops)
    {
        if (drops.isEmpty())
        {
            return drops;
        }

        purgeExpiredCompletedDropDenyCache();
        final List<PendingDrop> notKnownCompletedDrops = new ArrayList<>();
        for (PendingDrop drop : drops)
        {
            if (completedDropItemDenyCache.containsKey(drop.getItemId()))
            {
                debugLog("Skipping bingo drop screenshot for item {} ({}) because this drop item was recently confirmed complete.", drop.getItemId(), drop.getItemName());
                continue;
            }
            notKnownCompletedDrops.add(drop);
        }
        if (notKnownCompletedDrops.isEmpty())
        {
            return new ArrayList<>();
        }

        final String boardEndpoint = resolveBingoBoardEndpoint();
        if (boardEndpoint.isEmpty())
        {
            return new ArrayList<>();
        }

        try
        {
            final Set<Integer> eligibleItemIds = incompleteDropTileItemIds(boardEndpoint, playerRsn);
            final List<PendingDrop> eligibleDrops = new ArrayList<>();
            for (PendingDrop drop : notKnownCompletedDrops)
            {
                if (eligibleItemIds.contains(drop.getItemId()))
                {
                    eligibleDrops.add(drop);
                }
                else
                {
                    debugLog("Skipping bingo drop screenshot for item {} ({}) because no incomplete matching tile is active.", drop.getItemId(), drop.getItemName());
                }
            }
            return eligibleDrops;
        }
        catch (IOException ex)
        {
            log.warn("Failed to check bingo board before drop screenshot; skipping screenshot to avoid invalid proof capture.", ex);
            return new ArrayList<>();
        }
    }

    private Set<Integer> incompleteDropTileItemIds(String boardEndpoint, String playerRsn) throws IOException
    {
        final long now = System.currentTimeMillis();
        final Set<Integer> cachedItemIds = dropTileEligibilityCache;
        if ((now - dropTileEligibilityCacheAtMillis) < DROP_TILE_ELIGIBILITY_CACHE_MILLIS)
        {
            return cachedItemIds;
        }

        final Set<Integer> itemIds = fetchIncompleteDropTileItemIds(boardEndpoint, playerRsn);
        dropTileEligibilityCache = itemIds;
        dropTileEligibilityCacheAtMillis = now;
        return itemIds;
    }

    private Set<Integer> fetchIncompleteDropTileItemIds(String endpoint, String playerRsn) throws IOException
    {
        final String url = endpoint + "?playerRsn=" + URLEncoder.encode(playerRsn, StandardCharsets.UTF_8.toString());
        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build();

        try (Response response = okHttpClient.newCall(request).execute())
        {
            final String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful())
            {
                throw new IOException("HTTP " + response.code() + " for " + endpoint + ": " + responseBody);
            }

            final JsonObject payload = parseResponseJson(responseBody);
            if (!jsonBoolean(payload, "active") || !jsonBoolean(payload, "matched"))
            {
                return new HashSet<>();
            }

            final Set<Integer> itemIds = new HashSet<>();
            final JsonObject team = jsonObject(payload, "team");
            for (JsonElement rowElement : jsonArray(team, "grid"))
            {
                if (!rowElement.isJsonArray())
                {
                    continue;
                }

                for (JsonElement tileElement : rowElement.getAsJsonArray())
                {
                    if (!tileElement.isJsonObject())
                    {
                        continue;
                    }

                    final JsonObject tile = tileElement.getAsJsonObject();
                    final String tileType = normalizeName(jsonString(tile, "tileType"));
                    if (!("drop".equals(tileType) || "multi_item".equals(tileType)) || bingoDropTileIsComplete(tile))
                    {
                        continue;
                    }

                    final int dropItemId = jsonInt(tile, "dropItemId");
                    if (dropItemId > 0)
                    {
                        itemIds.add(dropItemId);
                    }
                    if ("multi_item".equals(tileType))
                    {
                        itemIds.addAll(multiItemTileItemIds(tile));
                    }
                }
            }
            return itemIds;
        }
    }

    private static boolean bingoDropTileIsComplete(JsonObject tile)
    {
        if (jsonBoolean(tile, "isCompleted"))
        {
            return true;
        }

        final int requiredCompletions = Math.max(1, jsonInt(tile, "requiredCompletions"));
        return jsonInt(tile, "approvedCompletions") >= requiredCompletions;
    }

    private static Set<Integer> multiItemTileItemIds(JsonObject tile)
    {
        final Set<Integer> itemIds = new HashSet<>();
        for (JsonElement itemElement : jsonArray(tile, "multiItems"))
        {
            if (!itemElement.isJsonObject())
            {
                continue;
            }

            final int itemId = jsonInt(itemElement.getAsJsonObject(), "itemId");
            if (itemId > 0)
            {
                itemIds.add(itemId);
            }
        }
        return itemIds;
    }

    private static String multiItemTileLabel(JsonObject tile)
    {
        final List<String> labels = new ArrayList<>();
        for (JsonElement itemElement : jsonArray(tile, "multiItems"))
        {
            if (!itemElement.isJsonObject())
            {
                continue;
            }

            final JsonObject item = itemElement.getAsJsonObject();
            final String label = firstNonBlank(
                jsonString(item, "itemName"),
                jsonString(item, "label"),
                jsonString(item, "pageTitle")
            );
            if (!label.isEmpty())
            {
                labels.add(label);
            }
        }
        if (labels.isEmpty())
        {
            return "";
        }
        if (labels.size() == 1)
        {
            return labels.get(0);
        }
        return labels.get(0) + " / " + labels.get(1) + (labels.size() > 2 ? " +" + (labels.size() - 2) : "");
    }

    private static List<BingoBoardItem> parseMultiItemTileItems(JsonObject tile)
    {
        final List<BingoBoardItem> items = new ArrayList<>();
        for (JsonElement itemElement : jsonArray(tile, "multiItems"))
        {
            if (!itemElement.isJsonObject())
            {
                continue;
            }

            final JsonObject item = itemElement.getAsJsonObject();
            final int itemId = jsonInt(item, "itemId");
            if (itemId <= 0)
            {
                continue;
            }
            final String itemName = firstNonBlank(
                jsonString(item, "itemName"),
                jsonString(item, "label"),
                jsonString(item, "pageTitle"),
                "Item " + itemId
            );
            items.add(new BingoBoardItem(itemId, itemName));
        }
        return items;
    }

    private void rememberCompletedDropItem(int itemId)
    {
        if (itemId > 0)
        {
            completedDropItemDenyCache.put(itemId, System.currentTimeMillis());
            dropTileEligibilityCacheAtMillis = 0L;
        }
    }

    private void purgeExpiredCompletedDropDenyCache()
    {
        final long cutoff = System.currentTimeMillis() - COMPLETED_DROP_DENY_CACHE_MILLIS;
        completedDropItemDenyCache.entrySet().removeIf((entry) -> entry.getValue() < cutoff);
    }

    private void submitDrop(String endpoint, String playerRsn, String sourceName, PendingDrop drop, byte[] screenshotBytes)
    {
        final MultipartBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("source", "runelite_plugin")
            .addFormDataPart("eventId", drop.getEventId())
            .addFormDataPart("playerRsn", playerRsn)
            .addFormDataPart("itemId", Integer.toString(drop.getItemId()))
            .addFormDataPart("itemName", drop.getItemName())
            .addFormDataPart("quantity", Integer.toString(drop.getQuantity()))
            .addFormDataPart("sourceName", sourceName)
            .addFormDataPart("capturedAtUtc", drop.getCapturedAtUtc())
            .addFormDataPart("proof", "parentsguild-bingo-drop.png", RequestBody.create(PNG, screenshotBytes))
            .build();

        final Request request = new Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(body)
            .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Bingo drop submission failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response httpResponse = response)
                {
                    final String responseBody = httpResponse.body() != null ? httpResponse.body().string() : "";
                    if (!httpResponse.isSuccessful())
                    {
                        log.warn("Bingo drop endpoint returned HTTP {}: {}", httpResponse.code(), responseBody);
                        return;
                    }

                    final JsonObject responseJson = parseResponseJson(responseBody);
                    final String outcome = jsonString(responseJson, "outcome");
                    if ("matched".equals(outcome))
                    {
                        final String teamName = jsonString(responseJson, "teamName");
                        final String memberRsn = jsonString(responseJson, "memberRsn");
                        notifier.notify("ParentsGuild: bingo matched " + (teamName.isEmpty() ? "team" : teamName) + " / " + (memberRsn.isEmpty() ? playerRsn : memberRsn));
                        requestBingoStatusRefresh(true);
                        return;
                    }

                    if ("duplicate".equals(outcome) || "no_match".equals(outcome))
                    {
                        final String reason = jsonString(responseJson, "reason");
                        if (normalizeName(reason).contains("already complete"))
                        {
                            rememberCompletedDropItem(drop.getItemId());
                        }
                        debugLog("Quiet bingo drop outcome={} item={} response={}", outcome, drop.getItemName(), responseBody);
                        return;
                    }

                    log.warn("Unexpected bingo drop response: {}", responseBody);
                }
            }
        });
    }

    private void submitLifetimeLoot(String endpoint, String playerRsn, String sourceName, List<PendingDrop> drops)
    {
        for (PendingDrop drop : drops)
        {
            final FormBody body = new FormBody.Builder()
                .add("source", "runelite_plugin")
                .add("eventId", drop.getEventId())
                .add("playerRsn", playerRsn)
                .add("itemId", Integer.toString(drop.getItemId()))
                .add("itemName", drop.getItemName())
                .add("quantity", Integer.toString(drop.getQuantity()))
                .add("unitValue", Long.toString(drop.getUnitValue()))
                .add("sourceName", sourceName)
                .add("capturedAtUtc", drop.getCapturedAtUtc())
                .add("seasonalWorld", Boolean.toString(client.getWorldType().contains(WorldType.SEASONAL)))
                .build();
            final Request request = new Request.Builder().url(endpoint).header("Accept", "application/json").post(body).build();
            okHttpClient.newCall(request).enqueue(new Callback()
            {
                @Override
                public void onFailure(Call call, IOException e)
                {
                    log.warn("Lifetime Loot submission failed", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException
                {
                    try (Response httpResponse = response)
                    {
                        final String responseBody = httpResponse.body() != null ? httpResponse.body().string() : "";
                        if (!httpResponse.isSuccessful())
                        {
                            log.warn("Lifetime Loot endpoint returned HTTP {}: {}", httpResponse.code(), responseBody);
                            return;
                        }
                        debugLog("Lifetime Loot outcome={} item={}", jsonString(parseResponseJson(responseBody), "outcome"), drop.getItemName());
                    }
                }
            });
        }
    }

    // Manual proof capture

    void submitBingoTileProof(BingoBoardCell cell)
    {
        if (cell == null || cleanText(cell.getTileId()).isEmpty())
        {
            notifier.notify("ParentsGuild: that bingo tile cannot accept proof.");
            return;
        }
        if (cell.isCompleted())
        {
            notifier.notify("ParentsGuild: that bingo tile is already complete.");
            return;
        }
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            notifier.notify("ParentsGuild: log in before capturing bingo proof.");
            return;
        }

        final String endpoint = resolveBingoProofEndpoint();
        if (!isConfiguredForSubmission(endpoint))
        {
            warnConfigIncomplete("ParentsGuild: set the website URL in plugin config.");
            return;
        }

        final Player localPlayer = client.getLocalPlayer();
        final String playerRsn = cleanText(localPlayer != null ? localPlayer.getName() : "");
        if (playerRsn.isEmpty())
        {
            notifier.notify("ParentsGuild: could not identify the logged-in account for proof.");
            return;
        }

        final BingoBoardItem selectedItem = selectProofItem(cell);
        if (selectedItem == null)
        {
            return;
        }

        final String capturedAtUtc = CAPTURED_AT_FORMAT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        final String eventId = buildProofEventId(playerRsn, cell.getTileId(), capturedAtUtc);
        notifier.notify("ParentsGuild: capturing proof for \"" + cell.getLabel() + "\".");
        requestPrivacyAwareScreenshot((Image image) -> {
            try
            {
                if (image == null)
                {
                    log.warn("RuneLite did not supply a frame for bingo proof capture.");
                    return;
                }

                final byte[] screenshotBytes = toPngBytes(image);
                submitTileProof(endpoint, playerRsn, cell, selectedItem, eventId, capturedAtUtc, screenshotBytes);
            }
            catch (IOException ex)
            {
                log.warn("Failed to encode bingo proof screenshot", ex);
            }
        });
    }

    private BingoBoardItem selectProofItem(BingoBoardCell cell)
    {
        if (!"multi_item".equals(normalizeName(cell.getTileType())))
        {
            return new BingoBoardItem(0, "");
        }
        final List<BingoBoardItem> items = cell.getMultiItems();
        if (items == null || items.isEmpty())
        {
            notifier.notify("ParentsGuild: this multi item tile has no selectable items.");
            return null;
        }
        if (items.size() == 1)
        {
            return items.get(0);
        }

        final Object selected = JOptionPane.showInputDialog(
            null,
            "Item being submitted",
            "ParentsGuild Bingo Proof",
            JOptionPane.PLAIN_MESSAGE,
            null,
            items.toArray(),
            items.get(0)
        );
        return selected instanceof BingoBoardItem ? (BingoBoardItem) selected : null;
    }

    private void submitTileProof(String endpoint, String playerRsn, BingoBoardCell cell, BingoBoardItem selectedItem, String eventId, String capturedAtUtc, byte[] screenshotBytes)
    {
        final MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("source", "runelite_plugin")
            .addFormDataPart("eventId", eventId)
            .addFormDataPart("playerRsn", playerRsn)
            .addFormDataPart("tileId", cell.getTileId())
            .addFormDataPart("capturedAtUtc", capturedAtUtc);
        if (selectedItem != null && selectedItem.getItemId() > 0)
        {
            bodyBuilder
                .addFormDataPart("itemId", Integer.toString(selectedItem.getItemId()))
                .addFormDataPart("itemName", selectedItem.getItemName());
        }
        final MultipartBody body = bodyBuilder
            .addFormDataPart("proof", "parentsguild-bingo-proof.png", RequestBody.create(PNG, screenshotBytes))
            .build();

        final Request request = new Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(body)
            .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Bingo proof submission failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response httpResponse = response)
                {
                    final String responseBody = httpResponse.body() != null ? httpResponse.body().string() : "";
                    if (!httpResponse.isSuccessful())
                    {
                        log.warn("Bingo proof endpoint returned HTTP {}: {}", httpResponse.code(), responseBody);
                        notifier.notify("ParentsGuild: bingo proof upload failed.");
                        return;
                    }

                    final JsonObject responseJson = parseResponseJson(responseBody);
                    final String outcome = jsonString(responseJson, "outcome");
                    if ("submitted".equals(outcome))
                    {
                        final String tileLabel = firstNonBlank(jsonString(responseJson, "tileLabel"), cell.getLabel());
                        notifier.notify("ParentsGuild: proof submitted for \"" + tileLabel + "\". Pending host approval.");
                        requestBingoStatusRefresh(true);
                        requestBingoBoardRefresh(true);
                        return;
                    }

                    if ("no_match".equals(outcome))
                    {
                        final String reason = jsonString(responseJson, "reason");
                        debugLog("Quiet bingo proof outcome=no_match tile={} reason={} response={}", cell.getLabel(), reason, responseBody);
                        notifier.notify("ParentsGuild: proof was not submitted" + (reason.isEmpty() ? "." : ": " + reason));
                        requestBingoBoardRefresh(true);
                        return;
                    }

                    log.warn("Unexpected bingo proof response: {}", responseBody);
                }
            }
        });
    }

    // Screenshot privacy

    private void requestPrivacyAwareScreenshot(Consumer<Image> consumer)
    {
        clientThread.invoke(() -> {
            final boolean hideChat = config.redactChatboxProofScreenshots();
            final boolean chatHidden = hideWidget(hideChat, InterfaceID.Chatbox.UNIVERSE);
            final boolean pmsHidden = hideWidget(hideChat, InterfaceID.ChatBoth.UNIVERSE);
            drawManager.requestNextFrameListener((Image image) -> {
                try
                {
                    consumer.accept(image);
                }
                finally
                {
                    unhideWidget(chatHidden, InterfaceID.Chatbox.UNIVERSE);
                    unhideWidget(pmsHidden, InterfaceID.ChatBoth.UNIVERSE);
                }
            });
        });
    }

    private boolean hideWidget(boolean shouldHide, int componentId)
    {
        if (!shouldHide)
        {
            return false;
        }

        final Widget widget = client.getWidget(componentId);
        if (widget == null || widget.isHidden())
        {
            return false;
        }

        widget.setHidden(true);
        return true;
    }

    private void unhideWidget(boolean shouldUnhide, int componentId)
    {
        if (!shouldUnhide)
        {
            return;
        }

        clientThread.invoke(() -> {
            final Widget widget = client.getWidget(componentId);
            if (widget != null)
            {
                widget.setHidden(false);
            }
        });
    }

    // HTTP and configuration helpers

    private JsonObject getJsonObject(String url) throws IOException
    {
        final JsonElement element = getJsonElement(url);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private JsonElement getJsonElement(String url) throws IOException
    {
        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build();

        try (Response response = okHttpClient.newCall(request).execute())
        {
            final String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful())
            {
                throw new IOException("HTTP " + response.code() + " for " + url + ": " + responseBody);
            }

            try
            {
                return gson.fromJson(responseBody, JsonElement.class);
            }
            catch (RuntimeException ex)
            {
                throw new IOException("Invalid JSON from " + url, ex);
            }
        }
    }

    private static boolean isConfiguredForSubmission(String endpoint)
    {
        return !endpoint.isEmpty();
    }

    private void warnConfigIncomplete(String message)
    {
        final long now = System.currentTimeMillis();
        if ((now - lastConfigWarningAtMillis) < CONFIG_WARNING_COOLDOWN_MILLIS)
        {
            return;
        }

        lastConfigWarningAtMillis = now;
        notifier.notify(message);
    }

    private int canonicalItemId(int itemId)
    {
        try
        {
            return itemManager.canonicalize(itemId);
        }
        catch (RuntimeException ex)
        {
            debugLog("Falling back to raw item id {} because canonicalization failed: {}", itemId, ex.getMessage());
            return itemId;
        }
    }

    private void purgeExpiredRecentDropEvents()
    {
        final long cutoff = System.currentTimeMillis() - RECENT_EVENT_TTL_MILLIS;
        recentDropEventIds.entrySet().removeIf((entry) -> entry.getValue() < cutoff);
    }

    private void purgeExpiredSubmissionNotifications()
    {
        final long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
        seenSubmissionNotificationIds.entrySet().removeIf((entry) -> entry.getValue() < cutoff);
    }

    private void purgeExpiredCompletionNotifications()
    {
        final long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
        seenCompletionNotificationIds.entrySet().removeIf((entry) -> entry.getValue() < cutoff);
    }

    private String resolveBingoDropEndpoint()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        return base.isEmpty() ? "" : base + "/api/integrations/bingo-drop.php";
    }

    private String resolveLifetimeLootEndpoint()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        return base.isEmpty() ? "" : base + "/api/integrations/lifetime-loot.php";
    }

    private String resolveBingoProofEndpoint()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        return base.isEmpty() ? "" : base + "/api/integrations/bingo-proof.php";
    }

    private String resolveBingoMetricEndpoint()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        return base.isEmpty() ? "" : base + "/api/integrations/bingo-metric.php";
    }

    private String resolveBingoStatusEndpoint()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        return base.isEmpty() ? "" : base + "/api/integrations/bingo-status.php";
    }

    private String resolveBingoBoardEndpoint()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        return base.isEmpty() ? "" : base + "/api/integrations/bingo-board.php";
    }

    private String resolveClanPanelEndpoint()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        return base.isEmpty() ? "" : base + "/api/integrations/clan-panel.php";
    }

    private void requestWomPlayerUpdate(String playerRsn)
    {
        if (womExecutor == null)
        {
            return;
        }

        final String cleanedRsn = cleanText(playerRsn);
        if (cleanedRsn.isEmpty())
        {
            return;
        }

        womExecutor.execute(() -> {
            final String url = WISE_OLD_MAN_PLAYER_API + urlEncode(cleanedRsn).replace("+", "%20");
            final Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(RequestBody.create(JSON, "{}"))
                .build();
            try (Response response = okHttpClient.newCall(request).execute())
            {
                final String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful())
                {
                    log.warn("WOM player update failed for {} with HTTP {}: {}", cleanedRsn, response.code(), body);
                    return;
                }
                debugLog("WOM player update triggered for {}", cleanedRsn);
            }
            catch (IOException | RuntimeException ex)
            {
                log.warn("Failed to trigger WOM player update for {}", cleanedRsn, ex);
            }
        });
    }

    // Clan panel payload parsing

    private WomPanelState fetchClanPanelState(String playerRsn) throws IOException
    {
        final String cleanedRsn = cleanText(playerRsn);
        if (cleanedRsn.isEmpty())
        {
            return WomPanelState.message("Log in to load clan panel.", "RuneLite has not provided an account name.");
        }

        final String endpoint = resolveClanPanelEndpoint();
        if (endpoint.isEmpty())
        {
            return WomPanelState.message("Set the website base URL.", "The plugin loads cached clan panel data from the ParentsGuild website.");
        }

        final String url = endpoint + "?playerRsn=" + URLEncoder.encode(cleanedRsn, StandardCharsets.UTF_8.toString());
        final JsonObject payload = getJsonObject(url);
        if (!jsonBoolean(payload, "matched"))
        {
            return WomPanelState.message("Clan panel unavailable.", "This account is not on the active ParentsGuild roster.");
        }

        final ClanProfileState profile = parseClanProfileState(jsonObject(payload, "profile"), cleanedRsn);
        final BingoPanelState bingo = parseBingoPanelState(jsonObject(payload, "bingo"));
        final WomEventsPayload womEvents = parseWomEventsPayload(jsonObject(payload, "womEvents"));
        final AnnouncementState announcement = parseAnnouncementState(jsonObject(payload, "announcements"));
        final QuickLinksState quickLinks = parseQuickLinksState(jsonObject(payload, "quickLinks"));
        final List<UpcomingEventState> upcomingEvents = parseUpcomingEvents(jsonArray(payload, "upcomingEvents"));
        final List<CompetitionView> competitions = womEvents.getCompetitions();
        final String statusMessage = competitions.isEmpty()
            ? "No active WOM events."
            : competitions.size() + " active WOM event" + (competitions.size() == 1 ? "" : "s") + ".";
        final String detail = womEvents.getDetailMessage().isEmpty()
            ? "Last updated " + formatDisplayDateTime(Instant.now())
            : womEvents.getDetailMessage();

        return new WomPanelState(false, statusMessage, detail, profile, bingo, announcement, quickLinks, upcomingEvents, competitions);
    }

    private ClanProfileState parseClanProfileState(JsonObject profile, String fallbackRsn)
    {
        return new ClanProfileState(
            true,
            "",
            jsonString(profile, "memberId"),
            firstNonBlank(jsonString(profile, "rsn"), fallbackRsn),
            firstNonBlank(jsonString(profile, "displayRank"), "Squire"),
            firstNonBlank(jsonString(profile, "computedRank"), "Squire"),
            jsonString(profile, "selectedRank"),
            Math.max(0, jsonInt(profile, "combinedPoints")),
            Math.max(0, jsonInt(profile, "participationPoints")),
            firstNonBlank(jsonString(profile, "nextRank"), "Max rank"),
            Math.max(0, jsonInt(profile, "pointsUntilNextRank")),
            Math.max(0, jsonInt(profile, "totalLevel")),
            Math.max(0D, jsonDouble(profile, "overallXp")),
            jsonString(profile, "joinDate"),
            Math.max(0, jsonInt(profile, "seniorityPoints")),
            Math.max(0, jsonInt(profile, "botwWins")),
            Math.max(0, jsonInt(profile, "sotwWins"))
        );
    }

    private BingoPanelState parseBingoPanelState(JsonObject bingo)
    {
        final List<String> teamMembers = new ArrayList<>();
        for (JsonElement element : jsonArray(bingo, "teamMembers"))
        {
            final String value = element.isJsonPrimitive() ? cleanText(element.getAsString()) : "";
            if (!value.isEmpty())
            {
                teamMembers.add(value);
            }
        }

        final List<BingoActivityState> activity = new ArrayList<>();
        for (JsonElement element : jsonArray(bingo, "recentActivity"))
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            activity.add(new BingoActivityState(
                jsonString(object, "type"),
                jsonString(object, "label"),
                jsonString(object, "createdAtUtc")
            ));
        }

        return new BingoPanelState(
            jsonBoolean(bingo, "active"),
            jsonBoolean(bingo, "matched"),
            jsonString(bingo, "bingoName"),
            jsonString(bingo, "teamName"),
            teamMembers,
            Math.max(0, jsonInt(bingo, "completedTiles")),
            Math.max(0, jsonInt(bingo, "pendingTiles")),
            Math.max(0, jsonInt(bingo, "remainingTiles")),
            activity
        );
    }

    private WomEventsPayload parseWomEventsPayload(JsonObject womEvents)
    {
        final List<CompetitionView> competitions = new ArrayList<>();
        for (JsonElement element : jsonArray(womEvents, "events"))
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            final Instant startsAt = jsonInstant(object, "startsAtUtc");
            final Instant endsAt = jsonInstant(object, "endsAtUtc");
            if (startsAt == null || endsAt == null)
            {
                continue;
            }

            final List<LeaderboardEntry> leaderboard = parseLeaderboardEntries(jsonArray(object, "leaderboard"));
            final LeaderboardEntry localPlayerEntry = parseLeaderboardEntry(jsonObject(object, "localPlayerEntry"));
            final int limit = Math.max(3, config.womLeaderboardSize());
            final List<LeaderboardEntry> visibleLeaderboard = new ArrayList<>();
            for (LeaderboardEntry entry : leaderboard)
            {
                if (visibleLeaderboard.size() < limit)
                {
                    visibleLeaderboard.add(entry);
                }
            }
            if (localPlayerEntry != null && visibleLeaderboard.stream().noneMatch(entry -> entry.getRank() == localPlayerEntry.getRank()))
            {
                visibleLeaderboard.add(localPlayerEntry);
            }

            competitions.add(new CompetitionView(
                jsonInt(object, "id"),
                jsonString(object, "title"),
                jsonString(object, "metric"),
                startsAt,
                endsAt,
                firstNonBlank(jsonString(object, "timeRemainingText"), describeTimeRemaining(Instant.now(), endsAt)),
                visibleLeaderboard,
                localPlayerEntry,
                Math.max(0D, jsonDouble(object, "gapToNext"))
            ));
        }

        final String lastRefreshedAt = jsonString(womEvents, "lastRefreshedAtUtc");
        final Instant lastRefreshedInstant = parseServerUtcInstant(lastRefreshedAt);
        final String lastRefreshedDisplay = lastRefreshedInstant == null ? lastRefreshedAt : formatDisplayDateTime(lastRefreshedInstant);
        final String detail = firstNonBlank(
            jsonBoolean(womEvents, "stale") && !lastRefreshedDisplay.isEmpty() ? "Cached WOM data is stale. Last refreshed " + lastRefreshedDisplay : "",
            !lastRefreshedDisplay.isEmpty() ? "WOM cache refreshed " + lastRefreshedDisplay : ""
        );
        return new WomEventsPayload(
            jsonString(womEvents, "status"),
            lastRefreshedAt,
            jsonBoolean(womEvents, "stale"),
            detail,
            competitions
        );
    }

    private List<LeaderboardEntry> parseLeaderboardEntries(JsonArray rows)
    {
        final List<LeaderboardEntry> entries = new ArrayList<>();
        for (JsonElement element : rows)
        {
            if (element.isJsonObject())
            {
                final LeaderboardEntry entry = parseLeaderboardEntry(element.getAsJsonObject());
                if (entry != null)
                {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private LeaderboardEntry parseLeaderboardEntry(JsonObject row)
    {
        final String displayName = jsonString(row, "displayName");
        if (displayName.isEmpty())
        {
            return null;
        }
        return new LeaderboardEntry(
            Math.max(0, jsonInt(row, "rank")),
            displayName,
            jsonDouble(row, "gained"),
            jsonDouble(row, "start"),
            jsonDouble(row, "end"),
            jsonBoolean(row, "localPlayer")
        );
    }

    private AnnouncementState parseAnnouncementState(JsonObject announcement)
    {
        return new AnnouncementState(
            jsonString(announcement, "message"),
            jsonString(announcement, "updatedAtUtc")
        );
    }

    private QuickLinksState parseQuickLinksState(JsonObject links)
    {
        return new QuickLinksState(
            jsonString(links, "discordInviteCode"),
            Math.max(0, jsonInt(links, "womGroupId"))
        );
    }

    private List<UpcomingEventState> parseUpcomingEvents(JsonArray events)
    {
        final List<UpcomingEventState> results = new ArrayList<>();
        for (JsonElement element : events)
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            final String title = jsonString(object, "title");
            if (title.isEmpty())
            {
                continue;
            }
            results.add(new UpcomingEventState(
                title,
                jsonString(object, "type"),
                jsonString(object, "startsAtUtc"),
                jsonString(object, "endsAtUtc"),
                jsonString(object, "eventDate"),
                jsonString(object, "source"),
                Math.max(0, jsonInt(object, "womCompetitionId")),
                jsonString(object, "description"),
                jsonString(object, "location"),
                jsonString(object, "status"),
                Math.max(0, jsonInt(object, "interestedCount")),
                jsonBoolean(object, "isRecurring"),
                jsonInt(object, "recurrenceFrequency")
            ));
        }
        return results;
    }

    // URL helpers

    private static String resolveEndpointBase(String baseUrl)
    {
        final String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty())
        {
            return "";
        }

        String normalized = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://"))
        {
            return "";
        }
        if (normalized.startsWith("http://") && !isLocalHttpBase(normalized))
        {
            normalized = "https://" + normalized.substring("http://".length());
        }
        return normalized;
    }

    private static String urlEncode(String value)
    {
        try
        {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.toString());
        }
        catch (IOException ex)
        {
            return value == null ? "" : value;
        }
    }

    private static boolean isLocalHttpBase(String normalizedBaseUrl)
    {
        final String lower = normalizedBaseUrl.toLowerCase(Locale.US);
        return lower.startsWith("http://localhost")
            || lower.startsWith("http://127.")
            || lower.startsWith("http://10.")
            || lower.startsWith("http://172.16.")
            || lower.startsWith("http://172.17.")
            || lower.startsWith("http://172.18.")
            || lower.startsWith("http://172.19.")
            || lower.startsWith("http://172.20.")
            || lower.startsWith("http://172.21.")
            || lower.startsWith("http://172.22.")
            || lower.startsWith("http://172.23.")
            || lower.startsWith("http://172.24.")
            || lower.startsWith("http://172.25.")
            || lower.startsWith("http://172.26.")
            || lower.startsWith("http://172.27.")
            || lower.startsWith("http://172.28.")
            || lower.startsWith("http://172.29.")
            || lower.startsWith("http://172.30.")
            || lower.startsWith("http://172.31.")
            || lower.startsWith("http://192.168.");
    }

    // Bingo payload parsing

    private BingoOverlayState fetchBingoOverlayState(String endpoint, String playerRsn) throws IOException
    {
        final String url = endpoint + "?playerRsn=" + URLEncoder.encode(playerRsn, StandardCharsets.UTF_8.toString());
        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build();

        try (Response response = okHttpClient.newCall(request).execute())
        {
            final String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful())
            {
                throw new IOException("HTTP " + response.code() + " for " + endpoint + ": " + responseBody);
            }

            final JsonObject payload = parseResponseJson(responseBody);
            if (!jsonBoolean(payload, "active") || !jsonBoolean(payload, "matched"))
            {
                return BingoOverlayState.hidden();
            }

            final String bingoName = jsonString(payload, "bingoName");
            final String teamName = jsonString(payload, "teamName");
            final String timeText = jsonString(payload, "overlayDateTime");
            if (bingoName.isEmpty() || teamName.isEmpty() || timeText.isEmpty())
            {
                return BingoOverlayState.hidden();
            }

            handleSubmissionNotifications(jsonArray(payload, "submissionNotifications"), playerRsn);
            handleCompletionNotifications(jsonArray(payload, "completionNotifications"), playerRsn);
            return new BingoOverlayState(true, bingoName, teamName, timeText);
        }
    }

    private void handleSubmissionNotifications(JsonArray notifications, String playerRsn)
    {
        if (notifications == null || notifications.size() == 0)
        {
            return;
        }

        purgeExpiredSubmissionNotifications();
        for (JsonElement element : notifications)
        {
            if (!element.isJsonObject())
            {
                continue;
            }

            final JsonObject notification = element.getAsJsonObject();
            final String id = jsonString(notification, "id");
            if (id.isEmpty() || seenSubmissionNotificationIds.putIfAbsent(id, System.currentTimeMillis()) != null)
            {
                continue;
            }

            final Instant createdAt = parseServerUtcInstant(jsonString(notification, "createdAtUtc"));
            if (createdAt != null && createdAt.isBefore(pluginStartedAt.minusSeconds(5)))
            {
                continue;
            }

            final String bingoName = firstNonBlank(jsonString(notification, "bingoName"), "Bingo");
            final String teamName = firstNonBlank(jsonString(notification, "teamName"), "Team");
            final String tileLabel = firstNonBlank(jsonString(notification, "tileLabel"), "tile");
            final String submittedBy = firstNonBlank(jsonString(notification, "submittedByLabel"), "A teammate");
            if (sameNormalizedName(firstNonBlank(jsonString(notification, "submittedByRsn"), submittedBy), playerRsn))
            {
                continue;
            }

            sendGameMessage("ParentsGuild: " + submittedBy + " submitted \"" + tileLabel + "\" for " + teamName + " in " + bingoName + ". Pending host approval.");
        }
    }

    private void handleCompletionNotifications(JsonArray notifications, String playerRsn)
    {
        if (notifications == null || notifications.size() == 0)
        {
            return;
        }

        purgeExpiredCompletionNotifications();
        for (JsonElement element : notifications)
        {
            if (!element.isJsonObject())
            {
                continue;
            }

            final JsonObject notification = element.getAsJsonObject();
            final String id = jsonString(notification, "id");
            if (id.isEmpty() || seenCompletionNotificationIds.putIfAbsent(id, System.currentTimeMillis()) != null)
            {
                continue;
            }

            final Instant createdAt = parseServerUtcInstant(jsonString(notification, "createdAtUtc"));
            if (createdAt != null && createdAt.isBefore(pluginStartedAt.minusSeconds(5)))
            {
                continue;
            }

            final String bingoName = firstNonBlank(jsonString(notification, "bingoName"), "Bingo");
            final String teamName = firstNonBlank(jsonString(notification, "teamName"), "Team");
            final String tileLabel = firstNonBlank(jsonString(notification, "tileLabel"), "tile");
            final String completedBy = jsonString(notification, "completedByLabel");
            if (sameNormalizedName(firstNonBlank(jsonString(notification, "completedByRsn"), completedBy), playerRsn))
            {
                continue;
            }

            final String suffix = completedBy.isEmpty() ? "" : " by " + completedBy;
            sendGameMessage("ParentsGuild: " + bingoName + " - " + teamName + " completed \"" + tileLabel + "\"" + suffix + ".");
        }
    }

    private BingoBoardState fetchBingoBoardState(String endpoint, String playerRsn) throws IOException
    {
        final String url = endpoint + "?playerRsn=" + URLEncoder.encode(playerRsn, StandardCharsets.UTF_8.toString());
        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build();

        try (Response response = okHttpClient.newCall(request).execute())
        {
            final String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful())
            {
                throw new IOException("HTTP " + response.code() + " for " + endpoint + ": " + responseBody);
            }

            final JsonObject payload = parseResponseJson(responseBody);
            if (!jsonBoolean(payload, "active") || !jsonBoolean(payload, "matched"))
            {
                return BingoBoardState.hidden();
            }

            final String bingoName = jsonString(payload, "bingoName");
            final String teamName = jsonString(payload, "teamName");
            final JsonObject team = jsonObject(payload, "team");
            final int rowsCount = Math.max(1, jsonInt(team, "rowsCount"));
            final int colsCount = Math.max(1, jsonInt(team, "colsCount"));
            final List<String> teamMembers = parseBoardTeamMembers(team);
            final JsonArray gridArray = jsonArray(team, "grid");
            final List<List<BingoBoardCell>> grid = new ArrayList<>();
            for (JsonElement rowElement : gridArray)
            {
                if (!rowElement.isJsonArray())
                {
                    continue;
                }

                final List<BingoBoardCell> row = new ArrayList<>();
                for (JsonElement tileElement : rowElement.getAsJsonArray())
                {
                    if (!tileElement.isJsonObject())
                    {
                        continue;
                    }

                    final JsonObject tile = tileElement.getAsJsonObject();
                    final String label = firstNonBlank(
                        jsonString(tile, "label"),
                        jsonString(tile, "dropItemName"),
                        multiItemTileLabel(tile),
                        jsonString(tile, "metricLabel"),
                        normalizeTileTypeLabel(jsonString(tile, "tileType"))
                    );
                    row.add(new BingoBoardCell(
                        jsonString(tile, "id"),
                        label,
                        buildBoardTooltip(tile),
                        buildBoardProgressText(tile),
                        jsonBoolean(tile, "isCompleted"),
                        jsonBoolean(tile, "pendingClaim"),
                        jsonString(tile, "tileType"),
                        jsonString(tile, "metricKey"),
                        jsonString(tile, "metricLabel"),
                        Math.max(0L, jsonLong(tile, "progressValue")),
                        Math.max(0L, jsonLong(tile, "pendingProgressValue")),
                        Math.max(0L, jsonLong(tile, "targetValue")),
                        Math.max(1, jsonInt(tile, "requiredCompletions")),
                        Math.max(0, jsonInt(tile, "approvedCompletions")),
                        Math.max(0, jsonInt(tile, "pendingCompletions")),
                        parseMultiItemTileItems(tile),
                        boardTileImage(endpoint, tile)
                    ));
                }
                if (!row.isEmpty())
                {
                    grid.add(row);
                }
            }

            if (bingoName.isEmpty() || teamName.isEmpty() || grid.isEmpty())
            {
                return BingoBoardState.hidden();
            }

            return new BingoBoardState(true, bingoName, teamName, teamMembers, rowsCount, colsCount, grid);
        }
    }

    private static List<String> parseBoardTeamMembers(JsonObject team)
    {
        final List<String> members = new ArrayList<>();
        final Set<String> seenNames = new HashSet<>();
        for (JsonElement participantElement : jsonArray(team, "participants"))
        {
            if (!participantElement.isJsonObject())
            {
                continue;
            }

            final String displayName = firstNonBlank(
                jsonString(participantElement.getAsJsonObject(), "rsnLabel"),
                jsonString(participantElement.getAsJsonObject(), "rsn")
            );
            final String normalized = normalizeName(displayName);
            if (!normalized.isEmpty() && seenNames.add(normalized))
            {
                members.add(displayName);
            }
        }
        return members;
    }

    private static String buildBoardTooltip(JsonObject tile)
    {
        final List<String> lines = new ArrayList<>();
        final String description = cleanText(jsonString(tile, "description"));
        if (!description.isEmpty())
        {
            lines.add(escapeTooltipHtml(description));
        }

        final String tileType = normalizeName(jsonString(tile, "tileType"));
        if ("multi_item".equals(tileType))
        {
            final int requiredCount = Math.max(1, jsonInt(tile, "multiItemRequiredCount"));
            if ("all".equals(normalizeName(jsonString(tile, "multiItemRequirement"))))
            {
                lines.add("<b>Rules:</b> All listed items are required.");
            }
            else
            {
                lines.add("<b>Rules:</b> Any " + requiredCount + " listed item" + (requiredCount == 1 ? " is" : "s are") + " required.");
            }
            if ("unique".equals(normalizeName(jsonString(tile, "multiItemCountMode"))))
            {
                lines.add("Each counted submission must be a different item.");
            }
            final String memberRule = normalizeName(jsonString(tile, "multiItemMemberRule"));
            if ("same".equals(memberRule))
            {
                lines.add("Every counted item must come from the same team member.");
            }
            else if ("different".equals(memberRule) || jsonBoolean(tile, "multiItemRequireDifferentMembers"))
            {
                lines.add("Each counted item must come from a different team member.");
            }
        }
        else if (("manual".equals(tileType) || "drop".equals(tileType))
            && "one_per_account".equals(normalizeName(jsonString(tile, "repeatContributionMode"))))
        {
            lines.add("<b>Rules:</b> Each counted claim must be from a different team account.");
        }

        if ("participant".equals(normalizeName(jsonString(tile, "metricScope"))))
        {
            lines.add("<b>Rules:</b> One team account must meet this metric.");
        }
        return lines.isEmpty() ? "" : "<html>" + String.join("<br>", lines) + "</html>";
    }

    private static String escapeTooltipHtml(String value)
    {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>");
    }

    private static String buildBoardProgressText(JsonObject tile)
    {
        final int approvedCompletions = Math.max(0, jsonInt(tile, "approvedCompletions"));
        final int pendingCompletions = Math.max(0, jsonInt(tile, "pendingCompletions"));
        final int requiredCompletions = Math.max(1, jsonInt(tile, "requiredCompletions"));
        final String tileType = normalizeName(jsonString(tile, "tileType"));
        if (("manual".equals(tileType) || "drop".equals(tileType) || "multi_item".equals(tileType))
            && (requiredCompletions > 1 || "drop".equals(tileType) || "multi_item".equals(tileType)))
        {
            if (pendingCompletions <= 0 && requiredCompletions <= 1)
            {
                return Math.min(approvedCompletions, requiredCompletions) + "/" + requiredCompletions;
            }
            return Math.min(approvedCompletions, requiredCompletions) + "(+" + pendingCompletions + ")/" + requiredCompletions;
        }

        final long targetValue = Math.max(0L, jsonLong(tile, "targetValue"));
        if ("metric".equals(tileType) && targetValue > 0L)
        {
            final long progressValue = Math.max(0L, jsonLong(tile, "progressValue"));
            final long pendingProgressValue = Math.min(
                Math.max(0L, jsonLong(tile, "pendingProgressValue")),
                Math.max(0L, targetValue - Math.min(progressValue, targetValue))
            );
            final String suffix = metricProgressSuffix(jsonString(tile, "metricLabel"));
            if (pendingProgressValue > 0L)
            {
                return compactMetricValue(Math.min(progressValue, targetValue)) + "(+" + compactMetricValue(pendingProgressValue) + ")/" + compactMetricValue(targetValue) + suffix;
            }
            return compactMetricValue(Math.min(progressValue, targetValue)) + "/" + compactMetricValue(targetValue) + suffix;
        }

        if (approvedCompletions > 0)
        {
            return approvedCompletions + "/" + requiredCompletions;
        }

        return "";
    }

    private String configuredWebsiteApiUrl(String endpoint, String apiPath)
    {
        if (!apiPath.startsWith("/api/"))
        {
            return "";
        }

        try
        {
            return URI.create(endpoint).resolve(apiPath).toString();
        }
        catch (IllegalArgumentException ex)
        {
            return "";
        }
    }

    private BufferedImage boardTileImage(String endpoint, JsonObject tile)
    {
        final String tileId = cleanText(jsonString(tile, "id"));
        if (!tileId.isEmpty() && !cleanText(jsonString(tile, "backgroundImageStorageName")).isEmpty())
        {
            final String updatedAt = cleanText(jsonString(tile, "backgroundImageUpdatedAt"));
            final String version = updatedAt.isEmpty() ? "" : "&v=" + urlEncode(updatedAt);
            return loadRemoteImage(configuredWebsiteApiUrl(endpoint, "/api/bingo-tile-background.php?id=" + urlEncode(tileId) + "&format=png" + version));
        }

        if ("multi_item".equals(normalizeName(jsonString(tile, "tileType"))))
        {
            return multiItemTileImage(tile);
        }

        int itemId = jsonInt(tile, "dropItemId");
        if (itemId > 0)
        {
            try
            {
                return itemManager.getImage(itemId);
            }
            catch (RuntimeException ex)
            {
                debugLog("RuneLite item image failed for item {}: {}", itemId, ex.getMessage());
            }
            return null;
        }

        final String metricKey = cleanText(jsonString(tile, "metricKey"));
        if ("metric".equals(normalizeName(jsonString(tile, "tileType"))) && metricKey.startsWith("boss:"))
        {
            return loadRemoteImage(configuredWebsiteApiUrl(endpoint, "/api/bingo-icon.php?metricKey=" + urlEncode(metricKey) + "&format=png"));
        }

        return null;
    }

    private BufferedImage multiItemTileImage(JsonObject tile)
    {
        final List<BufferedImage> images = new ArrayList<>();
        for (JsonElement itemElement : jsonArray(tile, "multiItems"))
        {
            if (!itemElement.isJsonObject())
            {
                continue;
            }
            final JsonObject item = itemElement.getAsJsonObject();
            if (item.has("displayOnTile") && !jsonBoolean(item, "displayOnTile"))
            {
                continue;
            }
            final int itemId = jsonInt(item, "itemId");
            if (itemId <= 0)
            {
                continue;
            }
            try
            {
                final AsyncBufferedImage image = itemManager.getImage(itemId);
                if (image != null)
                {
                    images.add(image);
                    image.onLoaded(this::requestBingoBoardImageRefresh);
                }
            }
            catch (RuntimeException ex)
            {
                debugLog("RuneLite item image failed for multi item {}: {}", itemId, ex.getMessage());
            }
            if (images.size() >= 4)
            {
                break;
            }
        }

        if (images.isEmpty())
        {
            return null;
        }
        if (images.size() == 1)
        {
            return images.get(0);
        }

        final int canvasSize = 96;
        final int slotSize = images.size() <= 2 ? 58 : 44;
        final BufferedImage combined = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = combined.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            for (int index = 0; index < images.size(); index++)
            {
                final BufferedImage image = images.get(index);
                final int slotX;
                final int slotY;
                if (images.size() == 2)
                {
                    slotX = index == 0 ? 8 : 40;
                    slotY = index == 0 ? 22 : 34;
                }
                else
                {
                    slotX = 8 + (index % 2) * 40;
                    slotY = 8 + (index / 2) * 40;
                }
                drawScaledImage(graphics, image, slotX, slotY, slotSize, slotSize);
            }
        }
        finally
        {
            graphics.dispose();
        }
        return combined;
    }

    private void requestBingoBoardImageRefresh()
    {
        if (womExecutor == null || !bingoBoardImageRefreshQueued.compareAndSet(false, true))
        {
            return;
        }
        womExecutor.schedule(() -> {
            bingoBoardImageRefreshQueued.set(false);
            requestBingoBoardRefresh(false);
        }, 1, TimeUnit.SECONDS);
    }

    private static void drawScaledImage(Graphics2D graphics, BufferedImage image, int x, int y, int width, int height)
    {
        final double scale = Math.min(width / (double) image.getWidth(), height / (double) image.getHeight());
        final int drawWidth = Math.max(1, (int) Math.ceil(image.getWidth() * scale));
        final int drawHeight = Math.max(1, (int) Math.ceil(image.getHeight() * scale));
        final int drawX = x + ((width - drawWidth) / 2);
        final int drawY = y + ((height - drawHeight) / 2);
        graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
    }

    // Image loading

    private BufferedImage loadRemoteImage(String imageUrl)
    {
        if (imageUrl.isEmpty())
        {
            return null;
        }

        final BufferedImage cachedImage = bingoBoardImageCache.get(imageUrl);
        if (cachedImage != null)
        {
            return cachedImage;
        }

        final Request request = new Request.Builder()
            .url(imageUrl)
            .header("Accept", "image/png,image/jpeg,image/webp,image/*")
            .build();
        try (Response response = okHttpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                return null;
            }

            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body().bytes()));
            if (image != null)
            {
                bingoBoardImageCache.put(imageUrl, image);
            }
            return image;
        }
        catch (IOException | RuntimeException ex)
        {
            if (config.debug())
            {
                log.debug("Failed to load bingo board image {}", imageUrl, ex);
            }
            return null;
        }
    }

    private static String metricProgressSuffix(String metricLabel)
    {
        final String normalized = normalizeName(metricLabel);
        return normalized.contains("xp") || normalized.contains("experience") ? " xp" : "";
    }

    private static String compactMetricValue(long value)
    {
        final long absolute = Math.abs(value);
        if (absolute >= 1_000_000_000L)
        {
            return compactMetricDecimal(value / 1_000_000_000D) + "b";
        }
        if (absolute >= 1_000_000L)
        {
            return compactMetricDecimal(value / 1_000_000D) + "m";
        }
        if (absolute >= 10_000L)
        {
            return compactMetricDecimal(value / 1_000D) + "k";
        }
        return Long.toString(value);
    }

    // General utilities

    private static String compactMetricDecimal(double value)
    {
        if (Math.abs(value - Math.rint(value)) < 0.000001D)
        {
            return DECIMAL_FORMATS.INTEGER.format(value);
        }
        return DECIMAL_FORMATS.ONE_DECIMAL.format(value);
    }

    private void debugLog(String message, Object... args)
    {
        if (config.debug())
        {
            log.debug(message, args);
        }
    }

    BingoOverlayState getBingoOverlayState()
    {
        return bingoOverlayState;
    }

    boolean useDayFirstDates()
    {
        return config.dayFirstDates();
    }

    boolean useTwentyFourHourTime()
    {
        return config.twentyFourHourTime();
    }

    private String formatDisplayDateTime(Instant instant)
    {
        return ParentsGuildDateTimeFormatter.formatDateTime(instant, useDayFirstDates(), useTwentyFourHourTime());
    }

    BingoBoardState getBingoBoardState()
    {
        return bingoBoardState;
    }

    private String currentLocalPlayerName()
    {
        final Player localPlayer = client.getLocalPlayer();
        return localPlayer == null ? "" : cleanText(localPlayer.getName());
    }

    private void sendGameMessage(String message)
    {
        final String cleaned = cleanText(message);
        if (cleaned.isEmpty())
        {
            return;
        }

        clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", cleaned, null));
    }

    private static String firstNonBlank(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.trim().isEmpty())
            {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizeSourceName(String value)
    {
        final String cleaned = cleanText(value);
        return cleaned.isEmpty() ? "Unknown source" : cleaned;
    }

    private static String cleanText(String value)
    {
        return Text.removeTags(value == null ? "" : value).trim();
    }

    private static String normalizeName(String value)
    {
        return cleanText(value).replace('\u00A0', ' ').toLowerCase(Locale.US);
    }

    private static boolean sameNormalizedName(String left, String right)
    {
        final String normalizedLeft = normalizeName(left);
        return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalizeName(right));
    }

    private static String normalizeTileTypeLabel(String tileType)
    {
        final String normalized = cleanText(tileType).toLowerCase(Locale.US);
        switch (normalized)
        {
            case "wildcard":
                return "Wild card";
            case "manual":
                return "Manual";
            case "metric":
                return "Metric";
            case "drop":
                return "Drop";
            case "multi_item":
                return "Multi item";
            default:
                return normalized.isEmpty() ? "" : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
        }
    }

    private static String buildEventId(String sourceName, String playerRsn, int itemId, int quantity, String capturedAtUtc)
    {
        final String eventKey = String.join("|",
            sourceName.toLowerCase(Locale.US),
            playerRsn.toLowerCase(Locale.US),
            Integer.toString(itemId),
            Integer.toString(quantity),
            capturedAtUtc
        );
        return sha1(eventKey);
    }

    private static String buildProofEventId(String playerRsn, String tileId, String capturedAtUtc)
    {
        return sha1(String.join("|",
            "proof",
            playerRsn.toLowerCase(Locale.US),
            cleanText(tileId).toLowerCase(Locale.US),
            capturedAtUtc,
            UUID.randomUUID().toString()
        ));
    }

    private static String buildMetricEventId(String playerRsn, String tileId, String metricKey, long targetValue, String achievedAtUtc)
    {
        return sha1(String.join("|",
            "metric",
            playerRsn.toLowerCase(Locale.US),
            cleanText(tileId).toLowerCase(Locale.US),
            cleanText(metricKey).toLowerCase(Locale.US),
            Long.toString(Math.max(0L, targetValue)),
            achievedAtUtc,
            UUID.randomUUID().toString()
        ));
    }

    private static String metricKeyForSkill(Skill skill)
    {
        if (skill == null)
        {
            return "";
        }

        final String name = normalizeName(skill.getName()).replace(' ', '_');
        if (name.isEmpty())
        {
            return "";
        }
        return "skill:" + ("runecraft".equals(name) ? "runecrafting" : name);
    }

    private static String metricKeySlug(String value)
    {
        final StringBuilder builder = new StringBuilder();
        boolean previousUnderscore = false;
        for (char character : normalizeName(value).toCharArray())
        {
            if (Character.isLetterOrDigit(character))
            {
                builder.append(character);
                previousUnderscore = false;
            }
            else if (!previousUnderscore)
            {
                builder.append('_');
                previousUnderscore = true;
            }
        }
        int length = builder.length();
        while (length > 0 && builder.charAt(length - 1) == '_')
        {
            builder.deleteCharAt(length - 1);
            length--;
        }
        return builder.toString();
    }

    private static int parseFirstCount(String value)
    {
        final String cleaned = cleanText(value);
        final StringBuilder digits = new StringBuilder();
        for (int index = 0; index < cleaned.length(); index++)
        {
            final char character = cleaned.charAt(index);
            if (Character.isDigit(character))
            {
                digits.append(character);
            }
            else if (character == ',')
            {
                continue;
            }
            else if (digits.length() > 0)
            {
                break;
            }
        }
        return parsePositiveInt(digits.toString());
    }

    private static int parseTrailingCount(String value)
    {
        return parseFirstCount(value);
    }

    private static int parsePositiveInt(String value)
    {
        try
        {
            return Math.max(0, Integer.parseInt(cleanText(value)));
        }
        catch (RuntimeException ex)
        {
            return 0;
        }
    }

    private static String clueTierFromMessage(String normalizedMessage)
    {
        for (String tier : List.of("beginner", "easy", "medium", "hard", "elite", "master"))
        {
            if (normalizedMessage.contains(" " + tier + " treasure trail"))
            {
                return tier;
            }
        }
        return "";
    }

    private static Instant parseServerUtcInstant(String value)
    {
        final String cleaned = cleanText(value);
        if (cleaned.isEmpty())
        {
            return null;
        }

        try
        {
            return Instant.parse(cleaned.endsWith("Z") ? cleaned : cleaned.replace(' ', 'T') + "Z");
        }
        catch (RuntimeException ignored)
        {
            try
            {
                return LocalDateTime.parse(cleaned, SERVER_UTC_FORMAT).toInstant(ZoneOffset.UTC);
            }
            catch (RuntimeException ex)
            {
                return null;
            }
        }
    }

    private static byte[] toPngBytes(Image image) throws IOException
    {
        final BufferedImage bufferedImage = ImageUtil.bufferedImageFromImage(image);
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        if (!ImageIO.write(bufferedImage, "png", outputStream))
        {
            throw new IOException("PNG encoding was not available");
        }
        return outputStream.toByteArray();
    }

    private NavigationButton buildNavigationButton()
    {
        return NavigationButton.builder()
            .tooltip("ParentsGuild")
            .icon(createToolbarIcon())
            .priority(6)
            .panel(womPanel)
            .build();
    }

    private static BufferedImage createToolbarIcon()
    {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(36, 28, 18));
        graphics.fillRoundRect(0, 0, 16, 16, 4, 4);
        graphics.setColor(new Color(204, 170, 74));
        graphics.drawRoundRect(0, 0, 15, 15, 4, 4);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        graphics.drawString("PG", 2, 11);
        graphics.dispose();
        return image;
    }

    static String formatMetricValue(double value)
    {
        if (Math.abs(value - Math.rint(value)) < 0.000001D)
        {
            return DECIMAL_FORMATS.INTEGER.format(value);
        }
        return DECIMAL_FORMATS.DECIMAL.format(value);
    }

    static String describeTimeRemaining(Instant now, Instant endsAt)
    {
        final Duration duration = Duration.between(now, endsAt);
        if (duration.isNegative() || duration.isZero())
        {
            return "ended";
        }

        final long days = duration.toDays();
        final long hours = duration.minusDays(days).toHours();
        final long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        if (days > 0)
        {
            return days + "d " + hours + "h remaining";
        }
        if (hours > 0)
        {
            return hours + "h " + minutes + "m remaining";
        }
        return Math.max(1, minutes) + "m remaining";
    }

    // JSON helpers

    private JsonObject parseResponseJson(String json)
    {
        try
        {
            final JsonElement element = gson.fromJson(json, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        }
        catch (RuntimeException ex)
        {
            return new JsonObject();
        }
    }

    private static JsonArray jsonArray(JsonObject object, String key)
    {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray())
        {
            return new JsonArray();
        }
        return object.getAsJsonArray(key);
    }

    private static JsonObject jsonObject(JsonObject object, String key)
    {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject())
        {
            return new JsonObject();
        }
        return object.getAsJsonObject(key);
    }

    private static String jsonString(JsonObject object, String key)
    {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
        {
            return "";
        }
        try
        {
            return object.get(key).getAsString();
        }
        catch (RuntimeException ex)
        {
            return "";
        }
    }

    private static int jsonInt(JsonObject object, String key)
    {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
        {
            return 0;
        }
        try
        {
            return object.get(key).getAsInt();
        }
        catch (RuntimeException ex)
        {
            return 0;
        }
    }

    private static long jsonLong(JsonObject object, String key)
    {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
        {
            return 0L;
        }
        try
        {
            return object.get(key).getAsLong();
        }
        catch (RuntimeException ex)
        {
            return 0L;
        }
    }

    private static double jsonDouble(JsonObject object, String key)
    {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
        {
            return 0D;
        }
        try
        {
            return object.get(key).getAsDouble();
        }
        catch (RuntimeException ex)
        {
            return 0D;
        }
    }

    private static Instant jsonInstant(JsonObject object, String key)
    {
        final String value = jsonString(object, key);
        if (value.isEmpty())
        {
            return null;
        }
        try
        {
            return Instant.parse(value);
        }
        catch (RuntimeException ex)
        {
            try
            {
                return OffsetDateTime.parse(value).toInstant();
            }
            catch (RuntimeException ignored)
            {
                return null;
            }
        }
    }

    private static boolean jsonBoolean(JsonObject object, String key)
    {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
        {
            return false;
        }
        try
        {
            return object.get(key).getAsBoolean();
        }
        catch (RuntimeException ex)
        {
            final String value = jsonString(object, key).trim().toLowerCase(Locale.US);
            return "1".equals(value) || "true".equals(value) || "yes".equals(value);
        }
    }

    private static String sha1(String input)
    {
        try
        {
            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            final byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash)
            {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        }
        catch (java.security.NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException("SHA-1 not available", ex);
        }
    }

    private static final class DECIMAL_FORMATS
    {
        private static final DecimalFormat INTEGER = new DecimalFormat("#,##0");
        private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("#,##0.#");
        private static final DecimalFormat DECIMAL = new DecimalFormat("#,##0.##");
    }

    // State models

    @Value
    private static class PendingDrop
    {
        String eventId;
        int itemId;
        int quantity;
        String itemName;
        long unitValue;
        String capturedAtUtc;
    }

    @Value
    static class LeaderboardEntry
    {
        int rank;
        String displayName;
        double gained;
        double start;
        double end;
        boolean localPlayer;
    }

    @Value
    static class CompetitionView
    {
        int id;
        String title;
        String metric;
        Instant startsAt;
        Instant endsAt;
        String timeRemainingText;
        List<LeaderboardEntry> leaderboardEntries;
        LeaderboardEntry localPlayerEntry;
        double gapToNext;
    }

    @Value
    static class ClanProfileState
    {
        boolean available;
        String message;
        String memberId;
        String rsn;
        String displayRank;
        String computedRank;
        String selectedRank;
        int combinedPoints;
        int participationPoints;
        String nextRank;
        int pointsUntilNextRank;
        int totalLevel;
        double overallXp;
        String joinDate;
        int seniorityPoints;
        int botwWins;
        int sotwWins;

        static ClanProfileState unavailable(String message)
        {
            return new ClanProfileState(false, message, "", "", "", "", "", 0, 0, "", 0, 0, 0D, "", 0, 0, 0);
        }
    }

    @Value
    static class BingoActivityState
    {
        String type;
        String label;
        String createdAtUtc;
    }

    @Value
    static class BingoPanelState
    {
        boolean active;
        boolean matched;
        String bingoName;
        String teamName;
        List<String> teamMembers;
        int completedTiles;
        int pendingTiles;
        int remainingTiles;
        List<BingoActivityState> recentActivity;

        static BingoPanelState empty()
        {
            return new BingoPanelState(false, false, "", "", new ArrayList<>(), 0, 0, 0, new ArrayList<>());
        }
    }

    @Value
    static class AnnouncementState
    {
        String message;
        String updatedAtUtc;

        static AnnouncementState empty()
        {
            return new AnnouncementState("", "");
        }
    }

    @Value
    static class QuickLinksState
    {
        String discordInviteCode;
        int womGroupId;

        static QuickLinksState empty()
        {
            return new QuickLinksState("", 0);
        }
    }

    @Value
    static class UpcomingEventState
    {
        String title;
        String type;
        String startsAtUtc;
        String endsAtUtc;
        String eventDate;
        String source;
        int womCompetitionId;
        String description;
        String location;
        String status;
        int interestedCount;
        boolean recurring;
        int recurrenceFrequency;
    }

    @Value
    private static class WomEventsPayload
    {
        String status;
        String lastRefreshedAtUtc;
        boolean stale;
        String detailMessage;
        List<CompetitionView> competitions;
    }

    @Value
    static class WomPanelState
    {
        boolean loading;
        String statusMessage;
        String detailMessage;
        ClanProfileState clanProfile;
        BingoPanelState bingo;
        AnnouncementState announcement;
        QuickLinksState quickLinks;
        List<UpcomingEventState> upcomingEvents;
        List<CompetitionView> competitions;

        static WomPanelState message(String status, String detail)
        {
            return new WomPanelState(false, status, detail, ClanProfileState.unavailable("Clan profile unavailable."), BingoPanelState.empty(), AnnouncementState.empty(), QuickLinksState.empty(), new ArrayList<>(), new ArrayList<>());
        }

        WomPanelState withLoading(boolean nextLoading, String nextStatus)
        {
            return new WomPanelState(nextLoading, nextStatus, detailMessage, clanProfile, bingo, announcement, quickLinks, upcomingEvents, competitions);
        }

        WomPanelState withFailure(String nextStatus, String nextDetail)
        {
            return new WomPanelState(false, nextStatus, nextDetail, clanProfile, bingo, announcement, quickLinks, upcomingEvents, competitions);
        }
    }

    @Value
    static class BingoOverlayState
    {
        boolean visible;
        String bingoName;
        String teamName;
        String timeText;

        static BingoOverlayState hidden()
        {
            return new BingoOverlayState(false, "", "", "");
        }
    }

    @Value
    static class BingoBoardItem
    {
        int itemId;
        String itemName;

        @Override
        public String toString()
        {
            return itemName;
        }
    }

    @Value
    static class BingoBoardCell
    {
        String tileId;
        String label;
        String tooltipText;
        String progressText;
        boolean completed;
        boolean pending;
        String tileType;
        String metricKey;
        String metricLabel;
        long progressValue;
        long pendingProgressValue;
        long targetValue;
        int requiredCompletions;
        int approvedCompletions;
        int pendingCompletions;
        List<BingoBoardItem> multiItems;
        BufferedImage backgroundImage;
    }

    @Value
    static class NpcInventoryRewardInteraction
    {
        String npcName;
        long expiresAtMillis;
    }

    @Value
    static class BingoBoardState
    {
        boolean visible;
        String bingoName;
        String teamName;
        List<String> teamMembers;
        int rowsCount;
        int colsCount;
        List<List<BingoBoardCell>> grid;

        static BingoBoardState hidden()
        {
            return new BingoBoardState(false, "", "", new ArrayList<>(), 0, 0, new ArrayList<>());
        }
    }
}
