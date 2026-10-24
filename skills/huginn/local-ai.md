# Local AI (experimental)

One of the person's computers can serve open models (llama.cpp through llama-swap, pinned
versions and checksums) to the server: chats in the apps can pick a local model, and the server
hands text tasks to it with `huginn llm`, without spending the Claude plan. Local models get no
tools, no files and none of the server's context.

```bash
huginn local plan             # what `on` would install here; changes nothing
huginn local status [--json]  # mode, class, services, persistence, models
huginn local doctor           # exits non-zero when the install is not doing what it says
huginn local on [--system] [--class G8|G16|C|A] [--adapter ollama|lmstudio|llama-swap]
huginn local persist          # Linux: make user services survive sign-out
huginn local update
huginn local off [--purge-models] [--purge]
huginn llm "prompt"           # one question to the tier, answered on the server (over SSH)
```

- Needs Node.js on the serving machine; the manager is `~/.huginn/huginn-local`.
- Files: `~/.config/huginn-local` (Linux, `HUGINN_LOCAL_DIR` moves it), `%ProgramData%\huginn-local`
  (Windows, LocalSystem services; `on`, `off`, `update`, `persist` ask for administrator approval).
- The model server listens on `127.0.0.1:8748` only, behind an API key made on the spot. The
  serving machine holds a second device enrolment, `<host>-llm`, at scope `generate`.
- Hardware classes: G8 = NVIDIA GPU with 7.5 GiB+ (Windows only), C = x86-64 with AVX2 and
  7.5 GiB RAM, A = arm64 with 7 GiB RAM. `--class` may only pick a smaller class.
- `--adapter` offers a model server the person already runs instead of installing one; the key
  goes in a file (`--adapter-key-file`), not on the command line.

Installing, updating or removing the tier downloads gigabytes or removes them: ask first.
`huginn local status` and `huginn local doctor` are the safe reads.
