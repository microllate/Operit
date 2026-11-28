package com.ai.assistance.operit.ui.features.tokenstats

import com.ai.assistance.operit.data.stats.TokenActivityViewMode
import com.ai.assistance.operit.data.stats.TokenStatsTimeRange
import com.ai.assistance.operit.data.stats.TokenStatsTimeRanges
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun activityRangeAnchorDate(
    range: TokenStatsTimeRange,
    zone: ZoneId,
): LocalDate = Instant.ofEpochMilli(range.endMs - 1L).atZone(zone).toLocalDate()

internal fun activityRangeForMode(
    mode: TokenActivityViewMode,
    anchorDate: LocalDate,
    historyStartDate: LocalDate?,
    zone: ZoneId,
): TokenStatsTimeRange? {
    val startDate = when (mode) {
        TokenActivityViewMode.DAILY -> anchorDate
        TokenActivityViewMode.WEEKLY -> {
            // Keep the range aligned with TokenActivityAggregator's Sunday-first weeks.
            anchorDate.minusDays((anchorDate.dayOfWeek.value % 7).toLong())
        }
        TokenActivityViewMode.CUMULATIVE -> historyStartDate ?: return null
    }
    if (startDate.isAfter(anchorDate)) return null
    return TokenStatsTimeRanges.customRange(
        startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
        anchorDate.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

/**
 * Older builds saved ranges without a follow/manual flag. Migrate only a recent,
 * calendar-aligned default/mode range; leave arbitrary historical selections fixed.
 */
internal fun inferLegacyFollowCurrentDay(
    range: TokenStatsTimeRange?,
    mode: TokenActivityViewMode,
    today: LocalDate,
    zone: ZoneId,
): Boolean {
    if (range == null) return true
    val anchor = activityRangeAnchorDate(range, zone)
    if (anchor.isBefore(today.minusDays(1)) || anchor.isAfter(today)) return false
    val start = Instant.ofEpochMilli(range.startMs).atZone(zone).toLocalDate()
    val aligned =
        start.atStartOfDay(zone).toInstant().toEpochMilli() == range.startMs &&
            anchor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() == range.endMs
    if (!aligned) return false
    val isDaily = start == anchor
    val isWeekly = start == anchor.minusDays((anchor.dayOfWeek.value % 7).toLong())
    val isDefaultThirtyDay = start == anchor.minusDays(29)
    return when (mode) {
        TokenActivityViewMode.DAILY -> isDaily || isDefaultThirtyDay
        TokenActivityViewMode.WEEKLY -> isWeekly || isDefaultThirtyDay
        TokenActivityViewMode.CUMULATIVE -> isDefaultThirtyDay
    }
}

/** Advance only automatically followed ranges; never rewrite manually selected history. */
internal fun advanceFollowedActivityRange(
    range: TokenStatsTimeRange,
    mode: TokenActivityViewMode,
    today: LocalDate,
    zone: ZoneId,
): TokenStatsTimeRange {
    val anchor = activityRangeAnchorDate(range, zone)
    if (!anchor.isBefore(today)) return range
    val startDate = Instant.ofEpochMilli(range.startMs).atZone(zone).toLocalDate()
    val startIsAligned = startDate.atStartOfDay(zone).toInstant().toEpochMilli() == range.startMs
    val endIsAligned = anchor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() == range.endMs
    if (!startIsAligned || !endIsAligned) return range

    val isSingleDay = startDate == anchor
    val isWeek = startDate == anchor.minusDays((anchor.dayOfWeek.value % 7).toLong())
    val nextStartDate = when {
        mode == TokenActivityViewMode.CUMULATIVE -> startDate
        mode == TokenActivityViewMode.DAILY && isSingleDay -> today
        mode == TokenActivityViewMode.WEEKLY && isWeek ->
            today.minusDays((today.dayOfWeek.value % 7).toLong())
        else -> startDate.plusDays(java.time.temporal.ChronoUnit.DAYS.between(anchor, today))
    }
    return TokenStatsTimeRanges.customRange(
        nextStartDate.atStartOfDay(zone).toInstant().toEpochMilli(),
        today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

internal fun resolveTokenStatsRange(
    storedRange: TokenStatsTimeRange?,
    mode: TokenActivityViewMode,
    followCurrentDay: Boolean?,
    today: LocalDate,
    zone: ZoneId,
): TokenStatsTimeRange {
    if (storedRange == null) {
        return TokenStatsTimeRanges.customRange(
            today.minusDays(29L).atStartOfDay(zone).toInstant().toEpochMilli(),
            today.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }
    val followsToday = followCurrentDay ?: inferLegacyFollowCurrentDay(storedRange, mode, today, zone)
    return if (followsToday) advanceFollowedActivityRange(storedRange, mode, today, zone) else storedRange
}
