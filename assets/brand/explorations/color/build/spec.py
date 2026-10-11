"""The three palette directions. Every hex here is a decision; palette.py checks
them and refuses to emit if a rule fails."""

# ---------------------------------------------------------------- shared rules
# (fg role, ground role, minimum ratio). Text pairs 4.5, UI pairs 3.0.
TEXT_PAIRS = [
    ('onBackground', 'background', 4.5),
    ('onSurface', 'surface', 4.5),
    ('onSurface', 'surfaceContainerLowest', 4.5),
    ('onSurface', 'surfaceContainerLow', 4.5),
    ('onSurface', 'surfaceContainer', 4.5),
    ('onSurface', 'surfaceContainerHigh', 4.5),
    ('onSurface', 'surfaceContainerHighest', 4.5),
    ('onSurfaceVariant', 'background', 4.5),
    ('onSurfaceVariant', 'surfaceContainer', 4.5),
    ('onSurfaceVariant', 'surfaceContainerHigh', 4.5),
    ('onSurfaceVariant', 'surfaceContainerHighest', 4.5),
    ('primary', 'background', 4.5),
    ('primary', 'surfaceContainerHigh', 4.5),
    ('onPrimary', 'primary', 4.5),
    ('onPrimaryContainer', 'primaryContainer', 4.5),
    ('secondary', 'background', 4.5),
    ('onSecondary', 'secondary', 4.5),
    ('onSecondaryContainer', 'secondaryContainer', 4.5),
    ('tertiary', 'background', 4.5),
    ('error', 'background', 4.5),
    ('error', 'surfaceContainerHigh', 4.5),
    ('onError', 'error', 4.5),
    ('onErrorContainer', 'errorContainer', 4.5),
    ('stateIdle', 'background', 4.5),
    ('stateWorking', 'background', 4.5),
    ('stateAttention', 'background', 4.5),
    ('stateSuccess', 'background', 4.5),
    ('stateInfo', 'background', 4.5),
    ('inkMark', 'boneTile', 4.5),
]
UI_PAIRS = [
    ('outline', 'background', 3.0),
    ('outline', 'surfaceContainerHigh', 3.0),
    ('stateIdle', 'surfaceContainerHigh', 3.0),
    ('stateWorking', 'surfaceContainerHigh', 3.0),
    ('stateAttention', 'surfaceContainerHigh', 3.0),
    ('stateSuccess', 'surfaceContainerHigh', 3.0),
    ('stateError', 'surfaceContainerHigh', 3.0),
    ('stateInfo', 'surfaceContainerHigh', 3.0),
    ('primary', 'surfaceContainerHighest', 3.0),
]
# Syntax classes sit on the code ground (surfaceContainerLowest).
SYNTAX_KEYS = ['keyword', 'string', 'number', 'comment', 'function', 'meta', 'added', 'removed']
# Tray states: adjacent L* gap the monochrome tray needs.
TRAY_ORDER = ['stateIdle', 'stateWorking', 'stateAttention']
TRAY_MIN_DL = 12.0
# The same ladder in a 16-colour terminal: brightBlack -> the accent slot -> brightYellow.
ANSI_LADDER_MIN_DL = 12.0
# Monochrome/template tray: onSurface at these opacities over the ground, adjacent >= TRAY_MIN_DL.
MONO_TRAY_ALPHAS = {'stateIdle': 0.45, 'stateWorking': 0.75, 'stateAttention': 1.0}
# tertiary sits beside state dots (subagent markers next to a session row), so it must be
# >= TERTIARY_MIN_DL lighter/darker than every state OR >= TERTIARY_MIN_DHUE degrees away
# (a hue gap only counts when both colours are saturated >= TERTIARY_MIN_SAT %; idle is a neutral).
TERTIARY_MIN_DL = 12.0
TERTIARY_MIN_DHUE = 60.0
TERTIARY_MIN_SAT = 15.0
# Slot pins: the ANSI-16 slot IS the app token, so the CLI, tmux and the app never show two reds.
SLOT_PINS = [('brightRed', 'error'), ('brightGreen', 'stateSuccess'), ('brightYellow', 'stateAttention'), ('brightBlue', 'stateInfo')]
# The CLI's 16-colour branch inherits the user's palette. These are the palettes it must stay
# readable on (ratio >= LEGACY_MIN against that palette's default ground): the pre-Campbell
# conhost table, the Windows PowerShell 5.1 console (same table, DarkYellow and DarkMagenta
# remapped by the shortcut to #EEEDF0 / #012456 and used as the ground), Windows Terminal's
# Campbell, and xterm's defaults.
LEGACY_MIN = 3.0
LEGACY_PALETTES = {
    'conhost': dict(ground='#000000', black='#000000', red='#800000', green='#008000', yellow='#808000', blue='#000080',
                    magenta='#800080', cyan='#008080', white='#C0C0C0', brightBlack='#808080', brightRed='#FF0000',
                    brightGreen='#00FF00', brightYellow='#FFFF00', brightBlue='#0000FF', brightMagenta='#FF00FF',
                    brightCyan='#00FFFF', brightWhite='#FFFFFF'),
    'powershell-5.1': dict(ground='#012456', black='#000000', red='#800000', green='#008000', yellow='#EEEDF0', blue='#000080',
                           magenta='#012456', cyan='#008080', white='#C0C0C0', brightBlack='#808080', brightRed='#FF0000',
                           brightGreen='#00FF00', brightYellow='#FFFF00', brightBlue='#0000FF', brightMagenta='#FF00FF',
                           brightCyan='#00FFFF', brightWhite='#FFFFFF'),
    'campbell': dict(ground='#0C0C0C', black='#0C0C0C', red='#C50F1F', green='#13A10E', yellow='#C19C00', blue='#0037DA',
                     magenta='#881798', cyan='#3A96DD', white='#CCCCCC', brightBlack='#767676', brightRed='#E74856',
                     brightGreen='#16C60C', brightYellow='#F9F1A5', brightBlue='#3B78FF', brightMagenta='#B4009E',
                     brightCyan='#61D6D6', brightWhite='#F2F2F2'),
    'xterm': dict(ground='#000000', black='#000000', red='#CD0000', green='#00CD00', yellow='#CDCD00', blue='#0000EE',
                  magenta='#CD00CD', cyan='#00CDCD', white='#E5E5E5', brightBlack='#7F7F7F', brightRed='#FF0000',
                  brightGreen='#00FF00', brightYellow='#FFFF00', brightBlue='#5C5CFF', brightMagenta='#FF00FF',
                  brightCyan='#00FFFF', brightWhite='#FFFFFF'),
}
# ConsoleColor name per ANSI slot (Write-Host -ForegroundColor) and SGR code per slot.
CONSOLE_NAME = {'black': 'Black', 'red': 'DarkRed', 'green': 'DarkGreen', 'yellow': 'DarkYellow', 'blue': 'DarkBlue',
                'magenta': 'DarkMagenta', 'cyan': 'DarkCyan', 'white': 'Gray', 'brightBlack': 'DarkGray',
                'brightRed': 'Red', 'brightGreen': 'Green', 'brightYellow': 'Yellow', 'brightBlue': 'Blue',
                'brightMagenta': 'Magenta', 'brightCyan': 'Cyan', 'brightWhite': 'White'}
