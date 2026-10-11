# word-07-cyberlarge — figlet `cyberlarge`, `HUGINN`, the slim ASCII mark

**Width:** 44 columns × 3 rows. **Files:** `wordmark.txt`, `preview.png`.

## Concept

Pure ASCII (`_ | \`) in three rows: a thin, airy capital with wide
proportions. Because it uses no box or block glyphs it is immune to the
one thing that bites every other variation: it looks the same in a terminal,
in GitHub's `<pre>`, in a plain-text email and in a `.txt` licence header. It
is the light-weight counterpart to the heavy baseline.

## Intended uses

README (seam-free), `--help` output, plain-text contexts (release notes,
email, `NOTICE` files), the light mode of a splash.

## Strengths

- Renders identically everywhere; no font dependency beyond ASCII.
- Light and modern; the wide stance reads as confident rather than loud.
- Passes `asciicheck.py` natively; 44 columns fits Termux.

## Weaknesses

- Thin: at phone sizes on a dark ground the strokes nearly vanish.
- The `G` is awkward (`______ / |  ____ / |_____|`) and the letters are
  slightly uneven in width.
- 44 columns for a 3-row mark is wide; little room for a prompt beside it on
  a phone.

## Relation to the current identity

A genuinely different weight from today's banner; the first ASCII-only
candidate. Terminal cousin of type-01's tight sans.
