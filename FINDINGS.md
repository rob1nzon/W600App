# W600 Glasses Reverse Engineering — Summary

## Device

| Field | Value |
|-------|-------|
| Model | W600 smart glasses |
| MAC | 92:03:53:05:33:18 |
| BT Name | W600_3318 |
| Firmware | 1.3.9 |
| Reference app | LensMoo (SJBT SDK) |

---

## Transport

**Classic BT RFCOMM SPP** — not BLE.

### RFCOMM channels on device
| Channel | DLCI (phone-initiated) | Service Name | Purpose |
|---------|------------------------|--------------|---------|
| 1 | 2 | JL_SPP | Unknown (Jieli chip aux?) |
| 4 | 8 | — | HFP handsfree (AT commands) |
| 8 | **17** | **SJBTSPP** | **SJBT data protocol** |
| 9 | 18 | — | Unknown |

**Critical:** The device initiates the RFCOMM mux (sends SABM DLCI=0 first), so the
phone opens SJBT channel with direction bit=1 → **DLCI=17** (not 16).

`createRfcommSocket(8)` via reflection bypasses SDP ambiguity (device has two 0x1101 UUIDs).

---

## SJBT Packet Format

### Outer frame (SppPacket) — 16-byte header + payload
```
[0]    head          : 0x30 (NODE_PROTOCOL)
[1]    cmdOrder      : sequential counter (all phone packets share one counter)
[2-3]  cmdId         : 0x0001 (phone→device request)
                       0x8001 (device notify)
                       0x8002 (device read-response)
                       0x8004 (device ACK)
[4-5]  divideFlags   : 0x0000
[6-7]  dividePayloadLen : payload size
[8-9]  payloadLen    : 0 (non-fragmented)
[10-11] offset       : 0
[12-13] crc          : CRC-16/XModem over payload bytes
[14-15] pad          : 0x0000
```

### PayloadPackage — inside SppPacket.payload
```
[0-1]  id            : sequential short
[2-5]  packageSeq    : 0xFFFFFFFF (always -1 in phone packets)
[6]    actionType    : 1=READ, 2=WRITE, 3=EXECUTE
[7-8]  packageLimit  : 0x0000
[9]    itemCount     : 1
[10+]  items         : NodeData[]
```

### NodeData
```
[0-3]  urn           : 4-char ASCII command code
[4]    dataFmt       : 0=BIN, 1=PLAIN_TXT, 2=JSON, 3=NODATA
[5-6]  dataLen       : LE uint16
[7+]   data          : dataLen bytes
(if dataFmt/dataLen absent → READ request, urn only)
```

### ACK packet (cmdId=0x0004)
Phone ACKs every device packet (cmdId 0x8001 or 0x8002).
Payload = `[0x01, deviceCmdOrder]`

---

## Handshake Sequence (confirmed from LensMoo btsnoop)

```
Phone → 0001 EXECUTE  61 bytes (0x01..0x3D sequential)   ← phone challenge
  [wait up to 5s for device challenge]
Phone → 0002 EXECUTE  64 bytes (encrypted response)       ← phone response
Phone → 7100 READ     (no data)
Phone → 102E WRITE    50 bytes: [0x01, 0x00×49]           ← bind
Phone → 7110 READ     (no data)
Phone → 1007 WRITE    JSON datetime
Phone → 1001 READ     device info
Phone → 1003 READ     battery
Phone → 5713 READ     media count
```

### handshakeResponse bytes (64 bytes, hardcoded in LensMoo btsnoop)
```
52 6e 65 77 79 6c 56 61 35 78 6f 6e 61 6a 52 6e  "RnewylVa5xonajRn"
23 de 52 d0 c9 13 45 ef 98 44 60 f6 6c 92 16 5e
6a 1c fa 22 4a d8 81 78 51 f2 44 2b a5 69 1e 7a
52 6e 65 77 79 6c 56 61 35 78 6f 6e 61 6a 42 4f  "RnewylVa5xonajBO"
```
**The 32 middle bytes are computed by `encryptData()` JNI in `libbtsdk-lib.so`.**
The algorithm is unknown. The result is likely keyed to device ID / MAC.
**These bytes work for LensMoo's test device, NOT for our device.**

---

