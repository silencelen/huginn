package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ExtraUsage
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.Spend
import kotlin.math.roundToLong

/**
 * Everything the plan card SAYS, decided once for both clients.
 *
 * This exists because the two shells answered the same question differently and
 * one of them was wrong. The phone turned Claude's reset timestamps into a
 * countdown; the desktop sliced the string with a regex — which silently dropped
 * the `+00:00` on the end and then printed a UTC wall-clock time as if it were
 * local, so "resets 08:00" was an hour that had already passed, or had not
 * arrived, depending on the season. A countdown is immune to that by
 * construction: it is arithmetic on two instants, and instants have no timezone.
 *
 * No `java.time` here — `:core` is commonMain — so the RFC 3339 parse is hand
 * rolled below. That is the price of having ONE answer instead of two.
 */
object PlanFormat {

    /**
     * `2026-08-31T17:00:00.296172+00:00` → epoch millis, or null if it is not a
     * timestamp at all.
     *
     * Accepts what Claude actually sends and the neighbouring shapes: seconds and
     * fractions optional, `Z` or `±HH:MM` or `±HHMM`, either case. A missing
     * offset is read as UTC — RFC 3339 requires one, but a daemon that ever drops
     * it should degrade to an hour's error, not to a blank row.
     */
    fun parseIsoToEpochMs(iso: String?): Long? {
        val s = iso?.trim().orEmpty()
        if (s.isEmpty()) return null
        val m = ISO.matchEntire(s) ?: return null
        val (yS, moS, dS, hS, miS) = m.destructured
        val secS = m.groupValues[6]
        val fracS = m.groupValues[7]
        val offS = m.groupValues[8]

        val year = yS.toIntOrNull() ?: return null
        val month = moS.toIntOrNull() ?: return null
        val day = dS.toIntOrNull() ?: return null
        val hour = hS.toIntOrNull() ?: return null
        val minute = miS.toIntOrNull() ?: return null
        val second = if (secS.isEmpty()) 0 else secS.toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
        if (hour !in 0..23 || minute !in 0..59) return null
        // 60 is a leap second, which no calendar day of ours contains; clamp
        // rather than reject, since rejecting would blank the row for one second.
        if (second !in 0..60) return null

        val millis = if (fracS.isEmpty()) 0 else fracS.padEnd(3, '0').take(3).toInt()
        val offsetSec = parseOffsetSeconds(offS) ?: return null

        val days = daysFromCivil(year, month, day)
        val secs = days * 86_400L + hour * 3600L + minute * 60L + second.coerceAtMost(59).toLong()
        return (secs - offsetSec) * 1000L + millis
    }

    /**
     * "resets in 3h 12m" — a countdown is what you act on, not a timestamp.
     *
     * Phone semantics, ported verbatim so the two clients cannot disagree: days
     * and hours past a day out, hours and minutes inside one, minutes alone
     * inside an hour, and "resetting now" once the instant has passed.
     */
    fun resetCountdown(nowMs: Long, targetMs: Long): String {
        val secs = (targetMs - nowMs) / 1000
        if (secs <= 0) return "resetting now"
        val d = secs / 86_400
        val h = (secs % 86_400) / 3600
        val m = (secs % 3600) / 60
        return when {
            d > 0 -> "resets in ${d}d ${h}h"
            h > 0 -> "resets in ${h}h ${m}m"
            else -> "resets in ${m}m"
        }
    }

    /** The countdown for a wire timestamp, or null when there is nothing to count to. */
    fun resetLabel(iso: String?, nowMs: Long): String? {
        val at = parseIsoToEpochMs(iso) ?: return null
        return resetCountdown(nowMs, at)
    }

    /**
     * Minor units to money: `(10055, 2, "USD")` → `$100.55`.
     *
     * The exponent belongs to the currency, so it is applied here and nowhere
     * else — 5000 JPY at exponent 0 is five thousand yen, not fifty of anything.
     * Only USD gets a symbol; every other currency keeps its code, which is
     * honest about a formatter that knows one locale's conventions.
     */
    fun minorAmount(minor: Long, exponent: Int, currency: String = "USD"): String {
        val exp = exponent.coerceIn(0, 6)
        val negative = minor < 0
        val digits = (if (negative) -minor else minor).toString()
        val body = if (exp == 0) {
            grouped(digits)
        } else {
            val padded = digits.padStart(exp + 1, '0')
            grouped(padded.dropLast(exp)) + "." + padded.takeLast(exp)
        }
        val code = currency.trim().ifEmpty { "USD" }.uppercase()
        val sign = if (negative) "-" else ""
        return if (code == "USD") sign + "$" + body else "$sign$body $code"
    }

    /**
     * The one line under the meter that says where extra usage stands.
     *
     * "Off" is not one state but three, and they need different things from the
     * reader: an org pause lifts by itself at the monthly reset, a switch the
     * owner flipped is the owner's to flip back, and a reached limit is money
     * already spent. Saying "disabled" to all three was how a real $100.55
     * managed to look like nothing at all.
     */
    fun extraUsageState(
        enabled: Boolean,
        spendLimitReached: Boolean = false,
        disabledReason: String? = null,
        userDisabled: Boolean? = null,
    ): String = when {
        enabled && spendLimitReached -> "limit reached"
        enabled -> "on"
        userDisabled == true -> "turned off"
        disabledReason == "org_level_disabled_until" -> "paused until the monthly reset"
        spendLimitReached -> "limit reached, now paused"
        disabledReason != null -> "paused"
        else -> "off"
    }

