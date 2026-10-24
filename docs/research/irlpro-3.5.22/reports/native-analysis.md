# Native libraries in IRL Pro 3.5.22

## Scope and method

Static inspection of the 48 ELF shared objects extracted from the supplied APK. Four ABIs each contain the same 12 library basenames. Hashes, sizes, DT_NEEDED dependencies and exported JNI symbols for every object are recorded in `evidence/native/library-inventory.json`. Symbol inventories and dynamic headers are retained under each ABI directory in `evidence/native/`.

The main behavioral inspection below uses the arm64-v8a copy. Addresses are ELF virtual addresses before any runtime load bias. `/usr/bin/objdump` is Apple LLVM 21.0.0 and successfully decoded these AArch64 ELF objects. No native library was loaded or executed. The retained disassembly establishes control/data flow, not observed network outcomes. Other ABIs were inventoried but their algorithms were not separately compared instruction by instruction.

## Native architecture established by exports and dependencies

`libsrtsender.so` directly depends on `libsrt.so` and `librist.so`. Its 19 named JNI exports bind to `com.wmspanel.libsrtsender.SrtSender`. The exports cover SRT startup/cleanup, register/unregister, SRT and RIST connect, video/AAC frame submission, PAT/PMT submission, receive, configuration-record retrieval, and per-transport statistics. Native class symbols identify MPEG-2 transport stream handling, including `MPEG2TSLiveStream::processAACAudioMessage`, `processAVCVideoMessageInternal`, `processHEVCVideoMessageInternal`, and `Mpeg2Ts::write_mpeg2ts_avc`, `write_mpeg2ts_hevc`, `write_mpeg2ts_audio`. This establishes an available MPEG-TS packaging implementation and native SRT/RIST transport boundary. It does not by itself prove which codecs the UI enables or which transport a session uses.

Useful JNI entry points in arm64 `libsrtsender.so`:

- `register` at `0x17cc4`, length `0x300`.
- `ristRegister` at `0x180a8`, length `0x1c0`.
- `srtConnect` at `0x189a0`, length `0x5b0`.
- `sendVideoFrame` at `0x19530`, length `0x2a4`.
- `sendAacFrame` at `0x197d4`, length `0x2b8`.
- `sendPatPmt` at `0x19a8c`, length `0x1a4`.
- `srtBstats` at `0x19e98`, length `0x140`.
- `getSrtStats` at `0x19fd8`, length `0x180`.
- `ristConnect` at `0x1a860`, length `0x264`.
- `srtClose` at `0x1ac80`, length `0x1c8`.

`libsrtla.so` is a separate UDP relay/bonding implementation. It has no DT_NEEDED dependency on `libsrt.so`. It receives SRT packets over a local UDP socket, chooses an outgoing connection and forwards the datagram to the SRTLA destination. It maintains packet/acknowledgment state independently of the SRT sender. This separation is established by the loop and send/receive call paths described below.

`libuvccamera.so` exports nine `com.wmspanel.libuvccamera.UVCCamera` JNI methods, including `wrap`, `getFormatDescs`, `setSurface`, `startStreaming`, and `stopStreaming`. It depends on libuvc, libusb, libjpeg, yyjson, Android graphics and Android native libraries. This is an available USB-video capture implementation; device support and UI reachability require Java and runtime corroboration.

`libobjectbox-jni.so` exports the ObjectBox transaction, cursor, box-store and query API. It establishes the bundled database engine. The application's entity/schema use must be recovered from the DEX code.

## SRTLA behavior recovered from AArch64 instructions

The arm64 object is only 22,888 bytes, with 37 exported text functions. Relevant complete disassembly is retained in `evidence/native/srtla-arm64-disassembly.txt`, and printable strings with file offsets in `evidence/native/srtla-arm64-strings.txt`.

### Java/native contract and event loop

`Java_com_wmspanel_streamer_Srtla_startSRTLA` starts at `0x3f78`, length `0x7ec`. It obtains three UTF strings from its Java parameters and resolves these Java callbacks by name and signature:

- `getConnsString()Ljava/lang/String;`
- `getLastUpdate()I`
- `shouldKill()I`
- `setSrtlaIp(Ljava/lang/String;)V`
- `updateConn(Ljava/lang/String;IIIIIIJJJJJI)V`
- `cleanupFD(I)V`, resolved as a static callback.

The JNI loop calls `setup_conns`, parses the listen port, obtains 256 random bytes from `/dev/urandom` for the group identity, opens an IPv4 UDP socket and binds it. The bind address is zero, which means the wildcard IPv4 address, rather than a hardcoded loopback address. It resolves/opens outbound connections using `open_conns`. Once initialized, the loop updates Java connection statistics and polls Java's configuration-update index and stop condition roughly every 500 ms. It calls native connection housekeeping, then `select`, then `handle_srt_data` for the listener and `handle_srtla_data` for readable outbound connections. These are static control-flow observations, not proof of externally reachable behavior under Android routing or firewall rules.

The startup routine returns 1 for failure to open `/dev/urandom`, 2 for listener socket creation failure, 3 for bind failure, 10 when initial outbound connections cannot be established, and 0 after Java's stop condition triggers the normal listener-close path. These values should be matched against Java-side handling before assigning user-facing error meanings.

### Connection configuration

