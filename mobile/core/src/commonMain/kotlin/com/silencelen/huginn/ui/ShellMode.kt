package com.silencelen.huginn.ui

/**
 * Claude Code's shell mode, as the SESSION composer sees it: a message whose
 * first character is `!` runs as a shell command in the pane rather than being
 * sent to Claude.
 *
 * The daemon enforces the contract (appd 3.17): a send that means it carries
 * `shell: true` on `POST /v1/sessions/:name/keys` and the server types the `!`,
 * the command and Enter itself; a `!` message WITHOUT the flag is refused with
 * 409 before anything is typed, and a multi-line shell send is refused with 400.
 * This is the one place both shells decide "this text is a shell command", so
 * the tint, the hint and the flag cannot disagree.
 */
object ShellMode {
    /** The one-line hint under a tinted composer. */
    const val HINT: String = "Runs as a shell command in Claude Code"

    /**
     * A leading `!` with something to run after it, on ONE line.
     *
     * ⚠ "! echo spaced" IS A COMMAND (2026-11-03). This used to want a non-space
     * right after the `!`, on the theory that `! ls` is a person typing an
     * exclamation mark. Claude Code does not read it that way: ANY `!` typed into
     * an empty box puts the box in shell mode, and the daemon's `shell: true`
     * drops the `!` and trims what follows. So `! echo spaced` got no tint and no
     * hint, went out without the flag, and the daemon refused it with 409 for
     * starting with `!` — the command never ran and nothing on screen said why.
     *
     * Not a command: `!` alone or with only whitespace after it (nothing to run;
     * the daemon would answer "a shell command needs text"), anything that does
     * not START with `!` (` !ls` is a message, as the daemon reads it), and a
     * multi-line `!…`, which the daemon refuses anyway — in shell mode a newline
     * is an Enter.
     */
    fun isShellCommand(text: String): Boolean {
        if (text.isEmpty() || text[0] != '!') return false
        if ('\n' in text || '\r' in text) return false
        return text.substring(1).isNotBlank()
    }

    /** Whether the box should take the shell tint: the text as typed, untrimmed on purpose. */
    fun tinted(draft: String): Boolean = isShellCommand(draft)
}
