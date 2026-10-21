package com.acme.ai.helpdesk;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain JDBC access to the helpdesk tables seeded by Flyway (V2__helpdesk_seed.sql).
 */
@Repository
public class HelpdeskRepository {

	private final JdbcClient jdbc;

	public HelpdeskRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Employee> findEmployee(String username) {
		return this.jdbc.sql("SELECT username, full_name, email, department, location, manager FROM employee WHERE username = ?")
			.param(username.trim().toLowerCase())
			.query(Employee.class)
			.optional();
	}

	public List<Employee> searchEmployees(String nameFragment) {
		return this.jdbc.sql("""
				SELECT username, full_name, email, department, location, manager FROM employee
				WHERE lower(full_name) LIKE ? OR username LIKE ? ORDER BY username""")
			.param("%" + nameFragment.trim().toLowerCase() + "%")
			.param("%" + nameFragment.trim().toLowerCase() + "%")
			.query(Employee.class)
			.list();
	}

	public List<Asset> assetsOf(String username) {
		return this.jdbc.sql("""
				SELECT asset_tag, type, model, assigned_to, purchased_on, warranty_until, status
				FROM asset WHERE assigned_to = ? ORDER BY asset_tag""")
			.param(username.trim().toLowerCase())
			.query(Asset.class)
			.list();
	}

	public Optional<Asset> firstSpareLaptop() {
		return this.jdbc.sql("""
				SELECT asset_tag, type, model, assigned_to, purchased_on, warranty_until, status
				FROM asset WHERE type = 'LAPTOP' AND status = 'SPARE' ORDER BY asset_tag LIMIT 1""")
			.query(Asset.class)
			.optional();
	}

	@Transactional
	public void assignAsset(String assetTag, String username) {
		this.jdbc.sql("UPDATE asset SET assigned_to = ?, status = 'LOANED' WHERE asset_tag = ?")
			.param(username)
			.param(assetTag)
			.update();
	}

	public List<Ticket> tickets(String username) {
		return this.jdbc.sql("""
				SELECT ticket_no, username, asset_tag, category, priority, status, summary, created_at
				FROM ticket WHERE (CAST(? AS VARCHAR) IS NULL OR username = ?) ORDER BY created_at DESC""")
			.param(username)
			.param(username)
			.query(Ticket.class)
			.list();
	}

	@Transactional
	public Ticket createTicket(String username, String assetTag, String category, String priority, String summary) {
		String ticketNo = "INC-" + this.jdbc.sql("SELECT nextval('ticket_number_seq')").query(Long.class).single();
		this.jdbc.sql("""
				INSERT INTO ticket (ticket_no, username, asset_tag, category, priority, status, summary)
				VALUES (?, ?, ?, ?, ?, 'OPEN', ?)""")
			.params(ticketNo, username, assetTag, category, priority, summary)
			.update();
		return tickets(username).stream().filter(t -> t.ticketNo().equals(ticketNo)).findFirst().orElseThrow();
	}

	public void audit(String actor, String action, String details) {
		this.jdbc.sql("INSERT INTO audit_log (actor, action, details) VALUES (?, ?, ?)")
			.params(actor, action, details)
			.update();
	}

	public List<AuditEntry> auditLog() {
		return this.jdbc.sql("SELECT actor, action, details, created_at FROM audit_log ORDER BY id DESC LIMIT 50")
			.query(AuditEntry.class)
			.list();
	}

	public record Employee(String username, String fullName, String email, String department, String location,
			String manager) {
	}

	public record Asset(String assetTag, String type, String model, String assignedTo, LocalDate purchasedOn,
			LocalDate warrantyUntil, String status) {

		public boolean inWarranty() {
			return !this.warrantyUntil.isBefore(LocalDate.now());
		}

	}

	public record Ticket(String ticketNo, String username, String assetTag, String category, String priority,
			String status, String summary, LocalDateTime createdAt) {
	}

	public record AuditEntry(String actor, String action, String details, LocalDateTime createdAt) {
	}

}
