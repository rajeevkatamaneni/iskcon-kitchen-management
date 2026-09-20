# A book cannot tell a temple that leaf plates are a supply

*One-paragraph note for Rajeev's decision. Not built; written up as asked.*

**What the app does today.** When a recipe is imported from the library, the app creates any
ingredient the temple does not already have. A line in a library recipe carries seven things — the
name, the quantity as the book wrote it, that quantity parsed into a value and a unit, the cook's
preparation note, the "never bought" mark, and the book's own precomputed scalings — and **none of
them says what kind of thing it is**. So when a book names leaf plates, the import has nothing to go
on and files them as **food**: `is_supply` is not in the insert's column list at all, and the
column's `false` default decides it. The same happens to the Ekadashi flag, and the category falls
to "Other" because the keyword map has no rule matching "plate".

**Who it affects.** Whoever loads the recipe library — the Super Admin — and then the **Temple
Admin**, who is the one who finds leaf plates sitting in Ingredients instead of Supplies.

**So in plain terms:** a recipe book can tell us an ingredient is never bought, but it cannot tell us
it is not food, so anything like leaf plates arrives filed as an ingredient and nobody is told.

**Where this came from.** The seeding work found it. It is the twin of the gap that was closed for
"never bought" a day earlier.

**What is in the code.** The line record is `MasterRecipeIngredient` and the write-side twin
`MasterRecipeInput.Line` is narrower still — name, quantity, preparation, never-bought. The loader
writes exactly those keys into the stored recipe. The import's create-ingredient statement names
seven columns and `is_supply` is not among them. One comment in the same file does own up to the
Ekadashi half of the problem — it says the flag "is a Temple Admin's to set, and the import cannot
tell a grain from a spice", and points at a warning box on the Recipes page. **There is no equivalent
sentence, and no warning box, for supply.** So of the three facts, one is acknowledged and two are
silent.

**The argument against fixing it.** A recipe book is a book of food. A line that is really a supply
is arguably a defect in the book, not a missing field in our model — and adding a per-line flag
invites every future fact about an ingredient to be carried on a recipe line, which is the wrong
place for facts about a catalogue.

**The options.**
1. **Add `supply` to a library recipe line**, exactly as `notBought` already is. The mechanism
   already runs end to end — book JSON → stored recipe → the insert's column list — so this is the
   same shape of change, not a new one. Cost: small, and it is a change to the curated catalogue's
   file format, which means the catalogue files get rebuilt.
2. **Say nothing on the line; warn on the screen instead.** Do for supply what is already done for
   Ekadashi: after an import, list what was created so the Temple Admin can mark what is not food.
   Cost: smaller still, but it is a person's job every time rather than a fact the book carries.
3. **Leave it.** The curated catalogue is Rajeev's own 44 recipes; if none of them names a supply,
   this costs nothing today. Cost: nothing now, and it is silent the day it matters.

**The recommendation: option 1, and option 2 beside it.** The field is cheap and the mechanism is
proven, but a flag only helps where somebody remembered to set it — and the Ekadashi comment is
already an admission that the import creates rows nobody has reviewed. A list of what an import
created, with the two flags editable on it, answers all three facts at once.

**Hard to reverse?** Only mildly. Adding the field to the catalogue format means the curated files
are rewritten; removing it later would mean rewriting them again. Nothing about it is permanent in
the way an error code or a shipped migration is.
