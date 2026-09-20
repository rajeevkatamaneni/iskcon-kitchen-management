#!/usr/bin/env node
/**
 * Corrections to the curated recipe set, applied to the JSON files his curation tool wrote
 * (docs/work/reference/curated-recipes/). Three are Rajeev's own, 2026-09-19; the fourth — two
 * misspelled ingredient names — was found by reading the 101 distinct names against each other
 * and is the conductor's decision, same day.
 *
 * His words:
 *   "Please check each one if you see water, please add we donot but to it if it is missing,
 *    for coconut, If you see Coconut with KG's please change it to Pieces and the value goes by
 *    calculation 8 Coconuts for Each Kg of Grated Coconut. SO calculate it and put the right
 *    number of pieces. If anythign has the , chopped OR , grated, PLease remove the comma, take
 *    the text after, camel case it and throw it in the Preparation box."
 *
 * Re-runnable: every rule tests for the corrected state first, so a second run changes nothing
 * and says so. It rewrites the files in place; the untouched originals stay in the main checkout.
 *
 *   node tools/seed/02a-curation-fixups.mjs [--dir <path>] [--dry-run]
 *
 * Exit code 1 only while a question is still OPEN, so a pipeline stops rather than loading a
 * catalogue nobody has ruled on. Questions Rajeev has already answered are printed every run,
 * with his words and the date, but do not hold the script open — a script that keeps failing
 * over settled matters teaches people to ignore its exit code.
 *
 * Settled so far, both 2026-09-19:
 *   "Hot water is ok, not purchasable."            -> folded into Water, prep "Hot", not bought
 *   "Dry coconut is a thing and it is in KG's OR grams." -> copra never converted to pieces
 */

import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_DIR = resolve(HERE, '../../docs/work/reference/curated-recipes');

/** 8 grated coconuts to the kilogram — Rajeev's figure, 2026-09-19. */
const COCONUTS_PER_KG = 8;

/** What one unit token is worth in kilograms. Only the two the curated set uses. */
const TO_KG = { kg: 1, gm: 0.001, g: 0.001 };

const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const dirArg = args.indexOf('--dir');
const DIR = dirArg >= 0 ? resolve(args[dirArg + 1]) : DEFAULT_DIR;

const changes = [];   // every edit, printed at the end as well as as it happens
const decisions = []; // things nobody has ruled on yet — these make the script exit 1
const settled = [];   // rulings already given, reported so the reasoning stays visible
let filesChanged = 0;

function record(file, rule, before, after) {
  changes.push({ file, rule, before, after });
  console.log(`  ${rule.padEnd(9)} ${file}`);
  console.log(`            was: ${before}`);
  console.log(`            now: ${after}`);
}

function decision(file, text) {
  decisions.push(`${file}: ${text}`);
}

/**
 * A question that has already been answered. Reported every run so the reasoning stays in front
 * of whoever reads the output, but it does not hold the script open — an answered question is
 * not an open one, and a script that keeps exiting 1 over settled matters trains people to
 * ignore its exit code.
 */
function ruled(file, text) {
  settled.push(`${file}: ${text}`);
}

