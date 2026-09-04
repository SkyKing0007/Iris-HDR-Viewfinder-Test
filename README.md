# Iris HDR Viewfinder Test V1.4.11 V2.8

V2.8 is a narrow saved-JPEG fusion correction derived only from the exact successful V2.7 GitHub Actions compiled candidate (`f6bd9202063cdabd7eea76f7c2575a6ec11c453e`, run `33813880497`, artifact `9915838166`). V2.7 remains runtime authority until V2.8 passes Actions.

## What changes

Only `JpegFusion.java` changes at runtime. V2.7 alignment/registration, HdrGlView, all GLSL, capture/DNG ownership, AUTO/MANUAL exposure policy, live mode=2, orientation and unrelated runtime code are protected byte-for-byte.

V2.7 correctly registered SHORT into LONG coordinates but could still leave fractional clipped LONG contribution inside genuinely destroyed highlight cores. V2.8 keeps V2.7's conservative fractional ownership as a feather while adding a strict inner state: if LONG is bright with two channels essentially clipped, registered SHORT has real signal/headroom, radiometric proof is positive, support is coherent and registration is fully trusted, the core becomes exactly 100% SHORT RGB.

In that hard core one common scalar linear-light exposure scale is applied to all three registered SHORT channels. This preserves SHORT's real RGB relationship. It does not globally neutralize warm clouds or invent white; genuine SHORT warmth remains genuine SHORT warmth.

## Real-photo replay

The exact outdoor cloud and indoor window pairs supplied after V2.7 were replayed before source translation. The replay reproduced the actual V2.7 fused JPEG within roughly 1–2 code values/channel, and independently recovered the observed subpixel registration. In the conservative clipped/SHORT-valid population, >99% SHORT ownership rises from about 26.5% to 82.3% outdoors and 48.5% to 87.3% indoors, while valid-LONG pixels (`secondLong < 0.90`) receive no new core ownership.

## Verification

The successful V2.7 verification/build mechanics are inherited unchanged in order and compiler commands. The workflow advances only the exact V2.7 Actions authority pins, V2.8 version/hash/allowlist/protected-file proofs, artifact names and permanent V2.8 regressions. Runtime allowlist equality is exactly one file: `app/src/main/java/com/skyking0007/irishdrviewfinder/JpegFusion.java`.

V2.8 is **PREPARED / UPLOAD-READY** until GitHub Actions runs the real Java compiler and full `:app:assembleDebug` successfully.
