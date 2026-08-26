package com.parentsguild.parentsguild;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import net.runelite.client.ui.PluginPanel;

class ParentsGuildPanel extends PluginPanel
{
    private static final int EVENTS_TAB_INDEX = 1;
    private static final int BINGO_TAB_INDEX = 2;
    private static final Color CARD_BACKGROUND = new Color(38, 38, 38);
    private static final Color CARD_BACKGROUND_DEEP = new Color(30, 30, 30);
    private static final Color CARD_BORDER = new Color(77, 66, 38);
    private static final Color HIGHLIGHT = new Color(222, 184, 73);
    private static final Color MUTED = new Color(178, 173, 157);
    private static final Color GREEN_GLOW = new Color(72, 204, 94);
    private static final ZoneId LOCAL_ZONE = ZoneId.systemDefault();
    private final ParentsGuildPlugin plugin;
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton boardButton = new JButton("Open Board");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel profilePanel = new FullWidthPanel();
    private final JPanel bingoPanel = new FullWidthPanel();
    private final JPanel womEventsPanel = new FullWidthPanel();
    private String currentAnnouncementMarker = "";

    ParentsGuildPanel(ParentsGuildPlugin plugin)
    {
        super(false);
        this.plugin = plugin;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        final JLabel titleLabel = new JLabel("ParentsGuild");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16F));
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        refreshButton.setText("\u21BB");
        refreshButton.setToolTipText("Refresh");
        refreshButton.setPreferredSize(new Dimension(34, 26));
        refreshButton.setMaximumSize(new Dimension(34, 26));
        refreshButton.addActionListener(event -> plugin.requestWomRefresh(true));
        boardButton.addActionListener(event -> {
            plugin.toggleBingoBoardOverlay();
            updateBoardButtonLabel();
        });

        header.add(titleLabel, BorderLayout.WEST);
        header.add(refreshButton, BorderLayout.EAST);

        profilePanel.setLayout(new BoxLayout(profilePanel, BoxLayout.Y_AXIS));
        bingoPanel.setLayout(new BoxLayout(bingoPanel, BoxLayout.Y_AXIS));
        womEventsPanel.setLayout(new BoxLayout(womEventsPanel, BoxLayout.Y_AXIS));

        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab("Profile", buildScrollPane(profilePanel));
        tabs.addTab("Events", buildScrollPane(womEventsPanel));
        tabs.addTab("Bingo", buildScrollPane(bingoPanel));

        add(header, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
    }

    // Panel refresh

    void updateState(ParentsGuildPlugin.WomPanelState state)
    {
        refreshButton.setEnabled(!state.isLoading());
        updateBoardButtonLabel();

        profilePanel.removeAll();
        profilePanel.add(buildClanProfileCard(state.getClanProfile()));
        profilePanel.add(Box.createVerticalStrut(8));
        currentAnnouncementMarker = announcementMarker(state.getAnnouncement());
        final boolean announcementUnseen = !currentAnnouncementMarker.isEmpty()
            && !currentAnnouncementMarker.equals(plugin.lastSeenAnnouncementMarker());
        profilePanel.add(buildAnnouncementCard(state.getAnnouncement(), announcementUnseen));
        profilePanel.add(Box.createVerticalStrut(8));
        profilePanel.add(buildQuickLinksCard(state.getQuickLinks(), state.getClanProfile()));

        bingoPanel.removeAll();
        bingoPanel.add(buildBingoCard(state.getBingo()));
        final boolean bingoActive = state.getBingo() != null && state.getBingo().isActive();
        tabs.setEnabledAt(BINGO_TAB_INDEX, bingoActive);
        tabs.setForegroundAt(BINGO_TAB_INDEX, bingoActive ? HIGHLIGHT : new Color(105, 105, 105));
        if (!bingoActive && tabs.getSelectedIndex() == BINGO_TAB_INDEX)
        {
            tabs.setSelectedIndex(0);
        }

        womEventsPanel.removeAll();
        final List<ParentsGuildPlugin.CompetitionView> competitions = state.getCompetitions();
        final List<ParentsGuildPlugin.UpcomingEventState> upcomingEvents = state.getUpcomingEvents();
        final boolean hasEventData = (upcomingEvents != null && !upcomingEvents.isEmpty()) || !competitions.isEmpty();
        tabs.setTitleAt(EVENTS_TAB_INDEX, "Events");
        if (!hasEventData)
        {
            womEventsPanel.add(buildEmptyState());
        }
        else
        {
            final Set<Integer> matchedCompetitionIds = new HashSet<>();
            if (upcomingEvents != null)
            {
                for (ParentsGuildPlugin.UpcomingEventState event : upcomingEvents)
                {
                    final ParentsGuildPlugin.CompetitionView match = findMatchingCompetition(event, competitions);
                    if (match != null)
                    {
                        matchedCompetitionIds.add(match.getId());
                    }
                    womEventsPanel.add(buildEventCard(event, match));
                    womEventsPanel.add(Box.createVerticalStrut(8));
                }
            }
            for (ParentsGuildPlugin.CompetitionView competition : competitions)
            {
                if (!matchedCompetitionIds.contains(competition.getId()))
                {
                    womEventsPanel.add(buildCompetitionCard(competition));
                    womEventsPanel.add(Box.createVerticalStrut(8));
                }
            }
        }

        profilePanel.revalidate();
        profilePanel.repaint();
        bingoPanel.revalidate();
        bingoPanel.repaint();
        womEventsPanel.revalidate();
        womEventsPanel.repaint();
    }

    private void updateBoardButtonLabel()
    {
        boardButton.setText(plugin.isBingoBoardOverlayEnabled() ? "Close Board" : "Open Board");
    }

    private static JScrollPane buildScrollPane(JPanel panel)
    {
        final JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scrollPane;
    }

    // Bingo tab

    private JPanel buildBingoCard(ParentsGuildPlugin.BingoPanelState bingo)
    {
        final JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CARD_BORDER),
            BorderFactory.createEmptyBorder(10, 14, 10, 10)
        ));
        card.setBackground(CARD_BACKGROUND);
        card.setAlignmentX(LEFT_ALIGNMENT);

        final JLabel titleLabel = new JLabel("Clan Bingo");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14F));
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        boardButton.setAlignmentX(LEFT_ALIGNMENT);

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(8));
        if (bingo == null || !bingo.isActive())
        {
            card.add(profileLine("Status", "No active bingo"));
        }
        else if (!bingo.isMatched())
        {
            card.add(profileLine("Status", "Active, but this RSN is not on a team"));
            card.add(profileLine("Bingo", bingo.getBingoName()));
        }
        else
        {
            card.add(profileLine("Bingo", bingo.getBingoName()));
            card.add(profileLine("Team", bingo.getTeamName()));
            card.add(profileLine("Completed", ParentsGuildPlugin.formatMetricValue(bingo.getCompletedTiles())));
            card.add(profileLine("Pending", ParentsGuildPlugin.formatMetricValue(bingo.getPendingTiles())));
            card.add(profileLine("Remaining", ParentsGuildPlugin.formatMetricValue(bingo.getRemainingTiles())));
            if (!bingo.getTeamMembers().isEmpty())
            {
                card.add(profileLine("Members", String.join(", ", bingo.getTeamMembers())));
            }
            if (!bingo.getRecentActivity().isEmpty())
            {
                card.add(Box.createVerticalStrut(8));
                card.add(sectionLabel("Recent Activity"));
                for (ParentsGuildPlugin.BingoActivityState activity : bingo.getRecentActivity())
                {
                    card.add(profileLine("-", activity.getLabel()));
                }
            }
        }
        card.add(Box.createVerticalStrut(8));
        final JPanel preview = buildBingoPreview(plugin.getBingoBoardState());
        if (preview != null)
        {
            card.add(preview);
            card.add(Box.createVerticalStrut(8));
        }
        card.add(boardButton);
        if (bingo != null && bingo.isActive())
        {
            card.add(Box.createVerticalStrut(6));
            card.add(linkButton("Open Website Board", plugin::openWebsiteBingoBoard));
        }
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private JPanel buildAnnouncementCard(ParentsGuildPlugin.AnnouncementState announcement, boolean unseen)
    {
        final JButton markSeenButton = unseen
            ? iconButton("\uD83D\uDC41", "mark seen", () -> plugin.markAnnouncementSeen(currentAnnouncementMarker))
            : null;
        final JPanel card = baseCard("Announcement", unseen ? GREEN_GLOW : new Color(84, 135, 74), unseen, markSeenButton);
        if (announcement == null || announcement.getMessage() == null || announcement.getMessage().trim().isEmpty())
        {
            card.add(profileLine("Status", "No announcement"));
        }
        else
        {
            card.add(wrapLabel(announcement.getMessage()));
            if (announcement.getUpdatedAtUtc() != null && !announcement.getUpdatedAtUtc().trim().isEmpty())
            {
                card.add(profileLine("Updated", formatDateTime(announcement.getUpdatedAtUtc())));
            }
        }
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private JPanel buildQuickLinksCard(ParentsGuildPlugin.QuickLinksState links, ParentsGuildPlugin.ClanProfileState profile)
    {
        final JPanel card = baseCard("Quick Links", new Color(80, 123, 171), false);
        if (links == null)
        {
            card.add(profileLine("Status", "No links available"));
        }
        else
        {
            final JPanel grid = new JPanel(new GridLayout(0, 2, 6, 6));
            grid.setOpaque(false);
            grid.setAlignmentX(LEFT_ALIGNMENT);
            addLink(grid, "Website", plugin::openWebsiteHome);
            if (!ParentsGuildPlugin.discordInviteUrl(links.getDiscordInviteCode()).isEmpty())
            {
                addLink(grid, "Discord", () -> plugin.openDiscordInvite(links.getDiscordInviteCode()));
            }
            if (links.getWomGroupId() > 0)
            {
                addLink(grid, "WOM Group", () -> plugin.openWomGroup(links.getWomGroupId()));
            }
            if (profile != null && profile.isAvailable())
            {
                addLink(grid, "My Profile", () -> plugin.openWebsiteProfile(profile));
            }
            if (grid.getComponentCount() == 0)
            {
                card.add(profileLine("Status", "No links available"));
            }
            else
            {
                card.add(grid);
            }
        }
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private JPanel buildBingoPreview(ParentsGuildPlugin.BingoBoardState board)
    {
        if (board == null || !board.isVisible() || board.getGrid().isEmpty())
        {
            return null;
        }

        final int rows = Math.max(1, board.getRowsCount());
        final int cols = Math.max(1, board.getColsCount());
        final JPanel preview = new JPanel(new GridLayout(rows, cols, 3, 3));
        preview.setOpaque(false);
        preview.setAlignmentX(LEFT_ALIGNMENT);
        preview.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.min(150, rows * 22)));
        preview.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(70, 70, 70)),
            BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ));

        for (int rowIndex = 0; rowIndex < rows; rowIndex++)
        {
            final List<ParentsGuildPlugin.BingoBoardCell> row = rowIndex < board.getGrid().size()
                ? board.getGrid().get(rowIndex)
                : java.util.Collections.emptyList();
            for (int colIndex = 0; colIndex < cols; colIndex++)
            {
                final ParentsGuildPlugin.BingoBoardCell cell = colIndex < row.size() ? row.get(colIndex) : null;
                preview.add(bingoPreviewSquare(cell));
            }
        }
        return preview;
    }

    private JPanel bingoPreviewSquare(ParentsGuildPlugin.BingoBoardCell cell)
    {
        final JPanel square = new JPanel();
        square.setPreferredSize(new Dimension(18, 18));
        square.setMinimumSize(new Dimension(12, 12));
        square.setOpaque(true);
        if (cell == null || "blank".equalsIgnoreCase(cell.getTileType()))
        {
            square.setBackground(new Color(32, 32, 32));
            square.setBorder(BorderFactory.createLineBorder(new Color(58, 58, 58)));
        }
        else if (cell.isCompleted())
        {
            square.setBackground(new Color(56, 142, 72));
            square.setBorder(BorderFactory.createLineBorder(new Color(116, 222, 128)));
        }
        else if (cell.isPending())
        {
            square.setBackground(new Color(184, 137, 42));
            square.setBorder(BorderFactory.createLineBorder(new Color(245, 205, 94)));
        }
        else
        {
            square.setBackground(CARD_BACKGROUND_DEEP);
            square.setBorder(BorderFactory.createLineBorder(CARD_BORDER));
        }
        return square;
    }

    // Events tab

    private JPanel buildCompactEventTiming(ParentsGuildPlugin.UpcomingEventState event)
    {
        final Instant startsAt = parseEventInstant(event.getStartsAtUtc());
        if (startsAt == null)
        {
            return null;
        }
        Instant endsAt = parseEventInstant(event.getEndsAtUtc());
        if (endsAt == null || endsAt.isBefore(startsAt))
        {
            endsAt = startsAt.plus(2, ChronoUnit.HOURS);
        }
        final String status = eventStatusLabel(event);
        final CompactEventTimePanel panel = new CompactEventTimePanel(
            startsAt,
            endsAt,
            eventAccent(status),
            "Ongoing".equals(status),
            plugin.useDayFirstDates(),
            plugin.useTwentyFourHourTime()
        );
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        return panel;
    }

    private JPanel buildEventCard(ParentsGuildPlugin.UpcomingEventState event, ParentsGuildPlugin.CompetitionView matchingCompetition)
    {
        final String status = eventStatusLabel(event);
        final JPanel card = baseCard(status, eventAccent(status), "Ongoing".equals(status));
        final String descriptionTypeLine = eventDescriptionTypeLine(event.getDescription());
        card.add(wrapHighlightLabel(event.getTitle()));
        if (!descriptionTypeLine.isEmpty())
        {
            card.add(mutedLine(descriptionTypeLine));
        }
        else if (event.getType() != null && !event.getType().trim().isEmpty())
        {
            card.add(mutedLine(event.getType()));
        }
        final JPanel eventTiming = buildCompactEventTiming(event);
        if (eventTiming != null)
        {
            card.add(eventTiming);
            card.add(Box.createVerticalStrut(4));
        }
        if (event.getLocation() != null && !event.getLocation().trim().isEmpty())
        {
            card.add(wrapProfileLine("Location", event.getLocation()));
        }
        if (event.getInterestedCount() > 0)
        {
            card.add(profileLine("Interested", ParentsGuildPlugin.formatMetricValue(event.getInterestedCount())));
        }
        if (matchingCompetition != null)
        {
            card.add(Box.createVerticalStrut(6));
            addCompetitionLeaderboard(card, matchingCompetition);
        }
        if (matchingCompetition != null)
        {
            card.add(Box.createVerticalStrut(6));
            card.add(linkButton("Open WOM Event", () -> plugin.openWomCompetition(matchingCompetition.getId())));
        }
        else if (event.getWomCompetitionId() > 0)
        {
            card.add(Box.createVerticalStrut(6));
            card.add(linkButton("Open WOM Event", () -> plugin.openWomCompetition(event.getWomCompetitionId())));
        }
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    // Shared UI helpers

    private JPanel baseCard(String title)
    {
        return baseCard(title, CARD_BORDER, false);
    }

    private JPanel baseCard(String title, Color accent, boolean glow)
    {
        return baseCard(title, accent, glow, null);
    }

    private JPanel baseCard(String title, Color accent, boolean glow, JButton actionButton)
    {
        final JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(cardBorder(accent, glow));
        card.setBackground(CARD_BACKGROUND);
        card.setAlignmentX(LEFT_ALIGNMENT);

        final JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setAlignmentX(LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        final JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14F));
        titleLabel.setForeground(accent);
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        header.add(titleLabel, BorderLayout.WEST);
        if (actionButton != null)
        {
            header.add(actionButton, BorderLayout.EAST);
        }
        card.add(header);
        card.add(Box.createVerticalStrut(6));
        return card;
    }

    private static javax.swing.border.Border cardBorder(Color accent, boolean glow)
    {
        final javax.swing.border.Border inner = BorderFactory.createEmptyBorder(10, 14, 10, 10);
        if (!glow)
        {
            return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent), inner);
        }
        return BorderFactory.createCompoundBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GREEN_GLOW, 2),
                BorderFactory.createLineBorder(new Color(36, 92, 45), 1)
            ),
            inner
        );
    }

    private JLabel sectionLabel(String text)
    {
        final JLabel label = new JLabel(text);
        label.setForeground(HIGHLIGHT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13F));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JLabel wrapHighlightLabel(String text)
    {
        final JLabel label = new JLabel("<html><body style='width: 175px'><strong>" + escapeHtml(text) + "</strong></body></html>");
        label.setForeground(HIGHLIGHT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13F));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JLabel mutedLine(String text)
    {
        final JLabel label = new JLabel(text == null || text.trim().isEmpty() ? "-" : text);
        label.setForeground(MUTED);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JLabel wrapLabel(String text)
    {
        final JLabel label = new JLabel("<html><body style='width: 175px'>" + escapeHtml(text).replace("\n", "<br>") + "</body></html>");
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JLabel wrapProfileLine(String label, String value)
    {
        final JLabel line = new JLabel("<html><body style='width: 175px'>" + escapeHtml(label) + ": " + escapeHtml(value == null || value.trim().isEmpty() ? "-" : value) + "</body></html>");
        line.setAlignmentX(LEFT_ALIGNMENT);
        return line;
    }

    private JButton linkButton(String label, Runnable action)
    {
        final JButton button = new JButton(label);
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.addActionListener(event -> {
            if (action != null)
            {
                action.run();
            }
        });
        return button;
    }

    private JButton iconButton(String label, String tooltip, Runnable action)
    {
        final JButton button = new JButton(label);
        button.setToolTipText(tooltip);
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setPreferredSize(new Dimension(34, 26));
        button.setMaximumSize(new Dimension(34, 26));
        button.addActionListener(event -> {
            if (action != null)
            {
                action.run();
            }
        });
        return button;
    }

    private void addLink(JPanel container, String label, Runnable action)
    {
        final JButton button = linkButton(label, action);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        container.add(button);
    }

    // Event text and matching helpers

    private static String escapeHtml(String text)
    {
        return (text == null ? "" : text)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String eventDescriptionTypeLine(String description)
    {
        for (String line : normalizedEventDescription(description).split("\n"))
        {
            final String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("type:"))
            {
                return trimmed;
            }
        }
        return "";
    }

    private static String normalizedEventDescription(String description)
    {
        return (description == null ? "" : description)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("(?i)\\s+(Type\\s*:)", "\n$1")
            .replaceAll("(?i)\\s+(Wise\\s+Old\\s+Man\\s*:)", "\n$1");
    }

    private JPanel buildEmptyState()
    {
        final JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CARD_BORDER),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));
        panel.setBackground(CARD_BACKGROUND);

        final JLabel label = new JLabel("No active competitions to display.", SwingConstants.LEFT);
        label.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(label, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        return panel;
    }

    // Profile tab

    private JPanel buildClanProfileCard(ParentsGuildPlugin.ClanProfileState profile)
    {
        final JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CARD_BORDER),
            BorderFactory.createEmptyBorder(10, 14, 10, 10)
        ));
        card.setBackground(CARD_BACKGROUND);
        card.setAlignmentX(LEFT_ALIGNMENT);

        final JLabel titleLabel = new JLabel("Clan Profile");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14F));
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        card.add(titleLabel);
        card.add(Box.createVerticalStrut(6));

        if (profile == null || !profile.isAvailable())
        {
            final JLabel unavailableLabel = new JLabel(profile == null ? "Clan profile unavailable." : profile.getMessage());
            unavailableLabel.setAlignmentX(LEFT_ALIGNMENT);
            card.add(unavailableLabel);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            return card;
        }

        card.add(profileLine("Current rank", profile.getDisplayRank()));
        card.add(profileLine("Points", ParentsGuildPlugin.formatMetricValue(profile.getCombinedPoints())));
        card.add(profileLine("Points rank", profile.getComputedRank()));
        card.add(profileLine("Next rank", profile.getNextRank()));
        card.add(profileLine("Points needed", ParentsGuildPlugin.formatMetricValue(profile.getPointsUntilNextRank())));

        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private static JLabel profileLine(String label, String value)
    {
        final JLabel line = new JLabel(label + ": " + (value == null || value.trim().isEmpty() ? "-" : value));
        line.setAlignmentX(LEFT_ALIGNMENT);
        return line;
    }

    // WOM leaderboard cards

    private JPanel buildCompetitionCard(ParentsGuildPlugin.CompetitionView competition)
    {
        final JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CARD_BORDER),
            BorderFactory.createEmptyBorder(10, 14, 10, 10)
        ));
        card.setBackground(CARD_BACKGROUND);
        card.setAlignmentX(LEFT_ALIGNMENT);

        final JLabel titleLabel = new JLabel(competition.getTitle());
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14F));
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);

        final JLabel metricLabel = new JLabel(metricText(competition));
        metricLabel.setAlignmentX(LEFT_ALIGNMENT);

        final JTextArea leaderboard = new JTextArea(buildLeaderboardText(competition.getLeaderboardEntries()));
        leaderboard.setEditable(false);
        leaderboard.setFocusable(false);
        leaderboard.setOpaque(false);
        leaderboard.setLineWrap(false);
        leaderboard.setWrapStyleWord(false);
        leaderboard.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        leaderboard.setAlignmentX(LEFT_ALIGNMENT);
        leaderboard.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(4));
        card.add(metricLabel);
        card.add(Box.createVerticalStrut(10));
        card.add(sectionLabel("Leaderboard"));
        card.add(Box.createVerticalStrut(4));
        card.add(leaderboard);

        if (competition.getLocalPlayerEntry() != null)
        {
            final JLabel youLabel = new JLabel("Event rank: #" + competition.getLocalPlayerEntry().getRank());
            youLabel.setForeground(HIGHLIGHT);
            youLabel.setAlignmentX(LEFT_ALIGNMENT);
            card.add(Box.createVerticalStrut(8));
            card.add(youLabel);
            card.add(profileLine("Gained", ParentsGuildPlugin.formatMetricValue(competition.getLocalPlayerEntry().getGained())));
            card.add(profileLine("Gap to next", ParentsGuildPlugin.formatMetricValue(competition.getGapToNext())));
        }
        card.add(Box.createVerticalStrut(6));
        card.add(linkButton("Open WOM Event", () -> plugin.openWomCompetition(competition.getId())));

        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private void addCompetitionLeaderboard(JPanel card, ParentsGuildPlugin.CompetitionView competition)
    {
        final JLabel metricLabel = new JLabel(metricText(competition));
        metricLabel.setAlignmentX(LEFT_ALIGNMENT);

        final JTextArea leaderboard = new JTextArea(buildLeaderboardText(competition.getLeaderboardEntries()));
        leaderboard.setEditable(false);
        leaderboard.setFocusable(false);
        leaderboard.setOpaque(false);
        leaderboard.setLineWrap(false);
        leaderboard.setWrapStyleWord(false);
        leaderboard.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        leaderboard.setAlignmentX(LEFT_ALIGNMENT);
        leaderboard.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        card.add(Box.createVerticalStrut(10));
        card.add(sectionLabel("WOM Leaderboard"));
        card.add(metricLabel);
        card.add(Box.createVerticalStrut(6));
        card.add(leaderboard);

        if (competition.getLocalPlayerEntry() != null)
        {
            final JLabel youLabel = new JLabel("Event rank: #" + competition.getLocalPlayerEntry().getRank());
            youLabel.setForeground(HIGHLIGHT);
            youLabel.setAlignmentX(LEFT_ALIGNMENT);
            card.add(Box.createVerticalStrut(8));
            card.add(youLabel);
            card.add(profileLine("Gained", ParentsGuildPlugin.formatMetricValue(competition.getLocalPlayerEntry().getGained())));
            card.add(profileLine("Gap to next", ParentsGuildPlugin.formatMetricValue(competition.getGapToNext())));
        }
    }

    private static ParentsGuildPlugin.CompetitionView findMatchingCompetition(
        ParentsGuildPlugin.UpcomingEventState event,
        List<ParentsGuildPlugin.CompetitionView> competitions
    )
    {
        final String eventTitle = normalizeMatchText(event.getTitle());
        final String eventType = normalizeMatchText(event.getType() + " " + event.getDescription());
        for (ParentsGuildPlugin.CompetitionView competition : competitions)
        {
            final String competitionTitle = normalizeMatchText(competition.getTitle());
            if (!eventTitle.isEmpty() && !competitionTitle.isEmpty()
                && (eventTitle.contains(competitionTitle) || competitionTitle.contains(eventTitle)))
            {
                return competition;
            }
            if (eventOverlapsCompetition(event, competition)
                && ((eventTitle + eventType).contains("sotw") && competitionTitle.contains("sotw")
                    || (eventTitle + eventType).contains("botw") && competitionTitle.contains("botw")))
            {
                return competition;
            }
        }
        return null;
    }

    private static boolean eventOverlapsCompetition(ParentsGuildPlugin.UpcomingEventState event, ParentsGuildPlugin.CompetitionView competition)
    {
        final Instant eventStart = parseEventInstant(event.getStartsAtUtc());
        final Instant eventEnd = parseEventInstant(event.getEndsAtUtc());
        if (eventStart == null || eventEnd == null)
        {
            return false;
        }
        return !eventStart.isAfter(competition.getEndsAt()) && !eventEnd.isBefore(competition.getStartsAt());
    }

    private static String eventStatusLabel(ParentsGuildPlugin.UpcomingEventState event)
    {
        final Instant now = Instant.now();
        final Instant startsAt = parseEventInstant(event.getStartsAtUtc());
        final Instant endsAt = parseEventInstant(event.getEndsAtUtc());
        if (startsAt != null && !now.isBefore(startsAt) && (endsAt == null || now.isBefore(endsAt)))
        {
            return "Ongoing";
        }
        if (startsAt != null && now.isBefore(startsAt) && isWeeklyEvent(event))
        {
            return "Weekly event";
        }
        return "Upcoming";
    }

    private static boolean isWeeklyEvent(ParentsGuildPlugin.UpcomingEventState event)
    {
        return event.isRecurring() && event.getRecurrenceFrequency() == 2;
    }

    private static Instant parseEventInstant(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        try
        {
            return Instant.parse(value.trim());
        }
        catch (RuntimeException ignored)
        {
            try
            {
                return OffsetDateTime.parse(value.trim()).toInstant();
            }
            catch (RuntimeException ignoredAgain)
            {
                return null;
            }
        }
    }

    private String formatDateTime(String value)
    {
        final Instant instant = parseEventInstant(value);
        return instant == null
            ? (value == null || value.trim().isEmpty() ? "-" : value)
            : ParentsGuildDateTimeFormatter.formatDateTime(instant, plugin.useDayFirstDates(), plugin.useTwentyFourHourTime());
    }

    private static Color eventAccent(String status)
    {
        if ("Ongoing".equals(status))
        {
            return new Color(84, 190, 103);
        }
        if ("Weekly event".equals(status))
        {
            return new Color(104, 145, 190);
        }
        return HIGHLIGHT;
    }

    private static String normalizeMatchText(String value)
    {
        return (value == null ? "" : value)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "");
    }

    private static String announcementMarker(ParentsGuildPlugin.AnnouncementState announcement)
    {
        if (announcement == null || announcement.getMessage() == null || announcement.getMessage().trim().isEmpty())
        {
            return "";
        }
        final String updatedAt = announcement.getUpdatedAtUtc() == null ? "" : announcement.getUpdatedAtUtc().trim();
        return updatedAt.isEmpty() ? announcement.getMessage().trim() : updatedAt;
    }

    private static String metricText(ParentsGuildPlugin.CompetitionView competition)
    {
        final String metric = competition.getMetric() == null || competition.getMetric().trim().isEmpty()
            ? "metric unavailable"
            : competition.getMetric();
        return metric + "  |  " + competition.getTimeRemainingText();
    }

    private static String buildLeaderboardText(List<ParentsGuildPlugin.LeaderboardEntry> entries)
    {
        final StringBuilder builder = new StringBuilder();
        for (ParentsGuildPlugin.LeaderboardEntry entry : entries)
        {
            final String label = entry.isLocalPlayer() ? entry.getDisplayName() + " *" : entry.getDisplayName();
            final String gained = "+" + ParentsGuildPlugin.formatMetricValue(entry.getGained());
            builder.append(padRight("#" + entry.getRank(), 4))
                .append(" ")
                .append(padRight(label, 13))
                .append(" ")
                .append(padLeft(gained, 6))
                .append('\n');
        }
        return builder.length() == 0 ? "No participant data available." : builder.toString().trim();
    }

    private static String padRight(String value, int width)
    {
        final String text = value == null ? "" : value;
        if (text.length() >= width)
        {
            return text.substring(0, width);
        }

        final StringBuilder builder = new StringBuilder(width);
        builder.append(text);
        while (builder.length() < width)
        {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static String padLeft(String value, int width)
    {
        final String text = value == null ? "" : value;
        if (text.length() >= width)
        {
            return text;
        }

        final StringBuilder builder = new StringBuilder(width);
        while (builder.length() + text.length() < width)
        {
            builder.append(' ');
        }
        builder.append(text);
        return builder.toString();
    }

    // Nested scroll and timeline components

    @Override
    public void scrollRectToVisible(Rectangle contentRect)
    {
        contentRect.x = 0;
        super.scrollRectToVisible(contentRect);
    }

    private static final class CompactEventTimePanel extends JPanel
    {
        private static final int VISIBLE_DAYS = 7;
        private final Instant startsAt;
        private final Instant endsAt;
        private final Color color;
        private final boolean ongoing;
        private final boolean dayFirstDates;
        private final boolean twentyFourHourTime;
        private final ZonedDateTime visibleStart;
        private final ZonedDateTime visibleEnd;

        CompactEventTimePanel(Instant startsAt, Instant endsAt, Color color, boolean ongoing, boolean dayFirstDates, boolean twentyFourHourTime)
        {
            this.startsAt = startsAt;
            this.endsAt = endsAt;
            this.color = color == null ? HIGHLIGHT : color;
            this.ongoing = ongoing;
            this.dayFirstDates = dayFirstDates;
            this.twentyFourHourTime = twentyFourHourTime;
            this.visibleStart = ZonedDateTime.now(LOCAL_ZONE).truncatedTo(ChronoUnit.MINUTES);
            this.visibleEnd = visibleStart.plusDays(VISIBLE_DAYS);
            setPreferredSize(new Dimension(185, 70));
            setMinimumSize(new Dimension(150, 70));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            super.paintComponent(graphics);
            final Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            final int width = getWidth();
            final int left = 0;
            final int right = Math.max(left + 1, width - 1);
            final int barY = 56;

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(MUTED);
            g.drawString("Start " + ParentsGuildDateTimeFormatter.formatDateTime(startsAt, dayFirstDates, twentyFourHourTime), left, 14);
            g.drawString("End " + ParentsGuildDateTimeFormatter.formatDateTime(endsAt, dayFirstDates, twentyFourHourTime), left, 29);

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            for (int day = 0; day <= VISIBLE_DAYS; day++)
            {
                final int x = left + Math.round((right - left) * (day / (float) VISIBLE_DAYS));
                g.setColor(new Color(65, 65, 65));
                g.drawLine(x, 41, x, 64);
                if (day < VISIBLE_DAYS && day % 2 == 0)
                {
                    g.setColor(MUTED);
                    final int labelX = Math.min(Math.max(left, x - 6), Math.max(left, right - 22));
                    g.drawString(ParentsGuildDateTimeFormatter.calendarDayFormatter(dayFirstDates).format(visibleStart.plusDays(day)), labelX, 47);
                }
            }

            g.setStroke(new BasicStroke(5F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(58, 58, 58));
            g.drawLine(left, barY, right, barY);
            g.setColor(color);
            final Instant now = Instant.now();
            final Instant displayStart = ongoing && now.isAfter(startsAt) ? now : startsAt;
            final int x1 = xForInstant(displayStart, left, right);
            final int x2 = Math.max(x1 + 4, xForInstant(endsAt, left, right));
            if (x2 >= left && x1 <= right)
            {
                g.drawLine(Math.max(left, x1), barY, Math.min(right, x2), barY);
            }
            if (ongoing)
            {
                final int nowX = xForInstant(now, left, right);
                g.setStroke(new BasicStroke(1F));
                g.setColor(new Color(236, 222, 174));
                g.drawLine(nowX, 50, nowX, 64);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
                g.drawString("Now", Math.min(Math.max(left, nowX - 9), Math.max(left, right - 18)), 53);
            }
            g.dispose();
        }

        private int xForInstant(Instant instant, int left, int right)
        {
            final long rangeMillis = Math.max(1L, ChronoUnit.MILLIS.between(visibleStart, visibleEnd));
            final long offsetMillis = Math.max(0L, Math.min(rangeMillis, ChronoUnit.MILLIS.between(visibleStart, ZonedDateTime.ofInstant(instant, LOCAL_ZONE))));
            return left + Math.round((right - left) * (offsetMillis / (float) rangeMillis));
        }
    }

    private static final class FullWidthPanel extends JPanel implements Scrollable
    {
        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }
}
