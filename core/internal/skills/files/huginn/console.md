# The console

Plain `huginn` in an interactive terminal (stdin and stdout a terminal, `TERM` not `dumb`,
`HUGINN_CONSOLE` not `0`, huginn core 2.3+ installed) opens a full-screen view: sessions, chats,
projects, rounds, devices, usage, archive, settings and the core. Every screen names the verb that
does the same thing at the bottom (`same as: huginn devices`). Piped, scripted or without a core,
plain `huginn` attaches to `main` instead, as it always did.

All of its data comes through huginn core's socket, so the core must run and be paired. Attaching
to a session from the console goes over SSH (`Host huginn`).

## When it cannot start

| On screen | Meaning | Do |
|---|---|---|
| a short screen with `s` / `i` / `a` / `q` | huginn core is installed but not running | `s` starts it (`huginn core start`), `i` sets it up at boot (`huginn core install`), `a` attaches to `main` over SSH anyway |
| `core not answering` (top right) | the core is not running | `huginn core start`, then `huginn doctor` |
| `huginn unreachable` | the core runs but cannot reach the server | `huginn core route`, then `huginn doctor` |
| a note suggesting `huginn core pair --url ...` | the core is not paired | pair it (asks for the token) |

## Keys

Everywhere: arrows or `j`/`k` move, `Enter` opens or attaches, `Esc` goes back, `1`-`9` jump to
a screen, `/` filters, `:` opens the palette (go anywhere, attach, or run any `huginn` verb with
the real terminal), `r` refreshes, `?` help, `q` quits.

| Screen | Keys |
|---|---|
| 1 Sessions | `a` attach, `S` solo, `n` new, `s` send one line, `e` soft end, `R` rename, `x` kill |
| 2 Chats | `n` new (ask or act), `R` rename, `x` delete; in a chat `i` writes, `Ctrl-X` cancels |
| 3 Projects | `Enter` dashboard, `e` end gracefully |
| 4 Rounds | `o` last run, `p` pause/resume, `x` delete |
| 5 Devices | `o` offer this computer or stop, `c` change its scope |
| 7 Archive | `v` revive and attach, `x` forget |
| 9 Core | `u` update everything, `R` restart huginn core, `i` set it up at boot |

Actions that lose something ask for a typed word or name. The console does not animate; it needs
at least 60 columns by 18 rows. `NO_COLOR` and `HUGINN_ASCII` apply.