SGR = {'black': 30, 'red': 31, 'green': 32, 'yellow': 33, 'blue': 34, 'magenta': 35, 'cyan': 36, 'white': 37,
       'brightBlack': 90, 'brightRed': 91, 'brightGreen': 92, 'brightYellow': 93, 'brightBlue': 94,
       'brightMagenta': 95, 'brightCyan': 96, 'brightWhite': 97}
# The one-row raven. The Unicode form is the ascii dimension's pick (ascii-03-compact
# raven-1line-a: tail wedge, body, raised head, bill); 'block' is its fallback for fonts
# without Geometric Shapes; 'ascii' is the 7-bit form for client/huginn.sh and huginn.ps1,
# which must stay pure ASCII: it is the head row of the help-screen raven those files
# already print (huginn.sh:988-994), verbatim.
GLYPH = {'unicode': '\u25e2\u2586\u2588\u2580', 'block': '\u2597\u2586\u2588\u2580', 'ascii': '(o)==-'}
# Where every state surface in the product takes its colour from (root README "state mapping").
# glyphs: single-width Unicode first, 7-bit ASCII second; the app draws dots, not glyphs.
STATE_GLYPH = {'stateWorking': ('\u25cf', '*'), 'stateAttention': ('\u25c6', '!'), 'stateIdle': ('\u25cb', 'o'),
               'stateSuccess': ('\u25a0', '+'), 'stateError': ('\u25b2', 'x'), 'stateInfo': ('\u25b8', '>'),
               'outline': ('\u25e6', '-')}

