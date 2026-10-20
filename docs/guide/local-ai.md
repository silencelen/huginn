# Local AI

> **Experimental.** Local AI works, but its commands, hardware support and models may change
> between releases, and it has had less use than the rest of Huginn. Everything else in these
> guides is stable.

One of your computers can serve open language models to Huginn. You can then start a chat on a
local model from any client, and the server can hand text tasks to it with `huginn llm`,
without using your Claude plan.

Local models are text generators only. They get no tools, no files and none of the server's
context: a local chat sees exactly the conversation you type, and nothing else.

## How it works

`huginn local on`, run on the computer that will serve:

1. checks the hardware and picks a model set for it (see [Hardware](#hardware));
2. downloads a fixed, tested version of `llama-server` (from llama.cpp) and `llama-swap`, and
   the models from Hugging Face, and checks each download's SHA-256 before using it;
3. installs two services: the model server, listening on `127.0.0.1:8748` only, with an API
   key created on the spot; and a runner that offers the models to the server;
4. refuses to continue if the model server answers a request made without the key;
5. enrols the computer with the server as a second device, named `<host>-llm`, at the
   `generate` scope.

The runner connects out to the server and asks for work, like any [device](devices.md). The
model server is never reachable from the network.

The exact runtime and model versions are part of each Huginn release. A newer upstream release
is never picked up on its own; it arrives with a Huginn update.

## Hardware

| Class | Hardware | Serves on | Chat model |
|---|---|---|---|
| G8 | an NVIDIA GPU with at least 7.5 GiB of memory | Windows only | Qwen3 8B |
| C | an x86-64 CPU with AVX2 and at least 7.5 GiB of RAM | Linux, Windows | Qwen3 4B |
| A | an arm64 CPU with at least 7 GiB of RAM | Linux | Qwen3 1.7B |

Every class also installs Nomic Embed, an embedding model. All models are Apache-2.0.

- GPUs are found with `nvidia-smi`, so only NVIDIA GPUs count, and their drivers must already
  be installed. Huginn never installs drivers.
- A computer with two such GPUs is reported as `G16` and serves the G8 set on one of them.
- A computer with an NVIDIA GPU smaller than 7.5 GiB is refused.
- GPU serving is Windows-only in this release. On Linux with a capable NVIDIA GPU, serve on the
  CPU instead: `huginn local on --class C`.
- `--class` can only choose a smaller class than the one detected, never a larger one.

Disk space: the downloads plus 2 GiB, checked before anything is downloaded. That is roughly
8 GB free for G8, 5 GB for C and 4 GB for A. `huginn local plan` prints the exact numbers for
your computer.

## Before you start

- **Node.js** (LTS) on the computer. `huginn local` is a Node.js program.
- **SSH access to the server** from this computer (`huginn setup ssh` on a computer set up from
  the desktop app). `huginn local on` reads the server's access token over it.


## Turn it on

See what it would do first; this changes nothing:

```bash
huginn local plan
```

Then:

**Linux**

```bash
huginn local on --system
```

`--system` installs the two services as system services, after one `pkexec` or `sudo` prompt,
so the computer keeps serving when you sign out. Without it, or if the prompt is declined, they
are installed as user services and Huginn turns on *linger* for your account so they keep
running after you sign out; the output says which one you got.

**Windows**:

```powershell
huginn local on
```

On Windows the services always run as LocalSystem, so the computer serves with nobody signed in.
That needs administrator rights for `on`, `off`, `update` and `persist`: from a normal terminal
each one asks for approval once and runs in an administrator window of its own, which shows its
progress and stays open if something fails (since 2.5.10). The client installer and the desktop
app ask for approval the same way.

`huginn local on` prints the plan and asks before it installs anything; `--yes` skips the
question. The client installer offers local AI only on a computer with a supported GPU. In the
desktop app it is under **Settings → Devices → Local AI**.

### Options for `huginn local on`

| Option | Meaning |
|---|---|
| `--class G8\|G16\|C\|A` | Serve a smaller class than the one detected. |
| `--gpu-uuid UUID` | The GPU to serve on, when there is more than one. |
| `--name NAME` | The device name. Default: `<host>-llm`. |
| `--system` | Linux: install system services (one elevation prompt). |
| `--url URL` | The daemon's address, when it cannot be worked out from the SSH connection, for example `https://192.168.1.20:8787`. |
| `--yes` | Do not ask before installing. |
| `--adapter KIND` | Use a model server you already run, instead of installing one. See [Use an existing model server](#use-an-existing-model-server). |

## Use it

### Chats

In the desktop app or the Android app, start a new chat and pick a local model. Local models
appear in the model list with the machine's name, for example *Qwen3 8B · gpubox*; picking one
picks the machine. The desktop app's chat list also has a **Local** button for a new local chat.

A local chat:

- runs in ask mode only, and the model gets no tools;
- gets none of the server's context: the text in `HUGINN_APPD_PERSONA_FILE` and the tools in
  `HUGINN_APPD_EXTRA_TOOLS` are not sent to the serving machine;
- stays on its machine once started; to use Claude or another machine, start a new chat;
- fails with a message when the machine is offline. Huginn never moves a request from a local
  model to Claude, or from Claude to a local model.

[Rounds](rounds.md) cannot use local models.

### `huginn llm`

One question to a local model, answered on standard output:

```bash
huginn llm "Give me three names for a backup script"
huginn llm - < notes.txt
```

| Option | Meaning |
|---|---|
| `-` | Read the prompt from standard input. |
| `--model ROW` | Use one machine's model row. Default: the first local model that is serving. |
| `--timeout S` | Give up after this many seconds (default 120). |
| `--keep` | Keep the chat afterwards, to look at a bad answer. |

`huginn llm` runs on the server over SSH. On the server itself the same command is `huginn-llm`,
which sessions on the server can call to hand a text task (summarising a log, for example) to
the local model instead of spending Claude usage. It exits with status 1 and the reason when no
machine is serving, and never waits forever. The chat it creates is removed afterwards.

## The `generate` scope

The serving computer's enrolment is at scope `generate`, separate from any enrolment the same
computer has as a Claude device:

- it runs local-model requests only, and a local-model request runs only on a `generate`
  enrolment; neither ever runs on the other;
- the model gets no tools and no files, so there is nothing for a locked screen to withdraw, and
  it keeps serving while the screen is locked;
- like every device, the serving computer holds a copy of the server's access token. See
  [Security](../security.md).

`huginn devices` and the Devices page show both enrolments as one machine, with a line such as
`serves local AI: Qwen3 8B, Nomic Embed  .  serving`. The Devices page also says whether it
serves with nobody signed in (**always on**) or **only while someone is logged in**.

## Keep it serving after sign-out

| Platform | Serves with nobody signed in when |
|---|---|
| Windows | always (LocalSystem services) |
| Linux, `--system` | always (system services) |
| Linux, user services | only while linger is on for your account |

To make an existing Linux install permanent:

```bash
huginn local persist
```

It moves the user services to system services after one elevation prompt, or turns on linger
if there is no way to elevate. `huginn local doctor` fails when an install would stop at
sign-out.

## Manage it

| Command | What it does |
|---|---|
| `huginn local` / `huginn local status [--json]` | The mode, class, device name, both services, whether it survives sign-out, the models being served, and the pinned versions. |
| `huginn local plan [--class C] [--name N] [--json]` | What `on` would install here, changing nothing. |
| `huginn local on [options]` | Install and start serving. |
| `huginn local persist [--yes]` | Make an existing install survive sign-out. |
| `huginn local doctor` | Exits non-zero when the install is not doing what it says. |
| `huginn local update [--yes]` | Fetch the newest manager; if the pinned runtime or models changed, download and switch to them. |
| `huginn local unit [--system] [--which llm\|runner]` | Print the service definitions it installs. |
| `huginn local off [--purge-models] [--purge] [--yes]` | Stop serving and remove the services and the enrolment. Models are kept unless you add `--purge-models`; `--purge` removes everything, including the directory. |

`huginn update` also updates the local AI tier when it is installed.

Files live in `~/.config/huginn-local` on Linux (set `HUGINN_LOCAL_DIR` to move it) and
`C:\ProgramData\huginn-local` on Windows.

## Use an existing model server

If the computer already runs a model server on loopback, Huginn can offer its models instead of
installing its own:

```bash
huginn local on --adapter ollama
```

| `--adapter` | Default address |
|---|---|
| `ollama` | `http://127.0.0.1:11434` |
| `lmstudio` | `http://127.0.0.1:1234` |
| `llama-swap` | `http://127.0.0.1:8748` |

`--adapter-url URL` gives another address, which must be on loopback, and `--adapter-key KEY` the
server's API key. The server must already list at least one model. A `llama-swap` server must
refuse requests without its key. Huginn installs only the runner; `huginn local off` never stops
or deletes a model server it did not install.

A computer that already used an existing model server before 2.6.2 needs `huginn local update`
once, which records the account the model server runs as.

## Related

- [Devices](devices.md)
- [Install the clients](../install/clients.md)
- [Security](../security.md)
- [CLI reference](../reference/cli.md)
