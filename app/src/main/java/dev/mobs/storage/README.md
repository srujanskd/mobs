# Storage

Planned home for Room scene/goal metadata, DataStore preferences, and app-owned media. Exclude credentials from scene exports.

- `StreamDestinationStore` — the saved stream-key destination. `KeystoreStreamDestinationStore` keeps the server in private preferences and the stream key AES-GCM encrypted under a non-exportable Android Keystore key. An unreadable key (for example after a Keystore invalidation) loads as "not configured", and the user enters it again.
