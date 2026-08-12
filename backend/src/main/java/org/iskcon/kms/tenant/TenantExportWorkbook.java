package org.iskcon.kms.tenant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Builds a temple's data export: one sheet per table, raw rows, ready to read.
 *
 * <p>Deliberately dumb. It knows nothing about temples or tables — it takes a sheet name, a list of
 * column names and the rows, and produces a workbook someone can open a year later and use without
 * knowing anything about this system: the column names are the header row, that row is frozen and
 * carries an autofilter, so sorting and filtering work on arrival (E1-S15, D7).
 *
 * <p>Streaming ({@link SXSSFWorkbook}) rather than in-memory, so a large table costs a window of
 * rows rather than all of them. Rows are written as they arrive and cannot be revisited, which is
 * why the autofilter is applied at {@link SheetWriter#finish()} — its range needs the last row.
 *
 * <p>Values are written as they are stored, not interpreted (D9): a column-encrypted PAN stays
 * ciphertext, JSON stays JSON text. The only conversions are the ones a spreadsheet cannot avoid —
 * numbers and booleans become typed cells so they sort correctly, timestamps become ISO-8601 text
 * so they are unambiguous across locales, and bytes become Base64 rather than mojibake.
 */
class TenantExportWorkbook implements AutoCloseable {

	/** Excel's hard limit on a sheet name. */
	private static final int MAX_SHEET_NAME = 31;

	/** Excel forbids these in a sheet name. */
	private static final String ILLEGAL_SHEET_CHARS = "[]:*?/\\";

	/**
	 * Rows held in memory per sheet before being flushed to disk. 200 is enough that small tables
	 * never touch the disk at all, and large ones stay bounded.
	 */
	private static final int ROW_WINDOW = 200;

	/** Wide enough for a UUID at the default font, which is the widest thing most columns hold. */
	private static final int COLUMN_WIDTH = 20 * 256;

	private final SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
	private final CellStyle headerStyle;
	private final Set<String> usedNames = new HashSet<>();

	TenantExportWorkbook() {
		this.headerStyle = workbook.createCellStyle();
		Font bold = workbook.createFont();
		bold.setBold(true);
		headerStyle.setFont(bold);
		headerStyle.setBorderBottom(BorderStyle.THIN);
	}

	/**
	 * Starts a sheet with its header row written. Add rows to the returned writer in order, then
	 * finish it before starting the next sheet.
	 */
	SheetWriter startSheet(String name, List<String> columns) {
		SXSSFSheet sheet = workbook.createSheet(sheetName(name));

		Row header = sheet.createRow(0);
		for (int i = 0; i < columns.size(); i++) {
			Cell cell = header.createCell(i);
			cell.setCellValue(columns.get(i));
			cell.setCellStyle(headerStyle);
			sheet.setColumnWidth(i, COLUMN_WIDTH);
		}

		// The header stays put while someone scrolls a thousand rows of stock movements.
		sheet.createFreezePane(0, 1);

		return new SheetWriter(sheet, columns.size());
	}

	byte[] toBytes() {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			workbook.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void close() {
		// Also releases the temporary files SXSSF flushed rows into.
		try {
			workbook.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Excel's rules, applied so a table name can never break the file: illegal characters replaced,
	 * truncated to the limit, and made unique — because two tables could otherwise truncate to the
	 * same name and the second sheet would be rejected.
	 */
	private String sheetName(String table) {
		StringBuilder cleaned = new StringBuilder();
		for (char c : table.toCharArray()) {
			cleaned.append(ILLEGAL_SHEET_CHARS.indexOf(c) >= 0 ? '_' : c);
		}

		String base = cleaned.length() > MAX_SHEET_NAME
				? cleaned.substring(0, MAX_SHEET_NAME)
				: cleaned.toString();
		if (base.isBlank()) {
			base = "sheet";
		}

		String candidate = base;
		for (int suffix = 2; !usedNames.add(candidate.toLowerCase()); suffix++) {
			String tail = "~" + suffix;
			String head = base.length() + tail.length() > MAX_SHEET_NAME
					? base.substring(0, MAX_SHEET_NAME - tail.length())
					: base;
			candidate = head + tail;
		}
		return candidate;
	}

	/** Writes the rows of one sheet, in order. */
	final class SheetWriter {

		private final SXSSFSheet sheet;
		private final int columnCount;
		private int lastRow;

		private SheetWriter(SXSSFSheet sheet, int columnCount) {
			this.sheet = sheet;
			this.columnCount = columnCount;
		}

		void addRow(List<Object> values) {
			Row row = sheet.createRow(++lastRow);
			for (int i = 0; i < values.size(); i++) {
				write(row.createCell(i), values.get(i));
			}
		}

		/** Applies the autofilter over everything written, header included. */
		void finish() {
			if (columnCount > 0) {
				sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, columnCount - 1));
			}
		}

		int rowCount() {
			return lastRow;
		}

		private void write(Cell cell, Object value) {
			switch (value) {
				case null -> cell.setBlank();
				case Number n -> cell.setCellValue(n.doubleValue());
				case Boolean b -> cell.setCellValue(b);
				case byte[] bytes -> cell.setCellValue(Base64.getEncoder().encodeToString(bytes));
				case java.sql.Timestamp ts -> cell.setCellValue(ts.toInstant().toString());
				case java.sql.Date d -> cell.setCellValue(d.toLocalDate().toString());
				case Instant i -> cell.setCellValue(i.toString());
				case OffsetDateTime odt -> cell.setCellValue(odt.toInstant().toString());
				case Temporal t -> cell.setCellValue(t.toString());
				default -> cell.setCellValue(String.valueOf(value));
			}
		}
	}

	/** The rows written per sheet, in the order the sheets were added. */
	static List<String> sheetOrder(List<String> tables) {
		// `tenants` first — the temple's own row is what the rest of the workbook belongs to —
		// then everything else alphabetically, so two exports of the same temple read the same way.
		List<String> ordered = new ArrayList<>(tables);
		ordered.sort((a, b) -> {
			if (a.equals(b)) return 0;
			if ("tenants".equals(a)) return -1;
			if ("tenants".equals(b)) return 1;
			return a.compareTo(b);
		});
		return ordered;
	}
}
