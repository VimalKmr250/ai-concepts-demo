package com.acme.procurement;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The approved hardware catalog (static demo data).
 */
@Component
public class Catalog {

	/**
	 * @param audience who the item is meant for - the agent uses it to pick between models
	 */
	public record Item(String sku, String name, String category, int priceEur, String specs, String audience,
			int leadTimeDays) {
	}

	private static final List<Item> ITEMS = List.of(
			// the audience text is what the model uses to choose, so it states the rule
			new Item("LAP-STD-14", "Lenovo ThinkPad T14 Gen 5", "laptop", 1350, "14in, Ryzen 7 PRO, 32 GB RAM, 1 TB SSD",
					"Default laptop for everyone, including replacements, unless the request states a special need", 5),
			new Item("LAP-LITE-13", "Dell Latitude 7350", "laptop", 1190,
					"13in, Core Ultra 7, 16 GB RAM, 512 GB SSD, 1.1 kg",
					"Only when the request says the person travels a lot", 5),
			new Item("LAP-DEV-16", "Dell Precision 5690", "laptop", 2450,
					"16in, Core Ultra 9, 64 GB RAM, 2 TB SSD, RTX 2000 Ada",
					"Only when the request says the person runs large builds or local AI models", 10),
			new Item("LAP-MAC-14", "MacBook Pro 14 M4 Pro", "laptop", 2300, "14in, M4 Pro, 48 GB RAM, 1 TB SSD",
					"Only when the request says the person is a designer or iOS developer", 7),
			new Item("MON-27-QHD", "Dell UltraSharp U2724D", "monitor", 420, "27in, QHD, USB-C 90 W", "Everyone", 3),
			new Item("DOCK-TB4", "Lenovo ThinkPad Thunderbolt 4 Dock", "dock", 260, "Thunderbolt 4, 2x DP, 100 W",
					"Everyone with a laptop", 3),
			new Item("HS-ANC", "Jabra Evolve2 65", "headset", 210, "Wireless, noise cancelling, Teams certified",
					"Everyone", 2));

	public List<Item> search(String category) {
		String wanted = category == null ? "" : category.strip().toLowerCase(Locale.ROOT).replaceAll("s$", "");
		return ITEMS.stream().filter(i -> wanted.isEmpty() || i.category().equals(wanted)).toList();
	}

	public List<String> skus() {
		return ITEMS.stream().map(Item::sku).toList();
	}

	public Optional<Item> find(String sku) {
		String wanted = sku == null ? "" : sku.strip();
		return ITEMS.stream().filter(i -> i.sku().equalsIgnoreCase(wanted)).findFirst();
	}

}
