# Round 6 — the raven in motion (the last chooser)

Mark, colour and type are decided. Round 6 asks the three questions left on
the decision checklist, in the chooser "The Raven in Motion", set in the
decided system with live demos: the blink and the cursor breath rebuilt on the
decided mark, the tray pulse on the decided tile, and the round-1 terminal
frames played in place.

| question | options | recommendation |
|---|---|---|
| Hero form (README, splash, installer) | the still cursor lockup as SVG outlines · the text block (48 × 16 fold art over the hairline perch) · the blinking lockup (`anim-01-blink` with the lid moved to the decided eye) | the blinking lockup for the README with a poster PNG, since Chromium ignores reduced motion inside an `img`; the still outlines for the installer bitmap, the About box and social cards in every case; the text block as the host splash |
| The client's help header (7-bit) | keep today's `(o)==-` bird · `ascii-04`'s bigger bird · the decided raven at five rows (`../round5/raven-5row-ascii.txt`) beside the help lines | the decided raven: the client finally prints the brand's own bird, in the same cells as the host's hairline perch; the 7-bit landing and takeoff are regrown to end on it |
| Motion set | the recommended set (blink, cursor breath, tray pulse; wingbeat spinner, landing and takeoff, 7-bit boot, tmux drift) · the quiet set (the three screen pieces and the spinner) · everything | the recommended set: it is the ranked list from both motion dimensions and every piece obeys the house rules |

What the chooser's demos change from round 1:

- `anim-01-blink`: the lid circle moved from the old eye (64.5, 25.5, r 2.6) to
  the decided eye (51.75, 26.3, r 3.9, 1.2 × the r 3.2 hole); the 12 s timeline
  is unchanged.
- `anim-05-thinking` (c): the block cursor is one line tall (the decided bird's
  17.8 to 68.8), 0.55 × that wide, half a cell after the bill tip, breathing on
  the apps' PulseDot table at 160 ms per step.
- `anim-04-attention-pulse`: shown on `glyph-c` tinted idle → needs-you with the
  badge pop and the ring twice; the sprite spec's geometry still has to be
  regenerated for the tile.
- The terminal frames (`anim-ascii/`) are the round-1 files; they are grown
  from the old help-screen bird and are regrown on the decided raven in the
  composite round.

The round-6 chooser records the picks; after it, the brand book.

## Decided (round-6 chooser, 2026-10-13)

| question | pick |
|---|---|
| Hero form | the still cursor lockup as SVG outlines, with a dark twin |
| The client's help header | keep today's `(o)==-` bird, so the 7-bit landing and takeoff stay as grown |
| Motion set | the recommended set |

With this the decision checklist is complete; the brand book is `../../BRAND.md`.
Two consequences: the blink stays in the folder as an in-app idle option, not a
README hero; and the client's 7-bit art (help bird, landing, takeoff, boot) is
the one place the old drawing survives, by the owner's choice, so the brand book
lists it as a deliberate exception rather than a migration item.
