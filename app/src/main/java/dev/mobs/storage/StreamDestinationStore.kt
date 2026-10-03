package dev.mobs.storage

import dev.mobs.stream.StreamDestination

/** Persists the single stream-key destination used until YouTube sign-in replaces it. */
interface StreamDestinationStore {
    /** The saved destination, or null when none is saved or it can no longer be decrypted. */
    suspend fun load(): StreamDestination?

    suspend fun save(destination: StreamDestination)
}
