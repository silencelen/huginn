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
     * A leading `!` followed by something that is not whitespace, on ONE line.
     * `!` alone, `! ls` and a multi-line `!…` are not shell commands here: the
     * first two are what a person typing an exclamation mark looks like, and the
     * daemon refuses the third anyway.
     */
    fun isShellCommand(text: String): Boolean {
        if (text.length < 2 || text[0] != '!') return false
        if (text[1].isWhitespace()) return false
        return '\n' !in text && '\r' !in text
    }

    /** Whether the box should take the shell tint: the text as typed, untrimmed on purpose. */
    fun tinted(draft: String): Boolean = isShellCommand(draft)
}