ANSI_KEYS = ['black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white',
             'brightBlack', 'brightRed', 'brightGreen', 'brightYellow', 'brightBlue',
             'brightMagenta', 'brightCyan', 'brightWhite']

ROLE_NOTES = {
    'background': 'app ground (M3 background)',
    'onBackground': 'ink on the ground',
    'surface': 'default surface (= background)',
    'surfaceContainerLowest': 'tier 0: code wells, the terminal block, sunken areas',
    'surfaceContainerLow': 'tier 1: list rows, chat bubbles (was Surface1)',
    'surfaceContainer': 'tier 2: cards, sheets (was Surface2)',
    'surfaceContainerHigh': 'tier 3: dialogs, menus, raised cards',
    'surfaceContainerHighest': 'tier 4: chips, input fields on a dialog',
    'onSurface': 'primary text',
    'onSurfaceVariant': 'secondary text, labels, timestamps (was Muted)',
    'outline': '3:1 strokes: text fields, focus-adjacent borders',
    'outlineVariant': 'decorative borders, card edges',
    'outlineHairline': 'dividers between rows (huginn tier below outlineVariant)',
    'primary': 'the ONE accent: links, primary buttons, the raven eye',
    'onPrimary': 'text on a primary button',
    'primaryContainer': 'tinted container (selected row, accent chip)',
    'onPrimaryContainer': 'text on primaryContainer',
    'secondary': 'functions, links in code, calmer second accent',
    'onSecondary': 'text on secondary fills',
    'secondaryContainer': 'tinted secondary container',
    'onSecondaryContainer': 'text on secondaryContainer',
    'tertiary': 'a third voice: subagent markers, graph series',
    'error': 'destructive verbs, failures',
    'onError': 'text on an error fill',
    'errorContainer': 'error surface (banner, toast)',
    'onErrorContainer': 'text on errorContainer',
    'stateIdle': 'session idle: tray tint, list dot',
    'stateWorking': 'Claude is running: tray tint, list dot, pulse',
    'stateAttention': 'needs you (permission, question): tray tint + badge',
    'stateSuccess': 'turn finished, tests passed',
    'stateError': 'failed, disconnected (= error)',
    'stateInfo': 'informational, links (= secondary)',
    'inkMark': 'the raven on light grounds (icon ink)',
    'boneTile': 'the icon tile; the raven on dark grounds',
    'accent': 'alias of primary, the brand accent by its brand name',
}

DIRECTIONS = []

