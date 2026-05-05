#!/usr/bin/env python3
import struct
import sys

BTSNOOP_EPOCH_DELTA_US = 62168256000000000

def parse_btsnoop(filename):
    records = []
    with open(filename, "rb") as f:
        if f.read(8) != b"btsnoop\x00":
            raise SystemExit("bad btsnoop")
        f.read(8)
        num = 0
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                break
            orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIQ", hdr)
            data = f.read(incl_len)
            records.append((num, flags, ts, data))
            num += 1
    return records

def parse_acl(data):
    if len(data) < 5 or data[0] != 0x02:
        return None
    handle_flags = struct.unpack("<H", data[1:3])[0]
    total_len = struct.unpack("<H", data[3:5])[0]
    return handle_flags & 0x0FFF, data[5:5 + total_len]

def parse_l2cap(data):
    if len(data) < 4:
        return None
    length = struct.unpack("<H", data[:2])[0]
    cid = struct.unpack("<H", data[2:4])[0]
    return cid, data[4:4 + length]

def parse_rfcomm(data):
    if len(data) < 3:
        return None
    addr, ctrl, lb = data[0], data[1], data[2]
    if lb & 1:
        length = lb >> 1
        start = 3
    else:
        if len(data) < 4:
            return None
        length = ((data[3] << 7) | (lb >> 1))
        start = 4
    frame_type = ctrl & 0xEF
    dlci = (addr >> 2) & 0x3F
    payload = data[start:start + length]
    return frame_type, dlci, payload

def parse_node_payload(payload):
    if len(payload) < 12:
        return []
    pos = 12
    out = []
    while pos + 4 <= len(payload):
        urn = payload[pos:pos + 4].decode("ascii", errors="replace")
        pos += 4
        if pos >= len(payload):
            out.append((urn, None, b""))
            break
        fmt = payload[pos]
        pos += 1
        if pos + 2 > len(payload):
            out.append((urn, fmt, b""))
            break
        ln = struct.unpack(">H", payload[pos:pos + 2])[0]
        pos += 2
        data = payload[pos:pos + ln]
        pos += ln
        out.append((urn, fmt, data))
    return out

def parse_sjbt_packets(stream):
    pos = 0
    packets = []
    while pos + 16 <= len(stream):
        head = stream[pos]
        if head not in (0x0A, 0x1E, 0x1F, 0x30, 0x4A):
            pos += 1
            continue
        if pos + 16 > len(stream):
            break
        cmd_order = stream[pos + 1]
        cmd_id = struct.unpack(">H", stream[pos + 2:pos + 4])[0]
        div_len = struct.unpack(">H", stream[pos + 4:pos + 6])[0]
        payload_len = struct.unpack("<I", stream[pos + 6:pos + 10])[0]
        body_len = div_len if div_len else payload_len
        total = 16 + body_len
        if body_len < 0 or pos + total > len(stream):
            break
        payload = stream[pos + 16:pos + total]
        packets.append((head, cmd_order, cmd_id, payload))
        pos += total
    return packets

def main():
    filename = sys.argv[1]
    want = set(sys.argv[2:]) if len(sys.argv) > 2 else None
    records = parse_btsnoop(filename)
    buffers = {}
    for num, flags, ts, data in records:
        acl = parse_acl(data)
        if not acl:
            continue
        handle, acl_payload = acl
        l2 = parse_l2cap(acl_payload)
        if not l2:
            continue
        cid, l2_payload = l2
        if cid < 0x0040:
            continue
        rf = parse_rfcomm(l2_payload)
        if not rf:
            continue
        frame_type, dlci, payload = rf
        if frame_type != 0xEF or dlci == 0 or not payload:
            continue
        direction = "P2D" if (flags & 1) == 0 else "D2P"
        key = (handle, cid, dlci, direction)
        stream = buffers.get(key, b"") + payload
        packets = parse_sjbt_packets(stream)
        consumed = 0
        for head, order, cmd_id, body in packets:
            consumed += 16 + len(body)
            if head != 0x30:
                if want and f"head:{head:02x}" not in want:
                    continue
                print(f"{num:06d} {direction} head=0x{head:02x} cmd=0x{cmd_id:04x} ord={order} len={len(body)}")
                continue
            for urn, fmt, nd in parse_node_payload(body):
                if want and urn not in want:
                    continue
                txt = ""
                if fmt == 2:
                    txt = nd.decode("utf-8", errors="replace")
                print(f"{num:06d} {direction} cmd=0x{cmd_id:04x} ord={order} urn={urn} fmt={fmt} len={len(nd)} hex={nd[:48].hex()} {txt}")
        buffers[key] = stream[consumed:]

if __name__ == "__main__":
    main()
