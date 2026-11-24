import { describe, expect, it } from 'vitest';
import { codeLabel, humanizeText, money } from './format';

describe('humanizeText', () => {
  it('formats money after Rs. with at most two decimals and no trailing zeros', () => {
    expect(humanizeText('Gross value Rs. 72750.000000 exceeds the Rs. 50000.000000 approval threshold')).toBe(
      'Gross value Rs. 72,750 exceeds the Rs. 50,000 approval threshold',
    );
    expect(humanizeText('Total Rs. 75750.000000')).toBe('Total Rs. 75,750');
    expect(humanizeText('Total Rs. 75,750.00 · awaiting independent approval')).toBe(
      'Total Rs. 75,750 · awaiting independent approval',
    );
    expect(humanizeText('Rs. 1234.5')).toBe('Rs. 1,234.5');
    expect(humanizeText('Rs. 12.346')).toBe('Rs. 12.35');
    expect(humanizeText('Rs.980.100000')).toBe('Rs.980.1');
    expect(humanizeText('+Rs. 1500.000000 / −Rs. 250.500000')).toBe('+Rs. 1,500 / −Rs. 250.5');
  });

  it('matches the money() helper used by the rest of the UI', () => {
    expect(humanizeText('Rs. 72750.000000')).toBe(money(72750));
    expect(humanizeText('Rs. 1234.560000')).toBe(money(1234.56));
  });

  it('groups and trims quantities without rounding them', () => {
    expect(humanizeText('4000 good; 20 scrap; WIP 221301.740000')).toBe(
      '4,000 good; 20 scrap; WIP 221,301.74',
    );
    expect(humanizeText('1 box = 12.000000 pcs · purchase')).toBe('1 box = 12 pcs · purchase');
    expect(humanizeText('0.000500 kg')).toBe('0.0005 kg');
    expect(humanizeText('-12.500000 kg')).toBe('-12.5 kg');
    expect(humanizeText('97.500000% yield')).toBe('97.5% yield');
    expect(humanizeText('BOM R03 snapshotted; 5000 units')).toBe('BOM R03 snapshotted; 5,000 units');
    expect(humanizeText('line 1 1250.000000 kg; line 2 3.250000 kg')).toBe('line 1 1,250 kg; line 2 3.25 kg');
  });

  it('leaves identifiers, dates, times, versions, barcodes and years alone', () => {
    const ids = 'GRN-2026-0004 · delivery note DN 2026/11 into LOT-20261124-01 at 14:05 on 2026-11-24';
    expect(humanizeText(ids)).toBe(ids);
    expect(humanizeText('4791234567890 = 1 pcs')).toBe('4791234567890 = 1 pcs');
    expect(humanizeText('Recount for 2026')).toBe('Recount for 2026');
    expect(humanizeText('Version 1.2.3 and code 007')).toBe('Version 1.2.3 and code 007');
    expect(humanizeText('MO-0042 child of MO-0041')).toBe('MO-0042 child of MO-0041');
    expect(humanizeText('lines 1,2,3')).toBe('lines 1,2,3');
  });

  it('turns status codes into sentence case and keeps arrows', () => {
    expect(humanizeText('MATERIALS_RESERVED → RELEASED')).toBe('Materials reserved → Released');
    expect(humanizeText('DRAFT → CANCELLED: Supplier closed')).toBe('Draft → Cancelled: Supplier closed');
    expect(humanizeText('IN_PROGRESS -> QUALITY_REVIEW')).toBe('In progress -> Quality review');
    expect(humanizeText('Was APPROVAL_REQUIRED')).toBe('Was approval required');
    expect(humanizeText('Role PLANNER')).toBe('Role planner');
    expect(humanizeText('Main store · WAREHOUSE')).toBe('Main store · Warehouse');
  });

  it('keeps acronyms and unknown upper-case words', () => {
    expect(humanizeText('QC_PASSED')).toBe('QC passed');
    expect(humanizeText('WIP 0.500000 to COGS')).toBe('WIP 0.5 to COGS');
    expect(humanizeText('DO NOT USE')).toBe('DO NOT USE');
    expect(humanizeText('RM-STEEL-01 of SKU STEEL')).toBe('RM-STEEL-01 of SKU STEEL');
  });

  it('handles empty values', () => {
    expect(humanizeText(null)).toBe('');
    expect(humanizeText(undefined)).toBe('');
    expect(humanizeText('')).toBe('');
  });
});

describe('codeLabel', () => {
  it('produces sentence-case labels with acronyms kept', () => {
    expect(codeLabel('wip_cost')).toBe('WIP cost');
    expect(codeLabel('estimated_cost')).toBe('Estimated cost');
    expect(codeLabel('finished_cost')).toBe('Finished cost');
    expect(codeLabel('MATERIALS_RESERVED')).toBe('Materials reserved');
    expect(codeLabel('IN_PROGRESS')).toBe('In progress');
    expect(codeLabel('COGS')).toBe('COGS');
    expect(codeLabel('grni')).toBe('GRNI');
    expect(codeLabel('fifo_layer')).toBe('FIFO layer');
    expect(codeLabel('APPROVAL_REQUIRED', false)).toBe('approval required');
  });
});
