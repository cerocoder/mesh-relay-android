# Design: the Mesh Relay user guide

## 1. Why

Mesh Relay is useful only to someone who can read its screens. Today the only documentation is a
short README and developer notes; a newcomer cannot learn what a relay byte is, why the SNR of a
packet describes only its last hop, or what to do with the numbers. The guide fixes that: it
explains every feature, shows it on a real screen, and then shows how people use it.

## 2. Goals, non-goals, anti-goals

**Goals**
- A newcomer to Meshtastic can install the app, connect to a node and correctly read the Relays
  screen within five minutes of reading.
- Every user-visible feature has a section: what it is, where to find it, a screenshot, and an
  "In practice" example.
- The examples teach decisions (move the antenna, compare two spots, notice a fragile relay), not
  only buttons.
- The guide is honest about what the app cannot tell you.

**Non-goals**
- Not a Meshtastic tutorial, not an antenna-building or radio-physics course. One short glossary,
  with links nowhere; the guide stands on its own.
- Not developer documentation (that stays in `docs/`).
- No website yet; the Markdown must move to a site later without rewriting.

**Anti-goals (a failure even if it looks finished)**
- Any screenshot or text exposing personal data: real node names/IDs, coordinates, home map area,
  notification-shade content.
- Naming or retelling any person, company, blog, forum or community, or reproducing third-party
  posts (owner's rule, 2026-11-07). No "where to ask" section.
- Third-party measured figures. Concrete numbers come from our own captures or the demo device.
- Claiming a behaviour the app does not have. Every claim is checked against the running app or
  its source.
- One "right" hop limit or SNR value. Thresholds depend on the modem preset; the guide says so.

## 3. Constraints

- Language: English, and the app UI in the screenshots is English (phone locale en-GB, app
  language setting SYSTEM).
- Audience: newcomers and a broad public; short sentences, no unexplained jargon.
- Format: Markdown under `docs/user-guide/`, screenshots under `docs/user-guide/images/`, both
  committed to this repository. Relative links only, one topic per file, one image per figure with
  alt text, so a site generator can consume the tree later.
- Screenshots: taken over `adb` from the debug build on the owner's phone (SM-S721B, 1080x2340,
  Android 16), in demo mode, so no real traffic is shown. The installed app already is the debug
  build of the current commit (0.6.1); it is not reinstalled.
- The owner's settings are backed up before capture and restored after.
- Region-neutral: no frequency plan or national rule is stated as universal.

## 4. Structure

`docs/user-guide/`
- `README.md` - contents, how to read, version covered (0.6.1).
- `00-start.md` - what the app shows and why (last-hop SNR, one-byte relay); first five minutes; glossary.
- `01-connecting.md` - device list, permissions, pairing, the demo device (debug only).
- `02-relays.md` - Relays screen: header, cards, gauges, sort modes, pause/reset.
- `03-relay-details.md` - numbers, Matching nodes (candidates, skip), Remote nodes, a remote node's page.
- `04-neighbours.md`, `05-my-node.md`.
- `06-graph.md` - signal over time, crosshair, bookmarks and Marks, Compare with.
- `07-data.md` - Export data (CSV), Save/Load nodes.
- `08-settings.md`.
- `09-recipes.md` - ten end-to-end jobs built from the features.
- `10-limits.md` - what the app cannot do; troubleshooting; good habits.
- `images/` - screenshots plus `images/README.md` saying how each was made.

(Changed from the first draft: the planned separate "remote nodes" page became part of
`03-relay-details.md`, because the app has no top-level Remote nodes tab.)

Every feature section ends with **In practice**: one short, anonymous, generic scenario written
from scratch.

## 5. Process

1. Verify the feature list against the app source, `strings.xml` and the running app, screen by screen.
2. Capture the screenshots (privacy checklist in `.research-tmp/notes/05-screenshot-plan.md`).
3. Write each page from the verified behaviour and its screenshots; read each page against the app.
4. Owner reviews the whole guide.
No commit is made until the owner asks.

## 6. Left to the builder

- Exact wording, figure numbering, which screenshots need crops or masks.
- Which demo scenario states best show each feature, and, where the demo cannot show a feature
  (a node with a position has none), how to mark that honestly in the guide or capture it another way.
- Whether a screen needs two shots (light/dark, simple/complex).

## 7. To confirm when reading

- Demo-mode limits: the demo's local node has no coordinates, so distance/bearing and the crosshair
  globe may not appear; the guide must not show invented positions.
- Whether a "dark mode" capture is worth the extra images.
