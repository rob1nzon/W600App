#!/usr/bin/env python3
import struct
import sys

EPOCH_DELTA_US = 62168256000000000


def records(path):
    with open(path, "rb") as f:
        if f.read(8) != b"btsnoop\x00":
            raise SystemExit("not a btsnoop file")
        f.read(8)
        idx = 0
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                break
            _orig, incl, flags, _drops, ts = struct.unpack(">IIIIQ", hdr)
            yield idx, flags, ts, f.read(incl)
            idx += 1


def acl(data):
    if len(data) < 5 or data[0] != 0x02:
        return None
    hf = struct.unpack("<H", data[1:3])[0]
    length = struct.unpack("<H", data[3:5])[0]
    return hf & 0x0FFF, (hf >> 12) & 3, data[5:5 + length]


def l2cap(payload):
    if len(payload) < 4:
        return None
    length, cid = struct.unpack("<HH", payload[:4])
    return length, cid, payload[4:4 + length]


def ts_text(ts):
    from datetime import datetime
    return datetime.utcfromtimestamp((ts - EPOCH_DELTA_US) / 1_000_000).strftime("%H:%M:%S.%f")[:-3]


def show_sig(num, direction, ts, handle, payload):
    pos = 0
    while pos + 4 <= len(payload):
        code, ident, length = payload[pos], payload[pos + 1], struct.unpack("<H", payload[pos + 2:pos + 4])[0]
        params = payload[pos + 4:pos + 4 + length]
        name = {
            0x02: "CONN_REQ",
            0x03: "CONN_RSP",
            0x04: "CONFIG_REQ",
            0x05: "CONFIG_RSP",
            0x06: "DISCONN_REQ",
            0x07: "DISCONN_RSP",
            0x0A: "INFO_REQ",
            0x0B: "INFO_RSP",
        }.get(code, f"0x{code:02x}")
        extra = ""
        if code == 0x02 and len(params) >= 4:
            psm, scid = struct.unpack("<HH", params[:4])
            extra = f" PSM=0x{psm:04x} SCID=0x{scid:04x}"
        elif code == 0x03 and len(params) >= 8:
            dcid, scid, result, status = struct.unpack("<HHHH", params[:8])
            extra = f" DCID=0x{dcid:04x} SCID=0x{scid:04x} result={result} status={status}"
        elif code in (0x04, 0x05) and len(params) >= 4:
            dcid = struct.unpack("<H", params[:2])[0]
            extra = f" DCID=0x{dcid:04x} params={params.hex()}"
        elif code in (0x06, 0x07) and len(params) >= 4:
            dcid, scid = struct.unpack("<HH", params[:4])
            extra = f" DCID=0x{dcid:04x} SCID=0x{scid:04x}"
        print(f"{num:06d} {ts_text(ts)} {direction} h=0x{handle:04x} SIG {name} id={ident}{extra}")
        pos += 4 + length


def main():
    path = sys.argv[1]
    start = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    end = int(sys.argv[3]) if len(sys.argv) > 3 else 10**9
    for num, flags, ts, data in records(path):
        if num < start or num > end:
            continue
        parsed_acl = acl(data)
        if not parsed_acl:
            continue
        handle, pb, acl_payload = parsed_acl
        parsed_l2 = l2cap(acl_payload)
        if not parsed_l2:
            continue
        length, cid, payload = parsed_l2
        direction = "P2D" if (flags & 1) == 0 else "D2P"
        if cid == 0x0001:
            show_sig(num, direction, ts, handle, payload)
        elif cid >= 0x0040:
            print(f"{num:06d} {ts_text(ts)} {direction} h=0x{handle:04x} cid=0x{cid:04x} pb={pb} len={length} data={payload[:40].hex()}")


if __name__ == "__main__":
    main()
