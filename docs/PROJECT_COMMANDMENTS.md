# The Ten Commandments — ISKCON Kitchen Management System Project

**1. Requirements & Wireframes First**
Build mock wireframes and collaborate with the user to refine requirements and features. Once every feature across the entire application is approved and no open questions remain, document the full set of requirements and features in a clear, logically organized specification before proceeding.

**2. System Design & Architecture**
Design the system architecture with multi-tenancy as a first-class concern, covering Identity and Access Management, application security, performance, observability, and cloud cost optimization, among other relevant dimensions.

**3. Technology Selection**
Select the technology stack: frontend framework, CMS (if applicable), backend framework, database, cloud provider (evaluated for cost and reliability), source control system, issue-tracking system, testing frameworks, and any other required tooling.

**4. Epics & User Stories**
Break requirements into epics, then decompose each epic into user stories. Each story should include assumptions, detailed requirements, mockups where useful, and clear, unambiguous acceptance criteria. Keep stories small — ideally one per feature; where a feature is too large, split it into smaller, self-contained units along logical boundaries. Upload all stories to the chosen issue-tracking system.

**5. Implementation**
Implement one story at a time. Code must be readable, logically structured, single-purpose, testable, and maintainable at scale. For each story, write unit and integration tests and run them to confirm they pass. A coding story is **done** when its automated tests pass, it has been reviewed, and it conforms to the locked design documents — and, where the story adds a user-facing surface, when that surface has also been smoke-tested by hand. Coding stories are not held open waiting on user acceptance; that is Commandment 6, and it runs on its own cadence.

**6. User Acceptance Testing**
User acceptance testing is a distinct activity from coding, scoped to a **demonstrable capability** — the smallest slice a person can actually drive end to end — not to individual coding stories. A capability often spans several stories, and pure-infrastructure work (tenant isolation, the audit kernel, background jobs, observability) has no manual surface and is accepted on its automated tests alone; forcing a one-to-one UAT story onto such work yields hollow tests that train everyone to rubber-stamp the checkbox.

For each demonstrable capability, write a UAT story containing: preconditions and setup; numbered steps; the expected result of each step; the acceptance criteria; what to look out for (edge cases, and the specific `KMS-nnnn` codes that should appear); and a defects section. Every UAT story names the coding stories it exercises, and every coding story links the UAT story that will cover it, so nothing is silently untested. A coding story that closes with no manual UAT records one line stating how it was verified.

When a UAT pass runs: collect feedback, log a defect in the issue tracker per reported issue, fix each with tests added or updated and evidence attached, and return a fresh, detailed retest plan until nothing is outstanding. UAT batches naturally at capability and release boundaries (Commandment 7); it is not a gate on every merge.

**7. Iterate**
This completes one full release cycle. Release to customers, gather feedback, and begin the next iteration — whether new features or enhancements to existing ones — repeating this same process.

**8. When In Doubt, Ask**
Never assume. When a requirement is unclear, missing, or contradicts something else, always ask the user rather than guessing. Every decision must be grounded in truth — either the documented requirements or the actual code — never in assumption.

**9. Challenge Each Other**
This is a partnership, not a one-way instruction channel. Push back when a requirement, design choice, or decision is not the most logical or sound option — explain why, and propose an alternative. Agreement should be earned, not automatic. Likewise, the user is expected to challenge Claude's recommendations in return. The goal of this friction is a better product, not deference to whoever spoke last.
