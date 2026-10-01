package com.parentsguild.parentsguild;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.Instant;
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
    private final ParentsGuildPlugin plugin;

    @Inject
    ParentsGuildBingoOverlay(ParentsGuildPlugin plugin)
    {
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setResizable(true);
        setMinimumSize(80);
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
        final Font overlayFont = originalFont.deriveFont(Font.BOLD, plugin.bingoOverlayFontSize());
        graphics.setFont(overlayFont);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        final String bingoNameText = state.getBingoName() + ":";
        final String teamNameText = state.getTeamName() + ":";
        final String timeText = ParentsGuildDateTimeFormatter.formatDateTime(
            Instant.now(),
            plugin.useDayFirstDates(),
            plugin.useTwentyFourHourTime()
        );
        final FontMetrics metrics = graphics.getFontMetrics();
        final int padding = 2;
        final String[] textSegments = {bingoNameText, teamNameText, timeText};
        final Color[] segmentColors = {BINGO_NAME_COLOR, TEAM_NAME_COLOR, DETAILS_COLOR};
        final int separatorWidth = metrics.stringWidth(" ");
        final int naturalContentWidth = metrics.stringWidth(bingoNameText)
            + separatorWidth + metrics.stringWidth(teamNameText)
            + separatorWidth + metrics.stringWidth(timeText);
        final int configuredWidth = getBounds().width;
        final int contentWidth = configuredWidth > 0
            ? Math.max(1, configuredWidth - (padding * 2))
            : naturalContentWidth;

        int x = padding;
        int y = padding + metrics.getAscent();
        int lineWidth = 0;
        int widestLine = 0;
        int lineCount = 1;
        for (int index = 0; index < textSegments.length; index++)
        {
            final int segmentWidth = metrics.stringWidth(textSegments[index]);
            final int requiredWidth = segmentWidth + (lineWidth == 0 ? 0 : separatorWidth);
            if (lineWidth > 0 && lineWidth + requiredWidth > contentWidth)
            {
                widestLine = Math.max(widestLine, lineWidth);
                x = padding;
                y += metrics.getHeight();
                lineWidth = 0;
                lineCount++;
            }
            else if (lineWidth > 0)
            {
                x += separatorWidth;
                lineWidth += separatorWidth;
            }

            drawShadowedText(graphics, textSegments[index], x, y, segmentColors[index]);
            x += segmentWidth;
            lineWidth += segmentWidth;
        }

        widestLine = Math.max(widestLine, lineWidth);
        final int width = configuredWidth > 0 ? configuredWidth : widestLine + (padding * 2);
        final int height = (lineCount * metrics.getHeight()) + (padding * 2);
        graphics.setFont(originalFont);
        return new Dimension(width, height);
    }

    private static void drawShadowedText(Graphics2D graphics, String text, int x, int y, Color color)
    {
        graphics.setColor(SHADOW_COLOR);
        graphics.drawString(text, x + 1, y + 1);
        graphics.setColor(color);
        graphics.drawString(text, x, y);
    }
}
