package com.acme.procurement;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Department hardware budgets and purchase requests - in memory, reset on restart (demo data).
 */
@Component
public class Ledger {

	/** Single purchase requests above this need a finance sign-off before the order is placed. */
	public static final int FINANCE_APPROVAL_LIMIT_EUR = 2000;

	/** Remaining hardware budget per department this quarter, in EUR. */
	private static final Map<String, Integer> INITIAL_BUDGETS = Map.of("Engineering", 8000, "Finance", 3000, "Sales",
			4500, "Marketing", 2500, "HR", 1500, "Support", 2000, "Operations", 3500);

	public record PurchaseRequest(String number, String sku, String item, int quantity, int unitPriceEur,
			int totalEur, String department, String requestedFor, String justification, String status,
			LocalDate expectedDelivery, Instant createdAt, String taskId) {

		/** Plain map for the A2A data part: strings and numbers only, so any JSON library can write it. */
		public Map<String, Object> toData() {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("number", this.number);
			data.put("sku", this.sku);
			data.put("item", this.item);
			data.put("quantity", this.quantity);
			data.put("unitPriceEur", this.unitPriceEur);
			data.put("totalEur", this.totalEur);
			data.put("department", this.department);
			data.put("requestedFor", this.requestedFor);
			data.put("justification", this.justification);
			data.put("status", this.status);
			data.put("expectedDelivery", this.expectedDelivery.toString());
			return data;
		}
	}

	private final Map<String, Integer> remaining = new ConcurrentHashMap<>();

	private final Map<String, PurchaseRequest> requests = new ConcurrentHashMap<>();

	private final AtomicInteger sequence = new AtomicInteger(3101);

	private final Clock clock;

	public Ledger() {
		this(Clock.systemDefaultZone());
	}

	Ledger(Clock clock) {
		this.clock = clock;
		INITIAL_BUDGETS.forEach((department, budget) -> this.remaining.put(key(department), budget));
	}

	private static String key(String department) {
		return department == null ? "" : department.strip().toLowerCase(Locale.ROOT);
	}

	public Optional<Integer> remaining(String department) {
		return Optional.ofNullable(this.remaining.get(key(department)));
	}

	public List<String> departments() {
		return INITIAL_BUDGETS.keySet().stream().sorted().toList();
	}

	/** Departments mentioned by name in a text, e.g. "Engineering department pays" or "for HR". */
	public List<String> departmentsNamedIn(String text) {
		return departments().stream()
			.filter(d -> Pattern.compile("\\b" + d + "\\b", Pattern.CASE_INSENSITIVE).matcher(text).find())
			.toList();
	}

	/**
	 * Reserves the budget and records the request. The agent checks the budget first; this
	 * re-checks atomically so two concurrent requests cannot overspend.
	 */
	public synchronized PurchaseRequest raise(Catalog.Item item, int quantity, String department, String requestedFor,
			String justification, String taskId) {
		int total = item.priceEur() * quantity;
		int left = remaining(department)
			.orElseThrow(() -> new IllegalArgumentException("Unknown department: " + department));
		if (total > left) {
			throw new IllegalStateException(
					"Budget exceeded: " + department + " has " + left + " EUR left, the request needs " + total);
		}
		this.remaining.put(key(department), left - total);
		String status = total > FINANCE_APPROVAL_LIMIT_EUR ? "PENDING_FINANCE_APPROVAL" : "APPROVED";
		PurchaseRequest pr = new PurchaseRequest("PR-" + this.sequence.getAndIncrement(), item.sku(), item.name(),
				quantity, item.priceEur(), total, department.strip(), requestedFor, justification, status,
				LocalDate.now(this.clock).plusDays(item.leadTimeDays()), this.clock.instant(), taskId);
		this.requests.put(pr.number(), pr);
		return pr;
	}

	public List<PurchaseRequest> forTask(String taskId) {
		return this.requests.values()
			.stream()
			.filter(pr -> taskId != null && taskId.equals(pr.taskId()))
			.sorted(Comparator.comparing(PurchaseRequest::number))
			.toList();
	}

}
