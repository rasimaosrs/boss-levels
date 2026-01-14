package com.bosslevels;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

public class BossLevelsPanel extends PluginPanel
{
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel root = new JPanel(cardLayout);

    // Overview (grid)
    private final JPanel grid = new JPanel();
    private final JScrollPane gridScroll;

    // NEW: Pull hiscores
    private final JButton hiscoresButton = new JButton("Pull hiscores data");
    private Runnable onPullHiscores = () -> {};
    private BooleanSupplier canPullHiscores = () -> true;

    // Detail
    private final JPanel detail = new JPanel(new BorderLayout());
    private final JButton backButton = new JButton("Back");
    private final JLabel detailTitle = new JLabel();
    private final JLabel detailXp = new JLabel();
    private final JLabel detailPct = new JLabel();
    private final JTextArea milestonesArea = new JTextArea();

    private final NumberFormat nf = NumberFormat.getInstance();

    private BossDefinition selectedBoss = null;
    private Consumer<BossDefinition> onBossClicked = boss -> {};

    public BossLevelsPanel()
    {
        setLayout(new BorderLayout());
        add(root, BorderLayout.CENTER);

        /* ---------- Overview card ---------- */
        grid.setLayout(new GridLayout(0, 3, 18, 18));

        JPanel gridWrapper = new JPanel(new BorderLayout());
        gridWrapper.setOpaque(false);

        // Top bar with hiscores button
        JPanel topBar = new JPanel();
        topBar.setOpaque(false);
        topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
        topBar.add(hiscoresButton);
        topBar.add(Box.createHorizontalGlue());
        topBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);
        container.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        container.add(topBar, BorderLayout.NORTH);
        container.add(grid, BorderLayout.CENTER);

        // Pin content to top of the scrollpane
        gridWrapper.add(container, BorderLayout.NORTH);

        gridScroll = new JScrollPane(gridWrapper);
        gridScroll.setBorder(null);

        root.add(gridScroll, "overview");

        /* ---------- Detail card ---------- */
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        // Row 1: title (full width)
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 16f));
        detailTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(detailTitle);

        // Row 2: xp + % (next line)
        JPanel statsRow = new JPanel(new BorderLayout());
        statsRow.setOpaque(false);
        statsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        detailXp.setHorizontalAlignment(SwingConstants.LEFT);
        detailPct.setHorizontalAlignment(SwingConstants.RIGHT);

        statsRow.add(detailXp, BorderLayout.WEST);
        statsRow.add(detailPct, BorderLayout.EAST);

        header.add(Box.createVerticalStrut(2));
        header.add(statsRow);

        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        topRow.add(backButton, BorderLayout.WEST);

        JPanel topWrap = new JPanel();
        topWrap.setLayout(new BoxLayout(topWrap, BoxLayout.Y_AXIS));
        topWrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        topWrap.add(topRow);
        topWrap.add(header);

        milestonesArea.setEditable(false);
        milestonesArea.setLineWrap(true);
        milestonesArea.setWrapStyleWord(true);
        milestonesArea.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        milestonesArea.setOpaque(false);

        JScrollPane milestonesScroll = new JScrollPane(milestonesArea);
        milestonesScroll.setBorder(null);

        detail.add(topWrap, BorderLayout.NORTH);
        detail.add(milestonesScroll, BorderLayout.CENTER);

        root.add(detail, "detail");

        backButton.addActionListener(e -> showOverview());

        hiscoresButton.addActionListener(e ->
        {
            if (!canPullHiscores.getAsBoolean())
            {
                JOptionPane.showMessageDialog(
                        this,
                        "Hiscores pull is unavailable right now.",
                        "Boss Levels",
                        JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }
            onPullHiscores.run();
        });

        showOverview();
    }

    /**
     * Plugin hooks for the "Pull hiscores data" button.
     */
    public void setOnPullHiscores(Runnable r)
    {
        this.onPullHiscores = (r != null) ? r : () -> {};
    }

    public void setCanPullHiscores(BooleanSupplier s)
    {
        this.canPullHiscores = (s != null) ? s : () -> true;
    }
    public void setOpenBossDetailConsumer(Consumer<BossDefinition> onBossClicked)
    {
        this.onBossClicked = (onBossClicked != null) ? onBossClicked : boss -> {};
    }

    public void showOverview()
    {
        selectedBoss = null;
        cardLayout.show(root, "overview");
        SwingUtilities.invokeLater(() -> gridScroll.getVerticalScrollBar().setValue(0));
    }

    public void showBoss(
            BossDefinition boss,
            long xp,
            int level,
            int pct,
            BufferedImage icon
    )
    {
        selectedBoss = boss;

        // Title: icon + name + level
        if (icon != null)
        {
            Image scaled = icon.getScaledInstance(20, 20, Image.SCALE_SMOOTH);
            detailTitle.setIcon(new ImageIcon(scaled));
            detailTitle.setIconTextGap(6);
        }
        else
        {
            detailTitle.setIcon(null);
        }

        detailTitle.setText("<html>" + boss.kcName + " — Lvl " + level + "</html>");
        detailXp.setText(nf.format(xp) + " xp");
        detailPct.setText(pct + "%");
        detailTitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < boss.milestones.size(); i++)
        {
            sb.append(boss.milestones.get(i));
            if (i < boss.milestones.size() - 1)
            {
                sb.append("\n\n");
            }
        }
        milestonesArea.setText(sb.toString().trim());

        cardLayout.show(root, "detail");
    }

    /**
     * Rebuilds the overview grid.
     */
    public void rebuildOverview(
            Map<BossDefinition, Long> xpMap,
            Map<BossDefinition, Integer> levelMap,
            Map<BossDefinition, BufferedImage> iconMap
    )
    {
        rebuildOverview(xpMap, levelMap, iconMap, onBossClicked);
    }

    /**
     * Rebuilds the overview grid.
     */
    public void rebuildOverview(
            Map<BossDefinition, Long> xpMap,
            Map<BossDefinition, Integer> levelMap,
            Map<BossDefinition, BufferedImage> iconMap,
            Consumer<BossDefinition> onBossClicked
    )
    {
        this.onBossClicked = (onBossClicked != null) ? onBossClicked : boss -> {};

        grid.removeAll();

        for (BossDefinition boss : BossDefinition.values())
        {
            int level = levelMap.getOrDefault(boss, 1);
            long xp = xpMap.getOrDefault(boss, 0L);

            // show "--" if XP is 0
            String levelText = (xp <= 0) ? "--" : String.valueOf(level);

            BufferedImage icon = iconMap.get(boss);
            JLabel iconLabel = new JLabel();
            if (icon != null)
            {
                Image scaled = icon.getScaledInstance(19, 19, Image.SCALE_SMOOTH);
                iconLabel.setIcon(new ImageIcon(scaled));
            }

            JLabel levelLabel = new JLabel(levelText, SwingConstants.CENTER);
            levelLabel.setForeground(Color.LIGHT_GRAY);

            JPanel cell = new JPanel();
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setOpaque(false);

            iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            levelLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            cell.add(iconLabel);
            cell.add(Box.createVerticalStrut(6));
            cell.add(levelLabel);

            cell.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    BossLevelsPanel.this.onBossClicked.accept(boss);
                }

                @Override
                public void mouseEntered(MouseEvent e)
                {
                    cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }
            });

            grid.add(cell);
        }

        grid.revalidate();
        grid.repaint();
    }
}
