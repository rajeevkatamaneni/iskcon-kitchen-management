package org.iskcon.kms.translation;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The translation glossary (E2-S6), behind {@code MANAGE_RECIPES}. */
@RestController
@RequestMapping("/api/v1/translation-glossary")
public class GlossaryController {

	private final GlossaryService glossaryService;
	private final StockNameFillService stockNameFillService;

	public GlossaryController(GlossaryService glossaryService, StockNameFillService stockNameFillService) {
		this.glossaryService = glossaryService;
		this.stockNameFillService = stockNameFillService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public List<GlossaryEntryView> list(@RequestParam(name = "language", required = false) String language) {
		return glossaryService.list(language);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Map<String, Object>> add(@Valid @RequestBody AddGlossaryEntryRequest request) {
		UUID id = glossaryService.upsert(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/**
	 * Names every stock item in Kannada that the glossary has no name for, by machine, once
	 * (E10-T8c). Never replaces a name that is there; running it again adds nothing. The Kitchen
	 * Assistant reads these names rather than making its own up.
	 *
	 * <p>Kannada only, because that is the one language anything reads stock names in today; another
	 * language is a parameter away when something needs it. {@code MANAGE_RECIPES}, the permission for
	 * adding a glossary entry by hand, because this adds glossary entries.
	 */
	@PostMapping("/fill-stock-names")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public StockNameFillService.FillResult fillStockNames() {
		return stockNameFillService.fillStockNames("kn");
	}

	/**
	 * Makes sure the temple's stock items have names in {@code language}, asking Claude in the
	 * background for any that are missing (E10-NAMES-A). Answers at once with READY, FILLING or
	 * FAILED and the counts; call it again to see a fill finish. The Kitchen Assistant calls the
	 * service itself at the start of a conversation; this is the same thing for a person with the
	 * glossary's permission, and how a fill is started by hand. The temple is the caller's own,
	 * from the verified token, never a parameter.
	 */
	@PostMapping("/ensure-names")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public StockNameFillService.NamesState ensureNames(@RequestParam("language") String language) {
		UUID tenant = TenantContext.get().orElseThrow(() -> new IllegalStateException("no tenant in context"));
		return stockNameFillService.ensureNames(tenant, language);
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		glossaryService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
