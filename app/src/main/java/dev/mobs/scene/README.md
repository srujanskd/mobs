# Scene collections

`Scene` and `SceneSource` contain only durable data. Sources draw in list order, with the last source on top. Each scene supports up to 12 sources and each collection up to 24 scenes. Camera sources are unique per facing; multiple text, image, and color sources are allowed. Coordinates and opacity are normalized and validated, including nonfinite values.

`SceneStore` stores a versioned collection with bounded counts and a size limit. It flushes a temporary file and atomically replaces the old collection. Unknown versions and corrupt files are reported and preserved. `SceneMedia` imports photo-picker images into owned PNG files, decoded to at most 1920x1080, with a bounded storage budget. Picker grants are not needed after import.

`SceneViewModel` keeps drafts separate from the engine, serializes collection writes on IO, and applies the saved active scene after a service recreation. The editor supports scene creation/duplication/deletion, source creation/removal, drag/resize, order, visibility, opacity, color, text, and placement locking. Deleting the active scene requires switching first. Only Apply or a scene-switch button changes broadcast output.

Scene audio currently preserves the existing microphone and mute state. Sources for goals, chat, remote video, and DJI have not been implemented. The draft canvas shows camera placeholders; actual capture is visible in the composed output preview after applying.
