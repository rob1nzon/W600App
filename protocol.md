# W600 Smart Glasses – Bluetooth Protocol Documentation

## Overview

The W600 smart glasses communicate with the LensMoo Android app over **Classic Bluetooth SPP (Serial Port Profile)**, not BLE. The connection uses the standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB` via `createRfcommSocketToServiceRecord()`.

There are **two distinct protocol layers** visible in the snoop log:

1. **HFP (Hands-Free Profile)** – standard AT-command handshake used during audio connection setup (`AT+BRSF`, `AT+BAC`, `AT+CIND`, etc.)
2. **SJBT Binary Node Protocol** – the primary data protocol for all device commands, framed in binary packets over the same SPP socket.

---

## Layer 1: HFP AT-Command Handshake

When the phone connects, a standard HFP negotiation occurs first:

```
APP → Device: AT+BRSF=157
Device → APP: +BRSF: 4079\r\nOK

APP → Device: AT+BAC=1,2
Device → APP: OK

APP → Device: AT+CIND=?
Device → APP: +CIND: ("call",(0,1)),("callsetup",(0-3)),("service",(0-1)),("signal",(0-5)),("roam",(0,1)),("battchg",(0-5)),("callheld",(0-2))

APP → Device: AT+CIND?
Device → APP: +CIND: 0,0,0,0,0,1,0

APP → Device: AT+CMER=3,0,0,1
APP → Device: AT+CLIP=1
APP → Device: AT+NREC=0
APP → Device: AT+XAPL=0042-0887-0100,2
Device → APP: +XAPL=iPhone,7
APP → Device: AT+VGS=11
APP → Device: AT+VGM=8

