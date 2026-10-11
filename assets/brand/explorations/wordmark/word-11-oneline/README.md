# word-11-oneline — `(o>`: the one-row mark

**Width:** 3 cells for the glyph, 10 columns × 1 row for the lockup `(o> huginn`.
**Charset: 7-bit ASCII**, eligible everywhere including `client/huginn.sh` /
`huginn.ps1`, tmux format strings and terminal titles. **Files:**
`wordmark.txt` (the lockup), `states.txt` (the three title states), `preview.png`.

```
(o> huginn
```

## Concept

The only brand surfaces that are one line tall are the tmux status bar
(`status-left` in `server/tmux.conf`, today `[#{session_name}]`) and the
terminal tab title (`huginn-claude-title`, today emoji). Neither can hold a
3-row mark, and emoji are out. `(o>` is the oldest ASCII bird there is: an
eye in a round head and a bill pointing right, the same posture as the raven
mark. It is three cells, single-width, 7-bit, and it is the glyph the raven
family shrinks to when there is one row.

Proposed placements:

- `set -g status-left "(o> #{session_name}  #[fg=colour238]Alt-d:detach  Alt-o:solo  ^b[:scroll#[fg=default]  "`
  — the glyph replaces the brackets around the session name (same length).
- Terminal title: the eye carries Claude's state, which is the brief's
  "words not emoji" rule in three characters:

  ```
  (o> main      Claude is working      (eye open)
  (-> main      waiting for you         (eye closed, resting)
  (!> main      needs you               (a permission prompt)
  ```

  These are candidates for `HUGINN_ICON_RUNNING / _WAITING / _NEEDS_YOU` in
  `huginn-claude-title`; the brief lists that surface as open to re-glyphing.
  Mapping them onto the unified semantic set is the colour/state dimension's
  job; this folder only offers the glyphs.
- CLI client version line: `(o> huginn-cli 0.9  host ravn  session main`.

Unicode alternatives were rendered (`▟▀`, `▄▀▀`, `●▶`, `◖▶`, `(•>`) and
rejected: the half-block ones read as stairs or flags at 12 px, the
geometric ones as bullet-and-arrow, and `●▶` is missing from IBM Plex Mono
and Liberation Mono. The preview shows the ASCII glyph in DejaVu Sans Mono,
JetBrains Mono and Liberation Mono at 12–13 px and 24 px, in a mock of the
current status bar beside the proposed one.

## Intended uses

tmux status bar, terminal and tab titles, the CLI client's one-line banners
(`version`, `update` result, help first line), log line prefixes, commit
message trailers if wanted.

## Strengths

- Three cells, 7-bit, identical in every font and every terminal; nothing
  can be missing.
- A bird at a glance at 12 px (preview), and the state variants are
  distinguishable from each other at the same size.
- Fits the brief's rule that brand assets carry no emoji, on the two
  surfaces that currently carry emoji or nothing.

## Weaknesses

- A generic bird, not a raven: one row cannot carry a species. Its claim to
  the brand is the posture (eye, bill right) and consistency with the perch.
- `(o>` reads as a face/emoticon to some; `(!>` could be read as alarm
  rather than "needs you".
- Not a wordmark: it never stands alone without the session name or the
  word `huginn`.

## Relation to the current identity

Shrinks the raven mark to one row; same posture as `raven.svg` and the
help-screen raven `(o)==-` (eye in a round head, bill to the right). New
surface for the brand: the tmux bar and the tab title currently carry none
of it.

## Revision

New in this revision, answering the review's missing item "a 1-row mark for
the tmux status bar and the terminal title".
