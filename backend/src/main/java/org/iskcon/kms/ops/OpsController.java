package org.iskcon.kms.ops;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Super-Admin ops page's data. Behind {@code VIEW_PLATFORM_OPERATIONS} — the platform operator's
 * permission, which by design carries none of the temple permissions, so this exposes operational
 * health, never a temple's business data.
 *
 * <p>One endpoint here is not a read: refreshing a temple's WhatsApp template status uses that temple's
 * own secret to call Meta, so it is behind {@code MANAGE_TENANTS} and audited on the temple (T-178).
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

	/**
	 * Per template, how many temples' stored copies of Meta's status say approved, pending, refused or
	 * marketing, and whether any says refused for its formatting (T-178). Counts only, never a temple.
	 */
	@GetMapping("/whatsapp-templates/status-counts")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public List<TemplateStatusCounts> whatsappTemplateStatusCounts() {
		return opsService.whatsappTemplateStatusCounts();
	}

	/** One temple's stored copy of Meta's status per WhatsApp template, and when it was taken (T-178). */
	@GetMapping("/tenants/{tenantId}/whatsapp-templates")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public TemplateStatusCopy.View templeTemplateStatus(@PathVariable UUID tenantId) {
		return opsService.templeTemplateStatus(tenantId);
	}

	/**
	 * Asks Meta again for one temple, with that temple's own token, and replaces the stored copy (T-178).
	 * Twenty GETs to Meta and nothing created or edited there. Audited on the temple as
	 * {@code WHATSAPP_TEMPLATE_STATUS_REFRESHED}.
	 */
	@PostMapping("/tenants/{tenantId}/whatsapp-templates/refresh")
	@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
	public TemplateStatusCopy.View refreshTempleTemplateStatus(@PathVariable UUID tenantId,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return opsService.refreshTempleTemplateStatus(tenantId, actor);
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
