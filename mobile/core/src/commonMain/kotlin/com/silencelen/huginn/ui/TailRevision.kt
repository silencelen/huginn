package com.silencelen.huginn.ui

/**
 * Everything that should make a follower move, including growth of the last item.
 *
 * Extracted from ui/Common.kt, whose remaining helpers are all Compose-Android
 * list plumbing. This one is a value: a follow-the-tail view must re-scroll when
 * the LAST item grows, and an item count alone cannot see that. Any client that
 * streams an answer needs the same rule.
 */
fun tailRevision(vararg parts: Any?): Any = parts.toList()
