package com.anchorutilities.service;

import com.anchorutilities.model.Submission;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.coords.WorldPoint;

/**
 * Works out who was "there" for a drop, and which of them are in the clan.
 *
 *  - Instanced content (raids, most boss instances): everyone in the instance.
 *  - Open world (e.g. GWD, Nex, wilderness bosses): players within NEARBY_TILES of you.
 *
 * Clan membership comes from the in-game clan member list, so it doesn't depend
 * on who has connected the plugin.
 */
@Singleton
public class PartyService
{
	/** How close another player must be, outside instances, to count as part of the kill. */
	private static final int NEARBY_TILES = 15;

	@Inject private Client client;

	/** Must be called on the client thread (game event handlers already are). */
	public List<Submission.PartyMember> currentParty()
	{
		List<Submission.PartyMember> party = new ArrayList<>();
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return party;
		}

		WorldView view = client.getTopLevelWorldView();
		boolean instanced = view.isInstance();
		WorldPoint here = local.getWorldLocation();

		for (Player player : view.players())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}
			if (!instanced && player.getWorldLocation().distanceTo(here) > NEARBY_TILES)
			{
				continue;
			}
			String name = clean(player.getName());
			boolean inClan = player == local || isClanMember(name);
			party.add(new Submission.PartyMember(name, inClan));
		}
		return party;
	}

	private boolean isClanMember(String name)
	{
		ClanSettings clan = client.getClanSettings();
		return clan != null && clan.findMember(name) != null;
	}

	private static String clean(String name)
	{
		return name.replace('\u00a0', ' ').trim();
	}
}