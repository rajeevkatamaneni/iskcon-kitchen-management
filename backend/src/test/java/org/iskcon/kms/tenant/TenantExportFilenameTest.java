package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the downloaded file is called. It matters more than it looks: this file is the only copy of a
 * temple that is about to be erased, and it will sit in somebody's downloads folder for years.
 */
class TenantExportFilenameTest {

	@Test
	@DisplayName("the file is named after the temple's web address, and says what it is")
	void namedAfterTheTemple() {
		assertThat(TenantExportService.filename("iskcon-south-bengaluru"))
				.isEqualTo("iskcon-south-bengaluru-ikms-data-export.xlsx");
	}

	@Test
	@DisplayName("anything that isn't a letter or digit becomes a single hyphen, so the name is safe everywhere")
	void unsafeCharactersAreReplaced() {
		String name = TenantExportService.filename("ISKCON Mumbai / Juhu: Sri Radha\\Rasabihari");

		assertThat(name).isEqualTo("iskcon-mumbai-juhu-sri-radha-rasabihari-ikms-data-export.xlsx");
		assertThat(name).doesNotContain("/", "\\", ":", " ");
	}

	@Test
	@DisplayName("a name with no usable characters still produces a usable file")
	void emptyNameFallsBack() {
		assertThat(TenantExportService.filename("///")).isEqualTo("temple-ikms-data-export.xlsx");
	}
}
