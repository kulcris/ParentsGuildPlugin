package com.parentsguild.parentsguild;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

class ParentsGuildBoardPopup extends JFrame
{
    private static final int PANEL_PADDING = 8;
    private static final int GRID_GAP = 6;
    private static final int HEADER_HEIGHT = 86;
    private static final int DEFAULT_CELL_SIZE = 126;
    private static final int MIN_CELL_SIZE = 84;
    private static final Color BACKGROUND = new Color(18, 18, 18);
    private static final Color PANEL_BORDER = new Color(207, 171, 78);
    private static final Color HEADER_COLOR = new Color(240, 232, 210);
    private static final Color TEAM_COLOR = new Color(214, 214, 214);
    private static final Color CELL_DEFAULT = new Color(46, 46, 46);
    private static final Color CELL_COMPLETE = new Color(48, 102, 58);
    private static final Color CELL_PENDING = new Color(125, 97, 28);
    private static final Color CELL_BORDER = new Color(90, 90, 90);
    private static final Color TICK_COMPLETE = new Color(73, 224, 100);
    private static final Color TICK_PENDING = new Color(255, 218, 91);
    private static final Color TEXT_COLOR = Color.WHITE;
    private static final Color BUTTON_BACKGROUND = new Color(207, 171, 78);
    private static final Color BUTTON_TEXT = new Color(18, 18, 18);

    private final ParentsGuildPlugin plugin;
    private final BoardPanel boardPanel;

    ParentsGuildBoardPopup(ParentsGuildPlugin plugin)
    {
        super("ParentsGuild Bingo Board");
        this.plugin = plugin;
        boardPanel = new BoardPanel(plugin);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(buildToolbar(), BorderLayout.NORTH);
        add(new JScrollPane(boardPanel), BorderLayout.CENTER);
        setMinimumSize(new Dimension(520, 420));
        setSize(720, 620);
        setLocationByPlatform(true);
        addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent event)
            {
                plugin.onBingoBoardPopupClosed();
            }