# ============================================================ 01 ink-bone-gold
DIRECTIONS.append(dict(
    id='color-01-ink-bone-gold', slug='ink-bone-gold', name='Ink, Bone & Gold',
    tagline='the palette Huginn already has, finished',
    accent_name='rune-gold', accent_ansi='yellow',
    dark=dict(
        background='#12100F', onBackground='#E8E2DA', surface='#12100F',
        surfaceContainerLowest='#0C0A09', surfaceContainerLow='#1B1817', surfaceContainer='#241F1D',
        surfaceContainerHigh='#2D2725', surfaceContainerHighest='#38312D',
        onSurface='#E8E2DA', onSurfaceVariant='#A39A8F',
        outline='#7A736C', outlineVariant='#3A3431', outlineHairline='#2A2523',
        primary='#C8A45C', onPrimary='#241C09', primaryContainer='#3E3117', onPrimaryContainer='#EBD5A3',
        secondary='#7DAFEA', onSecondary='#0B1622', secondaryContainer='#1E2F44', onSecondaryContainer='#C6DCF5',
        tertiary='#C9A3D2',
        error='#E8736D', onError='#2A0E0C', errorContainer='#4A1F1C', onErrorContainer='#F6C7C3',
        stateIdle='#8A8177', stateWorking='#C8A45C', stateAttention='#F3D58F',
        stateSuccess='#8CCB7B', stateError='#E8736D', stateInfo='#7DAFEA',
        inkMark='#161310', boneTile='#E8E2DA', accent='#C8A45C',
    ),
    light=dict(
        background='#FAF7F2', onBackground='#1B1817', surface='#FAF7F2',
        surfaceContainerLowest='#FFFFFF', surfaceContainerLow='#F4EFE8', surfaceContainer='#EFE9E0',
        surfaceContainerHigh='#E8E1D7', surfaceContainerHighest='#E0D8CC',
        onSurface='#1B1817', onSurfaceVariant='#5C544B',
        outline='#847B71', outlineVariant='#D3CAC0', outlineHairline='#E6DFD5',
        primary='#6B4E10', onPrimary='#FFFFFF', primaryContainer='#F1E3C2', onPrimaryContainer='#3E2D06',
        secondary='#1F5FA8', onSecondary='#FFFFFF', secondaryContainer='#D9E7F7', onSecondaryContainer='#0E2D52',
        tertiary='#5F3D66',
        error='#B3261E', onError='#FFFFFF', errorContainer='#F9DBD7', onErrorContainer='#5E1410',
        stateIdle='#777069', stateWorking='#6B4E10', stateAttention='#3A2A06',
        stateSuccess='#3F7A33', stateError='#B3261E', stateInfo='#1F5FA8',
        inkMark='#161310', boneTile='#E8E2DA', accent='#6B4E10',
    ),
    syntax_dark=dict(keyword='#C495DC', string='#8CCB7B', number='#E3C169', comment='#8F8679',
                     function='#7DAFEA', meta='#6FC4C7', added='#8CCB7B', removed='#E8736D'),
    syntax_light=dict(keyword='#7A3E96', string='#2F6B33', number='#8A5A00', comment='#7A7168',
                      function='#1F5FA8', meta='#10666A', added='#2F6B33', removed='#B3261E'),
    ansi=dict(
        black='#3A3431', red='#D1544F', green='#69A95B', yellow='#C8A45C', blue='#5B8FD6',
        magenta='#A974C4', cyan='#4FA5A8', white='#CFC8BF',
        brightBlack='#7A736C', brightRed='#E8736D', brightGreen='#8CCB7B', brightYellow='#F3D58F',
        brightBlue='#7DAFEA', brightMagenta='#C495DC', brightCyan='#6FC4C7', brightWhite='#F2ECE4',
        foreground='#E8E2DA', background='#12100F', cursor='#C8A45C', selection='#4A3F2A',
    ),
    state_ansi=dict(stateIdle='brightBlack', stateWorking='yellow', stateAttention='brightYellow',
                    stateSuccess='brightGreen', stateError='brightRed', stateInfo='brightBlue'),
    cli=dict(ok='brightGreen', warn='brightYellow', error='brightRed', muted='brightBlack', accent='yellow'),
))

