# UAT-066: Write to the community

| | |
|---|---|
| **Feature area** | Communications — composing, previewing, and sending |
| **Technical stories** | E8-S2 (compose, preview, test), E8-S3 (send and the sent log) |
| **Roles exercised** | Temple admin, kitchen staff (must be refused), volunteer (receives) |
| **Depends on** | UAT-008 |
| **Environment needs** | Email must be live for steps 14 onward. WhatsApp cannot be tested at all until credentials are in — see the note below |

## What this feature is for

A temple needs to be able to write to the people who come to it: a newsletter, a festival programme, a
notice that the kitchen is closed on Tuesday, an appeal when something is short. Until now the only
messages this product could send were ones it generated itself.

## How it is supposed to work

- Write it here, **or paste it in** from whatever the temple already writes in — Word, Google Docs.
  Headings, bold, lists and links survive; styling from the other program is deliberately dropped,
  because a Google Docs style describes a page that does not exist inside an email.
- Choose a **kind** — Newsletter, Festivals and events, Seva opportunities, Appeals for support, or
  Temple notices. The kind is what a devotee's preferences act on, so it is not decoration.
- **Preview** it. What you see is built by the same code that builds the real message.
- **Send yourself a copy** before sending it to anybody else. The preview shows what we hand the mail
  relay; only a real send shows what Gmail then makes of it.
- **Send.** You are told how many people it will reach before you commit, and afterwards you can see
  each of them and whether it arrived.
- Nothing is editable once sent. A sent message is a thing that happened.

### About WhatsApp — read this before recording a defect

**WhatsApp cannot carry a newsletter, and this is not a bug.** Meta only delivers business-initiated
messages that match a template it has already approved. A pasted letter has no route onto that
channel at all. So a WhatsApp communication is a short announcement — the temple's name, the subject,
one line you write, and a link to the full thing on the web.

Nothing will actually send on WhatsApp until the temple's Meta credentials are in **and** Meta has
approved the `temple_announcement` template. Until then, test the WhatsApp half by **preview only**.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org`
- **Start at:** **/communications** (menu: **People → Communications**)
- Have a newsletter ready to paste. A few paragraphs from a Google Doc with a heading, some **bold**,
  a bulleted list and a link is exactly the right test.

## Steps

### Writing one

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Communications** | An empty list, and a control to write a new one |
| 2 | Start a new one | A form: Kind, How to send, Subject, and the letter itself |
| 3 | Look at the **Kind** list | Five kinds. **Reminders and receipts is not offered** — the system sends those, nobody writes them |
| 4 | Choose **Newsletter**, subject `Janmashtami at the temple`, and type a few lines with a heading and a list | It takes formatting as you type |
| 5 | Now **paste** several paragraphs copied from Google Docs or Word | The words and structure arrive; the other program's fonts and colours do not |
| 6 | Save | It appears in the list as a **draft**, with who wrote it and when |
| 7 | Try to save one with an empty letter | Refused |

### Looking at it before anybody else does

| # | Do this | You should see |
|---|---|---|
| 8 | Press **Preview** | The letter as it will arrive: the temple's name above it, your words in the middle, and a footer with an unsubscribe line |
| 9 | Check the footer wording | It says shift reminders and receipts are **not** affected by unsubscribing. That sentence matters — without it people assume the worst |
| 10 | Switch the preview to the phone width | Still readable; nothing runs off the side |
| 11 | Change **How to send** to **WhatsApp** and preview again | A short line — temple, subject, your one line, and a link. **Not** the letter. If the screen offers to send the whole letter on WhatsApp, that *is* a defect |
| 12 | Switch back to Email |  |

### Testing it on yourself

| # | Do this | You should see |
|---|---|---|
| 13 | Press **Send myself a copy** | Told it is on its way |
| 14 | *(Email live)* Open your inbox | The letter, from *Sri Sri Radha Govinda Temple via ISKCON Kitchen*, replying to the temple's own address |
| 15 | Compare it against the preview | They agree. Any difference is worth recording precisely |
| 16 | Check the recipient list on the draft | **Empty.** A test copy is not a send |
| 17 | Check the draft is still a draft | Yes — testing does not send it |

### Sending it

| # | Do this | You should see |
|---|---|---|
| 18 | Press **Send** | You are told **how many devotees** it will reach, and asked to confirm. Count them against your devotee list |
| 19 | Confirm | It becomes **Sent**, with the time and the number reached |
| 20 | Open it again | No Edit, no Send, no Delete. Try to reach them another way if you can — refused (`KMS-4951`) |
| 21 | Open the **recipient list** | Every person, their channel, and whether it was sent, delivered, failed or suppressed |
| 22 | Sign in as `ikms.volunteer.1@…` and read the email | The letter, with a working **Read this in your browser** link |
| 23 | Open that browser link **while signed out** | The letter on a public page. This is what the WhatsApp link would open |
| 24 | Take the browser link, change a few characters, and open it | Not found — one link must never lead to another temple's letter |
| 25 | Find a **draft**'s public link if you can construct one | Not found. Nothing half-written is readable |

### Who may do this

| # | Do this | You should see |
|---|---|---|
| 26 | Sign in as `ikms.kitchen-staff.1@…` | **No Communications** in the menu, and the page refuses if opened directly |
| 27 | As the admin, open **Audit log** | The send is recorded with the subject, the kind, the channel and the number of people |

### The empty case

| # | Do this | You should see |
|---|---|---|
| 28 | Have every devotee turn off a kind (UAT-065), then try to send that kind | Refused, saying nobody would receive it (`KMS-4952`) — rather than reporting a successful send to nobody |

## It passes if

- [ ] A letter can be typed or pasted, and pasted formatting is reduced to what an email can carry.
- [ ] Only the five composable kinds are offered.
- [ ] The preview matches what actually arrives.
- [ ] The WhatsApp form is a short line and a link, and the screen says so before anybody writes a letter for it.
- [ ] A test copy reaches the author and writes no recipients.
- [ ] The count shown before sending matches who actually receives it.
- [ ] A sent communication cannot be edited, re-sent, or deleted.
- [ ] The recipient list shows each person's channel and outcome.
- [ ] The public web copy resolves for a sent message and not for a draft or a wrong address.
- [ ] Kitchen staff are refused.
- [ ] Sending with nobody to send to is refused.

## Watch out for

- **Step 15.** The preview is only worth having if it is true. Record any difference exactly — a
  heading that lost its size, a list that lost its bullets, a link that lost its colour.
- **Step 18.** A count that disagrees with reality is worse than no count: it is the number somebody
  will quote in a meeting. Check it against your devotee list minus anyone who opted out.
- **Step 5.** Paste something genuinely messy — a Google Doc with a table and an image, not three tidy
  sentences. Note anything that survives but should not, and anything lost that a temple would miss.
- Sending twice by pressing the button twice. It must not go out twice.
- A very long letter (several thousand words) — does the preview cope, does the email arrive whole?
- Anything technical reaching the screen. Every failure here should be a `KMS-` code in plain language.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT066-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
