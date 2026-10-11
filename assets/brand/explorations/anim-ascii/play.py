#!/usr/bin/env python3
"""play.py <frames.json | frames.txt> [--once] [--frames N] [--fps F]

Plays a Huginn terminal animation. frames.json holds {"fps", "loop", "rest", "cols", "rows",
"frames": ["line\\nline", ...]} and optionally "holds": [ms per frame]; frames.txt is the same
data in the text format described in anim-ascii/README.md (header line, frames separated by
"---", pad character for trailing blanks). Every frame must have identical dimensions; this
player refuses ragged input (exit 2) so a broken frame file never reaches a user's terminal.

Repaints with ANSI cursor-up (ESC [ n A), hides the cursor while playing and restores it on
exit or Ctrl-C. Not a tty / TERM=dumb / HUGINN_NO_ANIM set / terminal narrower than the frame:
the rest frame ("rest": first | last | none | index) is printed once, no escape codes.
--frames N forces playback of N frames even without a tty (a smoke test).
"""
import sys, os, json, time, shutil
PAD = '⠀'
def parse_txt(text):
    lines = text.split('\n'); doc = {}; pad = PAD; start = 0
    if lines and lines[0].startswith('# huginn-frames'):
        for kv in lines[0][len('# huginn-frames'):].split():
            k, _, v = kv.partition('=')
            if k in ('cols', 'rows', 'fps'): doc[k] = int(v)
            elif k == 'loop': doc['loop'] = (v == 'yes')
            elif k == 'rest': doc['rest'] = v
            elif k == 'holds': doc['holds'] = [int(x) for x in v.split(',')]
            elif k == 'pad': pad = v
        start = 1
    frames, cur = [], []
    for l in lines[start:]:
        if l == '---': frames.append('\n'.join(cur)); cur = []
        elif l == '': continue
        else: cur.append(l.replace(pad, ' '))
    if cur: frames.append('\n'.join(cur))
    doc['frames'] = frames
    return doc
def load(path):
    text = open(path, encoding='utf-8').read()
    doc = parse_txt(text) if path.endswith('.txt') else json.loads(text)
    frames = [[l.replace(PAD, ' ') for l in fr.split('\n')] for fr in doc['frames']]
    if not frames: sys.exit('play.py: no frames')
    rows = {len(fr) for fr in frames}; cols = {len(l) for fr in frames for l in fr}
    if len(rows) != 1 or len(cols) != 1:
        sys.exit(f'play.py: frames are not all the same size: rows {sorted(rows)}, widths {sorted(cols)}')
    return doc, frames, rows.pop(), cols.pop()
def rest_frame(doc, frames):
    r = doc.get('rest', 'first' if doc.get('loop') else 'last')
    if r == 'none': return None
    if r == 'first': return frames[0]
    if r == 'last': return frames[-1]
    try: return frames[int(r)]
    except (ValueError, IndexError): return frames[0]
def main(argv):
    if not argv or argv[0] in ('-h', '--help'): print(__doc__.strip()); return 0
    path, once, limit, fps = argv[0], '--once' in argv, None, None
    if '--frames' in argv: limit = int(argv[argv.index('--frames') + 1])
    if '--fps' in argv: fps = float(argv[argv.index('--fps') + 1])
    doc, frames, rows, cols = load(path)
    fps = fps or float(doc.get('fps', 8)); loop = bool(doc.get('loop', False)) and not once
    holds = doc.get('holds'); out = sys.stdout
    def static():
        rest = rest_frame(doc, frames)
        if rest is not None: out.write('\n'.join(l.rstrip() for l in rest) + '\n')
        return 0
    if limit is None:
        if (not out.isatty()) or os.environ.get('TERM') == 'dumb' or os.environ.get('HUGINN_NO_ANIM'): return static()
        try:
            if shutil.get_terminal_size((0, 0)).columns < cols: return static()   # would wrap: cursor-up maths breaks (COLUMNS overrides)
        except (OSError, ValueError): pass
    out.write('\x1b[?25l'); out.flush()
    shown = 0; i = 0; first = True
    try:
        while True:
            if not first: out.write(f'\x1b[{rows}A')
            out.write('\r' + '\n'.join(frames[i]) + '\n'); out.flush(); first = False
            shown += 1
            if limit is not None and shown >= limit: break
            delay = (holds[i] / 1000.0) if holds and i < len(holds) else 1.0 / fps
            time.sleep(delay)
            i += 1
            if i >= len(frames):
                if not loop: break
                i = 0
    except KeyboardInterrupt:
        out.write('\n')
    finally:
        out.write('\x1b[?25h'); out.flush()
    return 0
if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
