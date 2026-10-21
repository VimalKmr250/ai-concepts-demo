package com.acme.procurement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.procurement.Ledger.PurchaseRequest;
import com.acme.procurement.ProcurementTools.BudgetCheck;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text summary other agents read must not contain facts the ledger does not back.
 */
class SummaryTest {

	private static final BudgetCheck HR_TOO_SMALL = new BudgetCheck("HR", "LAP-STD-14", "Lenovo ThinkPad T14 Gen 5", 3,
			4050, 1500, false);

	private static final BudgetCheck COVERED = new BudgetCheck("Engineering", "LAP-DEV-16", "Dell Precision 5690", 1,
			2450, 8000, true);

	@Test
	void aPurchaseRequestIsDescribedFromTheLedger() {
		PurchaseRequest pr = new PurchaseRequest("PR-3102", "HS-ANC", "Jabra Evolve2 65", 3, 210, 630, "Support",
				"support team", "noise", "APPROVED", LocalDate.of(2026, 10, 24), Instant.now(), "t1");

		// live run: the model wrote "Total Price: 1350 EUR" for this request
		assertThat(ProcurementAgentExecutor.summary("Item: Jabra Evolve2 65, Total Price: 1350 EUR.", List.of(pr),
				List.of()))
			.isEqualTo("Raised PR-3102: 3 x Jabra Evolve2 65 (HS-ANC) for 630 EUR, paid by Support. "
					+ "Status APPROVED; expected delivery 2026-10-24.");
	}

	@Test
	void aRefusalIsExplainedFromTheBudgetCheck() {
		assertThat(ProcurementAgentExecutor.summary("Sorry!", List.of(), List.of(HR_TOO_SMALL)))
			.isEqualTo("No purchase request was raised: the HR budget has 1500 EUR left this quarter, "
					+ "and 3 x Lenovo ThinkPad T14 Gen 5 costs 4050 EUR.");
	}

	@Test
	void claimedPurchaseRequestsThatDoNotExistAreDiscarded() {
		// live runs: nothing was raised, yet the model announced these
		for (String reply : List.of("Budget covers this. PR-7892 pending approval.",
				"The budget is covered. PR number 8873 has status Pending Finance Approval.",
				"PR number 2023PR12345 has status Approved.")) {
			assertThat(ProcurementAgentExecutor.summary(reply, List.of(), List.of(COVERED)))
				.isEqualTo("No purchase request was raised.");
		}
	}

	@Test
	void anHonestReplyIsKeptAsALastResort() {
		String reply = "Which laptop do you mean? The catalog has four models.";

		assertThat(ProcurementAgentExecutor.summary(reply, List.of(), List.of())).isEqualTo(reply);
	}

}
