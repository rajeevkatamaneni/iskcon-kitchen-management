package org.iskcon.kms.donation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The donations ledger and accounting view (E7-S7), behind {@code VIEW_DONATIONS}. */
@RestController
@RequestMapping("/api/v1/donations/ledger")
public class DonationLedgerController {

	private final DonationLedgerService service;

	public DonationLedgerController(DonationLedgerService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public List<LedgerRow> ledger(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String status) {
		return service.ledger(from, to, type, status);
	}

	@GetMapping("/summary")
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public LedgerSummary summary() {
		return service.summary();
	}

	@GetMapping("/donor/{donationId}")
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public List<LedgerRow> donor(@PathVariable UUID donationId) {
		return service.donorHistory(donationId);
	}

	@GetMapping("/export")
	@PreAuthorize("hasAuthority('VIEW_DONATIONS')")
	public ResponseEntity<String> export(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String status) {
		String csv = service.exportCsv(from, to, type, status);
		return ResponseEntity.ok()
				.contentType(MediaType.valueOf("text/csv"))
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"donations.csv\"")
				.body(csv);
	}
}
