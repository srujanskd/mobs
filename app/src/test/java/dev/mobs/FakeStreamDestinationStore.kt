package dev.mobs

import dev.mobs.storage.StreamDestinationStore
import dev.mobs.stream.StreamDestination
import kotlinx.coroutines.CompletableDeferred

/** [load] suspends until [loaded] completes, so a test can hold the disk read open. */
class FakeStreamDestinationStore : StreamDestinationStore {
    val loaded = CompletableDeferred<StreamDestination?>()
    val saved = mutableListOf<StreamDestination>()

    override suspend fun load() = loaded.await()

    override suspend fun save(destination: StreamDestination) {
        saved += destination
    }

    companion object {
        fun holding(destination: StreamDestination?) =
            FakeStreamDestinationStore().apply { loaded.complete(destination) }
    }
}
