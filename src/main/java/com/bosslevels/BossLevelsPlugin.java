package com.bosslevels;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@PluginDescriptor(
		name = "Boss Levels",
		description = "Gives bosses fake XP and levels based on RuneLite KC",
		tags = {"boss", "xp", "levels"}
)
public class BossLevelsPlugin extends Plugin
{
	/* ===================== CONSTANTS ===================== */

	private static final String CONFIG_GROUP = "bosslevels";

	// In-game command output: "Your Abyssal Sire kill count is: 22."
	private static final Pattern KC_PATTERN =
			Pattern.compile("^Your (.+) kill count is: (\\d+)\\.$");

	/* ===================== INJECTED ===================== */

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private ClientToolbar clientToolbar;
	@Inject private OverlayManager overlayManager;
	@Inject private BossLevelsConfig config;

	// Hiscores
	@Inject private HiscoreClient hiscoreClient;
	@Inject private ScheduledExecutorService executor;

	@Provides
	BossLevelsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BossLevelsConfig.class);
	}

	/* ===================== STATE ===================== */

	private final Map<BossDefinition, Long> bossXp = new EnumMap<>(BossDefinition.class);
	private final Map<BossDefinition, Integer> bossLevels = new EnumMap<>(BossDefinition.class);
	private final Map<BossDefinition, Integer> lastKcSeen = new EnumMap<>(BossDefinition.class);

	// Pre-scaled icons
	private final Map<BossDefinition, BufferedImage> bossIcons16 = new EnumMap<>(BossDefinition.class);

	private int spotAnimKey = 1;

	/* ===================== PANEL UI ===================== */

	private BossLevelsPanel panel;
	private NavigationButton navButton;

	private void openBossDetail(BossDefinition boss)
	{
		if (panel == null)
		{
			return;
		}

		long xp = bossXp.getOrDefault(boss, 0L);
		int level = bossLevels.getOrDefault(boss, 1);

		int cur = xpForLevel(level);
		int nxt = xpForNextLevel(level);
		int pct = (level >= 99) ? 100 : (int) Math.floor(100.0 * (xp - cur) / Math.max(1, (nxt - cur)));

		BufferedImage icon = bossIcons16.get(boss);
		panel.showBoss(boss, xp, level, pct, icon);
	}

	/* ===================== XP DROP OVERLAY ===================== */

	private BossLevelsOverlay xpDropOverlay;

	/* ===================== STARTUP/SHUTDOWN ===================== */

	private BufferedImage pluginIcon;

	@Override
	protected void startUp()
	{
		pluginIcon = ImageUtil.loadImageResource(BossLevelsPlugin.class, "icons/plugin_icon.png");

		// Load XP + level state
		for (BossDefinition boss : BossDefinition.values())
		{
			long xp = loadLong(xpKey(boss), 0L);
			bossXp.put(boss, xp);
			bossLevels.put(boss, levelForXp(xp));
			lastKcSeen.put(boss, -1);
		}

		// Load icons
		bossIcons16.clear();
		for (BossDefinition boss : BossDefinition.values())
		{
			BufferedImage img = ImageUtil.loadImageResource(BossLevelsPlugin.class, "icons/" + boss.iconFile);
			if (img != null)
			{
				bossIcons16.put(boss, ImageUtil.resizeImage(img, 16, 16));
			}
		}

		// Panel
		panel = new BossLevelsPanel();
		panel.setOpenBossDetailConsumer(this::openBossDetail);

		// Button: pull hiscores
		panel.setOnPullHiscores(() ->
		{
			clientThread.invoke(() ->
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Boss Levels: Pulling hiscores…", null)
			);

			refreshAllBossKcFromHiscores(true);
		});

		panel.setCanPullHiscores(() -> client.getGameState() == GameState.LOGGED_IN);
		if (!hiscoresPulledThisLogin)
		{
			hiscoresPulledThisLogin = true;
			refreshAllBossKcFromHiscores(false); // silent auto pull
		}

		navButton = NavigationButton.builder()
				.tooltip("Boss Levels")
				.icon(pluginIcon)
				.priority(5)
				.panel(panel)
				.build();

		SwingUtilities.invokeLater(() ->
		{
			clientToolbar.addNavigation(navButton);
			panel.rebuildOverview(bossXp, bossLevels, bossIcons16);
		});

		// XP overlay
		xpDropOverlay = new BossLevelsOverlay(client, this::colorForBoss, config, bossIcons16);
		overlayManager.add(xpDropOverlay);
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			SwingUtilities.invokeLater(() -> clientToolbar.removeNavigation(navButton));
		}
		navButton = null;
		panel = null;

		if (xpDropOverlay != null)
		{
			overlayManager.remove(xpDropOverlay);
			xpDropOverlay = null;
		}

		bossIcons16.clear();
	}

	/* ===================== AUTO HISCORES REFRESH ===================== */

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			refreshAllBossKcFromHiscores(false);
			hiscoresPulledThisLogin = false;
		}
	}

	private void refreshAllBossKcFromHiscores(boolean showChat)
	{
		final Player p = client.getLocalPlayer();
		if (p == null || p.getName() == null)
		{
			clientThread.invoke(() ->
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Boss Levels: No player name available yet.", null)
			);
			return;
		}

		final String username = p.getName().trim();

		executor.execute(() ->
		{
			HiscoreResult result = lookupHiscoresSafe(username);
			if (result == null)
			{
				clientThread.invoke(() ->
						client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Boss Levels: Hiscores lookup failed for " + username, null)
				);
				return;
			}

			clientThread.invoke(() ->
			{
				int updated = 0;
				int mapped = 0;

				for (BossDefinition def : BossDefinition.values())
				{
					Integer kc = tryGetBossKillCount(result, def);
					if (kc == null)
					{
						continue;
					}

					mapped++;

					if (kc >= 0)
					{
						// Silent absolute set so pulling doesn't spam drops/fireworks/chat line
						if (setKcAbsolute(def, kc))
						{
							updated++;
						}
					}
				}

				if (panel != null)
				{
					SwingUtilities.invokeLater(() -> panel.rebuildOverview(bossXp, bossLevels, bossIcons16));
				}

				if (showChat)
				{
					client.addChatMessage(
							ChatMessageType.GAMEMESSAGE,
							"",
							"Boss Levels: Hiscores pulled. Mapped=" + mapped + ", Updated=" + updated,
							null
					);
				}
			});
		});

	}

	private HiscoreResult lookupHiscoresSafe(String username)
	{
		// Try every endpoint; works across normal/iron/group/seasonal depending on RL version.
		for (HiscoreEndpoint ep : HiscoreEndpoint.values())
		{
			HiscoreResult r = tryLookup(username, ep);
			if (r != null)
			{
				return r;
			}
		}
		return null;
	}

	private HiscoreResult tryLookup(String username, HiscoreEndpoint endpoint)
	{
		try
		{
			return hiscoreClient.lookup(username, endpoint);
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	/**
	 * Absolute set of KC -> XP/Level, persisted, and lastKcSeen updated.
	 * Returns true if state changed.
	 */
	private boolean setKcAbsolute(BossDefinition boss, int kc)
	{
		if (kc < 0)
		{
			return false;
		}

		Integer prev = lastKcSeen.getOrDefault(boss, -1);
		if (prev == kc)
		{
			return false;
		}

		lastKcSeen.put(boss, kc);

		long newXp = (long) kc * boss.xpPerKill;
		int newLevel = levelForXp(newXp);

		Long oldXp = bossXp.getOrDefault(boss, 0L);
		Integer oldLevel = bossLevels.getOrDefault(boss, 1);

		bossXp.put(boss, newXp);
		bossLevels.put(boss, newLevel);
		saveLong(xpKey(boss), newXp);

		return oldXp != newXp || oldLevel != newLevel;
	}

	/* ===================== HISCORES BOSS KC LOOKUP ===================== */
	private boolean hiscoresPulledThisLogin = false;

	private Integer tryGetBossKillCount(HiscoreResult result, BossDefinition def)
	{
		if (result == null || def == null)
		{
			return null;
		}

		// Primary: match by enum constant name (your BossDefinition format matches this best)
		String target = def.name();

		// 1) Try any public methods like getBossKc/getBossScore that take an enum parameter
		Integer viaMethods = tryExtractViaBossMethods(result, target);
		if (viaMethods != null)
		{
			return viaMethods;
		}

		// 2) Generic fallback: scan any Map fields in HiscoreResult and match enum keys by name()
		return tryExtractFromMapFields(result, target);
	}

	private Integer tryExtractViaBossMethods(HiscoreResult result, String targetEnumName)
	{
		try
		{
			for (Method m : result.getClass().getMethods())
			{
				String n = m.getName();
				if (!n.equals("getBossKc") && !n.equals("getBossScore"))
				{
					continue;
				}

				Class<?>[] params = m.getParameterTypes();
				if (params.length != 1 || !params[0].isEnum())
				{
					continue;
				}

				Object bossEnum = findEnumConstantByName(params[0], targetEnumName);
				if (bossEnum == null)
				{
					continue;
				}

				Object out = m.invoke(result, bossEnum);

				// getBossKc -> Integer
				if (out instanceof Integer)
				{
					return (Integer) out;
				}

				// getBossScore -> some object; extract int
				Integer kc = extractIntFromValue(out);
				if (kc != null)
				{
					return kc;
				}
			}
		}
		catch (Exception ignored)
		{
		}

		return null;
	}

	private Integer tryExtractFromMapFields(HiscoreResult result, String targetEnumName)
	{
		try
		{
			for (Field f : result.getClass().getDeclaredFields())
			{
				if (!Map.class.isAssignableFrom(f.getType()))
				{
					continue;
				}

				f.setAccessible(true);
				Object mapObj = f.get(result);
				if (!(mapObj instanceof Map))
				{
					continue;
				}

				Map<?, ?> map = (Map<?, ?>) mapObj;

				for (Map.Entry<?, ?> e : map.entrySet())
				{
					Object key = e.getKey();
					if (!(key instanceof Enum))
					{
						continue;
					}

					String keyName = ((Enum<?>) key).name();
					if (!keyName.equalsIgnoreCase(targetEnumName))
					{
						// extra tolerance: spaces/underscores differences
						String kn = keyName.replace('_', ' ').toUpperCase(Locale.ROOT);
						String tn = targetEnumName.replace('_', ' ').toUpperCase(Locale.ROOT);
						if (!kn.equals(tn))
						{
							continue;
						}
					}

					Integer kc = extractIntFromValue(e.getValue());
					if (kc != null)
					{
						return kc;
					}
				}
			}
		}
		catch (Exception ignored)
		{
		}

		return null;
	}

	private Object findEnumConstantByName(Class<?> enumClass, String name)
	{
		try
		{
			Object[] constants = enumClass.getEnumConstants();
			if (constants == null)
			{
				return null;
			}

			for (Object c : constants)
			{
				if (c instanceof Enum && ((Enum<?>) c).name().equalsIgnoreCase(name))
				{
					return c;
				}
			}
		}
		catch (Exception ignored)
		{
		}

		return null;
	}

	/**
	 * Extracts an int KC from various RL hiscore value types.
	 * Handles Integer directly and common getter names used by different RL versions.
	 */
	private Integer extractIntFromValue(Object value)
	{
		if (value == null)
		{
			return null;
		}

		if (value instanceof Integer)
		{
			return (Integer) value;
		}

		// Common RL hiscore score containers
		String[] methodNames = {"getKillCount", "getKc", "getLevel", "getScore", "getValue"};

		for (String mn : methodNames)
		{
			try
			{
				Method m = value.getClass().getMethod(mn);
				Object out = m.invoke(value);
				if (out instanceof Integer)
				{
					return (Integer) out;
				}
			}
			catch (Exception ignored)
			{
			}
		}

		return null;
	}
	/* ===================== CHAT HANDLER ===================== */

	@Subscribe
	@SuppressWarnings("unused")
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		final String msg = Text.removeTags(event.getMessage()).trim();

		// In-game ::kc format: "Your X kill count is: N."
		Matcher m = KC_PATTERN.matcher(msg);
		if (!m.matches())
		{
			return;
		}

		BossDefinition boss = findBossByKcName(m.group(1));
		if (boss == null)
		{
			return;
		}

		applyKcUpdate(boss, Integer.parseInt(m.group(2)));
	}

	private void applyKcUpdate(BossDefinition boss, int kc)
	{
		int previousKc = lastKcSeen.getOrDefault(boss, -1);
		int gainedKills = (previousKc == -1) ? 1 : (kc - previousKc);
		lastKcSeen.put(boss, kc);

		if (gainedKills <= 0)
		{
			return;
		}

		int oldLevel = bossLevels.getOrDefault(boss, 1);

		long newXp = (long) kc * boss.xpPerKill;
		int newLevel = levelForXp(newXp);

		bossXp.put(boss, newXp);
		bossLevels.put(boss, newLevel);
		saveLong(xpKey(boss), newXp);

		long gainedXp = (long) gainedKills * boss.xpPerKill;

		// Screen XP drop
		if (config.enableXpDrops() && xpDropOverlay != null)
		{
			xpDropOverlay.pushDrop(boss, gainedXp);
		}

		// Update panel and auto-open the boss that changed
		if (panel != null)
		{
			SwingUtilities.invokeLater(() ->
			{
				panel.rebuildOverview(bossXp, bossLevels, bossIcons16);
				openBossDetail(boss);
			});
		}

		// Optional chat line
		if (config.enableChatLine())
		{
			client.addChatMessage(
					ChatMessageType.GAMEMESSAGE,
					"",
					"Boss Levels: " + boss.kcName + " +" + gainedXp +
							" xp (Total: " + newXp +
							", Level: " + newLevel + ")",
					null
			);
		}

		// Optional fireworks
		if (config.enableFireworks() && newLevel > oldLevel)
		{
			playLevelUpFireworks(newLevel);
		}
	}

	/* ===================== FIREWORKS ===================== */

	private void playLevelUpFireworks(int level)
	{
		final int anim = (level >= 99) ? SpotanimID.LEVELUP_99_ANIM : SpotanimID.LEVELUP_ANIM;

		clientThread.invoke(() ->
		{
			Player p = client.getLocalPlayer();
			if (p != null)
			{
				p.createSpotAnim(spotAnimKey++, anim, 0, 0);
			}
		});
	}

	/* ===================== XP CURVE ===================== */

	private static int levelForXp(long xp)
	{
		for (int level = 1; level < 99; level++)
		{
			if (xp < xpForLevel(level + 1))
			{
				return level;
			}
		}
		return 99;
	}

	private static int xpForLevel(int level)
	{
		double points = 0;
		for (int i = 1; i < level; i++)
		{
			points += Math.floor(i + 300.0 * Math.pow(2.0, i / 7.0));
		}
		return (int) Math.floor(points / 4.0);
	}

	private static int xpForNextLevel(int level)
	{
		if (level >= 99)
		{
			return xpForLevel(99);
		}
		return xpForLevel(level + 1);
	}

	/* ===================== CONFIG PERSISTENCE ===================== */

	private String xpKey(BossDefinition boss)
	{
		return "xp_" + boss.configKey;
	}

	private long loadLong(String key, long def)
	{
		String v = configManager.getConfiguration(CONFIG_GROUP, key);
		if (v == null)
		{
			return def;
		}

		try
		{
			return Long.parseLong(v);
		}
		catch (NumberFormatException e)
		{
			return def;
		}
	}

	private void saveLong(String key, long value)
	{
		configManager.setConfiguration(CONFIG_GROUP, key, Long.toString(value));
	}

	/* ===================== HELPERS ===================== */

	private BossDefinition findBossByKcName(String name)
	{
		String normalized = name == null ? "" : name.trim();
		for (BossDefinition boss : BossDefinition.values())
		{
			if (boss.kcName.equalsIgnoreCase(normalized))
			{
				return boss;
			}
		}
		return null;
	}

	private Color colorForBoss(BossDefinition boss)
	{
		if (config.colorMode() == BossLevelsConfig.ColorMode.GLOBAL)
		{
			return config.globalXpColor();
		}

		float hue = (boss.ordinal() * 0.21f) % 1.0f;
		Color base = Color.getHSBColor(hue, 0.70f, 1.0f);
		int a = config.globalXpColor().getAlpha();
		return new Color(base.getRed(), base.getGreen(), base.getBlue(), a);
	}
}
