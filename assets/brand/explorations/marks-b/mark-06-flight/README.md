# mark-06-flight — the raven in flight

**Look at:** `preview.png`

## Concept

Huginn's story is the flight: the raven goes out over the world and comes
back with what it saw. This is that raven seen from below, wings spread, six
fingered primaries with wedge-shaped slots over the outer half of the hand,
the two outermost swept back, and the straight-sided wedge tail that
identifies a raven from any other large black bird. It is ONE filled path,
symmetric about its centre, no eye (from below a raven shows none), and it is
built for the big moments where the perched mark is too quiet: the README
hero, the splash/launch screen, the desktop installer's welcome panel. A
separate simplified glyph — three shapes, 24x24, no fingers — covers the one
small job a flying bird can do: an attention/notification cue that says
"something came back" next to the perched tray icon that says "idle".

## Files

| File | What | Sizes it is for |
|---|---|---|
| `mark-06-flight.svg` | the full silhouette, one path, viewBox 0 0 128 128, wingspan 119 (x 4.7-123.3), bill y 4 to tail y 99.5; head projection 27 against a 36-unit tail | 96px and up; hero, splash, installer, about panel. Still a bird at 64, a dark smudge at 32 |
| `mark-06-flight-inverse.svg` | bone raven on the ink ground | dark hero / installer panel (`wizard.bmp` is bone-on-`#12100F` today) |
| `mark-06-flight-tile.svg` | ink raven on a rounded bone tile (160x160, rx 36 = 22.5%), wingspan 80% of the tile | square icon slots at 128+ only; it leaves ~18% bone bands above and below |
| `mark-06-flight-splash.svg` | wide lockup 192x108 (16:9): bone raven at 90% of the height on the app ground `#12100F` | launch screen, installer welcome panel, README hero: the right shape for a wide bird |
| `mark-06-flight-small.svg` | simplified "bird returning": body with head and wedge tail + two swept wings, three shapes, 20x20 content centred in a 24 box (2-unit pad for notification / tray slots) | 16 to 32px: tray "attention" state, notification glyph, inline status |
| `preview.png` | all of the above at their sizes, the small glyph at its native 24 and tinted bone and error-red on the app background at 32/16, the current mark for comparison | |

## Intended uses

- **README hero**: the splash lockup or the inverse left of the wordmark,
  large, on the dark ground, so the first thing the page says is "thought
  flies out".
- **Splash / launch moment**: the splash lockup; the natural animation is
  wings from folded to spread (two or three keyframes of the same path) and
  a settle into the perched mark.
- **Installer welcome panel**: the inverse or the splash at the panel's width.
- **Attention state**: the small glyph, tinted (the apps' `error` colour for
  "needs you"), replaces or sits beside the perched tray raven when a session
  needs the user — the raven has returned with something.

## Strengths

- Says "raven" more specifically than any profile mark can: wedge tail and
  fingered primaries are the field marks, and both are present at 64 and up.
- One path, one colour, no strokes, inside its viewBox; symmetric, so it
  centres cleanly in a tile and in a wide hero.
- Carries the brand story literally, which gives the splash and the hero a
  reason to exist beyond decoration.
- The small glyph reads as a bird in flight at 16, 24 and 32 on both grounds,
  with margin for a notification slot, which gives the tray a second,
  meaningful state.

## Weaknesses

- A flying raven at 16px is impossible and this one is no exception: at 16
  the full mark is a dark T-shaped smudge; at 32 it is "a bird" with no
  species. Use the small glyph below 48 and never use the full mark in a tray.
- The small glyph is not a raven: no fingers, no wedge tail worth the name,
  a dagger body; at 512 it is a dove, a swallow or a sword. It is honest only
  at the sizes it is for. Fingered and notched versions were rendered and
  rejected: at 24px the slots are sub-pixel and at 64 they turn the wing
  into a bat.
- From below there is no eye, no profile and no perched posture, so this
  cannot replace the primary mark; it is a companion.
- The head is small, as it is on the real bird from below; some viewers will
  still read "eagle" or "thunderbird" before the wedge tail registers, and
  the six-finger hand reads as a hand to anyone looking for one.
- A wide bird in a square tile floats between bone bands; use the splash
  lockup for anything that is not an icon slot.

## Relation to the current mark and palette

Same ink `#161310` and bone `#E8E2DA`, same one-path discipline, same "no
legs" rule. It is the other half of the story the perched mark tells: the
current raven sits and watches; this one is out doing the work. The two are
meant to be used together (perched = idle/attached, flight = launching/
returning), which also gives the tray's state colours a shape to attach to
instead of a colour alone.

## Revision

After review: the path no longer leaves the viewBox (the previous one
reached x -2.4 and 130.4 and shipped with clipped wingtips; the span is now
119 of 128, verified by the ink columns of the 512 render, 20-491). The
hand was redrawn over seven rounds — the five 8-10-unit rounded nubs that
read as a mitten are now six 21-25-unit tapered primaries radiating from a
deeper wrist with wedge slots over the outer half, pointed tips, the outer
two swept back. The head was extended (bill tip y 4, neck width 9, a step
where the bill meets the skull) so the head projection is 75% of the tail;
the wings' trailing edges now sweep low into the tail base (intermediate
versions with a visible belly read as a moth or a vase and were rejected);
the tail is a straight-sided wedge, not an oval. Generator residue (duplicate
points, `.0` suffixes) is gone. The small glyph was refit into 20x20 of 24
with a longer tail and narrower head, its wing roots overlap the body (the
previous point-contact showed a seam), and it is relabelled as "a bird
returning", not a raven. The tile is now 80% span and a wide 192x108
splash lockup was added as the real splash shape.
