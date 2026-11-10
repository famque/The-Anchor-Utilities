package com.anchorutilities.model;

import java.util.ArrayList;
import java.util.List;

/** One drop sent to the Apps Script backend. Field names match what the script reads. */
public class Submission
{
	public String action = "submit";
	public String code;
	public String rsn;       // logged-in player name
	public String type;      // "loot" or "pet"
	public String source;    // boss / chest name
	public List<Item> items = new ArrayList<>();
	public List<String> party = new ArrayList<>();
	public String screenshot; // base64 jpeg, added later

	public static class Item
	{
		public String name;
		public int quantity;
		public long unitValue;

		public Item(String name, int quantity, long unitValue)
		{
			this.name = name;
			this.quantity = quantity;
			this.unitValue = unitValue;
		}
	}

	/** What the script sends back. */
	public static class Result
	{
		public boolean ok;
		public String error;
		public List<String> submissionIds;
	}
}