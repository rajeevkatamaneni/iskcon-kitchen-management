#!/usr/bin/env node
/**
 * Builds the recipe library's book files from Rajeev's curated set.
 *
 * His curation tool writes **one file per recipe**; the loader reads **one file per book**, with
 * the recipes nested inside it and a category index at the top. This is the permanent converter
 * between the two, so the staging load does not depend on something thrown together in a temp
 * directory to prove a point.
 *
 *   node tools/seed/02b-build-catalogue.mjs [--in <dir>] [--out <dir>] [--dry-run] [--keep-old]
 *
 * In:  docs/work/reference/curated-recipes/   (the canonical copy — see that folder's README)
 * Out: backend/src/main/resources/recipe-library/
 *
 * **It replaces the whole directory.** The 33 vendored books go; the curated books take their
 * place. That deliberately ends the rule the loader's own comment states — that the books are
 * "byte-for-byte copies rather than pre-transformed ones, so they can still be diffed against
 * upstream". Rajeev's decision, 2026-09-19: his 45 approved recipes are the master catalogue now,
 * and there is no upstream to diff against any more. `--keep-old` leaves them in place for a
 * side-by-side comparison.
 *
 * **It carries `prep` and `not_bought` through**, which is the whole reason this exists. The
 * loader dropped both until the fix that goes with this work; a converter that quietly lost them
 * on the way in would put the gap back in a different place.
 *
 * **It round-trips before it writes.** Every curated recipe must come back out of the books it
 * built, with every field it went in with. A converter nobody checks is a converter that loses
 * one field in one recipe and is not noticed until a cook follows it.
 */

import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync, unlinkSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_IN = resolve(HERE, '../../docs/work/reference/curated-recipes');
const DEFAULT_OUT = resolve(HERE, '../../backend/src/main/resources/recipe-library');

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const value = (name, fallback) => {
  const at = args.indexOf(name);
  return at >= 0 ? resolve(args[at + 1]) : fallback;
};

const IN = value('--in', DEFAULT_IN);
const OUT = value('--out', DEFAULT_OUT);
const dryRun = flag('--dry-run');
const keepOld = flag('--keep-old');

/** Every field the loader reads off a book, so the round-trip check knows what to look for. */
const BOOK_FIELDS = ['slug', 'state', 'language', 'categories', 'title', 'title_l', 'tagline', 'recipes'];

/** Every field a curated recipe can carry. Anything here must survive into the book. */
const RECIPE_FIELDS = [
  'slug', 'name', 'name_l', 'cat', 'badge', 'sub', 'sub_l', 'yield', 'per', 'cost',
  'ing', 'method', 'catering', 'why', 'why_l', 'notes', 'region', 'tags', 'serve_with',
];

/** Every field an ingredient line can carry, including the two the old loader dropped. */
const LINE_FIELDS = [
  'name', 'name_l', 'qty', 'qty_l', 'qty_amount', 'qty_unit', 'prep', 'not_bought',
  'derived', 'src_i', 'scaled',
];

