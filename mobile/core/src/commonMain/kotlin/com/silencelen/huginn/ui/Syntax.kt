package com.silencelen.huginn.ui

/**
 * A deliberately small syntax tokenizer for the code that actually shows up in a
 * Claude conversation: shell commands, diffs, and short source snippets.
 *
 * It is a lexer, not a parser. Getting a keyword wrong costs a colour, never
 * correctness, and the whole point is readability at a glance on a phone — so it
 * favours the few token classes that carry meaning (comment, string, number,
 * keyword, and diff signs) over completeness.
 *
 * Kept free of Compose types so it can be unit-tested on the JVM.
 */
object Syntax {

    enum class Tok { PLAIN, KEYWORD, STRING, NUMBER, COMMENT, FUNCTION, PUNCT, ADDED, REMOVED, META }

    data class Span(val start: Int, val end: Int, val tok: Tok)

    private val SHELL = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "exit", "export", "local", "readonly", "set", "unset",
        "echo", "cd", "sudo", "source", "trap", "shift", "eval", "test", "in",
    )
    private val C_LIKE = setOf(
        "val", "var", "fun", "class", "object", "interface", "data", "sealed", "enum",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "import", "package", "private", "public", "internal", "protected", "override",
        "suspend", "const", "companion", "null", "true", "false", "this", "super",
        "try", "catch", "finally", "throw", "new", "function", "let", "async", "await",
        "def", "elif", "None", "True", "False", "self", "lambda", "yield", "with", "as",
        "from", "type", "struct", "func", "go", "defer", "static", "void", "int", "string",
    )

    /** Normalizes a fence label to the dialect used for tokenizing. */
    fun dialect(lang: String?): String = when (lang?.lowercase()?.trim()) {
        "sh", "bash", "zsh", "shell", "console", "terminal" -> "shell"
        "diff", "patch" -> "diff"
        "json" -> "json"
        "yaml", "yml", "toml", "ini", "conf" -> "conf"
        null, "" -> "plain"
        else -> "code"
    }

    fun highlight(code: String, lang: String?): List<Span> = when (dialect(lang)) {
        "diff" -> diff(code)
        "shell" -> generic(code, SHELL, hash = true, slash = false)
        "conf" -> generic(code, emptySet(), hash = true, slash = false)
        "json" -> generic(code, setOf("true", "false", "null"), hash = false, slash = false)
        "plain" -> emptyList()
        else -> generic(code, C_LIKE, hash = true, slash = true)
    }

    /** Whole-line classification: the only thing that matters in a diff. */
    private fun diff(code: String): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        for (line in code.split("\n")) {
            val end = i + line.length
            val tok = when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> Tok.META
                line.startsWith("+") -> Tok.ADDED
                line.startsWith("-") -> Tok.REMOVED
                else -> null
            }
            if (tok != null && end > i) out.add(Span(i, end, tok))
            i = end + 1
        }
        return out
    }

    private fun generic(code: String, keywords: Set<String>, hash: Boolean, slash: Boolean): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                // Comments run to end of line.
                hash && c == '#' -> {
                    val end = lineEnd(code, i); out.add(Span(i, end, Tok.COMMENT)); i = end
                }
                slash && c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                    val end = lineEnd(code, i); out.add(Span(i, end, Tok.COMMENT)); i = end
                }
                slash && c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val close = code.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    out.add(Span(i, end, Tok.COMMENT)); i = end
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val end = stringEnd(code, i, c)
                    out.add(Span(i, end, Tok.STRING)); i = end
                }
                c.isDigit() && (i == 0 || !isWordChar(code[i - 1])) -> {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    out.add(Span(i, j, Tok.NUMBER)); i = j
                }
                isWordStart(c) -> {
                    var j = i
                    while (j < n && isWordChar(code[j])) j++
                    val word = code.substring(i, j)
                    when {
                        word in keywords -> out.add(Span(i, j, Tok.KEYWORD))
                        // A word directly followed by '(' is being called.
                        j < n && code[j] == '(' -> out.add(Span(i, j, Tok.FUNCTION))
                        // A leading flag reads as structure in a shell command.
                        word.length > 1 && i > 0 && code[i - 1] == '-' -> out.add(Span(i - 1, j, Tok.META))
                    }
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun lineEnd(code: String, from: Int): Int {
        val nl = code.indexOf('\n', from)
        return if (nl < 0) code.length else nl
    }

    /** Handles escapes so a `\"` inside a string does not end it. */
    private fun stringEnd(code: String, from: Int, quote: Char): Int {
        var j = from + 1
        while (j < code.length) {
            val ch = code[j]
            if (ch == '\\') { j += 2; continue }
            if (ch == quote) return j + 1
            // An unterminated quote must not swallow the rest of the block.
            if (ch == '\n' && quote != '`') return j
            j++
        }
        return code.length
    }

    private fun isWordStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