Device → APP (async): +CIEV: 3,1   (signal=1)
Device → APP (async): +CIEV: 4,3   (callsetup=3)
```

---

## Layer 2: SJBT Binary Node Protocol

### 2.1 SPP Packet Frame (Outer Envelope)

Every command is framed as a fixed 16-byte header followed by an optional payload:

```
Offset  Size  Field            Description
------  ----  -----            -----------
0       1     head             Message category byte (see §2.3)
1       1     cmdOrder         Sequence counter (0–252, wraps at 253)
2       2     cmdId            16-bit command ID (little-endian)
4       2     divideFlags      Packed field: bits[7:3]=totalLen[12:8], bits[2:0]=divideType
6       2     dividePayloadLen Lower 8 bits of total length; combined with divideFlags
8       2     payloadLen       Actual payload length in this packet (little-endian)
10      4     offset           Byte offset (for large/split payloads) (little-endian)
14      4     crc              CRC-16 over payload, seeded with 0xFFFF (little-endian)
[16]    *     payload          Variable-length payload (payloadLen bytes)
```

**Total minimum packet size: 16 bytes** (with no payload).

### 2.2 Divide / Fragmentation Types

The `divideType` field (3 bits) controls fragmentation:

| Value | Meaning                                       |
|-------|-----------------------------------------------|
| 0     | Single complete packet (no fragmentation)     |
| 1     | First fragment of a multi-packet message      |
| 2     | Middle fragment                               |
| 3     | Last fragment of a multi-packet message       |
| 4     | Single packet, no-timeout variant             |
| 6     | Middle fragment, no-timeout                   |
| 7     | Last fragment, no-timeout                     |

### 2.3 Head Byte Values

The `head` byte identifies the high-level message category:

| head (hex/dec) | Category                                    |
|---------------|---------------------------------------------|
| 0x0A / 10     | Session initiation (connect/auth flow)      |
| 0x0B / 11     | Device info & settings (old-style JSON)     |
| 0x0E / 14     | File transfer (OTA, firmware)               |
| 0x1A / 26     | Camera preview stream (H.264)               |
| 0x1D / 29     | Navigation frame data                       |
| 0x1E / 30     | Video preview                               |
| 0x1F / 31     | Assistant / media gather                    |
| 0x2B / 43     | Custom / raw data channel                   |
| 0x30 / 48     | Node protocol wrapper (main data plane)     |
| 0x4A / 74     | Photo library operations                    |
| 0xEF / -17    | Error notification                          |

---

## Layer 3: Node Protocol (head = 0x30 / 48)

When `head == 0x30`, the payload contains a **PayloadPackage** – a structured container holding one or more **NodeData** items. This is the primary data plane for all glasses-specific commands.

### 3.1 PayloadPackage Structure (inside the SPP payload)

```
Offset  Size  Field          Description
------  ----  -----          -----------
0       2     _id            Package ID (short, little-endian) – used to correlate requests/responses
2       4     packageSeq     Sequence number (int, little-endian); -1 means last/only package
6       1     actionType     Request/Response type (see §3.2)
7       2     packageLimit   Max items per package
9       1     itemCount      Number of NodeData items that follow
[10]    *     items          itemCount × NodeData records
```

### 3.2 ActionType Values

| Value | Constant           | Meaning                             |
|-------|--------------------|-------------------------------------|
| 1     | REQ_TYPE_READ      | Read request from app               |
| 2     | REQ_TYPE_WRITE     | Write request from app              |
| 3     | REQ_TYPE_EXECUTE   | Execute/trigger command             |
| 4     | REQ_TYPE_NOTIFY    | Unsolicited notification from device|
| 100   | RESPONSE_EACH      | Per-item response from device       |
| 101   | RESPONSE_ALL_OK    | All items succeeded                 |
| 102   | RESPONSE_ALL_FAIL  | All items failed                    |

### 3.3 NodeData Structure (inside PayloadPackage)

Each NodeData item:

```
Offset  Size  Field     Description
------  ----  -----     -----------
0       4     urn       4-byte ASCII command identifier (e.g., "1001", "5713")
4       1     dataFmt   Data format (0=BIN, 1=PLAIN_TXT, 2=JSON, 3=NODATA, 4=ERRCODE)
6       2     dataLen   Length of following data (little-endian)
[8]     *     data      Payload data (dataLen bytes)
```

**On READ requests**, only the 4-byte URN is sent (no dataFmt/dataLen/data fields).

### 3.4 URN Encoding

URNs are 4-byte ASCII strings that act as command identifiers. The 4 bytes map to the 4-character hex-like code seen in the snoop log. For example:
- URN bytes `{0x31, 0x30, 0x30, 0x31}` = ASCII `"1001"` = device info query
- URN bytes `{0x35, 0x37, 0x31, 0x33}` = ASCII `"5713"` = media file count

The URN namespace is hierarchical by first byte:
- `'0'` (0x30) = Session/connection control
- `'1'` (0x31) = Common/device info
- `'2'` (0x32) = User settings
- `'3'` (0x33) = Dial/watchface
- `'4'` (0x34) = Apps (alarm, sport, contacts, weather)
- `'5'` (0x35) = Find, music, navigation, media/camera, phone
- `'6'` (0x36) = Health data sync
- `'7'` (0x37) = File sync test
- `'A'` (0x41) = Sensor collector
- `'B'` (0x42) = Muslim features
- `'C'` (0x43) = Assistant

---

## Command Reference

### Session / Connection Commands (URN prefix `'0'`)

| URN   | Direction      | Description                                           |
|-------|----------------|-------------------------------------------------------|
| `0001`| Device→App     | Challenge/key exchange step 1 (sends random key data) |
| `0002`| App→Device     | Key exchange step 2 (sends encrypted response)        |
| `0031`| Both           | MTU negotiation; device replies with 2-byte LE int    |
| `0030`| Device→App     | Reboot result; data[0]==0 means success               |
| `0033`| Device→App     | Power-off result; data[0] is result code              |
| `002E`| App→Device     | Bind/pair command (extended timeout = 60s+)           |

### Common Device Commands (URN prefix `'1'`)

| URN   | Direction      | Description                                                    |
|-------|----------------|----------------------------------------------------------------|
| `1001`| App→Device     | Request device info (READ, no data)                            |
| `1001`| Device→App     | Device info response (JSON, FMT_JSON)                          |
| `1003`| App→Device     | Request battery info (READ, no data)                           |
| `1003`| Device→App     | Battery response (FMT_BIN: data[0]=is_charging, data[1]=level) |
| `1007`| App→Device     | Datetime sync (FMT_JSON)                                       |
| `1007`| Device→App     | Datetime sync result (data[0]==0 means success)                |
| `1004`| Both           | Notification enable/disable                                    |
| `1008`| Both           | Location info set/result                                       |
| `1017`| Both           | Sound & haptic / wrist raise settings                          |
| `1030`| App→Device     | Reboot device                                                  |
| `1031`| Both           | MTU negotiation                                                |
| `1032`| App→Device     | Query storage/memory (sends 1-byte storage type)               |
| `1032`| Device→App     | Storage response (FMT_BIN: 4-byte LE int = free bytes)         |
| `1033`| App→Device     | Power off command                                              |
| `102E`| App→Device     | Bind operation (long-timeout command)                          |
| `1028`| Both           | Camera shutter notification (device→app) / camera ACK         |
| `1029`| Device→App     | Camera mode change event                                       |
| `102A`| Device→App     | Camera capture result (data[0]==0 = success)                   |
| `102B`| Both           | Camera zoom (data[0]=type, data[1]=value)                      |

#### Device Info JSON (URN `1001` response)

```json
{
  "prod_mode":     "W600",
  "soft_ver":      "1.3.9",
  "hard_ver":      "",
  "mac_addr":      "92:03:53:05:33:18",
  "dev_id":        "TBZNDZAIEYE-W20-------W600600E--------920353053318099238--------",
  "dev_name":      "W600_3318",
  "prod_category": "01",
  "prod_subcate":  "01",
  "battery_main":  "73",
  "dial_ability":  "2",
  "screen":        "w320h380",
  "ch":            "304",
  "cw":            "320",
  "nch":           "304",
  "ncw":           "320",
  "screen_shape":  "0",
  "preview_width": "160",
  "preview_height":"120",
  "remain_memory": <long>,
  "total_memory":  <long>,
  "offline_asr_auth": "",
  "chip_mode":     "",
  "spo2":          "",
  "lang":          <int>,
  "is_charging":   <int>,
  "alipay":        ""
}
```

#### Datetime Sync JSON (URN `1007` request, App→Device)

```json
{
  "currDate":  "2025-07-30",
  "currTime":  "2025-07-30 19:44:15",
  "timeZoo":   "GMT+03:00",
  "timestamp": 1753893855
}
```

Response (Device→App): `data[0] == 0` = success.

### User Settings Commands (URN prefix `'2'`)

| URN   | Direction  | Description                               |
|-------|------------|-------------------------------------------|
| `2410`| Both       | Language setting (FMT_PLAIN_TXT)          |
| `2210`| Both       | Sport goal settings                       |
| `2310`| Both       | Personal info settings                    |
| `2410`| Both       | Unit info settings (also used for lang)   |
| `2510`| Both       | Sedentary reminder                        |
| `2610`| Both       | Drink water reminder                      |
| `2710`| Both       | Date/time settings (separate from 1007)   |

#### Language Setting (URN `2410`)

FMT_PLAIN_TXT payload contains language string(s). From dump:
```
en    en    zh-cn
```
(Space-padded fields; the three values appear to represent current language, primary, and fallback.)

### Dial / Watchface Commands (URN prefix `'3'`)

| URN   | Direction  | Description             |
|-------|------------|-------------------------|
| `3110`| Both       | Watchface list/transfer |
| `3210`| Both       | Watchface notify event  |
| `3310`| Both       | Watchface activate/set  |

### App Commands (URN prefix `'4'`)

| URN   | Direction  | Description         |
|-------|------------|---------------------|
| `4110`| Both       | Alarm management    |
| `4210`| Both       | Sport type/modes    |
| `4310`| Both       | Contacts management |
| `4320`| Device→App | Contact call event  |
| `4410`| Both       | Weather data        |
| `4510`| Both       | Heart rate alerts   |
| `4610`| Both       | Sleep settings      |
| `4710`| Both       | Notification config |
| `4910`| Both       | Widget settings     |

### Media / Camera Commands (URN prefix `'5'`)

| URN    | Direction      | Description                                                       |
|--------|----------------|-------------------------------------------------------------------|
| `5710` | Both           | Video preview control (start/stop preview stream)                 |
| `5711` | Device→App     | Preview frame or status                                           |
| `5712` | App→Device     | SD card capacity query (READ)                                     |
| `5712` | Device→App     | SD capacity response (JSON: `WmDeviceSDInfo`)                     |
| `5713` | App→Device     | Media file count query (READ, no data)                            |
| `5713` | Device→App     | Media count response (JSON)                                       |
| `57A0` | App→Device     | Photo library operation / query (exact sub-type TBD)             |
| `57B0` | App→Device     | Photo library operation B (exact sub-type TBD)                   |
| `57B1` | Device→App     | Photo library response B1 (exact sub-type TBD)                   |
| `5720` | Both           | Photo library list request/response                               |
| `5730` | Both           | Photo library sub-operation 0 (delete/download)                  |
| `5731` | Both           | Photo library sub-operation 1                                     |
| `5732` | Both           | Photo library sub-operation 2                                     |
| `5740` | Device→App     | Photo library download progress notify                            |
| `5750` | Device→App     | Photo library event / photo taken notify                          |
| `5760` | Both           | Video preview related                                             |
| `5770` | App→Device     | Photo/video library query (exact sub-type TBD)                   |
| `5410` | Both           | Music control                                                     |
| `5510` | Both           | Phone/call handling                                               |
| `5610` | Both           | Navigation data                                                   |

#### Media Count JSON (URN `5713` response)

```json
{
  "photo_num":  "85",
  "video_num":  "10",
  "record_num": "1",
  "music_num":  "0"
}
```

### Unknown Commands from Dump

| URN    | Direction      | Observations from snoop log                                    |
|--------|----------------|----------------------------------------------------------------|
| `C10A` | Both           | Appears early in session; device responds with short payload   |
| `57A0` | App→Device     | Sent during media session; device responds immediately (empty) |
| `5770` | App→Device     | Sent paired with 57A0; device responds immediately (empty)     |
| `7100` | Both           | Unknown; appears during negotiation; 2-byte payload            |
| `7110` | Both           | Unknown; appears after 7100                                    |

**Note on `C10A`**: The first nibble `C` (0x43) falls outside the standard URN namespace described in the code. This may be a glasses-specific extension not present in the generic SJBT SDK, or it could be an obfuscated command from a different protocol layer.

### Health Data Sync Commands (URN prefix `'6'`)

| URN    | Description              |
|--------|--------------------------|
| `6110` | Step count data          |
| `6210` | Calories data            |
| `6310` | Activity duration        |
| `6410` | Daily activity duration  |
| `6510` | Distance data            |
| `6610` | Blood oxygen (SpO2)      |
| `6710` | Heart rate realtime      |
| `6720` | Heart rate historical    |
| `6810` | Sleep data               |
| `6910` | Sport summary data       |

---

## Observed Session Sequence (from bfcom.txt)

The session captured shows the following sequence after HFP:

```
1. [Node 0x30] Handshake: 0001 challenge → 0002 response (auth/key exchange)
2. [Node 0x30] 7100 (unknown negotiation)
3. [Node 0x30] 102E (bind operation, MD5 of device ID in payload)
4. [Node 0x30] 7110 (unknown, follows 102E)
5. [Node 0x30] App→Device 1007 (datetime sync JSON)
6. [Node 0x30] App→Device 1001 (device info request)
7. [Node 0x30] Device→App 1001 (device info JSON response)
8. [Node 0x30] App→Device 1003 (battery request)
9. [Node 0x30] App→Device 2410 (language setting)
10. [Node 0x30] App→Device C10A (unknown)
11. [Node 0x30] App→Device 57A0 + 5770 (photo library init?)
12. [Node 0x30] App→Device 5713 (media count query)
13. [Node 0x30] Device→App 5713 → {"photo_num":"85","video_num":"10","record_num":"1","music_num":"0"}
14. (sequence repeats on reconnect)
15. [Node 0x30] App→Device 57B0 (photo library operation)
16. [Node 0x30] Device→App 57B1 (response)
17. [Node 0x30] App→Device 5713 → Device returns updated count (photo_num now "86")
```

This shows a photo was taken between sessions (photo_num incremented from 85 to 86).

---

## CRC Algorithm

CRC is CRC-16 with initial seed `0xFFFF`, computed over the payload bytes only (not the header):

```java
BtUtils.getCrc(65535, payload, payload.length)
```

The result is stored in little-endian at header offset 14.

---

## Bluetooth Connection Details

| Property | Value |
|----------|-------|
| Transport | Classic Bluetooth (BR/EDR) |
| Profile | SPP (Serial Port Profile) |
| UUID | `00001101-0000-1000-8000-00805F9B34FB` |
| Socket type | `createRfcommSocketToServiceRecord()` |
| Buffer size | 11,980 bytes read buffer |
| MTU | Negotiated via URN `0031` (default 160px×120px preview) |
| Max command index | 252 (wraps at 253) |

The device also advertises BLE for scanning/discovery, but all data transfer uses classic Bluetooth SPP.

---

## File Transfer Protocol (head = 0x0E)

File transfer uses a separate framing with `head = 0x0E`:

| cmdId | Direction  | Description                                          |
|-------|------------|------------------------------------------------------|
| 1     | App→Device | Transfer start: type(1), fileLen(4), fileCount(1), appendixSize(4) |
| 2     | App→Device | Transfer file name: fileLen(4), name(n bytes)        |
| 3     | App→Device | Transfer data chunk (divideType indicates position)  |
| 4     | App→Device | Transfer cancel                                      |
| 5     | App→Device | Transfer cancel (OTA timeout)                        |
| 8     | App→Device | Transfer cancel (alternative)                        |
| 10    | App→Device | Transfer start with CRC: type(1), fileLen(4), fileCount(1), appendixSize(4), crc(4) |

---

## Source Code Locations (APK decompiled to /tmp/lensmoo_decompiled)

| File | Purpose |
|------|---------|
| `com/sjbt/sdk/SJUniWatch.java` | Main SDK; all command dispatch/receive logic |
| `com/sjbt/sdk/entity/MsgBean.java` | SPP packet model (`head`, `cmdId`, `payloadLen`, etc.) |
| `com/sjbt/sdk/entity/PayloadPackage.java` | Node container; `_id`, `packageSeq`, `actionType`, items |
| `com/sjbt/sdk/entity/BaseNodeData.java` | Individual node item; `urn[4]`, `dataFmt`, `dataLen`, `data` |
| `com/sjbt/sdk/entity/DataFormat.java` | Data format enum: BIN, PLAIN_TXT, JSON, NODATA, ERRCODE |
| `com/sjbt/sdk/entity/RequestType.java` | ActionType enum: READ(1), WRITE(2), EXECUTE(3), NOTIFY(4) |
| `com/sjbt/sdk/entity/ResponseResultType.java` | Response enum: EACH(100), ALL_OK(101), ALL_FAIL(102) |
| `com/android/mltcode/paycertificationapi/l9c.java` | CmdHelper: packet builder for all commands |
| `com/android/mltcode/paycertificationapi/e7c.java` | BtEngineMsgQue: SPP socket read/write; `parseMsg()` |
| `com/sjbt/sdk/entity/old/BasicInfo.java` | Device info fields |
| `com/sjbt/sdk/entity/old/TimeSyncBean.java` | Datetime sync fields |
| `com/base/sdk/entity/settings/WmDeviceMediaCountInfo.java` | Media count fields |
