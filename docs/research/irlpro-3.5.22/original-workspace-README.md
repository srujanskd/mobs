# IRL Pro reverse-engineering workspace

The supplied APK has been analyzed using REA, its pinned JADX engine, native ELF inspection and an offline Android emulator.

Start with [the investigation dossier](reports/README.md). It links the streaming, native, protocol, UI/storage and runtime reports, the evidence ledger and remaining gaps.

- Decompiled Java and decoded resources: `artifacts/jadx/`.
- Selected raw Dalvik smali: `artifacts/smali/`.
- Original extracted native libraries: `artifacts/unpacked/lib/`.
- REA Evidence, inventories, native disassembly and runtime captures: `evidence/`.
- Reproducible analysis helpers: `scripts/`.

Target: `app.irlpro.android` 3.5.22, versionCode 305221. APK SHA-256 `f5aa0343467b9890e72cf2292a4ddc68a15660c8e30996ab44f45e1e29c89bcd`.

This workspace contains reconstructed code and evidence, not recovered original source or a buildable replacement app. Server internals, complete native semantics and physical-device behavior remain outside the established coverage.
