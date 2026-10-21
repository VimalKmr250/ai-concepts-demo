package com.acme.ai.helpdesk;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.acme.ai.helpdesk.HelpdeskRepository.Asset;
import com.acme.ai.helpdesk.HelpdeskRepository.Employee;
import com.acme.ai.helpdesk.HelpdeskRepository.Ticket;

/**
 * Business operations the helpdesk agent can call. The {@code @Tool} descriptions are what the
 * model reads to decide which tool to use, so they are written for the model, not for humans.
 */
@Component
public class HelpdeskTools {

	private static final Set<String> CATEGORIES = Set.of("HARDWARE", "SOFTWARE", "NETWORK", "ACCESS");

	private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");

	private final HelpdeskRepository repository;

	public HelpdeskTools(HelpdeskRepository repository) {
		this.repository = repository;
	}

	@Tool(description = "Look up an Acme employee by username (e.g. 'jdoe'). Returns name, email, department, location and manager.")
	public Employee lookupEmployee(@ToolParam(description = "Employee username, lowercase") String username) {
		return this.repository.findEmployee(username)
			.orElseThrow(() -> new IllegalArgumentException("No employee with username '" + username + "'"));
	}

	@Tool(description = "Find employees whose name or username contains the given text. Use when the user gives a name instead of a username.")
	public List<Employee> searchEmployees(@ToolParam(description = "Part of the person's name") String name) {
		return this.repository.searchEmployees(name);
	}

	@Tool(description = "List the IT assets (laptops, monitors) assigned to an employee, including warranty end date and whether it is still in warranty.")
	public List<AssetView> listAssets(@ToolParam(description = "Employee username") String username) {
		return this.repository.assetsOf(username).stream().map(AssetView::of).toList();
	}

	@Tool(description = "List the helpdesk tickets raised by an employee, newest first.")
	public List<Ticket> listTickets(@ToolParam(description = "Employee username") String username) {
		return this.repository.tickets(username.trim().toLowerCase(Locale.ROOT));
	}

	@Tool(description = "Create a new helpdesk ticket for an employee. Returns the created ticket with its number.")
	public Ticket createTicket(@ToolParam(description = "Employee username") String username,
			@ToolParam(description = "Asset tag of the affected device, e.g. LAP-2041. Empty if no device is involved.",
					required = false) String assetTag,
			@ToolParam(description = "One of HARDWARE, SOFTWARE, NETWORK, ACCESS") String category,
			@ToolParam(description = "One of LOW, MEDIUM, HIGH. Use HIGH when the employee cannot work.") String priority,
			@ToolParam(description = "One-sentence summary of the problem") String summary) {
		String user = lookupEmployee(username).username();
		String cat = normalize(category, CATEGORIES, "HARDWARE");
		String prio = normalize(priority, PRIORITIES, "MEDIUM");
		String tag = assetTag == null || assetTag.isBlank() ? null : assetTag.trim().toUpperCase(Locale.ROOT);
		Ticket ticket = this.repository.createTicket(user, tag, cat, prio, summary);
		this.repository.audit("helpdesk-agent", "CREATE_TICKET", ticket.ticketNo() + " for " + user);
		return ticket;
	}

	@Tool(description = "Reset an employee's password and email them a one-time reset link. Sensitive: requires human approval before it runs.")
	@RequiresApproval("Password resets can lock people out or enable account takeover")
	public String resetPassword(@ToolParam(description = "Employee username") String username,
			@ToolParam(description = "Why the reset is needed") String reason) {
		Employee employee = lookupEmployee(username);
		this.repository.audit("helpdesk-agent", "RESET_PASSWORD", employee.username() + ": " + reason);
		return "Password reset completed. A one-time reset link was sent to " + employee.email()
				+ " and expires in 30 minutes.";
	}

	@Tool(description = "Assign a spare loaner laptop to an employee whose own laptop is broken. Sensitive: requires human approval before it runs.")
	@RequiresApproval("Hands out company hardware")
	public String assignLoanerLaptop(@ToolParam(description = "Employee username") String username) {
		Employee employee = lookupEmployee(username);
		Asset spare = this.repository.firstSpareLaptop()
			.orElseThrow(() -> new IllegalStateException("No spare laptops left in stock"));
		this.repository.assignAsset(spare.assetTag(), employee.username());
		this.repository.audit("helpdesk-agent", "ASSIGN_LOANER", spare.assetTag() + " -> " + employee.username());
		return "Loaner laptop %s (%s) assigned to %s; pick it up at the %s IT desk.".formatted(spare.assetTag(),
				spare.model(), employee.fullName(), employee.location());
	}

	private static String normalize(String value, Set<String> allowed, String fallback) {
		String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		return allowed.contains(v) ? v : fallback;
	}

	public record AssetView(String assetTag, String type, String model, String warrantyUntil, boolean inWarranty,
			String status) {

		static AssetView of(Asset a) {
			return new AssetView(a.assetTag(), a.type(), a.model(), a.warrantyUntil().toString(), a.inWarranty(),
					a.status());
		}

	}

}
