package com.ai.assistance.operit.ui.features.tokenstats

import com.ai.assistance.operit.data.stats.TokenActivityViewMode
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenStatsActivityRangePolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `daily mode selects the anchor date`() {
        val range = activityRangeForMode(
            mode = TokenActivityViewMode.DAILY,
            anchorDate = LocalDate.of(2026, 8, 22),
            historyStartDate = null,
            zone = zone,
        )

        assertEquals(
            customRangeInclusiveEnd(
                LocalDate.of(2026, 8, 22),
                LocalDate.of(2026, 8, 22),
                zone,
            ),
            range,
        )
    }

    @Test
    fun `weekly mode selects the existing Sunday-first calendar week`() {
        val range = activityRangeForMode(
            mode = TokenActivityViewMode.WEEKLY,
            anchorDate = LocalDate.of(2026, 8, 22),
            historyStartDate = null,
            zone = zone,
        )

        assertEquals(
            customRangeInclusiveEnd(
                LocalDate.of(2026, 8, 16),
                LocalDate.of(2026, 8, 22),
                zone,
            ),
            range,
        )
    }

    @Test
    fun `cumulative mode starts at the filtered history start and ends at anchor`() {
        val range = activityRangeForMode(
            mode = TokenActivityViewMode.CUMULATIVE,
            anchorDate = LocalDate.of(2026, 8, 22),
            historyStartDate = LocalDate.of(2026, 8, 1),
            zone = zone,
        )

        assertEquals(
            customRangeInclusiveEnd(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 22),
                zone,
            ),
            range,
        )
    }

    @Test
    fun `cumulative mode has no range before the first recorded date`() {
        assertNull(
            activityRangeForMode(
                mode = TokenActivityViewMode.CUMULATIVE,
                anchorDate = LocalDate.of(2026, 8, 1),
                historyStartDate = LocalDate.of(2026, 8, 2),
                zone = zone,
            )
        )
    }

    @Test
    fun `daily auto range moves to Beijing today without changing calendar boundaries`() {
        val yesterday = LocalDate.of(2026, 11, 19)
        val today = yesterday.plusDays(1)
        val previous = customRangeInclusiveEnd(yesterday, yesterday, zone)
        assertEquals(
            customRangeInclusiveEnd(today, today, zone),
            advanceFollowedActivityRange(previous, TokenActivityViewMode.DAILY, today, zone),
        )
        assertEquals(
            previous,
            advanceFollowedActivityRange(previous, TokenActivityViewMode.DAILY, yesterday, zone),
        )
    }

    @Test
    fun `weekly auto range resets on Sunday and keeps same Sunday inside week`() {
        val saturday = LocalDate.of(2026, 11, 21)
        val sunday = saturday.plusDays(1)
        val previous = customRangeInclusiveEnd(LocalDate.of(2026, 11, 15), saturday, zone)
        assertEquals(
            customRangeInclusiveEnd(sunday, sunday, zone),
            advanceFollowedActivityRange(previous, TokenActivityViewMode.WEEKLY, sunday, zone),
        )
    }

    @Test
    fun `legacy historical custom range remains fixed`() {
        val today = LocalDate.of(2026, 11, 20)
        val historical = customRangeInclusiveEnd(today.minusDays(12), today.minusDays(12), zone)
        assertEquals(
            false,
            inferLegacyFollowCurrentDay(historical, TokenActivityViewMode.DAILY, today, zone),
        )
        assertEquals(
            true,
            inferLegacyFollowCurrentDay(
                customRangeInclusiveEnd(today.minusDays(1), today.minusDays(1), zone),
                TokenActivityViewMode.DAILY,
                today,
                zone,
            ),
        )
    }

    @Test
    fun `rolling thirty day window advances without losing older days`() {
        val today = LocalDate.of(2026, 11, 20)
        val lastRange = customRangeInclusiveEnd(today.minusDays(30), today.minusDays(1), zone)
        assertEquals(
            customRangeInclusiveEnd(today.minusDays(29), today, zone),
            advanceFollowedActivityRange(lastRange, TokenActivityViewMode.DAILY, today, zone),
        )
    }
}
