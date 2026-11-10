package com.anchorutilities.events;

import com.anchorutilities.api.AnchorApiClient;
import com.anchorutilities.model.Submission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * One place for every kind of drop the clan cares about:
 *  - NPC kills (NpcLootReceived, plus Yama via ServerNpcLoot)
 *  - Chests and other non-NPC loot: raids, Barrows, clue caskets, etc. (LootReceived)
 *  - Pets (game chat messages)
 *
 * Loot items are submitted only when their GE value per item is at least
 * MIN_VALUE. Pets are always submitted.
 */
@Singleton
public class LootListener
{
	// ---------------------------------------------------------------- Rules

	/** Minimum GE value per item for loot to be submitted. */
	private static final long MIN_VALUE = 1_500_000L;

	/** How long after a kill or chest a pet message is still linked to it. */
	private static final long PET_SOURCE_WINDOW_MS = 10_000L;
	/** Ticks to wait before reporting a pet, so the matching loot event can arrive first. */
	private static final int PET_DELAY_TICKS = 2;

	@Inject private Client client;
	@Inject private ItemManager itemManager;
	@Inject private ChatMessageManager chatMessageManager;
	@Inject private AnchorApiClient api;

	private String lastSource;
	private long lastSourceAt;

	private PendingPet pendingPet;

	// ---------------------------------------------------------------- NPC kills

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		String source = npc == null || npc.getName() == null ? "Unknown" : npc.getName();
		handleLoot("NPC", source, event.getItems());
	}

	/** RuneLite publishes Yama's loot as ServerNpcLoot instead of NpcLootReceived. */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		NPCComposition composition = event.getComposition();
		if (composition == null || composition.getId() != NpcID.YAMA)
		{
			return;
		}
		handleLoot("NPC", composition.getName(), event.getItems());
	}

	// ---------------------------------------------------------------- Chests, raids, clues, etc.

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// NPC kills are already handled above, and PvP loot is not tracked.
		if (event.getType() == LootRecordType.NPC || event.getType() == LootRecordType.PLAYER)
		{
			return;
		}
		handleLoot(typeLabel(event.getType()), event.getName(), event.getItems());
	}

	// ---------------------------------------------------------------- Pets

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage()).replace('\u2019', '\'').toLowerCase(Locale.ROOT);
		if (!message.contains("funny feeling like you") && !message.contains("something weird sneaking into your backpack"))
		{
			return;
		}

		String kind;
		if (message.contains("would have been followed"))
		{
			kind = "duplicate";
		}
		else if (message.contains("backpack"))
		{
			kind = "new, in inventory";
		}
		else
		{
			kind = "new, following";
		}
		pendingPet = new PendingPet(kind, client.getTickCount());
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (pendingPet == null || client.getTickCount() - pendingPet.tick < PET_DELAY_TICKS)
		{
			return;
		}
		PendingPet pet = pendingPet;
		pendingPet = null;

		boolean recent = lastSource != null && System.currentTimeMillis() - lastSourceAt <= PET_SOURCE_WINDOW_MS;
		String source = recent ? lastSource : "Unknown source";
		// The pet's name isn't in the game message; it will be filled in by a later improvement.
		List<Submission.Item> items = Collections.singletonList(new Submission.Item("Unknown pet", 1, 0));
		send("pet", "Pet (" + pet.kind + ")", source, items, 0);
	}

	/** Type ::anchorpet in chat to simulate a pet drop for testing. */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"anchorpet".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		pendingPet = new PendingPet("test", client.getTickCount());
	}

	// ---------------------------------------------------------------- Shared handling

	private void handleLoot(String label, String source, Collection<ItemStack> stacks)
	{
		lastSource = source;
		lastSourceAt = System.currentTimeMillis();

		List<Submission.Item> items = new ArrayList<>();
		long totalValue = 0;
		for (ItemStack stack : stacks)
		{
			long unitValue = itemManager.getItemPrice(stack.getId());
			if (unitValue < MIN_VALUE)
			{
				continue; // below the clan's minimum value: not submitted
			}
			ItemComposition item = itemManager.getItemComposition(stack.getId());
			totalValue += unitValue * stack.getQuantity();
			items.add(new Submission.Item(item.getName(), stack.getQuantity(), unitValue));
		}
		if (items.isEmpty())
		{
			return; // nothing qualified; stay quiet
		}
		send("loot", label, source, items, totalValue);
	}

	/** The single exit point for every qualifying drop. */
	private void send(String type, String label, String source, List<Submission.Item> items, long totalValue)
	{
		String summary = label + " from " + source + ": " + describe(items)
				+ (totalValue > 0 ? " - Total " + gp(totalValue) : "");

		if (!api.hasConnectCode())
		{
			sendChat(summary + " (not submitted: no connect code set)");
			return;
		}

		Submission submission = new Submission();
		String rsn = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
		// RSNs can contain non-breaking spaces; store them as normal spaces.
		submission.rsn = rsn == null ? null : rsn.replace('\u00a0', ' ');
		submission.type = type;
		submission.source = source;
		submission.items = items;

		sendChat("Submitting " + summary);
		api.submit(submission, result ->
		{
			if (result.ok && result.submissionIds != null && !result.submissionIds.isEmpty())
			{
				sendChat("Submitted " + result.submissionIds.size() + " item(s) from " + source);
			}
			else if (result.ok)
			{
				sendChat("Not submitted: below the clan's minimum value");
			}
			else
			{
				sendChat("Submission failed: " + result.error);
			}
		});
	}

	private static String describe(List<Submission.Item> items)
	{
		List<String> parts = new ArrayList<>();
		for (Submission.Item item : items)
		{
			String part = item.name + " x" + item.quantity;
			if (item.unitValue > 0)
			{
				part += " (" + gp(item.unitValue * item.quantity) + ")";
			}
			parts.add(part);
		}
		return String.join(", ", parts);
	}

	private static String typeLabel(LootRecordType type)
	{
		switch (type)
		{
			case EVENT: return "Chest";
			case PICKPOCKET: return "Pickpocket";
			default: return "Loot";
		}
	}

	private static String gp(long value)
	{
		return String.format("%,d gp", value);
	}

	private void sendChat(String text)
	{
		// Never use the '|' character in chat text; OSRS hides everything before it.
		String message = new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("[Anchor] ")
				.append(ChatColorType.NORMAL)
				.append(text)
				.build();

		chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage(message)
				.build());
	}

	private static final class PendingPet
	{
		final String kind;
		final int tick;

		PendingPet(String kind, int tick)
		{
			this.kind = kind;
			this.tick = tick;
		}
	}
}