`setup_conns` at `0x3834` parses each connection record into an IP address, file descriptor and integer weight. The FD is stored at connection offset `+0x08`, the weight at `+0x10`, and the parsed IPv4 address at `+0x28`. Existing matching source addresses have their weight updated. New records allocate `0x498` bytes and start the internal window at 20,000. The callback/export names and instructions establish this parsing behavior. Exact delimiters should be corroborated with the Java string producer.

### Scheduling rule

`select_conn` at `0x2b0c` traverses the linked connection list. For the ordinary scheduling branch it ignores a connection whose receive timestamp is older than four seconds, then computes:

```text
score = weight * integer_divide(window, in_flight + 1)
```

It chooses the first candidate whose score exceeds the current best, starting with best = -1. Division occurs before multiplication. This matters because it is not floating-point proportional weighting. The winning path's last-use timestamp is updated.

Recovered struct offsets are `weight +0x10`, last-received seconds `+0x18`, last-used seconds `+0x20`, in-flight packets `+0x3c`, outstanding keepalives `+0x40`, internal window `+0x80`, and recent-packet ring insertion index `+0x90`. Log format at file offset `0x1c85` labels in-flight, keepalive and window values, corroborating the instruction-based field interpretation.

There is an extra branch when global `experimental == 1`. It compares a connection's internal window with one fifth of the maximum window. A sufficiently weak path may receive a 1,024-byte keepalive/probe, subject to keepalive and in-flight thresholds. That branch can reduce its window by 30 with a floor of 1,000 and skip ordinary selection for that iteration. The flag is assigned from a JNI integer argument. Whether the UI exposes/enables this flag is a Java-side question.

### Sending, packet history, acknowledgment and loss response

`handle_srt_data` at `0x2cd0` receives up to 1,500 bytes, invokes the scheduler, and sends the received datagram to the SRTLA destination through the chosen FD. For an SRT data sequence number it records that number in a 256-entry recent-packet ring, increments in-flight and sent-packet counters, and accumulates both data bytes and total bytes. A send-size mismatch stores a disabled/stale receive timestamp and emits a connection-disabled log.

`get_srt_sn` at `0x48fc` reads a network-order 32-bit sequence number when the datagram has at least four bytes, returning -1 for a control packet indicated by the high bit. This explains why only data packets contribute entries to that sequence-number ring.

`register_nak` at `0x2e58` searches connection packet rings for the requested sequence, invalidates the matching ring entry, increments a negative-acknowledgment counter and reduces the internal window by 100, with a floor of 1,000. It returns after finding the first match. `register_srtla_ack` at `0x2ef4` similarly invalidates matching sequence entries, reduces in-flight without making it negative, and increments an acknowledgment counter. It increases/adapts windows based on current in-flight state and the experimental flag, with a ceiling of 60,000. This is packet-routing/congestion state; it is separate from the Android encoder's video bitrate algorithm.

### Keepalive, re-registration and packet markers

`connection_housekeeping` at `0x3ce8` is gated by a 500 ms interval. It attempts to reopen a connection whose socket state is closed. A connection without a receive timestamp in the last four seconds is treated as stale. When resetting a formerly live path it clears timing/in-flight state, resets the window to 1,000 and fills the recent-sequence array with -1. It then sends a registration packet. Live connections receive a 16-byte keepalive containing a millisecond timestamp.

The marker helper functions compare the first two wire bytes, with these static constraints:

- SRT acknowledgment helper `0x4940`: prefix bytes `80 02`, minimum length 2.
- SRTLA keepalive helper `0x4960`: prefix bytes `90 00`, minimum length 2.
- SRTLA REG1 helper `0x4980`: prefix bytes `92 00`, exact length 258.
- SRTLA REG2 helper `0x49a0`: prefix bytes `92 01`, exact length 258.
- SRTLA REG3 helper `0x49c0`: prefix bytes `92 02`, exact length 2.

REG1/REG2 contain the 256-byte group identifier after the two-byte marker. The registration strings, memory copies and helper lengths support this layout. The complete receiver-side protocol and every malformed-packet condition have not been reconstructed.

## Native artifact identities

The following arm64 SHA-256 values bind these findings to exact objects:

- `libsrtla.so`: `9dab3e53608e3c8ffdef257dc53cbe98b3e1a68cb8eb6ac724c76e6108455472`.
- `libsrtsender.so`: `98716ef48e27a9099a99f91b3af3f21bb0d4060a19b760c1226b57a4d510b963`.
- `libsrt.so`: `856d33f00be9d8a7792db80fd85bd61cde4b653bdb72b3d0a2d9131ded6a8c46`.
- `librist.so`: `ecc99fbf066b1155ee89eb011267b4288efa4dd5070158e3a5440cc6eee44fa8`.

## Remaining limits

This pass inventories all native libraries and recovers the main SRTLA loop, scheduler, send path, acknowledgment/window response and housekeeping rules. It does not decompile every instruction in the SRT, RIST, ObjectBox, USB or JPEG engines. It does not prove codec settings, application-level reconnection policy, per-interface Android binding, actual server endpoints, live bonding performance or camera compatibility. Those need DEX call-path evidence and runtime measurements. Exported names identify SDK namespaces, not legal ownership or exact upstream source equivalence. No native-source equivalence or security vulnerability is claimed from strings or dependency presence alone.
