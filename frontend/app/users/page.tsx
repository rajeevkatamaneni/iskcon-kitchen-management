import { Sidebar } from "@/components/Sidebar";
import { TEMPLE_NAV } from "@/lib/nav";
import type { UserSummary } from "@/lib/api";

/**
 * Temple user management (E1-S12): the screen a Temple Admin uses to add people, change what they
 * can do, and disable those who leave — so a temple with one admin can actually staff itself.
 *
 * <p>Roles are the fixed set; a platform operator can't be minted here. Adding someone creates their
 * account ahead of their first sign-in (they claim it then, E1-S6, and give their own consent,
 * E1-S8). Disabling blocks access but never deletes — history stays intact. Like the other admin
 * screens, this is the shape for now, wired to `api.listUsers` / `api.addUser` /
 * `api.changeUserRole` / `api.setUserStatus` when the app is connected to live data.
 */

const ROLES: { value: string; label: string }[] = [
  { value: "TEMPLE_ADMIN", label: "Temple admin" },
  { value: "KITCHEN_STAFF", label: "Kitchen staff" },
  { value: "VOLUNTEER", label: "Volunteer" },
];

export default function UsersPage() {
  const users: UserSummary[] = [];

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" items={TEMPLE_NAV} activeHref="/users" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>People</h1>
            <p className="mt-1 text-ink-secondary">
              Everyone at your temple, and what they can do.
            </p>
          </header>

          <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
            <h2 id="add-heading" className="text-lg">
              Add someone
            </h2>
            <p className="mt-1 text-sm text-ink-secondary">
              They&apos;ll be able to sign in with the email or phone you enter here.
            </p>
            <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Add a person">
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Full name
                <input name="fullName" className="min-h-touch rounded border border-hairline bg-raised px-3" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Role
                <select name="role" className="min-h-touch rounded border border-hairline bg-raised px-3">
                  {ROLES.map((r) => (
                    <option key={r.value} value={r.value}>
                      {r.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Email
                <input name="email" type="email" className="min-h-touch rounded border border-hairline bg-raised px-3" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Phone
                <input name="phone" placeholder="+919876543210" className="min-h-touch rounded border border-hairline bg-raised px-3" />
              </label>
              <div className="col-span-2">
                <button
                  type="submit"
                  className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
                >
                  Add person
                </button>
              </div>
            </form>
          </section>

          {users.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Just you so far</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Add a cook, a volunteer coordinator, or a fellow administrator, and they&apos;ll
                appear here.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Name</th>
                    <th className="px-5 py-3 font-medium">Email</th>
                    <th className="px-5 py-3 font-medium">Role</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((user) => (
                    <tr key={user.id} className="border-t border-hairline">
                      <td className="px-5 py-4">{user.fullName}</td>
                      <td className="px-5 py-4 text-ink-secondary">{user.email}</td>
                      <td className="px-5 py-4">{user.role}</td>
                      <td className="px-5 py-4">{user.status}</td>
                      <td className="px-5 py-4 text-sm text-ink-secondary">Change role · Disable</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
