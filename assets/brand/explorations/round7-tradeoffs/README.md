# Round 7: the colour trade-offs, on the Fold

Built 2026-10-13 after the first device pass of PR 1 (the reskin preview on the
Fold, screenshots in `/root/huginn-reskin-shots/2026-10-13-pr1/`). BRAND.md
section 4 named three trade-offs to settle before the tokens freeze; the device
pass added a fourth. `index.html` is the chooser: one card per option, each the
real screens (Sessions, Rounds, Status, Settings routes, the setup rail, the
terminal block, the desktop tray at 64/24/16, the bottom bar) drawn in that
option's colours, dark and light at a switch. It writes the BRAND.md record row
and the token changes; nothing is sent from the page.

| question | what is at stake | recommended |
|---|---|---|
| Q1 two greens | working (moss) and success (lime) read as one family | B, ok is quiet: success in the ink, the terminal keeps brightGreen |
| Q2 where heather lives | the second voice never reaches a state; info is a third hue | B, info is heather, the blue leaves |
| Q3 the lit moss | needs-you beside the lime reads as highlighter; it is also every warning now | A, keep it, once Q1 removes the lime |
| Q4 the active tab | the bottom bar sits on Material's undefined violet | A, primaryContainer, the selected-row tint |

Serve it from huginn with any static server on the explorations folder; the
2026-10-13 session used port 8097. After the owner's picks: edit
`../color/build/spec.py`, run `palette.py` (it refuses if a contrast rule
fails), regenerate `tokens.json`, `Theme.kt`, `Ansi.kt` and `TrayIcons.kt`,
re-shoot the four screens, and add the row to BRAND.md "Record of decisions".

**Decided 2026-10-13 (owner, on the Fold):** Q1 C teal, Q2 B heather info, Q3 B gold, Q4 A moss pill.
The generator refused two of the page's proposed light twins (needs-you `#7A5A16` was only 6.6 L*
from working; tertiary collided with heather and then with idle/error) and settled on `#3D2B05` and
`#5E1E3C`. Record row in BRAND.md; tokens frozen. The chooser is kept as the record of the round.
