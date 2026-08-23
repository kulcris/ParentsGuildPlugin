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
import net.runelite.api.events.ItemContainerChanged;
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
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
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
    private static final DateTimeFormatter CAPTURED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter PANEL_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SERVER_UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneOffset.UTC);
    private static final long RECENT_EVENT_TTL_MILLIS = 30_000L;
    private static final long CONFIG_WARNING_COOLDOWN_MILLIS = 60_000L;
    private static final long DROP_TILE_ELIGIBILITY_CACHE_MILLIS = 10_000L;
    private static final long COMPLETED_DROP_DENY_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long WOM_HTTP_TIMEOUT_WARNING_MILLIS = 20_000L;
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
    private final Map<Integer, Long> womWarningMarkers = new ConcurrentHashMap<>();
    private final AtomicBoolean womRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean bingoStatusRefreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean bingoBoardRefreshInFlight = new AtomicBoolean(false);
    private volatile long lastConfigWarningAtMillis = 0L;
    private volatile long dropTileEligibilityCacheAtMillis = 0L;
    private volatile Set<Integer> dropTileEligibilityCache = new HashSet<>();
    private volatile Instant pluginStartedAt = Instant.EPOCH;
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
        womWarningMarkers.clear();
        lastConfigWarningAtMillis = 0L;
        dropTileEligibilityCacheAtMillis = 0L;
        dropTileEligibilityCache = new HashSet<>();
        pluginStartedAt = Instant.now();
        womRefreshInFlight.set(false);
        bingoStatusRefreshInFlight.set(false);
        bingoBoardRefreshInFlight.set(false);
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
        womWarningMarkers.clear();
        womRefreshInFlight.set(false);
        bingoStatusRefreshInFlight.set(false);
        bingoBoardRefreshInFlight.set(false);
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
            return;
        }

        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            bingoOverlayState = BingoOverlayState.hidden();
            bingoBoardState = BingoBoardState.hidden();
            updateBingoBoardPopup();
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        handleLoot(event.getNpc() != null ? event.getNpc().getName() : "", event.getItems());
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        handleLoot(event.getPlayer() != null ? event.getPlayer().getName() : "", event.getItems());
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        final int containerId = event.getContainerId();
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

        if (womExecutor == null || (!config.showBingoOverlay() && !config.enableBingoDrops()))
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
        if (currentLocalPlayerName().isEmpty())
        {
            debugLog("Skipping login sync attempt because local player name is not available yet.");
            return;
        }
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
        final String token = config.bingoDropToken().trim();
        final String playerRsn = currentLocalPlayerName();
        if (endpoint.isEmpty() || token.isEmpty() || playerRsn.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
        {
            bingoOverlayState = BingoOverlayState.hidden();
            return;
        }

        if (!bingoStatusRefreshInFlight.compareAndSet(false, true))
        {
            return;
        }

        womExecutor.execute(() -> refreshBingoStatusState(endpoint, token, playerRsn, manual));
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

    private void refreshBingoStatusState(String endpoint, String token, String playerRsn, boolean manual)
    {
        try
        {
            final BingoOverlayState nextState = fetchBingoOverlayState(endpoint, token, playerRsn);
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

        if (!bingoBoardOverlayEnabled)
        {
            bingoBoardState = BingoBoardState.hidden();
            updateBingoBoardPopup();
            return;
        }

        final String endpoint = resolveBingoBoardEndpoint();
        final String token = config.bingoDropToken().trim();
        final String playerRsn = currentLocalPlayerName();
        if (endpoint.isEmpty() || token.isEmpty() || playerRsn.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
        {
            bingoBoardState = BingoBoardState.hidden();
            updateBingoBoardPopup();
            return;
        }

        if (!bingoBoardRefreshInFlight.compareAndSet(false, true))
        {
            return;
        }

        womExecutor.execute(() -> refreshBingoBoardState(endpoint, token, playerRsn, manual));
    }

    // Side-panel actions

    void openWebsiteBingoBoard()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        if (base.isEmpty())
        {
            notifier.notify("ParentsGuild: set the website base URL before opening the website board.");
            return;
        }

        try
        {
            LinkBrowser.browse(base + "/bingo.php");
        }
        catch (IllegalArgumentException ex)
        {
            log.warn("Failed to open ParentsGuild bingo board", ex);
            notifier.notify("ParentsGuild: failed to open the website bingo board.");
        }
    }

    void openExternalUrl(String url, String label)
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

    String websiteProfileUrl(ClanProfileState profile)
    {
        if (profile == null || !profile.isAvailable() || profile.getMemberId() == null || profile.getMemberId().trim().isEmpty())
        {
            return "";
        }
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        if (base.isEmpty())
        {
            return "";
        }
        return base + "/member-profile.php?id=" + urlEncode(profile.getMemberId().trim());
    }

    // Bingo board data

    private void rescheduleBingoBoardRefresh()
    {
        if (bingoBoardTask != null)
        {
            bingoBoardTask.cancel(false);
            bingoBoardTask = null;
        }

        if (womExecutor == null || !bingoBoardOverlayEnabled)
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

    private void refreshBingoBoardState(String endpoint, String token, String playerRsn, boolean manual)
    {
        try
        {
            bingoBoardState = fetchBingoBoardState(endpoint, token, playerRsn);
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
        if (!config.enableBingoDrops())
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        final String endpoint = resolveBingoDropEndpoint();
        final String token = config.bingoDropToken().trim();
        final Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || items == null || items.isEmpty())
        {
            debugLog("Skipping loot submission because player/items were incomplete.");
            return;
        }
        if (!isConfiguredForSubmission(endpoint, token))
        {
            warnConfigIncomplete("ParentsGuild: set the website URL and bingo drop token in plugin config.");
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

            drops.add(new PendingDrop(eventId, canonicalItemId, item.getQuantity(), itemName, capturedAtUtc));
        }

        if (drops.isEmpty())
        {
            debugLog("No eligible item stacks found for loot event from {}", cleanedSourceName);
            return;
        }

        if (womExecutor == null)
        {
            debugLog("Skipping bingo drop screenshots because the plugin executor is unavailable.");
            return;
        }

        womExecutor.execute(() -> submitEligibleDropScreenshots(endpoint, token, playerRsn, cleanedSourceName, drops));
    }

    private void submitEligibleDropScreenshots(String dropEndpoint, String token, String playerRsn, String sourceName, List<PendingDrop> drops)
    {
        final List<PendingDrop> eligibleDrops = filterDropsForIncompleteBingoTiles(token, playerRsn, drops);
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
                    submitDrop(dropEndpoint, token, playerRsn, sourceName, drop, screenshotBytes);
                }
            }
            catch (IOException ex)
            {
                log.warn("Failed to encode bingo drop screenshot", ex);
            }
        });
    }

    private List<PendingDrop> filterDropsForIncompleteBingoTiles(String token, String playerRsn, List<PendingDrop> drops)
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
            final Set<Integer> eligibleItemIds = incompleteDropTileItemIds(boardEndpoint, token, playerRsn);
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

    private Set<Integer> incompleteDropTileItemIds(String boardEndpoint, String token, String playerRsn) throws IOException
    {
        final long now = System.currentTimeMillis();
        final Set<Integer> cachedItemIds = dropTileEligibilityCache;
        if ((now - dropTileEligibilityCacheAtMillis) < DROP_TILE_ELIGIBILITY_CACHE_MILLIS)
        {
            return cachedItemIds;
        }

        final Set<Integer> itemIds = fetchIncompleteDropTileItemIds(boardEndpoint, token, playerRsn);
        dropTileEligibilityCache = itemIds;
        dropTileEligibilityCacheAtMillis = now;
        return itemIds;
    }

    private Set<Integer> fetchIncompleteDropTileItemIds(String endpoint, String token, String playerRsn) throws IOException
    {
        final String url = endpoint + "?playerRsn=" + URLEncoder.encode(playerRsn, StandardCharsets.UTF_8.toString());
        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-BINGO-DROP-TOKEN", token)
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

    private static int firstMultiItemTileItemId(JsonObject tile)
    {
        for (JsonElement itemElement : jsonArray(tile, "multiItems"))
        {
            if (!itemElement.isJsonObject())
            {
                continue;
            }

            final int itemId = jsonInt(itemElement.getAsJsonObject(), "itemId");
            if (itemId > 0)
            {
                return itemId;
            }
        }
        return 0;
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

    private void submitDrop(String endpoint, String token, String playerRsn, String sourceName, PendingDrop drop, byte[] screenshotBytes)
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
            .header("X-BINGO-DROP-TOKEN", token)
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
        final String token = config.bingoDropToken().trim();
        if (!isConfiguredForSubmission(endpoint, token))
        {
            warnConfigIncomplete("ParentsGuild: set the website URL and bingo drop token in plugin config.");
            return;
        }

        final Player localPlayer = client.getLocalPlayer();
        final String playerRsn = cleanText(localPlayer != null ? localPlayer.getName() : "");
        if (playerRsn.isEmpty())
        {
            notifier.notify("ParentsGuild: could not identify the logged-in account for proof.");
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
                submitTileProof(endpoint, token, playerRsn, cell, eventId, capturedAtUtc, screenshotBytes);
            }
            catch (IOException ex)
            {
                log.warn("Failed to encode bingo proof screenshot", ex);
            }
        });
    }

    private void submitTileProof(String endpoint, String token, String playerRsn, BingoBoardCell cell, String eventId, String capturedAtUtc, byte[] screenshotBytes)
    {
        final MultipartBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("source", "runelite_plugin")
            .addFormDataPart("eventId", eventId)
            .addFormDataPart("playerRsn", playerRsn)
            .addFormDataPart("tileId", cell.getTileId())
            .addFormDataPart("capturedAtUtc", capturedAtUtc)
            .addFormDataPart("proof", "parentsguild-bingo-proof.png", RequestBody.create(PNG, screenshotBytes))
            .build();

        final Request request = new Request.Builder()
            .url(endpoint)
            .header("X-BINGO-DROP-TOKEN", token)
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

    private static boolean isConfiguredForSubmission(String endpoint, String token)
    {
        return !endpoint.isEmpty() && !token.isEmpty();
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

    private String resolveBingoProofEndpoint()
    {
        final String base = resolveEndpointBase(config.websiteBaseUrl());
        return base.isEmpty() ? "" : base + "/api/integrations/bingo-proof.php";
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
            ? "Last updated " + PANEL_TIME_FORMAT.format(Instant.now())
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
            jsonString(profile, "womProfileUrl"),
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
            activity,
            jsonString(bingo, "boardUrl")
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
        final String detail = firstNonBlank(
            jsonBoolean(womEvents, "stale") && !lastRefreshedAt.isEmpty() ? "Cached WOM data is stale. Last refreshed " + lastRefreshedAt : "",
            !lastRefreshedAt.isEmpty() ? "WOM cache refreshed " + lastRefreshedAt : ""
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
            jsonString(announcement, "url"),
            jsonString(announcement, "updatedAtUtc")
        );
    }

    private QuickLinksState parseQuickLinksState(JsonObject links)
    {
        return new QuickLinksState(
            jsonString(links, "website"),
            jsonString(links, "discord"),
            jsonString(links, "womGroup"),
            jsonString(links, "bingoBoard")
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
                jsonString(object, "url"),
                jsonString(object, "description"),
                jsonString(object, "location"),
                jsonString(object, "status"),
                Math.max(0, jsonInt(object, "interestedCount"))
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

    private BingoOverlayState fetchBingoOverlayState(String endpoint, String token, String playerRsn) throws IOException
    {
        final String url = endpoint + "?playerRsn=" + URLEncoder.encode(playerRsn, StandardCharsets.UTF_8.toString());
        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-BINGO-DROP-TOKEN", token)
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

    private BingoBoardState fetchBingoBoardState(String endpoint, String token, String playerRsn) throws IOException
    {
        final String url = endpoint + "?playerRsn=" + URLEncoder.encode(playerRsn, StandardCharsets.UTF_8.toString());
        final Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-BINGO-DROP-TOKEN", token)
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
                        buildBoardProgressText(tile),
                        jsonBoolean(tile, "isCompleted"),
                        jsonBoolean(tile, "pendingClaim"),
                        jsonString(tile, "tileType"),
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

    private static String buildBoardProgressText(JsonObject tile)
    {
        final int approvedCompletions = Math.max(0, jsonInt(tile, "approvedCompletions"));
        final int pendingCompletions = Math.max(0, jsonInt(tile, "pendingCompletions"));
        final int requiredCompletions = Math.max(1, jsonInt(tile, "requiredCompletions"));
        final String tileType = normalizeName(jsonString(tile, "tileType"));
        if (requiredCompletions > 1 && ("manual".equals(tileType) || "drop".equals(tileType) || "multi_item".equals(tileType)))
        {
            return Math.min(approvedCompletions, requiredCompletions) + "(+" + pendingCompletions + ")/" + requiredCompletions;
        }

        final long targetValue = Math.max(0L, jsonLong(tile, "targetValue"));
        if ("metric".equals(tileType) && targetValue > 0L)
        {
            final long progressValue = Math.max(0L, jsonLong(tile, "progressValue"));
            final String suffix = metricProgressSuffix(jsonString(tile, "metricLabel"));
            return compactMetricValue(Math.min(progressValue, targetValue)) + "/" + compactMetricValue(targetValue) + suffix;
        }

        if (approvedCompletions > 0)
        {
            return approvedCompletions + "/" + requiredCompletions;
        }

        return "";
    }

    private String resolveBoardTileImageUrl(String endpoint, JsonObject tile)
    {
        final String imageUrl = firstNonBlank(
            jsonString(tile, "backgroundImageUrl"),
            jsonString(tile, "tileIconUrl")
        );
        if (imageUrl.isEmpty())
        {
            return "";
        }
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))
        {
            return imageUrl;
        }

        try
        {
            return URI.create(endpoint).resolve(imageUrl).toString();
        }
        catch (IllegalArgumentException ex)
        {
            return "";
        }
    }

    private BufferedImage boardTileImage(String endpoint, JsonObject tile)
    {
        int itemId = jsonInt(tile, "dropItemId");
        if (itemId <= 0 && "multi_item".equals(normalizeName(jsonString(tile, "tileType"))))
        {
            itemId = firstMultiItemTileItemId(tile);
        }
        if (itemId > 0)
        {
            try
            {
                return itemManager.getImage(itemId);
            }
            catch (RuntimeException ex)
            {
                debugLog("Falling back to website tile image for item {} because RuneLite item image failed: {}", itemId, ex.getMessage());
            }
        }
        return loadRemoteImage(resolveBoardTileImageUrl(endpoint, tile));
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
        String womProfileUrl;
        int botwWins;
        int sotwWins;

        static ClanProfileState unavailable(String message)
        {
            return new ClanProfileState(false, message, "", "", "", "", "", 0, 0, "", 0, 0, 0D, "", 0, "", 0, 0);
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
        String boardUrl;

        static BingoPanelState empty()
        {
            return new BingoPanelState(false, false, "", "", new ArrayList<>(), 0, 0, 0, new ArrayList<>(), "");
        }
    }

    @Value
    static class AnnouncementState
    {
        String message;
        String url;
        String updatedAtUtc;

        static AnnouncementState empty()
        {
            return new AnnouncementState("", "", "");
        }
    }

    @Value
    static class QuickLinksState
    {
        String website;
        String discord;
        String womGroup;
        String bingoBoard;

        static QuickLinksState empty()
        {
            return new QuickLinksState("", "", "", "");
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
        String url;
        String description;
        String location;
        String status;
        int interestedCount;
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
    static class BingoBoardCell
    {
        String tileId;
        String label;
        String progressText;
        boolean completed;
        boolean pending;
        String tileType;
        BufferedImage backgroundImage;
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
