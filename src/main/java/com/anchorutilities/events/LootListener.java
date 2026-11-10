package com.anchorutilities.events;

import com.anchorutilities.api.AnchorApiClient;
import com.anchorutilities.model.Submission;
import com.anchorutilities.service.PartyService;
import com.anchorutilities.service.ScreenshotService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *  - Pets (game chat messages; the name comes from the clan broadcast or the boss)
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
	/** Ticks to wait before reporting a pet, so the loot event and clan broadcast can arrive first. */
	private static final int PET_DELAY_TICKS = 3;

	/**
	 * Clan broadcast, e.g.
	 * "Godly has a funny feeling like he's being followed: Little nightmare at 1,234 kills."
	 * "Godly feels something weird sneaking into his backpack: Vorki at 500 kills."
	 */
	private static final Pattern PET_BROADCAST = Pattern.compile(
			"^(?<player>[\\w\\s-]+?) (?:has a funny feeling like .+?(?:being followed|would have been followed)"
					+ "|feels something weird sneaking into .+? backpack): (?<pet>.+?)(?: at [\\d,]+ .*| from .*)?[.!]*$",
			Pattern.CASE_INSENSITIVE);

	/** Boss or raid name -> its pet. Used when no clan broadcast names the pet. */
	private static final Map<String, String> BOSS_PETS = Map.ofEntries(
			Map.entry("abyssal sire", "Abyssal orphan"),
			Map.entry("alchemical hydra", "Ikkle hydra"),
			Map.entry("amoxliatl", "Moxi"),
			Map.entry("araxxor", "Nid"),
			Map.entry("artio", "Callisto cub"),
			Map.entry("callisto", "Callisto cub"),
			Map.entry("calvarion", "Vet'ion jr."),
			Map.entry("vetion", "Vet'ion jr."),
			Map.entry("spindel", "Venenatis spiderling"),
			Map.entry("venenatis", "Venenatis spiderling"),
			Map.entry("cerberus", "Hellpuppy"),
			Map.entry("chaos elemental", "Pet chaos elemental"),
			Map.entry("chaos fanatic", "Pet chaos elemental"),
			Map.entry("commander zilyana", "Pet zilyana"),
			Map.entry("corporeal beast", "Pet dark core"),
			Map.entry("dagannoth prime", "Pet dagannoth prime"),
			Map.entry("dagannoth rex", "Pet dagannoth rex"),
			Map.entry("dagannoth supreme", "Pet dagannoth supreme"),
			Map.entry("doom of mokhaiotl", "Dom"),
			Map.entry("duke sucellus", "Baron"),
			Map.entry("general graardor", "Pet general graardor"),
			Map.entry("giant mole", "Baby mole"),
			Map.entry("grotesque guardians", "Noon"),
			Map.entry("dusk", "Noon"),
			Map.entry("kalphite queen", "Kalphite princess"),
			Map.entry("king black dragon", "Prince black dragon"),
			Map.entry("kraken", "Pet kraken"),
			Map.entry("kreearra", "Pet kree'arra"),
			Map.entry("kril tsutsaroth", "Pet k'ril tsutsaroth"),
			Map.entry("nex", "Nexling"),
			Map.entry("the nightmare", "Little nightmare"),
			Map.entry("phosanis nightmare", "Little nightmare"),
			Map.entry("phantom muspah", "Muphin"),
			Map.entry("sarachnis", "Sraracha"),
			Map.entry("scorpia", "Scorpia's offspring"),
			Map.entry("scurrius", "Scurry"),
			Map.entry("skotizo", "Skotos"),
			Map.entry("sol heredit", "Smol heredit"),
			Map.entry("thermonuclear smoke devil", "Pet smoke devil"),
			Map.entry("the hueycoatl", "Huberte"),
			Map.entry("the leviathan", "Lil'viathan"),
			Map.entry("the royal titans", "Bran"),
			Map.entry("the whisperer", "Wisp"),
			Map.entry("vardorvis", "Butch"),
			Map.entry("vorkath", "Vorki"),
			Map.entry("yama", "Yami"),
			Map.entry("zulrah", "Pet snakeling"),
			Map.entry("tztok jad", "TzRek-Jad"),
			Map.entry("tzkal zuk", "Jal-nib-rek"),
			Map.entry("the gauntlet", "Youngllef"),
			Map.entry("the corrupted gauntlet", "Youngllef"),
			Map.entry("crystalline hunllef", "Youngllef"),
			Map.entry("corrupted hunllef", "Youngllef"),
			Map.entry("tempoross", "Tiny tempor"),
			Map.entry("wintertodt", "Phoenix"),
			Map.entry("zalcano", "Smolcano")
	);

	@Inject private Client client;
	@Inject private ItemManager itemManager;
	@Inject private ChatMessageManager chatMessageManager;
	@Inject private AnchorApiClient api;
	@Inject private ScreenshotService screenshots;
	@Inject private PartyService parties;

	private String lastSource;
	private long lastSourceAt;

	private PendingPet pendingPet;

	private String broadcastPetName;
	private long broadcastPetAt;

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
		if (isClanBroadcast(event.getType()))
		{
			readPetBroadcast(event.getMessage());
			return;
		}
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

		long now = System.currentTimeMillis();
		boolean recentSource = lastSource != null && now - lastSourceAt <= PET_SOURCE_WINDOW_MS;
		String source = recentSource ? lastSource : "Unknown source";

		// 1. Clan broadcast names the pet.  2. Otherwise use the boss's pet.  3. Otherwise unknown.
		String petName = null;
		if (broadcastPetName != null && now - broadcastPetAt <= PET_SOURCE_WINDOW_MS)
		{
			petName = broadcastPetName;
		}
		else if (recentSource)
		{
			petName = petForSource(source);
		}
		broadcastPetName = null;

		if (petName == null)
		{
			petName = "Unknown pet";
		}
		List<Submission.Item> items = Collections.singletonList(new Submission.Item(petName, 1, 0));
		send("pet", "Pet (" + pet.kind + ")", source, items, 0);
	}

	/**
	 * Testing:
	 *   ::anchorpet          simulates a pet; the name comes from the last boss you killed
	 *   ::anchorpet Olmlet   also simulates a clan broadcast naming that pet
	 */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"anchorpet".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		pendingPet = new PendingPet("test", client.getTickCount());

		String[] args = event.getArguments();
		if (args != null && args.length > 0 && client.getLocalPlayer() != null)
		{
			String pet = String.join(" ", args);
			readPetBroadcast(client.getLocalPlayer().getName()
					+ " has a funny feeling like they're being followed: " + pet + " at 100 kills.");
		}
	}

	/** Remembers the pet named in a clan broadcast, if the broadcast is about you. */
	private void readPetBroadcast(String rawMessage)
	{
		String message = Text.removeTags(rawMessage).replace('\u00a0', ' ').replace('\u2019', '\'').trim();
		Matcher matcher = PET_BROADCAST.matcher(message);
		if (!matcher.matches() || !isLocalPlayer(matcher.group("player")))
		{
			return;
		}
		broadcastPetName = matcher.group("pet").trim();
		broadcastPetAt = System.currentTimeMillis();
	}

	private boolean isLocalPlayer(String name)
	{
		if (name == null || client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return false;
		}
		String local = client.getLocalPlayer().getName().replace('\u00a0', ' ').trim();
		return local.equalsIgnoreCase(name.trim());
	}

	private static boolean isClanBroadcast(ChatMessageType type)
	{
		return type == ChatMessageType.CLAN_MESSAGE
				|| type == ChatMessageType.CLAN_GUEST_MESSAGE
				|| type == ChatMessageType.CLAN_GIM_MESSAGE
				|| type == ChatMessageType.FRIENDSCHATNOTIFICATION;
	}

	/** Looks up the pet for a boss or raid name. */
	private static String petForSource(String source)
	{
		String key = source.toLowerCase(Locale.ROOT)
				.replace("'", "").replace('\u2019', ' ').replace('-', ' ').replace(':', ' ')
				.trim().replaceAll("\\s+", " ");
		if (key.startsWith("chambers of xeric"))
		{
			return "Olmlet";
		}
		if (key.startsWith("theatre of blood"))
		{
			return "Lil' zik";
		}
		if (key.startsWith("tombs of amascut"))
		{
			return "Tumeken's guardian";
		}
		return BOSS_PETS.get(key);
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

		List<Submission.PartyMember> party = "loot".equals(type) ? parties.currentParty() : new ArrayList<>();
		if (!party.isEmpty())
		{
			long clan = party.stream().filter(m -> m.inClan).count();
			summary += " [" + clan + " clan / " + party.size() + " total]";
		}

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
		submission.party = party;
		submission.items = items;

		sendChat("Submitting " + summary);
		// Capture the screen first, then send the drop with the screenshot attached.
		screenshots.capture(base64 ->
		{
			submission.screenshot = base64;
			api.submit(submission, result ->
			{
				if (result.ok && result.submissionIds != null && !result.submissionIds.isEmpty())
				{
					sendChat("Submitted " + result.submissionIds.size() + " item(s) from " + source
							+ (base64 == null ? " (no screenshot)" : ""));
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