import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';
import * as XLSX from 'xlsx';
import { BackupArchive, CurrencyCode, LocaleCode } from '../types/cashbook';
import { formatMinorAmount } from '../engine/money';

export interface ReconciledReportDocument {
  reportTitle: string;
  businessName: string;
  currency: CurrencyCode;
  periodLabel: string;
  filterDescription: string;
  totalsBasisLabel: string; // Explicitly states Cash Movement vs Income/Expense vs Outstanding Balances
  summaryMetrics: Array<{ label: string; value: string }>;
  columns: string[];
  rows: Array<string[]>;
  footerTotalsRow?: string[];
}

function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  setTimeout(() => {
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }, 300);
}

function sanitizeFilename(input: string): string {
  return input.replace(/[^a-zA-Z0-9_\-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 60) || 'vumiq_report';
}

/**
 * Exports full, untruncated report to UTF-8 CSV with BOM for proper Excel/Numbers character rendering.
 */
export function exportReportToCsv(docData: ReconciledReportDocument): void {
  const escapeCsv = (cell: string) => `"${String(cell ?? '').replace(/"/g, '""')}"`;

  const lines: string[] = [
    [escapeCsv('VUMIQ Cashbook — Financial Report'), escapeCsv(docData.reportTitle)].join(','),
    [escapeCsv('Business'), escapeCsv(docData.businessName)].join(','),
    [escapeCsv('Currency'), escapeCsv(docData.currency)].join(','),
    [escapeCsv('Reporting Period'), escapeCsv(docData.periodLabel)].join(','),
    [escapeCsv('Applied Filters'), escapeCsv(docData.filterDescription)].join(','),
    [escapeCsv('Accounting Basis'), escapeCsv(docData.totalsBasisLabel)].join(','),
    '',
    ...docData.summaryMetrics.map((m) => [escapeCsv(m.label), escapeCsv(m.value)].join(',')),
    '',
    docData.columns.map(escapeCsv).join(','),
    ...docData.rows.map((r) => r.map(escapeCsv).join(',')),
  ];

  if (docData.footerTotalsRow) {
    lines.push(docData.footerTotalsRow.map(escapeCsv).join(','));
  }

  const csvContent = '\uFEFF' + lines.join('\r\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const filename = `${sanitizeFilename(docData.businessName)}_${sanitizeFilename(docData.reportTitle)}_${docData.currency}.csv`;
  triggerBlobDownload(blob, filename);
}

/**
 * Exports full, untruncated report to Excel-compatible .xlsx workbook with Summary & Register sheets.
 */
export function exportReportToXlsx(docData: ReconciledReportDocument): void {
  const wb = XLSX.utils.book_new();

  const sheetRows: string[][] = [
    ['VUMIQ Cashbook', docData.reportTitle],
    ['Business', docData.businessName],
    ['Currency', docData.currency],
    ['Reporting Period', docData.periodLabel],
    ['Applied Filters', docData.filterDescription],
    ['Accounting Basis', docData.totalsBasisLabel],
    [],
    ...docData.summaryMetrics.map((m) => [m.label, m.value]),
    [],
    docData.columns,
    ...docData.rows,
  ];

  if (docData.footerTotalsRow) {
    sheetRows.push(docData.footerTotalsRow);
  }

  const ws = XLSX.utils.aoa_to_sheet(sheetRows);
  ws['!cols'] = docData.columns.map((_, idx) => ({ wch: idx === 1 ? 32 : 18 }));

  XLSX.utils.book_append_sheet(wb, ws, 'Report');
  const out = XLSX.write(wb, { bookType: 'xlsx', type: 'array' });
  const blob = new Blob([out], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const filename = `${sanitizeFilename(docData.businessName)}_${sanitizeFilename(docData.reportTitle)}_${docData.currency}.xlsx`;
  triggerBlobDownload(blob, filename);
}

/**
 * Exports full, untruncated, reconciled PDF financial report via jsPDF + autoTable (no window.open needed).
 */
export function exportReportToPdf(docData: ReconciledReportDocument): void {
  const pdf = new jsPDF({ orientation: docData.columns.length > 6 ? 'landscape' : 'portrait', unit: 'pt', format: 'a4' });

  // Brand Header
  pdf.setFillColor(18, 107, 69); // #126B45
  pdf.rect(0, 0, pdf.internal.pageSize.getWidth(), 64, 'F');

  pdf.setTextColor(255, 255, 255);
  pdf.setFont('helvetica', 'bold');
  pdf.setFontSize(16);
  pdf.text('VUMIQ Cashbook', 40, 30);

  pdf.setFont('helvetica', 'normal');
  pdf.setFontSize(10);
  pdf.text(`${docData.businessName}  |  ${docData.reportTitle} (${docData.currency})`, 40, 48);

  // Report Metadata
  pdf.setTextColor(23, 35, 29);
  pdf.setFontSize(9.5);
  pdf.text(`Period: ${docData.periodLabel}`, 40, 86);
  pdf.text(`Filters: ${docData.filterDescription}`, 40, 100);
  pdf.text(`Basis: ${docData.totalsBasisLabel}`, 40, 114);

  // Summary KPI Strip
  let yCursor = 134;
  pdf.setFont('helvetica', 'bold');
  for (const m of docData.summaryMetrics) {
    pdf.text(`${m.label}: ${m.value}`, 40, yCursor);
    yCursor += 14;
  }

  const bodyRows = [...docData.rows];
  if (docData.footerTotalsRow) {
    bodyRows.push(docData.footerTotalsRow);
  }

  autoTable(pdf, {
    startY: yCursor + 8,
    head: [docData.columns],
    body: bodyRows,
    styles: {
      fontSize: 8.5,
      cellPadding: 5,
      textColor: [23, 35, 29],
    },
    headStyles: {
      fillColor: [18, 107, 69],
      textColor: [255, 255, 255],
      fontStyle: 'bold',
    },
    alternateRowStyles: {
      fillColor: [246, 248, 245],
    },
    margin: { left: 40, right: 40, bottom: 40 },
  });

  const blob = pdf.output('blob');
  const filename = `${sanitizeFilename(docData.businessName)}_${sanitizeFilename(docData.reportTitle)}_${docData.currency}.pdf`;
  triggerBlobDownload(blob, filename);
}

export function downloadBackupJsonFile(archive: BackupArchive): void {
  const content = JSON.stringify(archive, null, 2);
  const blob = new Blob([content], { type: 'application/json;charset=utf-8;' });
  const datePart = archive.exportedAt.slice(0, 10);
  const filename = `VUMIQ_Backup_${sanitizeFilename(archive.business.name)}_${datePart}.json`;
  triggerBlobDownload(blob, filename);
}

export function formatExportAmount(minor: number, currency: CurrencyCode, locale: LocaleCode): string {
  return formatMinorAmount(minor, currency, locale);
}
