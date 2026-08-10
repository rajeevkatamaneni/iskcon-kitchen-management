package org.iskcon.kms.recipe;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The tenant's recipe categories (E2-S2). Seeded on provisioning; a Temple Admin adds more. */
@Service
public class RecipeCategoryService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public RecipeCategoryService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional(readOnly = true)
	public List<RecipeCategoryView> list() {
		return jdbc.query("""
				SELECT id, name, fasting_compatible FROM recipe_categories ORDER BY name
				""", MAPPER);
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateCategoryRequest request) {
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
					INSERT INTO recipe_categories (id, tenant_id, name, fasting_compatible)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
					""", id, request.name().trim(), request.fastingCompatible());
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.CATEGORY_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}
		auditService.record(actor, AuditAction.RECIPE_CATEGORY_ADDED, AuditEntityType.RECIPE_CATEGORY,
				id, null,
				Map.of("name", request.name().trim(), "fastingCompatible", request.fastingCompatible()),
				null);
		return id;
	}

	private static final RowMapper<RecipeCategoryView> MAPPER = (rs, rowNum) -> new RecipeCategoryView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getBoolean("fasting_compatible"));
}
