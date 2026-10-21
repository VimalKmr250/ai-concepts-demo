package com.acme.procurement;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;

import com.acme.procurement.ProcurementTools.BudgetCheck;
import com.acme.procurement.ProcurementTools.PurchaseOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcurementToolsTest {

	private final Ledger ledger = new Ledger(Clock.fixed(Instant.parse("2026-10-21T09:00:00Z"), ZoneOffset.UTC));

	private final ProcurementTools tools = new ProcurementTools(new Catalog(), this.ledger);

	private final List<String> progress = new ArrayList<>();

	private final List<BudgetCheck> checks = new ArrayList<>();

	private ToolContext context(String taskId) {
		Consumer<String> onProgress = this.progress::add;
		return new ToolContext(Map.of(ProcurementTools.TASK_ID, taskId, ProcurementTools.PROGRESS, onProgress,
				ProcurementTools.SEARCHED, new HashSet<String>(), ProcurementTools.CHECKS, this.checks));
	}

	@Test
	void searchesOneCategoryAndAcceptsPlurals() {
		assertThat(this.tools.searchCatalog("Laptops", null).items()).extracting(Catalog.Item::sku)
			.containsExactly("LAP-STD-14", "LAP-LITE-13", "LAP-DEV-16", "LAP-MAC-14");
		assertThat(this.tools.searchCatalog("headset", context("t1")).items()).singleElement()
			.satisfies(i -> assertThat(i.priceEur()).isEqualTo(210));
		assertThat(this.progress).containsExactly("Searching the catalog: headset");
	}

	@Test
	void aRepeatedSearchTellsTheModelToMoveOn() {
		// live run: the model searched the laptop catalog ten times in a row
		ToolContext task = context("t1");

		assertThat(this.tools.searchCatalog("laptop", task).next()).startsWith("Choose one SKU");
		assertThat(this.tools.searchCatalog("laptop", task).next()).startsWith("You already have this list");
	}

	@Test
	void raisingAPurchaseRequestChecksAndReservesTheBudget() {
		PurchaseOutcome outcome = this.tools.createPurchaseRequest("LAP-STD-14", 1, "Engineering", "mgarcia",
				"LAP-1502 is broken and out of warranty", context("task-1"));

		assertThat(outcome.raised()).isTrue();
		assertThat(outcome.purchaseRequest().number()).isEqualTo("PR-3101");
		assertThat(outcome.purchaseRequest().status()).isEqualTo("APPROVED");
		assertThat(outcome.purchaseRequest().expectedDelivery()).isEqualTo(LocalDate.of(2026, 10, 26));
		assertThat(this.ledger.remaining("engineering")).contains(8000 - 1350);
		assertThat(this.ledger.forTask("task-1")).containsExactly(outcome.purchaseRequest());
		assertThat(this.progress).containsExactly("Checking the Engineering budget for 1 x LAP-STD-14 = 1350 EUR",
				"Raising a purchase request for 1 x Lenovo ThinkPad T14 Gen 5", "Raised PR-3101 (APPROVED)");
	}

	@Test
	void expensiveRequestsWaitForFinance() {
		PurchaseOutcome outcome = this.tools.createPurchaseRequest("LAP-DEV-16", 1, "Engineering", "jdoe",
				"Runs local AI models", null);

		assertThat(outcome.purchaseRequest().status()).isEqualTo("PENDING_FINANCE_APPROVAL");
		assertThat(outcome.purchaseRequest().toData()).containsEntry("totalEur", 2450)
			.containsEntry("expectedDelivery", "2026-10-31");
	}

	@Test
	void theTotalIsComputedAndTheBudgetCheckedInCode() {
		// live runs: the model passed 3000 EUR for 3 x 1350, and sometimes skipped the check
		PurchaseOutcome outcome = this.tools.createPurchaseRequest("LAP-STD-14", 3, "HR", "new hires", "Onboarding",
				context("t2"));

		assertThat(outcome.raised()).isFalse();
		assertThat(outcome.budget().totalEur()).isEqualTo(4050);
		assertThat(outcome.note()).isEqualTo("Not raised: the budget has 1500 EUR left and the request needs 4050 EUR.");
		assertThat(this.ledger.remaining("hr")).contains(1500);
		assertThat(this.checks).containsExactly(outcome.budget());
	}

	@Test
	void anUnknownDepartmentRaisesNothing() {
		PurchaseOutcome outcome = this.tools.createPurchaseRequest("LAP-STD-14", 1, "tchen", "tchen", "x", null);

		assertThat(outcome.raised()).isFalse();
		assertThat(outcome.note()).contains("Unknown department 'tchen'", "Marketing");
	}

	@Test
	void unknownSkuErrorTellsTheModelTheValidOnes() {
		// the model confused the old asset tag with a SKU in a live run
		assertThatThrownBy(() -> this.tools.createPurchaseRequest("LAP-1502", 1, "Engineering", "mgarcia", "x", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Valid SKUs: LAP-STD-14");
	}

	@Test
	void findsDepartmentsNamedInARequest() {
		assertThat(this.ledger.departmentsNamedIn("Order for Miguel (mgarcia), Engineering department pays"))
			.containsExactly("Engineering");
		assertThat(this.ledger.departmentsNamedIn("Buy 3 headsets for the support team")).containsExactly("Support");
		assertThat(this.ledger.departmentsNamedIn("Order a laptop for tchen")).isEmpty();
	}

}
