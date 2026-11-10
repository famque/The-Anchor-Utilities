package com.anchorutilities.events;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Detects loot from NPC kills. For now every drop is announced in chat;
 * qualifying rules and submission come later.
 */
@Singleton
public class LootListener
{
	@Inject private ItemManager itemManager;
	@Inject private ChatMessageManager chatMessageManager;

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		String source = npc == null || npc.getName() == null ? "Unknown" : npc.getName();

		List<String> parts = new ArrayList<>();
		long totalValue = 0;
		for (ItemStack stack : event.getItems())
		{
			ItemComposition item = itemManager.getItemComposition(stack.getId());
			long value = (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
			totalValue += value;
			parts.add(item.getName() + " x" + stack.getQuantity() + " (" + String.format("%,d", value) + " gp)");
		}
		if (parts.isEmpty())
		{
			return;
		}

		sendChat("Drop from " + source + ": " + String.join(", ", parts)
				+ " - Total " + String.format("%,d", totalValue) + " gp");
	}

	private void sendChat(String text)
	{
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
}