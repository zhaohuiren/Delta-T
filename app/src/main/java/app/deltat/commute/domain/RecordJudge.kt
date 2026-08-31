package app.deltat.commute.domain

object RecordJudge {
    fun completedComparison(
        durationNanos: Long,
        monthlyBestDurationNanos: Long?,
        weeklyBestDurationNanos: Long?,
        qualityGrade: QualityGrade,
    ): PeriodComparisonResult {
        if (qualityGrade == QualityGrade.C) {
            return PeriodComparisonResult(PeriodComparisonStatus.INELIGIBLE, PeriodComparisonStatus.INELIGIBLE)
        }
        fun compare(reference: Long?): PeriodComparisonStatus = when {
            reference == null -> PeriodComparisonStatus.UNAVAILABLE
            durationNanos < reference -> PeriodComparisonStatus.FASTER
            durationNanos > reference -> PeriodComparisonStatus.SLOWER
            else -> PeriodComparisonStatus.TIED
        }
        return PeriodComparisonResult(compare(monthlyBestDurationNanos), compare(weeklyBestDurationNanos))
    }

    fun periodColor(
        durationNanos: Long,
        monthlyBestDurationNanos: Long?,
        weeklyBestDurationNanos: Long?,
        qualityGrade: QualityGrade,
        uncertaintyMillis: Long,
    ): ResultColor {
        val result = completedComparison(durationNanos, monthlyBestDurationNanos, weeklyBestDurationNanos, qualityGrade)
        return when {
            result.monthly == PeriodComparisonStatus.FASTER -> ResultColor.PURPLE
            result.weekly == PeriodComparisonStatus.FASTER -> ResultColor.GREEN
            result.weekly == PeriodComparisonStatus.SLOWER -> ResultColor.YELLOW
            result.weekly == PeriodComparisonStatus.UNAVAILABLE && result.monthly == PeriodComparisonStatus.SLOWER -> ResultColor.YELLOW
            else -> ResultColor.NEUTRAL
        }
    }

    fun periodColorFromDeltas(
        monthlyDeltaNanos: Long?,
        weeklyDeltaNanos: Long?,
        qualityGrade: QualityGrade,
        uncertaintyMillis: Long,
    ): ResultColor {
        if (qualityGrade == QualityGrade.C) return ResultColor.NEUTRAL
        if (monthlyDeltaNanos != null && monthlyDeltaNanos < 0) return ResultColor.PURPLE
        if (weeklyDeltaNanos != null && weeklyDeltaNanos < 0) return ResultColor.GREEN
        val effectiveDelta = weeklyDeltaNanos ?: monthlyDeltaNanos
        return if (effectiveDelta != null && effectiveDelta > 0) ResultColor.YELLOW else ResultColor.NEUTRAL
    }

    fun livePeriodColorFromDeltas(
        monthlyDeltaNanos: Long?,
        weeklyDeltaNanos: Long?,
        qualityGrade: QualityGrade,
        uncertaintyMillis: Long,
    ): ResultColor {
        if (qualityGrade == QualityGrade.C) return ResultColor.NEUTRAL
        val tolerance = maxOf(500_000_000L, uncertaintyMillis * 1_000_000L)
        if (monthlyDeltaNanos != null && monthlyDeltaNanos < -tolerance) return ResultColor.PURPLE
        if (weeklyDeltaNanos != null && weeklyDeltaNanos < -tolerance) return ResultColor.GREEN
        val effectiveDelta = weeklyDeltaNanos ?: monthlyDeltaNanos
        return if (effectiveDelta != null && effectiveDelta > tolerance) ResultColor.YELLOW else ResultColor.NEUTRAL
    }

    fun color(
        durationNanos: Long,
        referenceDurationNanos: Long?,
        previousBestDurationNanos: Long?,
        qualityGrade: QualityGrade,
        uncertaintyMillis: Long,
    ): ResultColor {
        if (qualityGrade == QualityGrade.C || referenceDurationNanos == null) return ResultColor.NEUTRAL
        if (previousBestDurationNanos != null && durationNanos < previousBestDurationNanos) return ResultColor.PURPLE
        if (durationNanos < referenceDurationNanos) return ResultColor.GREEN
        if (durationNanos > referenceDurationNanos) return ResultColor.YELLOW
        return ResultColor.NEUTRAL
    }
}
