package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the downloaded file is called. It matters more than it looks: this file is the only copy of a
 * temple that is about to be erased, and it will sit in somebody's downloads folder for years.
 */
class TenantExportFilenameTest {

	@Test
	@DisplayName("the file is named after the temple, says what it is, and carries the date")
	void namedAfterTheTemple() {
		String name = TenantExportService.filename("Sri Sri Radha Govinda Temple");

		assertThat(name)
				.isEqualTo("Sri Sri Radha Govinda Temple - Data Export - " + LocalDate.now() + ".xlsx");
	}

	@Test
	@DisplayName("characters a filesystem would reject are replaced, and the name is not mangled otherwise")
	void unsafeCharactersAreReplaced() {
		String name = TenantExportService.filename("ISKCON Mumbai / Juhu: Sri Radha\\Rasabihari");

		assertThat(name).startsWith("ISKCON Mumbai Juhu Sri Radha Rasabihari - Data Export - ");
		assertThat(name).doesNotContain("/", "\\", ":");
	}

	@Test
	@DisplayName("a temple named in an Indian script keeps its name")
	void nonAsciiNameSurvives() {
		String name = TenantExportService.filename("श्री श्री राधा गोविंद मंदिर");

		assertThat(name).startsWith("श्री श्री राधा गोविंद मंदिर - Data Export - ");
	}

	@Test
	@DisplayName("a name that is nothing but unusable characters still produces a usable file")
	void emptyNameFallsBack() {
		String name = TenantExportService.filename("///");

		assertThat(name).startsWith("Temple - Data Export - ").endsWith(".xlsx");
	}
}
