# Design: the SNR candidate line and Graph bookmarks (0.5.0)

**Date:** 2026-10-24
**Status:** built, awaiting CI and phone verification
**Source:** the owner's instruction of 2026-10-24 (the owner skipped the questions)
**Builds on:** `2026-09-04-relay-candidate-comparison-design.md`, `2026-10-24-node-file-design.md`

Items marked **(my call)** were not decided by the owner.

## 1. Requirements as given

1. A second vertical line on the Graph at the selected candidate's **average direct SNR**, drawn
   like the existing RSSI line but **yellow**.
2. Bookmarks, saved as `watch_points`: the owner marks where they stood when the signal looked
   good. Each crosshair row that is not bookmarked offers `+`; a bookmarked row offers `-` and `i`.
3. `-` removes the bookmark after a Yes/No pop-up "Remove bookmark".
4. `i` opens a pop-up with the bookmark's text, editable, with Ok and Cancel.
5. The symbols are black-and-white pictograms in the style of the globe, drawn at the **left** of
   the crosshair, as the globe is drawn at its right.
6. Version 0.5.0.

## 2. Decisions

| Decision | Choice |
| :--- | :--- |
| SNR average source | Direct SNR of the candidate this session, else the node file's `avg_snr` - the exact twin of the RSSI rule. **(my call:** this needed a new `directSnrAvg` on `RelayCandidate`; the node database's single `dbSnr` is a last value, not an average.) |
| Colour | `CandidateSnrLine`, `#FFD600`, its own constant. |
| Off-scale | Same clamp and triangle as the RSSI line; the SNR label sits 18 dp below the RSSI one. **(my call)** |
| What a bookmark is | A measurement: time, RSSI, SNR, observer position (with its origin) and a note. It stores its own copy, so it survives the 5000-sample buffer evicting the row. |
| Whose it is | The node's. A neighbour graph: that neighbour. A relay graph: the selected candidate, or the only candidate; with neither, `+` is disabled. **(my call)** A relay byte is not a node. |
| Where it is stored | `NodeAnnotations.watchPoints`, the field the node-file design already reserved, as one JSON object per bookmark (`WatchPointJson`, coordinates as 1e-7 integers). It is written to disk by "Save nodes" and read back by "Load nodes"; there is no separate auto-save. **(my call)** |
| Identity | A bookmark is matched to a measurement by its timestamp. |
| Statistics reset | Does not touch bookmarks (they are annotations, not statistics). |
| Bookmarks on the chart | A plain full-width line in the theme's `outline` gray on every bookmarked row whose sample is still in the series, no label. Loaded bookmarks have no sample and are not drawn; see section 3. |
| Info pop-up | Text only: the note. Cancel discards the edit. |
| Freeze | Holds the drawing, not the marks: a bookmark added while frozen shows at once. **(my call)** |
| Edit path | The screen sends a node's whole new list to the engine (`SetWatchPoints`); two edits inside one snapshot interval could overwrite each other. Accepted: edits are by hand, seconds apart. **(my call)** |

## 3. Loaded bookmarks and the carousel (added after the first build)

The first build matched a bookmark to a chart row by timestamp. The series is not saved, so
after a Load no bookmark matched any row: invisible, and impossible to edit or remove. A
bookmark is two things - a mark on a live row, and a saved record of a place and a signal -
and only the first needs a sample.

- **Marks list.** A `Marks` button in the Freeze / Auto scale row, present only while the node
  has at least one bookmark, loaded or new. It opens a sheet listing every bookmark by
  timestamp, **newest first (my call)**, each with time, RSSI, SNR, text, and the globe, `i`
  and `-` pictograms with the same pop-ups as the crosshair (shared composables). It closes
  itself when the last bookmark is removed.
- **Carousel.** A tap or drag places the crosshair as before. A **long press** steps the
  crosshair to the next bookmark that has a sample: newest first, the next older each time,
  the newest again after the oldest. The chart scrolls to it when it is off screen **(my call)**. The
  position is remembered by timestamp, so new samples shifting the rows do not disturb it. The
  press's own finger-down first places the crosshair under the finger and the long press then
  moves it on, so the line may flick once.
- **Colours.** The crosshair keeps its theme colour (`onSurface`: white in the dark theme, black
  in the light one), as the owner confirmed. The bookmark line uses the theme's `outline` gray.

## 4. Verification

CI-only for the unit tests (`WatchPointJsonTest`, `RelayCandidateTest`, `NodeDirectoryLoadTest`).
On the phone, in English and Spanish: yellow line follows Auto scale; `+` adds, tick appears,
`-` and `i` replace `+`; `-` asks first; `i` edits and Cancel discards; Save nodes then Load nodes
keeps the bookmarks.
