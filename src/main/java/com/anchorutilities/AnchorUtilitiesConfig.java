package com.anchorutilities;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(AnchorUtilitiesConfig.GROUP)
public interface AnchorUtilitiesConfig extends Config
{
	String GROUP = "anchorutilities";

	@ConfigSection(name = "Connection", description = "Link the plugin to your Discord account", position = 0)
	String CONNECTION = "connection";

	@ConfigItem(
		keyName = "connectCode",
		name = "Connect code",
		description = "Run /connect in The Anchor Discord and paste the code here",
		section = CONNECTION,
		position = 0,
		secret = true
	)
	default String connectCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "autoSubmit",
		name = "Auto-submit drops",
		description = "Automatically submit qualifying loot and pets with a screenshot",
		section = CONNECTION,
		position = 1
	)
	default boolean autoSubmit()
	{
		return true;
	}
}