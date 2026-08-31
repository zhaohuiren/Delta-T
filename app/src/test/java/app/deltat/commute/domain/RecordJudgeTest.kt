package app.deltat.commute.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordJudgeTest {
    @Test fun completedRecordUsesStrictImprovementWithoutGnssTolerance() {
        assertEquals(ResultColor.PURPLE, RecordJudge.periodColor(9_999_999_999, 10_000_000_000, 10_000_000_000, QualityGrade.A, 5_000))
        assertEquals(ResultColor.GREEN, RecordJudge.periodColor(9_999_999_999, 9_000_000_000, 10_000_000_000, QualityGrade.B, 5_000))
    }

    @Test fun monthAndWeekStatusesRemainIndependent() {
        val result = RecordJudge.completedComparison(9_500, monthlyBestDurationNanos = 9_000, weeklyBestDurationNanos = 10_000, QualityGrade.A)
        assertEquals(PeriodComparisonStatus.SLOWER, result.monthly)
        assertEquals(PeriodComparisonStatus.FASTER, result.weekly)
    }

    @Test fun missingWeekFallsBackToMonthForPrimarySlowColor() {
        assertEquals(ResultColor.YELLOW, RecordJudge.periodColor(11_000, 10_000, null, QualityGrade.A, 0))
        assertEquals(ResultColor.NEUTRAL, RecordJudge.periodColor(10_000, 10_000, null, QualityGrade.A, 0))
    }

    @Test fun qualityBParticipatesAndQualityCNeverColors() {
        assertEquals(ResultColor.PURPLE, RecordJudge.periodColor(9_000, 10_000, null, QualityGrade.B, 0))
        assertEquals(ResultColor.NEUTRAL, RecordJudge.periodColor(9_000, 10_000, 10_000, QualityGrade.C, 0))
        assertEquals(PeriodComparisonStatus.INELIGIBLE, RecordJudge.completedComparison(9_000, 10_000, 10_000, QualityGrade.C).monthly)
    }

    @Test fun liveEstimatesStillUseDynamicTolerance() {
        assertEquals(ResultColor.NEUTRAL, RecordJudge.livePeriodColorFromDeltas(-200_000_000, null, QualityGrade.A, 100))
        assertEquals(ResultColor.PURPLE, RecordJudge.livePeriodColorFromDeltas(-600_000_000, null, QualityGrade.A, 100))
    }
}