## URN Command Codes

| URN | Direction | Action | Description |
|-----|-----------|--------|-------------|
| 0001 | P→D | EXECUTE | Phone challenge (auth step 1) |
| 0002 | P→D | EXECUTE | Phone crypto response (auth step 2) |
| 102E | P→D | WRITE | Bind token |
| 7100 | P→D | READ | Handshake step 3 |
| 7110 | P→D | READ | Handshake step 4 |
| 1001 | P→D | READ | Device info (JSON) |
| 1003 | P→D | READ | Battery |
| 1007 | P→D | WRITE | Datetime sync (JSON) |
| 5710 | P→D | WRITE | Preview control (0=off, 1=on) |
| 5712 | P→D | READ | SD capacity |
| 5713 | P→D | READ | Media count |
| 5720 | P→D | WRITE | Media list (JSON: page, page_size, type) |
| 5730 | P→D | WRITE | Delete media |
| 5731 | P→D | WRITE | Download media |
| 1028 | P→D | EXECUTE | Camera shutter |
| 1029 | P→D | WRITE | Camera mode |
| 1030 | P→D | EXECUTE | Reboot |
| 1033 | P→D | EXECUTE | Power off |

---

## The Core Problem (UNSOLVED)

**The device sends zero SJBT bytes in response to anything.**

Confirmed in 4 btsnoop captures:
- bugreport2 (LensMoo session) — device: no SJBT responses
- bugreport3 (our v1.7) — device: no SJBT responses
- bugreport4 (our v1.8) — device: no SJBT responses
- br2 SJBT analysis — device: no SJBT responses

Device only sends **empty RFCOMM UIH ACK frames** (0-length) back.

### Root cause theories

1. **Crypto mismatch** — `handshakeResponse` 64 bytes are device-specific (computed from
   device MAC/ID via JNI `encryptData`). Our hardcoded bytes are for LensMoo's test device.
   Device silently rejects wrong crypto and closes SJBT session.

2. **encryptData algorithm unknown** — lives in `libbtsdk-lib.so` (ARM64 native library
   from LensMoo APK). Not yet reverse-engineered.

3. **Device challenge not captured** — the device may send its challenge (step 2) but
   btsnoop doesn't capture it (Android BT stack might process it internally). We're
   blindly sending the wrong crypto response.

### What needs to happen to fix it

- **Option A**: Reverse-engineer `libbtsdk-lib.so` → find `encryptData(key, challenge)`
  → reimplement in Kotlin/JNI using our device's key.
- **Option B**: Extract the key from the device (firmware dump / BLE pairing key).
- **Option C**: Find if there's a "no-auth" mode or debug backdoor in firmware 1.3.9.
- **Option D**: Instrument LensMoo on a rooted phone to intercept the JNI call at runtime.

---

## What Currently Works in Our App

- ✅ RFCOMM connects (channel 8, DLCI=17)
- ✅ All packets formatted correctly (matches LensMoo byte-for-byte)
- ✅ Challenge sent (0001)
- ✅ Handshake packets sent (0002, 7100, 102E, 7110, 1007, 1001, 1003, 5713)
- ❌ Device never responds → no device info, battery, media

---

## App Versions

| Version | Key change |
|---------|-----------|
| 1.7 | Server socket → DLCI=17; protocol packet fixes |
| 1.8 | Removed server socket; simple outbound connect; moved Connected after handshake |
| 1.9 | Force channel 8 via `createRfcommSocket(8)` reflection (bypass SDP ambiguity) |

---

## Files

| Path | Description |
|------|-------------|
| `/tmp/lensmoo_decompiled/` | Jadx decompile of LensMoo APK |
| `/tmp/lensmoo_decompiled/sources/com/android/mltcode/paycertificationapi/e7c.java` | BT engine (connect, read loop) |
| `/tmp/lensmoo_decompiled/sources/com/sjbt/sdk/SJUniWatch.java` | SJBT SDK main class |
| `/tmp/br2/FS/data/log/bt/btsnoop_hci.log` | LensMoo BT capture |
| `/tmp/bugreport4/FS/data/log/bt/btsnoop_hci.log` | Our v1.8 BT capture |
| `/Users/veedo/w600/W600App/` | Our Android app (v1.9) |
