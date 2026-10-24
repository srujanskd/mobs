# IRL Pro research handoff

Transferred to Mobs on October 21, 2026. This folder preserves the completed investigation of IRL Pro 3.5.22 and its evidence. It is a reference for Mobs implementation, not a replacement media engine or proof of feature parity.

Start with the [Mobs implementation handoff](../../irlpro-handoff.md), then the [original investigation dossier](reports/README.md).

## Preserved material

- `reports/`: all six original reports, unchanged, covering streaming/adaptation, native libraries, protocols/TLS, UI/storage/integrations, and runtime observations.
- `evidence/`: the complete evidence directory, including preference and source inventories, native disassembly for focused paths and inventories across four ABIs, screenshots/UI trees, logs, media captures, probes, hashes, and REA Evidence records.
- `scripts/`: the seven original investigation helpers, unchanged.
- `source-context.tar.gz`: the complete original `artifacts/` tree, including the JADX Java/resource export, selected smali, and extracted native libraries. Extract it here when following source citations.
- `original-workspace-README.md` and `rea-doctor.json`: original workspace context and tool diagnostics.
- `transfer-manifest.json`: SHA-256 and size of every transferred file, including the source archive, plus a per-file inventory of the archived artifacts.

The input APK and `tools/` installations are not duplicated. The APK is identified by SHA-256 `f5aa0343467b9890e72cf2292a4ddc68a15660c8e30996ab44f45e1e29c89bcd`, package `app.irlpro.android`, versionCode `305221`. REA was pinned to `6.7.0`, source checkout `25bea07b42119f5bb2f17719ffcdea9e37aa40ce`, with JADX-headless-MCP `0.7.1` JAR SHA-256 `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`.

## Reading and verification

From this folder:

```sh
python3 verify-transfer.py
# Optional: restores paths referenced by the reports and source catalog.
tar -xzf source-context.tar.gz
python3 verify-transfer.py
```

Source citations in the reports use paths relative to this folder after extraction. Markdown links between reports work without extraction. Absolute paths in historical logs describe the original machine/session and are retained as evidence.

The verifier checks every transferred file and streams every archived file to verify its hash. If an extracted `artifacts/` directory exists, it also verifies the extracted files. It does not run the APK or contact remote services.

## Reproducing the investigation

Original workspace: `/Users/srujankashyapd/personal/irl pro rea`. The original scripts are historical tools, not part of the Mobs build. `catalog_apk.py` and `inventory-preferences.py` can operate on the extracted artifacts relative to this folder, but overwrite their generated inventories. Work on a copy if preserving the transfer's byte identity.

`rea-android` requires Android Studio's configured JDK and the pinned JAR under `tools/android/`, which is not bundled. The Java exporters require the matching JADX dependencies and original APK. `capture-settings.py` assumes emulator `emulator-5580`, fixed screen coordinates, a configured ADB path, and execution from this folder. `pull-irlpro` requires an explicitly selected device with the app installed and its configured ADB path. Adapt these assumptions before rerunning; none ran during this handoff.

The archived Java is reconstructed, sometimes obfuscated or malformed. Nine JADX errors and 24 warnings remain documented. The SRTLA startup loop was checked against smali, but the whole export is not compile-ready. These files remain reference material outside `app/src`; they are not compiled, packaged, or loaded by Mobs.

The transfer preserves technical evidence, not a finding about rights to reuse proprietary implementation or assets. Implement Mobs behavior in its own source and choose dependencies with established reuse terms. Remote backend and multichat source are absent. The APK's available concurrent-camera code does not establish device support or a functioning dual-camera UI.
