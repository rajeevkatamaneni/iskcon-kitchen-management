package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The workbook itself: the thing a temple accountant opens a year later. These are the rules that
 * make it usable — a header row that says what each column is, an autofilter so it can be sorted and
 * filtered on arrival, a frozen header so it stays visible — and the Excel constraints that would
 * otherwise produce a file that refuses to open.
 */
class TenantExportWorkbookTest {

	@Test
	@DisplayName("every sheet has a header row, a frozen header, and an autofilter over its rows")
	void sheetsAreReadable() throws Exception {
		byte[] bytes;
		try (TenantExportWorkbook workbook = new TenantExportWorkbook()) {
			TenantExportWorkbook.SheetWriter writer =
					workbook.startSheet("ingredients", List.of("id", "name", "canonical_unit"));
			writer.addRow(List.of("1", "Rice", "KG"));
			writer.addRow(List.of("2", "Toor Dal", "KG"));
			writer.finish();
			bytes = workbook.toBytes();
		}

		try (Workbook read = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			XSSFSheet sheet = (XSSFSheet) read.getSheet("ingredients");

			assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("id");
			assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("name");
			assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Rice");
			assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Toor Dal");

			// Frozen below the header: the column names stay visible while scrolling.
			assertThat(sheet.getPaneInformation()).isNotNull();
			assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);

			// Autofilter covering the header and both rows, all three columns.
			assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
			assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:C3");
		}
	}

	@Test
	@DisplayName("an empty table still gets a sheet, with its columns and a header-only filter")
	void emptyTableStillGetsASheet() throws Exception {
		byte[] bytes;
		try (TenantExportWorkbook workbook = new TenantExportWorkbook()) {
			workbook.startSheet("donations", List.of("id", "amount_inr")).finish();
			bytes = workbook.toBytes();
		}

		try (Workbook read = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			XSSFSheet sheet = (XSSFSheet) read.getSheet("donations");
			assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("amount_inr");
			assertThat(sheet.getLastRowNum()).isZero();
			assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:B1");
		}
	}

	@Test
	@DisplayName("values keep their type: numbers and booleans sort, timestamps are unambiguous, bytes survive")
	void valuesAreWrittenFaithfully() throws Exception {
		Instant when = Instant.parse("2026-08-12T04:30:00Z");
		UUID id = UUID.randomUUID();

		byte[] bytes;
		try (TenantExportWorkbook workbook = new TenantExportWorkbook()) {
			TenantExportWorkbook.SheetWriter writer = workbook.startSheet(
					"stock_movements",
					List.of("id", "quantity", "is_correction", "occurred_at", "scan", "note"));
			writer.addRow(Arrays.asList(
					id, 42.5, Boolean.TRUE, Timestamp.from(when), new byte[] {1, 2, 3}, null));
			writer.finish();
			bytes = workbook.toBytes();
		}

		try (Workbook read = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Sheet sheet = read.getSheet("stock_movements");

			assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo(id.toString());
			assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(42.5);
			assertThat(sheet.getRow(1).getCell(2).getBooleanCellValue()).isTrue();
			// ISO-8601, so it reads the same in every locale that opens the file.
			assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("2026-08-12T04:30:00Z");
			assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("AQID");
			assertThat(sheet.getRow(1).getCell(5).getCellType()).isEqualTo(CellType.BLANK);
		}
	}

	@Test
	@DisplayName("a table name too long for Excel is truncated, and two that collide stay distinct")
	void sheetNamesObeyExcel() throws Exception {
		String longA = "notification_delivery_attempts_by_channel";
		String longB = "notification_delivery_attempts_by_status";

		byte[] bytes;
		try (TenantExportWorkbook workbook = new TenantExportWorkbook()) {
			workbook.startSheet(longA, List.of("id")).finish();
			workbook.startSheet(longB, List.of("id")).finish();
			bytes = workbook.toBytes();
		}

		try (Workbook read = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			assertThat(read.getNumberOfSheets()).isEqualTo(2);

			String first = read.getSheetName(0);
			String second = read.getSheetName(1);
			assertThat(first).hasSizeLessThanOrEqualTo(31);
			assertThat(second).hasSizeLessThanOrEqualTo(31);
			// The two names truncate to the same 31 characters, so the second must be made distinct
			// or Excel rejects the file outright.
			assertThat(first).isNotEqualTo(second);
			assertThat(second).endsWith("~2");
		}
	}

	@Test
	@DisplayName("the temple's own row comes first, then every other table alphabetically")
	void sheetOrderIsPredictable() {
		List<String> ordered = TenantExportWorkbook.sheetOrder(
				List.of("users", "ingredients", "tenants", "donations"));

		assertThat(ordered).containsExactly("tenants", "donations", "ingredients", "users");
	}
}