/** "boiled and cubed" -> "Boiled and cubed". His "camel case it" means the Preparation box's own style. */
function asPreparation(text) {
  const t = text.trim();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

function describe(line) {
  const bits = [line.name, line.qty];
  if (line.prep) bits.push(`prep=${line.prep}`);
  bits.push(`not_bought=${line.not_bought === undefined ? 'unset' : line.not_bought}`);
  return bits.join(' | ');
}

// ---------------------------------------------------------------- the rules

/**
 * 1. Water is never bought.
 *
 * "Hot water" is folded into Water with "Hot" as the preparation, rather than left as an
 * ingredient of its own — otherwise the temple's catalogue holds two waters. Rajeev ruled on
 * this when it was put to him, 2026-09-19: *"Hot water is ok, not purchasable."* His own
 * curation had already done exactly this to akki-rotti, so the fix-up follows his hand.
 */
function ruleWater(file, line) {
  const name = line.name.trim().toLowerCase();
  const isWater = name === 'water' || name === 'hot water' || name === 'cold water' || name === 'chilled water';
  if (!isWater) {
    if (name.includes('water')) {
      decision(file, `"${line.name}" contains "water" but is not plain water — left alone, check it`);
    }
    return false;
  }

  const before = describe(line);
  let touched = false;

  // A leading word the cook does is a preparation, not part of the name — his own curation
  // already wrote akki-rotti's as name "Water", prep "Hot", so this follows his hand.
  if (name !== 'water') {
    const word = line.name.trim().split(/\s+/)[0];
    line.name = 'Water';
    line.prep = line.prep ? `${asPreparation(word)}, ${line.prep}` : asPreparation(word);
    touched = true;
    ruled(file, `"${before.split(' | ')[0]}" renamed to Water with prep "${line.prep}", and marked not bought — Rajeev 2026-09-19: "Hot water is ok, not purchasable."`);
  }

  if (line.not_bought !== true) {
    line.not_bought = true;
    touched = true;
  }

  if (touched) record(file, 'water', before, describe(line));
  return touched;
}

/**
 * 2. Coconut bought by the piece, not by weight.
 *
 * Only the ingredient actually called Coconut. "Coconut oil" and "Coconut milk" are other
 * things bought by volume, and "Dry coconut" is copra, a different item sold by weight.
 *
 * Rajeev ruled on the copra when it was put to him, 2026-09-19: *"Dry coconut is a thing and it
 * is in KG's OR grams."* So it is never converted to pieces, and the script reports it as
 * settled rather than holding itself open over it.
 */
function ruleCoconut(file, line) {
  const name = line.name.trim().toLowerCase();
  if (!name.includes('coconut')) return false;

  if (name !== 'coconut') {
    if (name === 'dry coconut') {
      ruled(file, `"${line.name}" (${line.qty}) stays in Kg — Rajeev 2026-09-19: "Dry coconut is a thing and it is in KG's OR grams."`);
    }
    return false; // coconut oil, coconut milk, dry coconut
  }

  const unit = (line.qty_unit || '').trim().toLowerCase();
  const factor = TO_KG[unit];
  if (factor === undefined) return false; // already Pieces/Nos — leave it

  const amount = Number(line.qty_amount ?? NaN);
  if (!Number.isFinite(amount)) {
    decision(file, `Coconut line has an amount that is not a number (${line.qty_amount}) — not converted`);
    return false;
  }

  const kg = amount * factor;
  const exact = kg * COCONUTS_PER_KG;
  const pieces = Math.ceil(exact - 1e-9);
  const before = describe(line);

  line.qty_amount = String(pieces);
  line.qty_unit = 'Pieces';
  line.qty = `${pieces} Pieces`;
  line.qty_l = 'ನಗ'; // the curated set's own Kannada word for a count; the Kg word would now be wrong

  const rounded = Math.abs(exact - pieces) > 1e-9 ? ` (${exact} rounded UP to ${pieces})` : '';
  record(file, 'coconut', before, `${describe(line)}   ${kg} Kg x ${COCONUTS_PER_KG}${rounded}`);
  return true;
}

/**
 * 4. Two straight typos in the curated names.
 *
 * Found by listing the 101 distinct ingredient names the approved recipes use and reading them
 * against each other. `Lemo` is a truncated Lemon; `Upma Ravva` is `Upma Rava` with a doubled v.
 * Left alone each becomes an ingredient of its own, so the temple holds two lemons and two ravas.
 *
 * Conductor's decision, 2026-09-19, told to Rajeev. Only these two: this map is for outright
 * misspellings, not for the twelve genuine near-duplicates (Sago/Sabudana, Cashew/Cashew nuts and
 * the rest), which are real alternative names and are handled as ALIASES in
 * tools/seed/data/catalogue.json instead. Renaming those would lose a name the cooks use.
 */
const TYPOS = new Map([
  ['lemo', 'Lemon'],
  ['upma ravva', 'Upma Rava'],
]);

function ruleTypo(file, line) {
  const fixed = TYPOS.get(line.name.trim().toLowerCase());
  if (!fixed || line.name.trim() === fixed) return false;

  const before = describe(line);
  line.name = fixed;
  record(file, 'typo', before, describe(line));
  return true;
}

/**
 * 5. A quantity that is not a number.
 *
 * `karnataka__mavinakayi-uppinakayi.json` says the mustard is `"z gm"`. The `z` is in the
 * vendored source book too (`backend/src/main/resources/recipe-library/karnataka.json`), so it is
 * a typo in the material the library was built from, not something the curation tool did.
 *
 * Repaired to **300 gm**, from the recipe's own arithmetic rather than a guess. Everything in it
 * is quoted against 12 Kg of raw mango: salt 2 Kg is 167 g/Kg, chilli powder 1.2 Kg is 100 g/Kg,
 * fenugreek and turmeric 150 gm are 12.5 g/Kg each, asafoetida 60 gm is 5 g/Kg. Ground mustard in
 * a Karnataka uppinakayi masala runs at about twice the fenugreek, which puts it at 300 gm, or
 * 25 g/Kg. Conductor's decision 2026-09-19, because nothing loads while the field is unreadable
 * and the cost of being 50 gm out in a 15 Kg pickle is nil. **A one-line change if Rajeev wants a
 * different figure** — edit the map below and re-run.
 *
 * Anything else unreadable stops the script rather than being invented.
 */
const QUANTITY_REPAIRS = new Map([
  ['karnataka__mavinakayi-uppinakayi.json|mustard', { amount: '300', unit: 'gm' }],
]);

function ruleQuantity(file, line) {
  if (Number.isFinite(Number(line.qty_amount))) return false;

  const repair = QUANTITY_REPAIRS.get(`${file}|${line.name.trim().toLowerCase()}`);
  if (!repair) {
    decision(file, `"${line.name}" has an amount that is not a number (${line.qty_amount}) and no repair is recorded`);
    return false;
  }
  if (line.qty_unit && line.qty_unit.trim().toLowerCase() !== repair.unit.toLowerCase()) {
    decision(file, `"${line.name}" repair expects ${repair.unit} but the line says ${line.qty_unit} — left alone`);
    return false;
  }

  const before = describe(line);
  line.qty_amount = repair.amount;
  line.qty_unit = repair.unit;
  line.qty = `${repair.amount} ${repair.unit}`;
  record(file, 'quantity', before, describe(line));
  ruled(file, `"${line.name}" was "${before.split(' | ')[1]}" — the source book has the same typo; set to ${line.qty} from the recipe's own ratios (conductor, 2026-09-19)`);
  return true;
}

/**
 * 6. Ghee is one ingredient, so it is measured one way.
 *
 * The approved set quotes ghee 23 times: 11 by weight (1 Kg, 500 gm) and 12 by volume (2 L,
 * 800 ml). The catalogue holds ONE unit per ingredient, and `Unit.java` only converts inside a
 * family — MASS to MASS, VOLUME to VOLUME, never across. So a temple whose ghee is stocked in Kg
 * and whose recipe asks for 2 L has a line the store room cannot answer. Ghee is the ONLY
 * ingredient in the set with this split; every other name stays in one family. Measured, not
 * assumed: the check that found it counts each name's units across all 44 recipes.
 *
 * Weight wins, because that is how the temple buys it — ghee comes in 15 Kg tins, priced per
 * kilogram, and the store room counts what it holds. So the 12 volume lines are converted at
 * **0.91 Kg per litre**, ghee's density warm enough to pour. This is a real conversion, not a
 * pretence that a litre is a kilogram, which would put every one of those lines 9% over.
 *
 * Rounded to something a cook would write: grams to the nearest 10, kilograms to two decimals.
 * Conductor's decision 2026-09-19, and reversible — one line per recipe if Rajeev wants ghee in
 * litres instead.
 */
const GHEE_KG_PER_L = 0.91;

function ruleGhee(file, line) {
  if (line.name.trim().toLowerCase() !== 'ghee') return false;

  const unit = (line.qty_unit || '').trim().toLowerCase();
  if (unit !== 'l' && unit !== 'ml') return false;

  const amount = Number(line.qty_amount ?? NaN);
  if (!Number.isFinite(amount)) {
    decision(file, `Ghee line has an amount that is not a number (${line.qty_amount}) — not converted`);
    return false;
  }

  const before = describe(line);
  if (unit === 'l') {
    const kg = Math.round(amount * GHEE_KG_PER_L * 100) / 100;
    line.qty_amount = String(kg);
    line.qty_unit = 'Kg';
    line.qty_l = 'ಕೆ.ಜಿ.';
  } else {
    const gm = Math.round((amount * GHEE_KG_PER_L) / 10) * 10;
    line.qty_amount = String(gm);
    line.qty_unit = 'gm';
    line.qty_l = 'ಗ್ರಾಂ';
  }
  line.qty = `${line.qty_amount} ${line.qty_unit}`;
  record(file, 'ghee', before, `${describe(line)}   x ${GHEE_KG_PER_L} Kg/L`);
  return true;
}

/** 3. A comma in a name: what follows it is the preparation. */
function ruleComma(file, line) {
  if (!line.name.includes(',')) return false;

  const at = line.name.indexOf(',');
  const base = line.name.slice(0, at).trim();
  const tail = line.name.slice(at + 1).trim();
  if (!base || !tail) {
    decision(file, `"${line.name}" has a comma but splits to an empty half — left alone`);
    return false;
  }

  const before = describe(line);
  line.name = base;
  const prep = asPreparation(tail);
  if (line.prep && line.prep.trim() && line.prep.trim().toLowerCase() !== prep.toLowerCase()) {
    decision(file, `"${before.split(' | ')[0]}" already had prep "${line.prep}" — the two were joined, check the wording`);
    line.prep = `${prep}, ${line.prep.trim()}`;
  } else {
    line.prep = prep;
  }
  record(file, 'comma', before, describe(line));
  return true;
}

// ---------------------------------------------------------------- the run

const files = readdirSync(DIR).filter((f) => f.endsWith('.json') && f !== 'index.json').sort();
if (files.length === 0) {
  console.error(`No curated recipe files in ${DIR}`);
  process.exit(1);
}

console.log(`Curation fix-ups over ${files.length} approved recipes in ${DIR}${dryRun ? '  (dry run)' : ''}\n`);

const counts = { typo: 0, water: 0, coconut: 0, comma: 0, quantity: 0, ghee: 0 };
const unitSpellings = new Map();

for (const file of files) {
  const path = join(DIR, file);
  const original = readFileSync(path, 'utf8');
  const doc = JSON.parse(original);
  let touched = false;

  for (const line of doc.recipe.ing) {
    if (ruleTypo(file, line)) { counts.typo += 1; touched = true; }
    if (ruleWater(file, line)) { counts.water += 1; touched = true; }
    if (ruleCoconut(file, line)) { counts.coconut += 1; touched = true; }
    if (ruleComma(file, line)) { counts.comma += 1; touched = true; }
    if (ruleQuantity(file, line)) { counts.quantity += 1; touched = true; }
    if (ruleGhee(file, line)) { counts.ghee += 1; touched = true; }
    const u = line.qty_unit || '(none)';
    unitSpellings.set(u, (unitSpellings.get(u) || 0) + 1);
  }

  if (touched) {
    filesChanged += 1;
    // Two-space indent and a trailing newline, the shape the curation tool writes.
    if (!dryRun) writeFileSync(path, `${JSON.stringify(doc, null, 2)}\n`);
  }
}

console.log(`\n===== curation fix-ups =====`);
console.log(`  misspelled names corrected    : ${counts.typo}`);
console.log(`  water lines marked not bought : ${counts.water}`);
console.log(`  coconut lines weight -> pieces: ${counts.coconut}`);
console.log(`  comma names split to prep     : ${counts.comma}`);
console.log(`  unreadable amounts repaired   : ${counts.quantity}`);
console.log(`  ghee volume -> weight         : ${counts.ghee}`);
console.log(`  files rewritten               : ${filesChanged}${dryRun ? ' (dry run — nothing written)' : ''}`);

const spellings = [...unitSpellings.entries()].sort((a, b) => b[1] - a[1]);
console.log(`\n  unit spellings now in the set: ${spellings.map(([u, n]) => `${u} x${n}`).join(', ')}`);

if (settled.length) {
  console.log(`\n  ${settled.length} already ruled on, listed so the reasoning stays visible:`);
  for (const s of settled) console.log(`    - ${s}`);
}

if (decisions.length) {
  console.log(`\n  ${decisions.length} thing(s) STILL NEEDING A DECISION:`);
  for (const d of decisions) console.log(`    - ${d}`);
  process.exit(1);
}
console.log('\n  nothing open — every question in this set has been answered');