# ========================================================= 02 slate-verdigris
DIRECTIONS.append(dict(
    id='color-02-slate-verdigris', slug='slate-verdigris', name='Slate & Verdigris',
    tagline='night sky over oxidised bronze; the sheen on a raven\'s wing',
    accent_name='verdigris', accent_ansi='cyan',
    dark=dict(
        background='#14171C', onBackground='#E4E2DC', surface='#14171C',
        surfaceContainerLowest='#0E1013', surfaceContainerLow='#1B1F25', surfaceContainer='#22272E',
        surfaceContainerHigh='#2A3038', surfaceContainerHighest='#343B44',
        onSurface='#E4E2DC', onSurfaceVariant='#9DA6AE',
        outline='#6F7983', outlineVariant='#3A424B', outlineHairline='#2A3139',
        primary='#5DBBA6', onPrimary='#06201C', primaryContainer='#1C3D38', onPrimaryContainer='#BFE8DF',
        secondary='#C9A775', onSecondary='#2A1E08', secondaryContainer='#3E3220', onSecondaryContainer='#EBD7B3',
        tertiary='#C4A0D0',
        error='#E8767A', onError='#2B0F11', errorContainer='#4A2024', onErrorContainer='#F6C7C9',
        stateIdle='#7F8992', stateWorking='#5DBBA6', stateAttention='#EFD3A0',
        stateSuccess='#A9C97E', stateError='#E8767A', stateInfo='#86B8E8',
        inkMark='#15181C', boneTile='#E4E2DC', accent='#5DBBA6',
    ),
    light=dict(
        background='#F5F5F1', onBackground='#1A1D21', surface='#F5F5F1',
        surfaceContainerLowest='#FFFFFF', surfaceContainerLow='#EFF0EC', surfaceContainer='#E9EAE5',
        surfaceContainerHigh='#E2E4DE', surfaceContainerHighest='#DADDD6',
        onSurface='#1A1D21', onSurfaceVariant='#555C63',
        outline='#7A838C', outlineVariant='#C8CDD1', outlineHairline='#DEE1E3',
        primary='#175B50', onPrimary='#FFFFFF', primaryContainer='#CBEAE2', onPrimaryContainer='#063A33',
        secondary='#7A5A2E', onSecondary='#FFFFFF', secondaryContainer='#F0E2C8', onSecondaryContainer='#3A2810',
        tertiary='#5C3A63',
        error='#B3262E', onError='#FFFFFF', errorContainer='#F9DBDC', onErrorContainer='#5E1418',
        stateIdle='#677078', stateWorking='#175B50', stateAttention='#3A2A0A',
        stateSuccess='#2E7A4E', stateError='#B3262E', stateInfo='#245FA5',
        inkMark='#15181C', boneTile='#E4E2DC', accent='#175B50',
    ),
    syntax_dark=dict(keyword='#B9A3E3', string='#8FCBA3', number='#E3C58A', comment='#8A949D',
                     function='#86B8E8', meta='#6EC2B0', added='#8FCBA3', removed='#E8767A'),
    syntax_light=dict(keyword='#5E4A8A', string='#2E7A4E', number='#8A5A00', comment='#6B747C',
                      function='#245FA5', meta='#175B50', added='#2E7A4E', removed='#B3262E'),
    ansi=dict(
        black='#3A424B', red='#D9636A', green='#86B36A', yellow='#D4B06A', blue='#6A9ED8',
        magenta='#A98BD1', cyan='#5DBBA6', white='#C9CDCF',
        brightBlack='#6F7983', brightRed='#E8767A', brightGreen='#A9C97E', brightYellow='#EFD3A0',
        brightBlue='#86B8E8', brightMagenta='#B9A3E3', brightCyan='#7FD0BE', brightWhite='#E4E2DC',
        foreground='#E4E2DC', background='#14171C', cursor='#5DBBA6', selection='#2A3F3C',
    ),
    state_ansi=dict(stateIdle='brightBlack', stateWorking='cyan', stateAttention='brightYellow',
                    stateSuccess='brightGreen', stateError='brightRed', stateInfo='brightBlue'),
    cli=dict(ok='brightGreen', warn='brightYellow', error='brightRed', muted='brightBlack', accent='brightCyan'),
))

