/**
 * Design system reference screen.
 *
 * Not a product page — it exists so every token can be seen in one place, and so a
 * regression in the palette or type scale is visible immediately rather than
 * discovered three stories later. Moved off "/" once that became the authenticated
 * landing router; still here for design regressions.
 */
export default function DesignReferencePage() {
  return (
    <main className="mx-auto max-w-content px-6 py-12">
      <header className="mb-10">
        <h1>Design reference</h1>
        <p className="mt-2 text-ink-secondary">
          Tokens in context. See docs/DESIGN_SYSTEM.md for reasoning.
        </p>
      </header>

      <section className="mb-10">
        <h2 className="mb-4">Surfaces</h2>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="rounded-lg bg-raised p-5">
            <p className="text-sm text-ink-secondary">raised</p>
            <p className="mt-1 font-mono text-sm">#FAF8F7</p>
          </div>
          <div className="rounded-lg bg-sunken p-5">
            <p className="text-sm text-ink-secondary">sunken</p>
            <p className="mt-1 font-mono text-sm">#F1EDEB</p>
          </div>
          <div className="rounded-lg border border-hairline p-5">
            <p className="text-sm text-ink-secondary">hairline border</p>
            <p className="mt-1 font-mono text-sm">#E7E1DD</p>
          </div>
        </div>
      </section>

      <section className="mb-10">
        <h2 className="mb-4">Type scale</h2>
        <div className="rounded-lg bg-raised p-6">
          <p className="text-3xl">Janmashtami feast planning</p>
          <p className="mt-3 text-2xl">Kitchen inventory</p>
          <p className="mt-3 text-xl">Purchase orders</p>
          <p className="mt-3 text-lg">Prasadam serving shift</p>
          <p className="mt-3 text-base">
            Body text at sixteen pixels. Chosen over fourteen for bright kitchen
            lighting and older devotees, and over seventeen to keep inventory tables
            dense enough to be useful.
          </p>
          <p className="mt-3 text-sm text-ink-secondary">
            Secondary text for labels and supporting copy.
          </p>
          <p className="mt-3 text-xs text-ink-muted">
            Metadata, timestamps, table annotations.
          </p>
        </div>
      </section>

      <section className="mb-10">
        <h2 className="mb-4">Actions</h2>
        <div className="flex flex-wrap items-center gap-3">
          <button className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover">
            Save meal plan
          </button>
          <button className="min-h-touch rounded border border-hairline-strong bg-canvas px-5 transition-colors duration-state hover:bg-raised">
            Cancel
          </button>
        </div>
        <p className="mt-3 text-sm text-ink-secondary">
          One terracotta action per screen. If a second appears, one of them is not primary.
        </p>
      </section>

      <section className="mb-10">
        <h2 className="mb-4">Status</h2>
        <div className="flex flex-wrap gap-2">
          <span className="rounded-sm bg-warning-bg px-3 py-1 text-sm text-warning">
            Low stock &middot; 4 kg
          </span>
          <span className="rounded-sm bg-danger-bg px-3 py-1 text-sm text-danger">
            Invoice overdue
          </span>
          <span className="rounded-sm bg-success-bg px-3 py-1 text-sm text-success">
            Shift fully staffed
          </span>
        </div>
        <p className="mt-3 text-sm text-ink-secondary">
          Every badge carries text. Status is never colour alone — kitchens are bright,
          screens are cheap, and roughly one in twelve men has a colour vision
          deficiency.
        </p>
      </section>

      <section>
        <h2 className="mb-4">Script fallback</h2>
        <div className="rounded-lg bg-raised p-6">
          <p className="text-lg">Khichdi &middot; खिचड़ी &middot; కిచిడీ &middot; கிச்சடி</p>
          <p className="mt-2 text-sm text-ink-secondary">
            Every script is Anek — Latin, Devanagari, Telugu and Tamil drawn as one
            system, resolved per glyph with no font switching in application code.
          </p>
        </div>
      </section>
    </main>
  );
}