    /** Everything the extra-usage card draws, already decided. */
    data class ExtraUsageCard(
        val percent: Double,
        /** "$100.55 of $100.00 used", or null when no amount came down the wire. */
        val amountLine: String?,
        val state: String,
        /** Claude's word when it sent one; null means colour by [percent] alone. */
        val severity: String?,
    )

    /**
     * The card, or null when there is nothing worth showing.
     *
     * The daemon already decides whether an account HAS extra usage — it withholds
     * the block entirely for one that never enabled credits, where the wire reads
     * a permanent and meaningless 100% — so this trusts that gate rather than
     * second-guessing it. The one exception is money: a spend block carrying real
     * spend is shown whatever else is missing, because an unexplained charge is
     * the worst thing this screen could hide.
     */
    fun extraUsageCard(plan: Plan?): ExtraUsageCard? {
        val extra: ExtraUsage? = plan?.extraUsage
        val spend: Spend? = plan?.spend
        val spentMinor = spend?.usedMinor ?: 0L
        if (extra == null && spentMinor <= 0L) return null

        val percent = spend?.percent ?: extra?.utilization ?: 0.0
        return ExtraUsageCard(
            percent = percent,
            amountLine = amountLine(extra, spend),
            state = extraUsageState(
                enabled = spend?.enabled ?: extra?.isEnabled ?: false,
                spendLimitReached = extra?.spendLimitReached == true,
                disabledReason = spend?.disabledReason ?: extra?.disabledReason,
                userDisabled = extra?.userDisabled,
            ),
            severity = spend?.severity,
        )
    }

    /**
     * Prefers the `spend` block, falls back to the credit figures.
     *
     * Both carry the same money in minor units; `spend` says so in its own field
     * names, while extra usage's "credits" are minor units at `decimalPlaces` —
     * which is how the phone came to print "100% of 10000 USD" for a $100 cap.
     */
    private fun amountLine(extra: ExtraUsage?, spend: Spend?): String? {
        val used = spend?.usedMinor
        if (used != null) {
            val limit = spend.limitMinor
            val usedText = minorAmount(used, spend.exponent, spend.currency)
            return if (limit != null) {
                "$usedText of ${minorAmount(limit, spend.exponent, spend.currency)} used"
            } else {
                "$usedText used"
            }
        }
        val credits = extra?.usedCredits ?: return null
        val exp = extra.decimalPlaces ?: 2
        val usedText = minorAmount(credits.toLong(), exp, extra.currency)
        val cap = extra.monthlyLimit
        return if (cap != null) {
            "$usedText of ${minorAmount(cap.toLong(), exp, extra.currency)} used"
        } else {
            "$usedText used"
        }
    }

    // ---------------------------------------------------------- token volume

    /**
     * "1.2M tokens".
     *
     * The exact figure is thirteen digits nobody reads; the magnitude is the
     * information. Shared for the same reason as the countdown — the desktop
     * printed `1,234,567` and the phone printed `1.2M`, which is two answers to
     * one question.
     */
    fun compactTokens(n: Long): String = when {
        n >= 1_000_000_000L -> decimals(n / 1_000_000_000.0, 2) + "B tokens"
        n >= 1_000_000L -> decimals(n / 1_000_000.0, 1) + "M tokens"
        n >= 1_000L -> decimals(n / 1_000.0, 1) + "k tokens"
        else -> "$n tokens"
    }

    /** A whole-percent share, e.g. cache reads out of all tokens. */
    fun sharePercent(part: Long, whole: Long): String =
        if (whole <= 0L) "0%" else "" + (part * 100.0 / whole).roundToLong() + "%"

    /**
     * "approx $4,210".
     *
     * Always prefixed, never bare: ccusage prices at list rates and a Max plan
     * pays nothing like it, so this number is a trend line. The plan card's own
     * credit amounts are the opposite — those ARE the bill — which is why they go
     * through [minorAmount] and carry no hedge.
     */
    fun approxDollars(cost: Double): String = "approx $" + grouped(cost.roundToLong().toString())

    // ------------------------------------------------------------- internals

    private val ISO = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:[.,](\\d{1,9}))?" +
            "([Zz]|[+-]\\d{2}:?\\d{2})?$",
    )

    private fun parseOffsetSeconds(off: String): Int? {
        if (off.isEmpty() || off == "Z" || off == "z") return 0
        val sign = if (off[0] == '-') -1 else 1
        val body = off.substring(1).replace(":", "")
        if (body.length != 4) return null
        val h = body.substring(0, 2).toIntOrNull() ?: return null
        val m = body.substring(2, 4).toIntOrNull() ?: return null
        if (h > 23 || m > 59) return null
        return sign * (h * 3600 + m * 60)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    }

    /**
     * Days between 1970-01-01 and a civil date, exactly.
     *
     * Hinnant's era arithmetic: shift the year to start in March so the leap day
     * lands last and the month lengths become a linear sequence, then count whole
     * 400-year eras, whose day count is fixed. No loops, no table, no library.
     */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097L + doe.toLong() - 719_468L
    }

    /** A fixed number of decimal places, without `String.format` (JVM-only). */
    private fun decimals(v: Double, places: Int): String {
        var scale = 1L
        repeat(places) { scale *= 10L }
        val scaled = (v * scale).roundToLong()
        val negative = scaled < 0
        val digits = (if (negative) -scaled else scaled).toString().padStart(places + 1, '0')
        val body = grouped(digits.dropLast(places)) + "." + digits.takeLast(places)
        return if (negative) "-$body" else body
    }

    /** 1234567 → "1,234,567". Money at four digits is unreadable without it. */
    private fun grouped(digits: String): String {
        if (digits.length <= 3) return digits
        val out = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return out.toString()
    }
}
