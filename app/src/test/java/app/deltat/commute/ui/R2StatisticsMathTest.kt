package app.deltat.commute.ui

import app.deltat.commute.data.LeaderboardRow
import org.junit.Assert.*
import org.junit.Test

/** Arithmetic boundaries only; journey UI acceptance uses the local real export. */
class R2StatisticsMathTest {
    private fun row(id:String, duration:Long, time:Long=1)=LeaderboardRow(id,"r","r",time,duration,"A",null,null,null)
    @Test fun medianKeepsNanosecondPrecisionAndAvoidsOverflow() {
        assertNull(medianDuration(emptyList()))
        assertEquals(1L,medianDuration(listOf(row("a",1))))
        assertEquals(2L,medianDuration(listOf(row("a",1),row("b",4))))
        assertEquals(7L,medianDuration(listOf(row("a",9),row("b",1),row("c",7))))
        assertEquals(Long.MAX_VALUE-1,medianDuration(listOf(row("a",Long.MAX_VALUE-2),row("b",Long.MAX_VALUE))))
    }
    @Test fun orderingHasStableTiesAndDoesNotChangeBaseline() {
        val rows=listOf(row("c",20,2),row("b",10,1),row("a",10,1),row("d",30,3))
        assertEquals(listOf("a","b","c","d"),sortedStatisticsRows(rows,StatisticsSort.FASTEST).map{it.sessionId})
        assertEquals(listOf("d","c","a","b"),sortedStatisticsRows(rows,StatisticsSort.SLOWEST).map{it.sessionId})
        StatisticsSort.entries.forEach{assertEquals(15L,medianDuration(sortedStatisticsRows(rows,it)))}
        assertEquals("与中位数持平",medianDifferenceLabel(0))
    }
}