            @Override
            public void windowClosed(WindowEvent event)
            {
                plugin.onBingoBoardPopupClosed();
            }
        });
    }

    private JPanel buildToolbar()
    {
        final JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        toolbar.setBackground(BACKGROUND);

        final JButton openWebsiteBoardButton = new JButton("Open Website Board");
        openWebsiteBoardButton.addActionListener(event -> plugin.openWebsiteBingoBoard());
        toolbar.add(openWebsiteBoardButton, BorderLayout.EAST);
        return toolbar;
    }

    void updateBoardState(ParentsGuildPlugin.BingoBoardState state)
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            boardPanel.setBoardState(state);
            return;
        }

        SwingUtilities.invokeLater(() -> boardPanel.setBoardState(state));
    }

    private static class BoardPanel extends JPanel
    {
        private final ParentsGuildPlugin plugin;
        private final List<ProofButtonTarget> proofButtonTargets = new ArrayList<>();
        private ParentsGuildPlugin.BingoBoardState state = ParentsGuildPlugin.BingoBoardState.hidden();

        BoardPanel(ParentsGuildPlugin plugin)
        {
            this.plugin = plugin;
            setBackground(BACKGROUND);
            setBorder(BorderFactory.createEmptyBorder(PANEL_PADDING, PANEL_PADDING, PANEL_PADDING, PANEL_PADDING));
            final MouseAdapter mouseAdapter = new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent event)
                {
                    final ParentsGuildPlugin.BingoBoardCell cell = cellForProofButton(event.getX(), event.getY());
                    if (cell != null)
                    {
                        plugin.submitBingoTileProof(cell);
                    }
                }

                @Override
                public void mouseMoved(MouseEvent event)
                {
                    setCursor(cellForProofButton(event.getX(), event.getY()) == null
                        ? Cursor.getDefaultCursor()
                        : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }

                @Override
                public void mouseExited(MouseEvent event)
                {
                    setCursor(Cursor.getDefaultCursor());
                }
            };
            addMouseListener(mouseAdapter);
            addMouseMotionListener(mouseAdapter);
        }

        void setBoardState(ParentsGuildPlugin.BingoBoardState nextState)
        {
            state = nextState == null ? ParentsGuildPlugin.BingoBoardState.hidden() : nextState;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize()
        {
            final int cols = Math.max(1, state.getColsCount());
            final int rows = Math.max(1, state.getRowsCount());
            return new Dimension(
                Math.max(480, (DEFAULT_CELL_SIZE * cols) + (GRID_GAP * (cols - 1)) + (PANEL_PADDING * 2)),
                Math.max(360, HEADER_HEIGHT + (DEFAULT_CELL_SIZE * rows) + (GRID_GAP * (rows - 1)) + (PANEL_PADDING * 2))
            );
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            super.paintComponent(graphics);
            final Graphics2D graphics2D = (Graphics2D) graphics.create();
            try
            {
                renderBoard(graphics2D);
            }
            finally
            {
                graphics2D.dispose();
            }
        }

        private void renderBoard(Graphics2D graphics)
        {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            final int width = getWidth() - (PANEL_PADDING * 2);
            final int height = getHeight() - (PANEL_PADDING * 2);
            final int x = PANEL_PADDING;
            final int y = PANEL_PADDING;

            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, getWidth(), getHeight());
            graphics.setColor(PANEL_BORDER);
            graphics.drawRect(x, y, Math.max(1, width), Math.max(1, height));
            proofButtonTargets.clear();

            if (state == null || !state.isVisible())
            {
                graphics.setColor(HEADER_COLOR);
                graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 16F));
                graphics.drawString("No active bingo board loaded.", x + 14, y + 30);
                return;
            }

            final int cols = Math.max(1, state.getColsCount());
            final int rows = Math.max(1, state.getRowsCount());
            final int boardWidth = Math.max(MIN_CELL_SIZE * cols, width - (PANEL_PADDING * 2) - (GRID_GAP * (cols - 1)));
            final int boardHeight = Math.max(MIN_CELL_SIZE * rows, height - HEADER_HEIGHT - (PANEL_PADDING * 2) - (GRID_GAP * (rows - 1)));
            final int cellSize = Math.max(MIN_CELL_SIZE, Math.min(boardWidth / cols, boardHeight / rows));

            final Font originalFont = graphics.getFont();
            final Font titleFont = originalFont.deriveFont(Font.BOLD, 20F);
            final Font teamFont = originalFont.deriveFont(Font.PLAIN, 15F);
            final Font cellFont = originalFont.deriveFont(Font.BOLD, 15F);
            final Font progressFont = originalFont.deriveFont(Font.BOLD, 13F);

            graphics.setFont(titleFont);
            graphics.setColor(HEADER_COLOR);
            graphics.drawString(state.getBingoName(), x + 14, y + 24);
            graphics.setFont(teamFont);
            graphics.setColor(TEAM_COLOR);
            graphics.drawString(state.getTeamName(), x + 14, y + 44);

            final String membersText = String.join(", ", state.getTeamMembers());
            if (!membersText.isEmpty())
            {
                final Font membersFont = originalFont.deriveFont(Font.PLAIN, 13F);
                graphics.setFont(membersFont);
                final FontMetrics membersMetrics = graphics.getFontMetrics();
                final List<String> memberLines = wrapLines(membersMetrics, "Members: " + membersText, width - 28, 2);
                graphics.setColor(new Color(190, 184, 168));
                int membersY = y + 64;
                for (String line : memberLines)
                {
                    graphics.drawString(line, x + 14, membersY);
                    membersY += membersMetrics.getHeight();
                }
            }

            final List<List<ParentsGuildPlugin.BingoBoardCell>> grid = state.getGrid();
            for (int rowIndex = 0; rowIndex < Math.min(rows, grid.size()); rowIndex++)
            {
                final List<ParentsGuildPlugin.BingoBoardCell> row = grid.get(rowIndex);
                for (int colIndex = 0; colIndex < Math.min(cols, row.size()); colIndex++)
                {
                    final ParentsGuildPlugin.BingoBoardCell cell = row.get(colIndex);
                    final int cellX = x + PANEL_PADDING + (colIndex * (cellSize + GRID_GAP));
                    final int cellY = y + HEADER_HEIGHT + (rowIndex * (cellSize + GRID_GAP));
                    final Rectangle proofButton = drawCell(graphics, cell, cellX, cellY, cellSize, cellSize, cellFont, progressFont);
                    if (proofButton != null)
                    {
                        proofButtonTargets.add(new ProofButtonTarget(proofButton, cell));
                    }
                }
            }

            graphics.setFont(originalFont);
        }

        private ParentsGuildPlugin.BingoBoardCell cellForProofButton(int x, int y)
        {
            for (ProofButtonTarget target : proofButtonTargets)
            {
                if (target.bounds.contains(x, y))
                {
                    return target.cell;
                }
            }
            return null;
        }

        private static Rectangle drawCell(Graphics2D graphics, ParentsGuildPlugin.BingoBoardCell cell, int x, int y, int width, int height, Font cellFont, Font progressFont)
        {
            final Color fill = cell.isCompleted()
                ? CELL_COMPLETE
                : (cell.isPending() ? CELL_PENDING : CELL_DEFAULT);
            final BufferedImage backgroundImage = cell.getBackgroundImage();
            graphics.setColor(fill);
            graphics.fillRect(x, y, width, height);
            graphics.setColor(CELL_BORDER);
            graphics.drawRect(x, y, width, height);

            graphics.setFont(cellFont);
            final FontMetrics metrics = graphics.getFontMetrics();
            final String progressText = safeProgress(cell.getProgressText());
            final int maxLabelLines = height < 102 ? 2 : 3;
            final List<String> lines = wrapLines(metrics, safeLabel(cell.getLabel()), width - 10, maxLabelLines);
            graphics.setColor(TEXT_COLOR);
            int textY = y + 20;
            for (String line : lines)
            {
                graphics.setColor(new Color(0, 0, 0, 150));
                final int textX = x + Math.max(5, (width - metrics.stringWidth(line)) / 2);
                graphics.drawString(line, textX + 1, textY + 1);
                graphics.setColor(TEXT_COLOR);
                graphics.drawString(line, textX, textY);
                textY += metrics.getHeight() - 2;
            }

            final boolean eligible = proofButtonEligible(cell);
            final int reservedBottom = progressText.isEmpty() ? 18 : 30;
            final int imageTop = Math.max(y + 28, textY + 2);
            final int imageBottom = y + height - reservedBottom;
            if (backgroundImage != null && imageBottom > imageTop)
            {
                drawCellItemImage(graphics, backgroundImage, x + 6, imageTop, width - 12, imageBottom - imageTop);
            }

            if (!progressText.isEmpty())
            {
                graphics.setFont(progressFont);
                final FontMetrics progressMetrics = graphics.getFontMetrics();
                final String trimmedProgress = trimToWidth(progressMetrics, progressText, width - 42);
                final int statusY = y + height - 13;
                final int statusWidth = 24 + progressMetrics.stringWidth(trimmedProgress);
                final int statusX = x + Math.max(8, (width - statusWidth) / 2);
                drawStatusTick(graphics, statusX, statusY - 14, cell.isPending(), cell.isCompleted());
                drawProgressText(graphics, progressMetrics, trimmedProgress, statusX + 24, statusY);
            }

            if (!eligible)
            {
                return null;
            }

            final int proofButtonY = Math.min(y + height - 30, Math.max(y + 26, textY + 2));
            final Rectangle proofButton = new Rectangle(x + width - 30, proofButtonY, 24, 24);
            drawScreenshotButton(graphics, proofButton);
            return proofButton;
        }

        private static void drawProgressText(Graphics2D graphics, FontMetrics metrics, String progressText, int x, int y)
        {
            final int pendingStart = progressText.indexOf("(+");
            final int pendingEnd = progressText.indexOf(")/", pendingStart + 2);
            if (pendingStart < 0 || pendingEnd < 0)
            {
                drawTextShadow(graphics, progressText, x, y, new Color(236, 222, 174));
                return;
            }

            final String approvedText = progressText.substring(0, pendingStart);
            final String openText = "(+";
            final String pendingText = progressText.substring(pendingStart + 2, pendingEnd);
            final String closeText = progressText.substring(pendingEnd);
            int drawX = x;
            drawTextShadow(graphics, approvedText, drawX, y, TICK_COMPLETE);
            drawX += metrics.stringWidth(approvedText);
            drawTextShadow(graphics, openText, drawX, y, new Color(236, 222, 174));
            drawX += metrics.stringWidth(openText);
            drawTextShadow(graphics, pendingText, drawX, y, TICK_PENDING);
            drawX += metrics.stringWidth(pendingText);
            drawTextShadow(graphics, closeText, drawX, y, new Color(236, 222, 174));
        }

        private static void drawTextShadow(Graphics2D graphics, String text, int x, int y, Color color)
        {
            if (text == null || text.isEmpty())
            {
                return;
            }
            graphics.setColor(new Color(0, 0, 0, 150));
            graphics.drawString(text, x + 1, y + 1);
            graphics.setColor(color);
            graphics.drawString(text, x, y);
        }

        private static void drawScreenshotButton(Graphics2D graphics, Rectangle bounds)
        {
            final Graphics2D buttonGraphics = (Graphics2D) graphics.create();
            try
            {
                buttonGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                buttonGraphics.setColor(BUTTON_BACKGROUND);
                buttonGraphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                buttonGraphics.setColor(BUTTON_TEXT);
                buttonGraphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
                buttonGraphics.setStroke(new java.awt.BasicStroke(2F, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                buttonGraphics.drawRect(bounds.x + 5, bounds.y + 8, 14, 10);
                buttonGraphics.drawLine(bounds.x + 8, bounds.y + 8, bounds.x + 10, bounds.y + 5);
                buttonGraphics.drawLine(bounds.x + 10, bounds.y + 5, bounds.x + 14, bounds.y + 5);
                buttonGraphics.drawLine(bounds.x + 14, bounds.y + 5, bounds.x + 16, bounds.y + 8);
                buttonGraphics.drawOval(bounds.x + 10, bounds.y + 10, 4, 4);
            }
            finally
            {
                buttonGraphics.dispose();
            }
        }

        private static void drawStatusTick(Graphics2D graphics, int x, int y, boolean pending, boolean completed)
        {
            final Graphics2D tickGraphics = (Graphics2D) graphics.create();
            try
            {
                tickGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                tickGraphics.setColor(completed ? TICK_COMPLETE : (pending ? TICK_PENDING : new Color(236, 222, 174)));
                tickGraphics.drawOval(x, y, 18, 18);
                if (completed || pending)
                {
                    tickGraphics.setStroke(new java.awt.BasicStroke(2.4F, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                    tickGraphics.drawLine(x + 5, y + 9, x + 8, y + 12);
                    tickGraphics.drawLine(x + 8, y + 12, x + 14, y + 5);
                }
            }
            finally
            {
                tickGraphics.dispose();
            }
        }

        private static void drawCellItemImage(Graphics2D graphics, BufferedImage image, int x, int y, int width, int height)
        {
            final double scale = Math.min(width / (double) image.getWidth(), height / (double) image.getHeight()) * 0.9D;
            final int drawWidth = Math.max(1, (int) Math.ceil(image.getWidth() * scale));
            final int drawHeight = Math.max(1, (int) Math.ceil(image.getHeight() * scale));
            final int drawX = x + ((width - drawWidth) / 2);
            final int drawY = y + ((height - drawHeight) / 2);
            graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
        }

        private static boolean proofButtonEligible(ParentsGuildPlugin.BingoBoardCell cell)
        {
            final String tileType = safeProgress(cell == null ? "" : cell.getTileType()).toLowerCase();
            return cell != null
                && cell.getTileId() != null
                && !cell.getTileId().trim().isEmpty()
                && ("manual".equals(tileType) || "drop".equals(tileType) || "multi_item".equals(tileType))
                && !cell.isCompleted();
        }

        private static String safeLabel(String label)
        {
            final String cleaned = label == null ? "" : label.trim();
            return cleaned.isEmpty() ? "Blank" : cleaned;
        }

        private static String safeProgress(String progress)
        {
            return progress == null ? "" : progress.trim();
        }

        private static List<String> wrapLines(FontMetrics metrics, String text, int maxWidth, int maxLines)
        {
            final List<String> lines = new ArrayList<>();
            if (text.isEmpty())
            {
                lines.add("");
                return lines;
            }

            final String[] words = text.split("\\s+");
            String current = "";
            for (String word : words)
            {
                final String candidate = current.isEmpty() ? word : current + " " + word;
                if (metrics.stringWidth(candidate) <= maxWidth)
                {
                    current = candidate;
                    continue;
                }

                if (!current.isEmpty())
                {
                    lines.add(current);
                }
                current = trimToWidth(metrics, word, maxWidth);
                if (lines.size() >= maxLines - 1)
                {
                    break;
                }
            }

            if (lines.size() < maxLines && !current.isEmpty())
            {
                lines.add(trimToWidth(metrics, current, maxWidth));
            }

            if (lines.size() > maxLines)
            {
                return lines.subList(0, maxLines);
            }
            return lines;
        }

        private static String trimToWidth(FontMetrics metrics, String text, int maxWidth)
        {
            if (metrics.stringWidth(text) <= maxWidth)
            {
                return text;
            }

            String trimmed = text;
            while (trimmed.length() > 1 && metrics.stringWidth(trimmed + "...") > maxWidth)
            {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed + "...";
        }

        private static class ProofButtonTarget
        {
            private final Rectangle bounds;
            private final ParentsGuildPlugin.BingoBoardCell cell;

            ProofButtonTarget(Rectangle bounds, ParentsGuildPlugin.BingoBoardCell cell)
            {
                this.bounds = bounds;
                this.cell = cell;
            }
        }
    }
}
