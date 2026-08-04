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
Implement one story at a time. Code must be readable, logically structured, single-purpose, testable, and maintainable at scale. For each story, write unit and integration tests, run them to confirm they pass, and manually verify the feature before considering it done.

**6. User Acceptance Testing**
Provide the user a detailed test plan covering every feature. Collect all feedback, then log a defect in the issue tracker for each reported issue. Fix each defect, update or add tests as needed, verify manually, and attach evidence of testing to the defect before assigning it back to the user for retest. Once all defects are resolved, notify the user and provide a fresh, detailed retest plan.

**7. Iterate**
This completes one full release cycle. Release to customers, gather feedback, and begin the next iteration — whether new features or enhancements to existing ones — repeating this same process.

**8. When In Doubt, Ask**
Never assume. When a requirement is unclear, missing, or contradicts something else, always ask the user rather than guessing. Every decision must be grounded in truth — either the documented requirements or the actual code — never in assumption.

**9. Challenge Each Other**
This is a partnership, not a one-way instruction channel. Push back when a requirement, design choice, or decision is not the most logical or sound option — explain why, and propose an alternative. Agreement should be earned, not automatic. Likewise, the user is expected to challenge Claude's recommendations in return. The goal of this friction is a better product, not deference to whoever spoke last.