# ============================================================= 03 soot-ember
DIRECTIONS.append(dict(
    id='color-03-soot-ember', slug='soot-ember', name='Soot & Ember',
    tagline='a banked fire: soot ground, one ember, oxblood in daylight',
    accent_name='ember', accent_ansi='yellow',
    dark=dict(
        background='#141212', onBackground='#EAE3DC', surface='#141212',
        surfaceContainerLowest='#0D0B0B', surfaceContainerLow='#1C1919', surfaceContainer='#242020',
        surfaceContainerHigh='#2D2828', surfaceContainerHighest='#383131',
        onSurface='#EAE3DC', onSurfaceVariant='#A39A94',
        outline='#7C736E', outlineVariant='#3C3634', outlineHairline='#2B2626',
        primary='#E58A4C', onPrimary='#2E1207', primaryContainer='#4A2616', onPrimaryContainer='#F7CDB3',
        secondary='#C7B5A3', onSecondary='#2A2119', secondaryContainer='#3E3630', onSecondaryContainer='#EBDFD3',
        tertiary='#C29ECF',
        error='#EE6E7C', onError='#2B0C12', errorContainer='#4C1C25', onErrorContainer='#F8C5CC',
        stateIdle='#847B76', stateWorking='#E58A4C', stateAttention='#F2CF96',
        stateSuccess='#B5C27A', stateError='#EE6E7C', stateInfo='#8FB4DE',
        inkMark='#171312', boneTile='#EAE3DC', accent='#E58A4C',
    ),
    light=dict(
        background='#FAF6F2', onBackground='#1E1917', surface='#FAF6F2',
        surfaceContainerLowest='#FFFFFF', surfaceContainerLow='#F4EEE9', surfaceContainer='#EFE7E1',
        surfaceContainerHigh='#E8DFD8', surfaceContainerHighest='#E0D5CD',
        onSurface='#1E1917', onSurfaceVariant='#5E544F',
        outline='#857A74', outlineVariant='#D4C9C1', outlineHairline='#E7DED7',
        primary='#7A3E14', onPrimary='#FFFFFF', primaryContainer='#F6D9CB', onPrimaryContainer='#4A1A0A',
        secondary='#6B5A4C', onSecondary='#FFFFFF', secondaryContainer='#EBDFD3', onSecondaryContainer='#302419',
        tertiary='#633F7A',
        error='#B01C4C', onError='#FFFFFF', errorContainer='#F9D9DF', onErrorContainer='#5E0E1E',
        stateIdle='#776E69', stateWorking='#7A3E14', stateAttention='#4A1A0A',
        stateSuccess='#5A7A1E', stateError='#B01C4C', stateInfo='#2A5F9E',
        inkMark='#171312', boneTile='#EAE3DC', accent='#7A3E14',
    ),
    syntax_dark=dict(keyword='#D39BC0', string='#B5C27A', number='#E8B467', comment='#8F8580',
                     function='#8FB4DE', meta='#D6A08E', added='#B5C27A', removed='#EE6E7C'),
    syntax_light=dict(keyword='#7A3E7A', string='#5A7A1E', number='#8A5200', comment='#7A706B',
                      function='#2A5F9E', meta='#7A3E14', added='#5A7A1E', removed='#B01C4C'),
    ansi=dict(
        black='#3C3634', red='#D4514F', green='#8FA85A', yellow='#D9A45A', blue='#6E94C4',
        magenta='#B47BA4', cyan='#5EA9A0', white='#CBC3BD',
        brightBlack='#7C736E', brightRed='#EE6E7C', brightGreen='#B5C27A', brightYellow='#F2CF96',
        brightBlue='#8FB4DE', brightMagenta='#D39BC0', brightCyan='#7FC2BA', brightWhite='#F1E9E2',
        foreground='#EAE3DC', background='#141212', cursor='#E58A4C', selection='#452A1E',
    ),
    state_ansi=dict(stateIdle='brightBlack', stateWorking='yellow', stateAttention='brightYellow',
                    stateSuccess='brightGreen', stateError='brightRed', stateInfo='brightBlue'),
    cli=dict(ok='brightGreen', warn='brightYellow', error='brightRed', muted='brightBlack', accent='yellow'),
))
