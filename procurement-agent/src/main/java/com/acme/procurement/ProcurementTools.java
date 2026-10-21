package com.acme.procurement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.acme.procurement.Catalog.Item;
import com.acme.procurement.Ledger.PurchaseRequest;

/**
 * The procurement agent's private tools. Callers over A2A never see these - they only see the
 * agent card's skills and the task the agent returns.
 * <p>
 * The model chooses <i>what</i> to buy; the rules are enforced in code. {@link #createPurchaseRequest}
 * computes the total and checks the budget itself, so a model can neither skip the check nor get
 * the arithmetic wrong (a 4B model did both in live runs when they were separate steps). Each tool
 * reports progress through the {@link ToolContext}; the executor turns that into A2A task status
 * updates.
 */
@Component
public class ProcurementTools {

	/** ToolContext key for the A2A task id (links purchase requests to the task that raised them). */
	public static final String TASK_ID = "taskId";

	/** ToolContext key for a {@code Consumer<String>} that publishes a progress update. */
	public static final String PROGRESS = "progress";

	/** ToolContext key for a mutable {@code Set<String>} of categories already searched in this task. */
	public static final String SEARCHED = "searched";

	/** ToolContext key for a mutable {@code List<BudgetCheck>}: the executor explains a refusal from it. */
	public static final String CHECKS = "checks";

	private final Catalog catalog;

	private final Ledger ledger;

	public ProcurementTools(Catalog catalog, Ledger ledger) {
		this.catalog = catalog;
		this.ledger = ledger;
	}

	public record CatalogResult(List<Item> items, String next) {
	}

	/**
	 * @param remainingEur null when the department is unknown
	 */
	public record BudgetCheck(String department, String sku, String item, int quantity, int totalEur,
			Integer remainingEur, boolean covered) {
	}

	/**
	 * @param purchaseRequest null when nothing was raised
	 * @param note what happened, in words the model can repeat
	 */
	public record PurchaseOutcome(boolean raised, PurchaseRequest purchaseRequest, BudgetCheck budget, String note) {
	}

	@Tool(description = "List the approved hardware in a category. Returns SKU, name, unit price in EUR, specs, "
			+ "who the item is meant for (audience) and lead time in days.")
	public CatalogResult searchCatalog(@ToolParam(description = "One of: laptop, monitor, dock, headset") String category,
			ToolContext context) {
		List<Item> items = this.catalog.search(category);
		// a small model sometimes searches again and again; tell it plainly to move on
		boolean repeat = !searched(context).add(String.valueOf(category).toLowerCase(Locale.ROOT));
		report(context, repeat ? "Catalog already searched: " + category : "Searching the catalog: " + category);
		return new CatalogResult(items, (repeat ? "You already have this list. " : "")
				+ "Choose one SKU from these items now and call createPurchaseRequest with it. Do not search again.");
	}

	@Tool(description = "Raise a purchase request for a catalog item. Checks the paying department's quarterly "
			+ "budget first and refuses if it does not cover the total. Returns what happened.")
	public PurchaseOutcome createPurchaseRequest(@ToolParam(description = "SKU from searchCatalog") String sku,
			@ToolParam(description = "How many units") int quantity,
			@ToolParam(description = "The paying department") String department,
			@ToolParam(description = "Username or name of the person the hardware is for") String requestedFor,
			@ToolParam(description = "One sentence: why it is needed") String justification, ToolContext context) {
		Item item = item(sku);
		if (quantity < 1 || quantity > 10) {
			throw new IllegalArgumentException("Quantity must be between 1 and 10");
		}
		BudgetCheck budget = checkBudget(department, item, quantity, context);
		if (budget.remainingEur() == null) {
			return new PurchaseOutcome(false, null, budget, "Unknown department '" + department
					+ "'. Known departments: " + String.join(", ", this.ledger.departments()));
		}
		if (!budget.covered()) {
			report(context, "Refused: the " + department + " budget does not cover it");
			return new PurchaseOutcome(false, null, budget,
					"Not raised: the budget has %d EUR left and the request needs %d EUR.".formatted(budget.remainingEur(),
							budget.totalEur()));
		}
		report(context, "Raising a purchase request for " + quantity + " x " + item.name());
		PurchaseRequest pr = this.ledger.raise(item, quantity, department, requestedFor, justification,
				taskId(context));
		report(context, "Raised " + pr.number() + " (" + pr.status() + ")");
		return new PurchaseOutcome(true, pr, budget, "Raised " + pr.number() + " with status " + pr.status() + ".");
	}

	private BudgetCheck checkBudget(String department, Item item, int quantity, ToolContext context) {
		int total = item.priceEur() * quantity;
		report(context, "Checking the %s budget for %d x %s = %d EUR".formatted(department, quantity, item.sku(), total));
		Integer left = this.ledger.remaining(department).orElse(null);
		BudgetCheck check = new BudgetCheck(department, item.sku(), item.name(), quantity, total, left,
				left != null && total <= left);
		checks(context).add(check);
		return check;
	}

	private Item item(String sku) {
		// the error goes back to the model, so tell it how to recover
		return this.catalog.find(sku)
			.orElseThrow(() -> new IllegalArgumentException(
					"Unknown SKU: " + sku + ". Valid SKUs: " + String.join(", ", this.catalog.skus())));
	}

	@SuppressWarnings("unchecked")
	private static List<BudgetCheck> checks(ToolContext context) {
		Object value = context == null ? null : context.getContext().get(CHECKS);
		return value instanceof List<?> list ? (List<BudgetCheck>) list : new ArrayList<>();
	}

	@SuppressWarnings("unchecked")
	private static Set<String> searched(ToolContext context) {
		Object value = context == null ? null : context.getContext().get(SEARCHED);
		return value instanceof Set<?> set ? (Set<String>) set : new HashSet<>();
	}

	@SuppressWarnings("unchecked")
	private static void report(ToolContext context, String update) {
		Map<String, Object> values = context == null ? Map.of() : context.getContext();
		if (values.get(PROGRESS) instanceof Consumer<?> progress) {
			((Consumer<String>) progress).accept(update);
		}
	}

	private static String taskId(ToolContext context) {
		return context == null ? null : (String) context.getContext().get(TASK_ID);
	}

}
