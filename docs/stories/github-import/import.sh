#!/usr/bin/env bash
# One-time import of Stage 4 epics & stories into GitHub.
# Prereqs: GitHub CLI installed (https://cli.github.com) and authenticated: gh auth login
# Usage:   ./import.sh <owner>/<repo>     e.g. ./import.sh rajeevkatamaneni/iskcon-kitchen-management
set -euo pipefail
REPO="${1:?Usage: ./import.sh <owner>/<repo>}"
cd "$(dirname "$0")"

echo "== Creating labels =="
gh label create "story" --repo "$REPO" --color "0E8A16" --description "User story" --force
gh label create "epic:foundation" --repo "$REPO" --color "1D76DB" --description "Platform Foundation" --force
gh label create "epic:recipes" --repo "$REPO" --color "D93F0B" --description "Recipe Management" --force
gh label create "epic:inventory" --repo "$REPO" --color "FBCA04" --description "Inventory Management" --force
gh label create "epic:planning" --repo "$REPO" --color "5319E7" --description "Meal Planning & Calendar" --force
gh label create "epic:ordering" --repo "$REPO" --color "B60205" --description "Ordering & Vendors" --force
gh label create "epic:workforce" --repo "$REPO" --color "0052CC" --description "Workforce Management" --force
gh label create "epic:payments" --repo "$REPO" --color "006B75" --description "Payments & Donations" --force

echo "== Creating milestones (one per epic) =="
gh api "repos/$REPO/milestones" -f title="Epic 1: Platform Foundation" >/dev/null 2>&1 || echo "  milestone exists: Epic 1: Platform Foundation"
gh api "repos/$REPO/milestones" -f title="Epic 2: Recipe Management" >/dev/null 2>&1 || echo "  milestone exists: Epic 2: Recipe Management"
gh api "repos/$REPO/milestones" -f title="Epic 3: Inventory Management" >/dev/null 2>&1 || echo "  milestone exists: Epic 3: Inventory Management"
gh api "repos/$REPO/milestones" -f title="Epic 4: Meal Planning & Calendar" >/dev/null 2>&1 || echo "  milestone exists: Epic 4: Meal Planning & Calendar"
gh api "repos/$REPO/milestones" -f title="Epic 5: Ordering & Vendors" >/dev/null 2>&1 || echo "  milestone exists: Epic 5: Ordering & Vendors"
gh api "repos/$REPO/milestones" -f title="Epic 6: Workforce Management" >/dev/null 2>&1 || echo "  milestone exists: Epic 6: Workforce Management"
gh api "repos/$REPO/milestones" -f title="Epic 7: Payments & Donations" >/dev/null 2>&1 || echo "  milestone exists: Epic 7: Payments & Donations"

