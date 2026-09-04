package app.deltat.commute.location

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TimingServiceQueueTest {
    @Test fun runtimeUpdatesFromConcurrentCallbacksDoNotLoseStateChanges() = runTest {
        RuntimeBus.reset()
        coroutineScope {
            repeat(1_000) {
                launch(Dispatchers.Default) {
                    RuntimeBus.update { it.copy(droppedFixCount = it.droppedFixCount + 1) }
                }
            }
        }
        assertEquals(1_000L, RuntimeBus.state.value.droppedFixCount)
        RuntimeBus.reset()
    }

    @Test fun burstQueueStaysBoundedAndRetainsNewestFix() = runTest {
        val queue = Channel<Int>(
            capacity = TimingService.FIX_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        repeat(1_000) { queue.trySend(it) }
        queue.close()
        val received = buildList {
            for (value in queue) add(value)
        }

        assertEquals(TimingService.FIX_QUEUE_CAPACITY, received.size)
        assertEquals((1_000 - TimingService.FIX_QUEUE_CAPACITY until 1_000).toList(), received)
        assertEquals(999, received.last())
    }
}
