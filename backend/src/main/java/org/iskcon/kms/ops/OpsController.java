package org.iskcon.kms.ops;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Super-Admin ops page's data. Behind {@code VIEW_PLATFORM_OPERATIONS} — the platform operator's
 * permission, which by design carries none of the temple permissions, so this exposes operational
 * health, never a temple's business data.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

	private final OpsService opsService;
	private final WhatsAppTemplateCatalogue templateCatalogue;

	public OpsController(OpsService opsService, WhatsAppTemplateCatalogue templateCatalogue) {
		this.opsService = opsService;
		this.templateCatalogue = templateCatalogue;
	}

	/**
	 * Every WhatsApp template the app sends: its name, category, language, body with example values,
	 * what sends it, and when a running app first saw its wording (T-177). Read-only, and no temple
	 * data: the templates are source code and the dates come from a platform table.
	 */
	@GetMapping("/whatsapp-templates")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public WhatsAppTemplateCatalogue.Catalogue whatsappTemplates() {
		return templateCatalogue.read();
	}

	/** Platform-wide notification-send totals for today, plus a seven-day trend. */
	@GetMapping("/notifications")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public NotificationMetrics notifications() {
		return opsService.notificationMetrics();
	}

	/** The temples available to drill into. */
	@GetMapping("/tenants")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public List<Map<String, Object>> tenants() {
		return opsService.tenants();
	}

	/** One temple's operational health today. */
	@GetMapping("/tenants/{tenantId}")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public TenantOps tenant(@PathVariable UUID tenantId) {
		return opsService.tenantOperations(tenantId);
	}
}