echo "== Creating 55 issues =="
echo "  E1-S1"; gh issue create --repo "$REPO" --title "E1-S1: Project scaffolding and CI pipeline" --body-file "bodies/e1-s1.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S2"; gh issue create --repo "$REPO" --title "E1-S2: GCP infrastructure baseline" --body-file "bodies/e1-s2.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S3"; gh issue create --repo "$REPO" --title "E1-S3: Tenant model and Row-Level Security" --body-file "bodies/e1-s3.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S4"; gh issue create --repo "$REPO" --title "E1-S4: Firebase Authentication integration" --body-file "bodies/e1-s4.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S5"; gh issue create --repo "$REPO" --title "E1-S5: Role-based access control" --body-file "bodies/e1-s5.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S6"; gh issue create --repo "$REPO" --title "E1-S6: Tenant provisioning (Super-Admin)" --body-file "bodies/e1-s6.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S7"; gh issue create --repo "$REPO" --title "E1-S7: Audit log framework" --body-file "bodies/e1-s7.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S8"; gh issue create --repo "$REPO" --title "E1-S8: User accounts: contact channels and communication preference" --body-file "bodies/e1-s8.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S9"; gh issue create --repo "$REPO" --title "E1-S9: Background job infrastructure (Quartz)" --body-file "bodies/e1-s9.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S10"; gh issue create --repo "$REPO" --title "E1-S10: Notification service (WhatsApp / SMS / email)" --body-file "bodies/e1-s10.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E1-S11"; gh issue create --repo "$REPO" --title "E1-S11: Observability baseline" --body-file "bodies/e1-s11.md" --label "story,epic:foundation" --milestone "Epic 1: Platform Foundation" >/dev/null
echo "  E2-S1"; gh issue create --repo "$REPO" --title "E2-S1: Ingredient master" --body-file "bodies/e2-s1.md" --label "story,epic:recipes" --milestone "Epic 2: Recipe Management" >/dev/null
echo "  E2-S2"; gh issue create --repo "$REPO" --title "E2-S2: Recipe CRUD" --body-file "bodies/e2-s2.md" --label "story,epic:recipes" --milestone "Epic 2: Recipe Management" >/dev/null
echo "  E2-S3"; gh issue create --repo "$REPO" --title "E2-S3: Recipe scaling" --body-file "bodies/e2-s3.md" --label "story,epic:recipes" --milestone "Epic 2: Recipe Management" >/dev/null
echo "  E2-S4"; gh issue create --repo "$REPO" --title "E2-S4: Sattvic enforcement on recipes" --body-file "bodies/e2-s4.md" --label "story,epic:recipes" --milestone "Epic 2: Recipe Management" >/dev/null
echo "  E2-S5"; gh issue create --repo "$REPO" --title "E2-S5: Recipe PDF and print (English)" --body-file "bodies/e2-s5.md" --label "story,epic:recipes" --milestone "Epic 2: Recipe Management" >/dev/null
echo "  E2-S6"; gh issue create --repo "$REPO" --title "E2-S6: Recipe translation + translated PDF/print" --body-file "bodies/e2-s6.md" --label "story,epic:recipes" --milestone "Epic 2: Recipe Management" >/dev/null
echo "  E2-S7"; gh issue create --repo "$REPO" --title "E2-S7: Recipe browse and search UX" --body-file "bodies/e2-s7.md" --label "story,epic:recipes" --milestone "Epic 2: Recipe Management" >/dev/null
echo "  E3-S1"; gh issue create --repo "$REPO" --title "E3-S1: Consumable inventory items and stock view" --body-file "bodies/e3-s1.md" --label "story,epic:inventory" --milestone "Epic 3: Inventory Management" >/dev/null
echo "  E3-S2"; gh issue create --repo "$REPO" --title "E3-S2: Stock movements ledger" --body-file "bodies/e3-s2.md" --label "story,epic:inventory" --milestone "Epic 3: Inventory Management" >/dev/null
echo "  E3-S3"; gh issue create --repo "$REPO" --title "E3-S3: Reorder thresholds and low-stock alerts" --body-file "bodies/e3-s3.md" --label "story,epic:inventory" --milestone "Epic 3: Inventory Management" >/dev/null
echo "  E3-S4"; gh issue create --repo "$REPO" --title "E3-S4: Equipment inventory" --body-file "bodies/e3-s4.md" --label "story,epic:inventory" --milestone "Epic 3: Inventory Management" >/dev/null
echo "  E3-S5"; gh issue create --repo "$REPO" --title "E3-S5: In-kind donation intake" --body-file "bodies/e3-s5.md" --label "story,epic:inventory" --milestone "Epic 3: Inventory Management" >/dev/null
echo "  E3-S6"; gh issue create --repo "$REPO" --title "E3-S6: Consumption on meal production" --body-file "bodies/e3-s6.md" --label "story,epic:inventory" --milestone "Epic 3: Inventory Management" >/dev/null
echo "  E3-S7"; gh issue create --repo "$REPO" --title "E3-S7: Manual stock adjustment" --body-file "bodies/e3-s7.md" --label "story,epic:inventory" --milestone "Epic 3: Inventory Management" >/dev/null
echo "  E4-S1"; gh issue create --repo "$REPO" --title "E4-S1: Calendar engine: astronomical computation" --body-file "bodies/e4-s1.md" --label "story,epic:planning" --milestone "Epic 4: Meal Planning & Calendar" >/dev/null
echo "  E4-S2"; gh issue create --repo "$REPO" --title "E4-S2: Festival occasion catalog" --body-file "bodies/e4-s2.md" --label "story,epic:planning" --milestone "Epic 4: Meal Planning & Calendar" >/dev/null
echo "  E4-S3"; gh issue create --repo "$REPO" --title "E4-S3: Admin calendar override" --body-file "bodies/e4-s3.md" --label "story,epic:planning" --milestone "Epic 4: Meal Planning & Calendar" >/dev/null
echo "  E4-S4"; gh issue create --repo "$REPO" --title "E4-S4: Meal plan CRUD across four contexts" --body-file "bodies/e4-s4.md" --label "story,epic:planning" --milestone "Epic 4: Meal Planning & Calendar" >/dev/null
echo "  E4-S5"; gh issue create --repo "$REPO" --title "E4-S5: Ingredient sufficiency and shortfall feed" --body-file "bodies/e4-s5.md" --label "story,epic:planning" --milestone "Epic 4: Meal Planning & Calendar" >/dev/null
echo "  E4-S6"; gh issue create --repo "$REPO" --title "E4-S6: Ekadashi violation flagging" --body-file "bodies/e4-s6.md" --label "story,epic:planning" --milestone "Epic 4: Meal Planning & Calendar" >/dev/null
echo "  E5-S1"; gh issue create --repo "$REPO" --title "E5-S1: Vendor management" --body-file "bodies/e5-s1.md" --label "story,epic:ordering" --milestone "Epic 5: Ordering & Vendors" >/dev/null
echo "  E5-S2"; gh issue create --repo "$REPO" --title "E5-S2: Auto-generated order list" --body-file "bodies/e5-s2.md" --label "story,epic:ordering" --milestone "Epic 5: Ordering & Vendors" >/dev/null
echo "  E5-S3"; gh issue create --repo "$REPO" --title "E5-S3: Purchase order generation and lifecycle" --body-file "bodies/e5-s3.md" --label "story,epic:ordering" --milestone "Epic 5: Ordering & Vendors" >/dev/null
echo "  E5-S4"; gh issue create --repo "$REPO" --title "E5-S4: PO document: PDF and print (English)" --body-file "bodies/e5-s4.md" --label "story,epic:ordering" --milestone "Epic 5: Ordering & Vendors" >/dev/null
echo "  E5-S5"; gh issue create --repo "$REPO" --title "E5-S5: PO translation" --body-file "bodies/e5-s5.md" --label "story,epic:ordering" --milestone "Epic 5: Ordering & Vendors" >/dev/null
echo "  E5-S6"; gh issue create --repo "$REPO" --title "E5-S6: Receiving: full, partial, and rejected deliveries" --body-file "bodies/e5-s6.md" --label "story,epic:ordering" --milestone "Epic 5: Ordering & Vendors" >/dev/null
echo "  E5-S7"; gh issue create --repo "$REPO" --title "E5-S7: WhatsApp PO delivery" --body-file "bodies/e5-s7.md" --label "story,epic:ordering" --milestone "Epic 5: Ordering & Vendors" >/dev/null
echo "  E5-S8"; gh issue create --repo "$REPO" --title "E5-S8: Vendor invoice capture" --body-file "bodies/e5-s8.md" --label "story,epic:ordering" --milestone "Epic 5: Ordering & Vendors" >/dev/null
echo "  E6-S1"; gh issue create --repo "$REPO" --title "E6-S1: Staff profiles and weekly schedule" --body-file "bodies/e6-s1.md" --label "story,epic:workforce" --milestone "Epic 6: Workforce Management" >/dev/null
echo "  E6-S2"; gh issue create --repo "$REPO" --title "E6-S2: Volunteer shift posting with reminder configuration" --body-file "bodies/e6-s2.md" --label "story,epic:workforce" --milestone "Epic 6: Workforce Management" >/dev/null
echo "  E6-S3"; gh issue create --repo "$REPO" --title "E6-S3: Volunteer signup" --body-file "bodies/e6-s3.md" --label "story,epic:workforce" --milestone "Epic 6: Workforce Management" >/dev/null
echo "  E6-S4"; gh issue create --repo "$REPO" --title "E6-S4: Signup release" --body-file "bodies/e6-s4.md" --label "story,epic:workforce" --milestone "Epic 6: Workforce Management" >/dev/null
echo "  E6-S5"; gh issue create --repo "$REPO" --title "E6-S5: Waitlist with auto-promotion" --body-file "bodies/e6-s5.md" --label "story,epic:workforce" --milestone "Epic 6: Workforce Management" >/dev/null
echo "  E6-S6"; gh issue create --repo "$REPO" --title "E6-S6: Scheduled shift reminders" --body-file "bodies/e6-s6.md" --label "story,epic:workforce" --milestone "Epic 6: Workforce Management" >/dev/null
echo "  E6-S7"; gh issue create --repo "$REPO" --title "E6-S7: One-off reminder broadcast" --body-file "bodies/e6-s7.md" --label "story,epic:workforce" --milestone "Epic 6: Workforce Management" >/dev/null
echo "  E7-S1"; gh issue create --repo "$REPO" --title "E7-S1: Public temple donation page (WITHDRAWN 2026-08-29)" --body-file "bodies/e7-s1.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null
echo "  E7-S2"; gh issue create --repo "$REPO" --title "E7-S2: One-time donation via Razorpay" --body-file "bodies/e7-s2.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null
echo "  E7-S3"; gh issue create --repo "$REPO" --title "E7-S3: Recurring donation" --body-file "bodies/e7-s3.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null
echo "  E7-S4"; gh issue create --repo "$REPO" --title "E7-S4: 80G donor data capture" --body-file "bodies/e7-s4.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null
echo "  E7-S5"; gh issue create --repo "$REPO" --title "E7-S5: Wish list management (admin)" --body-file "bodies/e7-s5.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null
echo "  E7-S6"; gh issue create --repo "$REPO" --title "E7-S6: Wish list and sponsorship checkout" --body-file "bodies/e7-s6.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null
echo "  E7-S7"; gh issue create --repo "$REPO" --title "E7-S7: Donations ledger and accounting view" --body-file "bodies/e7-s7.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null
echo "  E7-S8"; gh issue create --repo "$REPO" --title "E7-S8: Vendor invoice payment recording" --body-file "bodies/e7-s8.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null
echo "  E7-S9"; gh issue create --repo "$REPO" --title "E7-S9: Razorpay webhook infrastructure" --body-file "bodies/e7-s9.md" --label "story,epic:payments" --milestone "Epic 7: Payments & Donations" >/dev/null

echo "Done. All issues created in $REPO."
echo "Optional: add them to a Project board with:"
echo "  gh project item-add <project-number> --owner <owner> --url <issue-url>"
