# anim-01-spinner-1line — one-line "connecting" spinners

**Encoding:** Unicode files (`frames.*`, `frames-b-thought.*`) for the README
and host-side tools; **7-bit twins** (`frames-ascii.*`, `frames-b-thought-ascii.*`)
for `client/huginn.sh` / `client/huginn.ps1`, which are ASCII-only.

**Preview:** `preview.png` (each candidate at 33 px and at 13 px, Unicode and
7-bit), `preview.gif` ((b) in motion).

The thing printed while `huginn` is reaching the host, before the session
attaches. One line, repainted in place, no colour. Two candidates.

| | file | cols | frames | fps | cycle |
|---|---|---|---|---|---|
| (a) wingbeat | `frames.*` | 3 | 4 | 8 | 0.5 s per beat |
| (a) 7-bit twin | `frames-ascii.*` | 3 | 4 | 8 | same |
| (b) thought out and back | `frames-b-thought.*` | 16 | 13 | 12 | 1.08 s out-and-back |
| (b) 7-bit twin | `frames-b-thought-ascii.*` | 16 | 13 | 12 | same |

## Concept

**(a) wingbeat** — `─▄─ ╲▄╱ ─▄─ ╱▀╲`. The raven in flight, seen from behind:
level, wings up with the body hanging low (`▄`), level, wings down with the
body risen (`▀`), which is also what a flapping bird does. The body is
registered to the wing roots in both strokes (in round 1 the downstroke was
`╱▄╲`, wings floating over a low body, which read as a little house). The
7-bit twin keeps the registration: `-_- \_/ -_- /'\` (`_` low body, `'` high
body). Print it as the leading glyph of the status line
(`╲▄╱ reaching huginn…`). The rest frame is the level one.

**(b) thought out and back** — the 1-row glyph raven `◢▆█▀` sits at the left, a
host ring `○` at the right; a thought `·` leaves the raven, eases out over
the 11 free columns, reaches the ring (which fills, `●`, for one frame) and
comes back. This is the brand story (fly out, see, return) as a progress
indicator, and frame 6 is a built-in "arrived" state the CLI can stop on when
the session answers. 7-bit twin: `(o>` raven, `.` thought, `o`/`*` ring.

**Motion house rule.** The brief asks for "a slow pulse rather than a
spinner": the apps' live marker is a 1.6 s stepped breath at ~6 frames/s.
(a) is faster than that on purpose: it is a *beat*, 2 Hz, the tempo of a real
raven's wingbeat, it lives only for the few seconds of an SSH negotiation,
and it is 3 cells wide. If that is still too lively, the same file at 4 fps
(`huginn_play frames.txt 0 4`) is a 1 Hz beat. (b) at 12 fps moves one dot;
its energy is the dot's, not the raven's. The round-1 stave orbit (c), a
generic spinner with no brand content, was dropped on the reviewer's advice.

## Where it plays

CLI (`client/huginn.sh`, `client/huginn.ps1`) while `ssh`/`tmux attach` is
negotiating, from the 7-bit twins; Termux the same. The Unicode files are for
host-side output (`huginn-status`) and the README. The desktop/Android apps
have their own progress UI and should not use this. (a) fits in front of a
message on the same line; (b) *is* the line.

## Fallbacks

- Not a tty, `TERM=dumb`, `HUGINN_NO_ANIM` set: the rest frame once (`─▄─`,
  `-_-`, or frame 0 of (b)) and no cursor movement. Both players do this.
- `NO_COLOR` is irrelevant: these are monochrome by construction.
- Fonts without box-drawing diagonals, blocks or `◢` (raster-font conhost,
  Consolas without a fallback): use the 7-bit twin; it is the same animation.

## Strengths

- (a) at 3 columns and 4 frames is the right size for a leading glyph, and
  both strokes now read as the same bird: at 33 px cells a bird, at 13 px a
  V and an A with a body, a bird by the `\_/` convention.
- (b) tells the product story and has the "arrived" frame a progress
  indicator needs; `○`/`●` are in every font that has the blocks.
- Both ship a 7-bit twin that can be pasted into the client today.

## Weaknesses (honest)

- The glyph raven `◢▆█▀` is a bird by convention: a wedge, a body and a head
  with a beak bar. One line cannot hold the canonical silhouette; see anim-02
  for the smallest version that does. The 7-bit `(o>` is a beak and an eye,
  no more.
- (a) is a *flying* raven seen from behind, a second drawing of the bird, not
  the profile mark.
- At 13 px the 2 Hz beat is small and quick; the 4 fps setting is the calm
  option, not the default.
- (b)'s 7-bit twin loses the eased `·`'s elegance a little: `.` sits on the
  baseline where `·` sat mid-line.

## Relation to the current mark / palette

Monochrome on the app ground (`#E8E2DA` on `#12100F` in the preview; in a
terminal it is the user's foreground colour). A client that colours may print
the `●` arrival frame in `stateWorking` (rune-gold `#C8A45C`, 8.06:1 on the
ground); nothing else in this variation takes colour.

## Revision (round 2)

Downstroke fixed to `╱▀╲` and the loop reordered so the rest frame is the
level one; glyph changed from `◢▟▀▸` (beak under the chin) to `◢▆█▀`, the one
glyph shared with anim-05; ring `◌` → `○`/`●`; (b) narrowed to 16 columns;
(c) dropped; 7-bit twins added for (a) and (b), rendered and checked; the
"reads as a bird at every size" claim replaced with what the 13 px strip
shows; the motion house rule is cited and the 4 fps option named.
