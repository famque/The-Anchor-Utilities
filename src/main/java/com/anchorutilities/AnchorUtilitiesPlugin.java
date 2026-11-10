package com.anchorutilities;

import com.anchorutilities.events.LootListener;
import com.anchorutilities.ui.AnchorPanel;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
		name = "The Anchor Utilities",
		description = "Clan loot and pet submissions, points, and competitions for The Anchor",
		tags = {"clan", "loot", "pvm", "pets", "competitions"}
)
public class AnchorUtilitiesPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientToolbar clientToolbar;
	@Inject private EventBus eventBus;
	@Inject private AnchorUtilitiesConfig config;
	@Inject private AnchorPanel panel;
	@Inject private LootListener lootListener;

	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		navButton = NavigationButton.builder()
				.tooltip("The Anchor Utilities")
				.icon(loadIcon())
				.priority(5)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);
		eventBus.register(lootListener);
		log.info("The Anchor Utilities started");
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(lootListener);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		log.info("The Anchor Utilities stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		String status = event.getGameState() == GameState.LOGGED_IN ? "Logged in" : "Not logged in";
		SwingUtilities.invokeLater(() -> panel.setStatus(status));
	}

	@Provides
	AnchorUtilitiesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AnchorUtilitiesConfig.class);
	}

	private BufferedImage loadIcon()
	{
		try
		{
			return ImageUtil.loadImageResource(getClass(), "icon.png");
		}
		catch (IllegalArgumentException e)
		{
			// Placeholder until a real 16x16 icon.png is added to resources.
			BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			g.setColor(new Color(28, 38, 52));
			g.fillOval(0, 0, 16, 16);
			g.setColor(new Color(207, 164, 76));
			g.drawLine(8, 2, 8, 13);
			g.drawArc(3, 7, 10, 7, 180, 180);
			g.dispose();
			return image;
		}
	}
}