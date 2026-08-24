package com.parentsguild.parentsguild;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class ParentsGuildBingoOverlay extends Overlay
{
    private static final Color BINGO_NAME_COLOR = new Color(86, 214, 108);
    private static final Color TEAM_NAME_COLOR = new Color(232, 74, 74);
    private static final Color DETAILS_COLOR = Color.WHITE;
    private static final Color SHADOW_COLOR = new Color(0, 0, 0, 180);
    private static final DateTimeFormatter OVERLAY_TIME_FORMAT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault());

    private final ParentsGuildPlugin plugin;

    @Inject
    ParentsGuildBingoOverlay(ParentsGuildPlugin plugin)
    {
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        final ParentsGuildPlugin.BingoOverlayState state = plugin.getBingoOverlayState();
        if (state == null || !state.isVisible())
        {
            return null;
        }

        final Font originalFont = graphics.getFont();
        final Font overlayFont = originalFont.deriveFont(Font.BOLD, 16F);
        graphics.setFont(overlayFont);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        final String bingoNameText = state.getBingoName() + ":";
        final String teamNameText = " " + state.getTeamName();
        final String timeText = ":" + OVERLAY_TIME_FORMAT.format(Instant.now());
        final FontMetrics metrics = graphics.getFontMetrics();
        final int padding = 2;
        final int x = padding;
        final int y = padding + metrics.getAscent();
        final int nameWidth = metrics.stringWidth(bingoNameText);
        final int teamWidth = metrics.stringWidth(teamNameText);

        drawShadowedText(graphics, bingoNameText, x, y, BINGO_NAME_COLOR);
        drawShadowedText(graphics, teamNameText, x + nameWidth, y, TEAM_NAME_COLOR);
        drawShadowedText(graphics, timeText, x + nameWidth + teamWidth, y, DETAILS_COLOR);

        final int totalWidth = nameWidth + teamWidth + metrics.stringWidth(timeText) + (padding * 2);
        final int height = metrics.getHeight() + (padding * 2);
        graphics.setFont(originalFont);
        return new Dimension(totalWidth, height);
    }

    private static void drawShadowedText(Graphics2D graphics, String text, int x, int y, Color color)
    {
        graphics.setColor(SHADOW_COLOR);
        graphics.drawString(text, x + 1, y + 1);
        graphics.setColor(color);
        graphics.drawString(text, x, y);
    }
}
