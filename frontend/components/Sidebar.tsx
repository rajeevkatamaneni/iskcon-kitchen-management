import Link from "next/link";

/**
 * Persistent navigation.
 *
 * <p>Icons appear here and nowhere else, and never without their label — per
 * DESIGN_SYSTEM.md, people navigate by shape and position before they read, so an icon speeds
 * recognition for daily users while the text carries the meaning for everyone else.
 *
 * <p>Deliberately no hover-reveal or blur transition. That is a marketing-site pattern for
 * dozens of destinations; we have a handful per role, it does not exist on touch devices, and
 * the animation is sluggish on the mid-range Android phones most volunteers carry.
 */

export interface NavItem {
  href: string;
  label: string;
  /** Tabler outline icon name, without the `ti-` prefix. */
  icon: string;
}

interface SidebarProps {
  templeName: string;
  items: NavItem[];
  activeHref: string;
}

export function Sidebar({ templeName, items, activeHref }: SidebarProps) {
  return (
    <nav
      aria-label="Main"
      className="w-sidebar shrink-0 border-r border-hairline bg-raised px-3 py-5"
    >
      <p className="px-3 pb-4 text-sm text-ink-muted">{templeName}</p>

      <ul className="space-y-1">
        {items.map((item) => {
          const active = item.href === activeHref;

          return (
            <li key={item.href}>
              <Link
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={[
                  "flex min-h-touch items-center gap-3 rounded px-3 text-sm",
                  "transition-colors duration-state",
                  active
                    ? "bg-accent-bg font-medium text-accent-text"
                    : "text-ink-secondary hover:bg-sunken hover:text-ink",
                ].join(" ")}
              >
                <i className={`ti ti-${item.icon} text-lg`} aria-hidden="true" />
                {item.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