/** "andhra_pradesh" -> "Andhra Pradesh", for a book whose state name is missing. */
function titleCase(slug) {
  return slug.split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

/** "rice" -> "Rice", "sabjis-dry" -> "Sabjis Dry". Only used when the source book is gone. */
function categoryName(key) {
  return key.split(/[-_]/).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

// ---------------------------------------------------------------- reading

const files = readdirSync(IN).filter((f) => f.endsWith('.json') && f !== 'index.json').sort();
if (files.length === 0) {
  console.error(`No curated recipes in ${IN}`);
  process.exit(1);
}

const curated = [];
for (const file of files) {
  const doc = JSON.parse(readFileSync(join(IN, file), 'utf8'));
  if (doc.status && doc.status !== 'approved') {
    console.log(`  skipped ${file} — status "${doc.status}", not approved`);
    continue;
  }
  curated.push({ file, doc });
}
console.log(`${curated.length} approved recipe(s) in ${IN}`);

// The old books, read only for their category names and their titles, so a converted book still
// says "Sabji's, Dry" the way the temple does rather than a slug turned back into words.
const oldBooks = new Map();
if (existsSync(OUT)) {
  for (const file of readdirSync(OUT).filter((f) => f.endsWith('.json'))) {
    try {
      const book = JSON.parse(readFileSync(join(OUT, file), 'utf8'));
      if (book.slug) oldBooks.set(book.slug, book);
    } catch { /* a malformed old book is not a reason to stop */ }
  }
}

// ---------------------------------------------------------------- grouping

const byBook = new Map();
for (const { file, doc } of curated) {
  const slug = doc.book;
  if (!slug) {
    console.error(`  ${file} has no "book" — cannot tell which state it belongs to`);
    process.exit(1);
  }
  if (!byBook.has(slug)) byBook.set(slug, []);
  byBook.get(slug).push({ file, doc });
}

const books = [];
for (const [slug, entries] of [...byBook.entries()].sort()) {
  const old = oldBooks.get(slug);
  const state = entries[0].doc.state || old?.state || titleCase(slug);
  const language = entries[0].doc.language || old?.language || 'English';

  const recipes = entries
    .map(({ doc }) => doc.recipe)
    .sort((a, b) => String(a.slug).localeCompare(String(b.slug)));

  // The category index the loader reads. Counted from what is actually in this book, not copied
  // from the old one — most of the old categories no longer have a recipe in them.
  const counts = new Map();
  for (const recipe of recipes) {
    const key = recipe.cat || 'other';
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  const oldNames = new Map((old?.categories || []).map((c) => [c.key, c]));
  const categories = [...counts.entries()].sort().map(([key, n]) => {
    const previous = oldNames.get(key);
    return {
      key,
      name: previous?.name || categoryName(key),
      ...(previous?.name_l ? { name_l: previous.name_l } : {}),
      n,
    };
  });

  books.push({
    slug,
    state,
    language,
    categories,
    title: old?.title || `${state} Temple Recipes`,
    title_l: old?.title_l || null,
    tagline: `${recipes.length} recipe${recipes.length === 1 ? '' : 's'} approved for ISKCON `
             + `South Bengaluru, curated from the ${state} book.`,
    recipes,
  });
}

// ---------------------------------------------------------------- the round trip

let lost = 0;
const byId = new Map(curated.map(({ doc }) => [`${doc.book}/${doc.recipe.slug}`, doc]));
const seen = new Set();

for (const book of books) {
  for (const field of BOOK_FIELDS) {
    if (!(field in book)) {
      console.error(`  ${book.slug}: the book has no "${field}"`);
      lost += 1;
    }
  }
  for (const recipe of book.recipes) {
    const id = `${book.slug}/${recipe.slug}`;
    const source = byId.get(id);
    if (!source) {
      console.error(`  ${id} is in a book but not in the curated set`);
      lost += 1;
      continue;
    }
    seen.add(id);

    for (const field of RECIPE_FIELDS) {
      const had = field in source.recipe;
      const has = field in recipe;
      if (had && !has) {
        console.error(`  ${id}: lost the recipe's "${field}"`);
        lost += 1;
      }
    }
    const sourceLines = source.recipe.ing || [];
    if ((recipe.ing || []).length !== sourceLines.length) {
      console.error(`  ${id}: ${sourceLines.length} ingredient(s) went in, `
                    + `${(recipe.ing || []).length} came out`);
      lost += 1;
      continue;
    }
    for (let i = 0; i < sourceLines.length; i += 1) {
      for (const field of LINE_FIELDS) {
        const had = field in sourceLines[i];
        const has = field in (recipe.ing[i] || {});
        if (had && !has) {
          console.error(`  ${id}: line ${i + 1} (${sourceLines[i].name}) lost "${field}"`);
          lost += 1;
        } else if (had && JSON.stringify(sourceLines[i][field]) !== JSON.stringify(recipe.ing[i][field])) {
          console.error(`  ${id}: line ${i + 1} (${sourceLines[i].name}) changed "${field}": `
                        + `${JSON.stringify(sourceLines[i][field])} -> `
                        + `${JSON.stringify(recipe.ing[i][field])}`);
          lost += 1;
        }
      }
    }
  }
}

for (const id of byId.keys()) {
  if (!seen.has(id)) {
    console.error(`  ${id} is in the curated set but reached no book`);
    lost += 1;
  }
}

if (lost > 0) {
  console.error(`\n${lost} problem(s) in the round trip. Nothing written.`);
  process.exit(1);
}

// ---------------------------------------------------------------- writing

const preps = curated.reduce(
  (n, { doc }) => n + (doc.recipe.ing || []).filter((l) => l.prep).length, 0);
const notBought = curated.reduce(
  (n, { doc }) => n + (doc.recipe.ing || []).filter((l) => l.not_bought).length, 0);
const lines = curated.reduce((n, { doc }) => n + (doc.recipe.ing || []).length, 0);

console.log(`\nround trip clean: ${curated.length} recipes, ${lines} ingredient lines, `
            + `${preps} preparations and ${notBought} not-bought marks all present`);

if (dryRun) {
  console.log('\ndry run — nothing written. Would write:');
  for (const book of books) {
    console.log(`  ${book.slug}.json  ${book.recipes.length} recipe(s), `
                + `${book.categories.length} category(s)`);
  }
  process.exit(0);
}

mkdirSync(OUT, { recursive: true });

let removed = 0;
if (!keepOld) {
  const keeping = new Set(books.map((b) => `${b.slug}.json`));
  for (const file of readdirSync(OUT).filter((f) => f.endsWith('.json'))) {
    if (!keeping.has(file)) {
      unlinkSync(join(OUT, file));
      removed += 1;
    }
  }
}

for (const book of books) {
  writeFileSync(join(OUT, `${book.slug}.json`), `${JSON.stringify(book, null, 2)}\n`);
  console.log(`  wrote ${book.slug}.json — ${book.recipes.length} recipe(s), `
              + `${book.categories.map((c) => `${c.name} (${c.n})`).join(', ')}`);
}

console.log(`\n${books.length} book(s) written to ${OUT}`);
if (removed) {
  console.log(`${removed} old vendored book(s) removed — the curated set is the catalogue now.`);
}
console.log('The loader still has to be told: POST /api/v1/library/recipes/load as the super admin.');
