#!/usr/bin/env python3
"""
Analyze btsnoop HCI log for W600 glasses connection.
Target MAC: 92:03:53:05:33:18
"""
import struct
import sys
from datetime import datetime, timedelta

BTSNOOP_FILE = "/Users/veedo/w600/btsnoop_android.log"
TARGET_MAC = bytes([0x92, 0x03, 0x53, 0x05, 0x33, 0x18])
TARGET_MAC_STR = "92:03:53:05:33:18"

# btsnoop epoch: Jan 1, 0 AD microseconds offset
# Python epoch: Jan 1, 1970
# Offset in microseconds from 0 AD to Unix epoch
BTSNOOP_EPOCH_DELTA_US = 62168256000000000  # microseconds from 0AD to 1970

def mac_to_str(mac_bytes):
    return ":".join(f"{b:02x}" for b in mac_bytes)

def ts_to_str(ts_us):
    try:
        unix_us = ts_us - BTSNOOP_EPOCH_DELTA_US
        unix_s = unix_us / 1_000_000
        dt = datetime.utcfromtimestamp(unix_s)
        return dt.strftime("%Y-%m-%d %H:%M:%S.%f UTC")
    except:
        return f"ts={ts_us}"

def parse_btsnoop(filename):
    records = []
    with open(filename, "rb") as f:
        # Parse header
        magic = f.read(8)
        assert magic == b"btsnoop\x00", f"Bad magic: {magic}"
        version = struct.unpack(">I", f.read(4))[0]
        datalink = struct.unpack(">I", f.read(4))[0]
        print(f"btsnoop version={version}, datalink={datalink}")

        pkt_num = 0
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                break
            orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIQ", hdr)
            data = f.read(incl_len)
            if len(data) < incl_len:
                break
            records.append({
                "num": pkt_num,
                "orig_len": orig_len,
                "incl_len": incl_len,
                "flags": flags,
                "drops": drops,
                "ts": ts,
                "ts_str": ts_to_str(ts),
                "data": data,
            })
            pkt_num += 1

    print(f"Total records: {len(records)}")
    return records

def find_hci_type(data):
    """Return HCI packet type from first byte."""
    if not data:
        return None
    return data[0]

# HCI packet types
HCI_CMD = 0x01
HCI_ACL = 0x02
HCI_SCO = 0x03
HCI_EVT = 0x04

def parse_hci_event(data):
    """Parse HCI event packet."""
    if len(data) < 3:
        return None
    evt_code = data[1]
    param_len = data[2]
    params = data[3:3+param_len]
    return {"evt_code": evt_code, "params": params}

def parse_hci_acl(data):
    """Parse HCI ACL packet."""
    if len(data) < 5:
        return None
    handle_flags = struct.unpack("<H", data[1:3])[0]
    handle = handle_flags & 0x0FFF
    pb = (handle_flags >> 12) & 0x03
    bc = (handle_flags >> 14) & 0x03
    total_len = struct.unpack("<H", data[3:5])[0]
    acl_data = data[5:5+total_len]
    return {"handle": handle, "pb": pb, "bc": bc, "data": acl_data}

def parse_l2cap(data):
    """Parse L2CAP PDU."""
    if len(data) < 4:
        return None
    length = struct.unpack("<H", data[0:2])[0]
    cid = struct.unpack("<H", data[2:4])[0]
    payload = data[4:4+length]
    return {"length": length, "cid": cid, "payload": payload}

def parse_rfcomm(data):
    """Parse RFCOMM frame."""
    if len(data) < 3:
        return None
    addr = data[0]
    ctrl = data[1]
    # Length field can be 1 or 2 bytes
    length_byte = data[2]
    if length_byte & 0x01:  # EA bit set = 1 byte length
        length = (length_byte >> 1)
        payload_start = 3
    else:
        if len(data) < 4:
            return None
        length = ((data[3] << 7) | (length_byte >> 1))
        payload_start = 4

    dlci = (addr >> 2) & 0x3F
    cr = (addr >> 1) & 0x01
    ea = addr & 0x01

    # Frame type
    frame_type = ctrl & 0xEF  # mask P/F bit
    frame_names = {
        0x2F: "SABM",
        0x63: "UA",
        0x0F: "DM",
        0x43: "DISC",
        0xEF: "UIH",
        0x03: "UI",
    }
    frame_name = frame_names.get(frame_type, f"0x{frame_type:02x}")

    fcs_pos = payload_start + length
    fcs = data[fcs_pos] if fcs_pos < len(data) else None
    payload = data[payload_start:payload_start+length]

    return {
        "dlci": dlci,
        "cr": cr,
        "ctrl": ctrl,
        "frame_type": frame_type,
        "frame_name": frame_name,
        "length": length,
        "payload": payload,
        "fcs": fcs,
    }

def analyze_records(records):
    # Track connection handles and their associated MACs
    handle_to_mac = {}
    mac_to_handle = {}

    # Events of interest
    target_handle = None
    target_connections = []

    print("\n" + "="*70)
    print("PHASE 1: Looking for connection events involving target MAC")
    print("="*70)

    for r in records:
        data = r["data"]
        if not data:
            continue

        pkt_type = data[0]

        # HCI Event
        if pkt_type == HCI_EVT and len(data) >= 3:
            evt_code = data[1]
            params = data[3:]

            # Connection Complete (0x03)
            if evt_code == 0x03 and len(params) >= 11:
                status = params[0]
                handle = struct.unpack("<H", params[1:3])[0]
                # BD_ADDR is 6 bytes, little-endian (reversed in packet)
                bd_addr_bytes = params[3:9]
                bd_addr = mac_to_str(bd_addr_bytes[::-1])
                link_type = params[9]
                encryption = params[10]

                handle_to_mac[handle] = bd_addr
                if bd_addr.lower() not in mac_to_handle:
                    mac_to_handle[bd_addr.lower()] = []
                mac_to_handle[bd_addr.lower()].append(handle)

                if TARGET_MAC_STR.lower() in bd_addr.lower():
                    target_handle = handle
                    target_connections.append({
                        "pkt": r["num"],
                        "ts": r["ts_str"],
                        "handle": handle,
                        "mac": bd_addr,
                        "status": status,
                        "link_type": link_type,
                        "encryption": encryption,
                    })
                    print(f"[PKT {r['num']}] [{r['ts_str']}] CONNECTION COMPLETE to {bd_addr}")
                    print(f"  Handle=0x{handle:04x}, status={status}, link_type={link_type}, encrypt={encryption}")

            # Disconnection Complete (0x05)
            elif evt_code == 0x05 and len(params) >= 4:
                status = params[0]
                handle = struct.unpack("<H", params[1:3])[0]
                reason = params[3]
                mac = handle_to_mac.get(handle, "unknown")
                if TARGET_MAC_STR.lower() in mac.lower() or handle == target_handle:
                    print(f"[PKT {r['num']}] [{r['ts_str']}] DISCONNECTION handle=0x{handle:04x} reason=0x{reason:02x} ({mac})")

            # Authentication Complete (0x06)
            elif evt_code == 0x06 and len(params) >= 3:
                status = params[0]
                handle = struct.unpack("<H", params[1:3])[0]
                mac = handle_to_mac.get(handle, "unknown")
                if TARGET_MAC_STR.lower() in mac.lower() or handle == target_handle:
                    print(f"[PKT {r['num']}] [{r['ts_str']}] AUTH COMPLETE handle=0x{handle:04x} status={status} ({mac})")

            # Encryption Change (0x08)
            elif evt_code == 0x08 and len(params) >= 4:
                status = params[0]
                handle = struct.unpack("<H", params[1:3])[0]
                enc_enabled = params[3]
                mac = handle_to_mac.get(handle, "unknown")
                if TARGET_MAC_STR.lower() in mac.lower() or handle == target_handle:
                    print(f"[PKT {r['num']}] [{r['ts_str']}] ENCRYPTION CHANGE handle=0x{handle:04x} status={status} enc={enc_enabled} ({mac})")

            # Remote Name Request Complete (0x07)
            elif evt_code == 0x07 and len(params) >= 8:
                status = params[0]
                bd_addr_bytes = params[1:7]
                bd_addr = mac_to_str(bd_addr_bytes[::-1])
                name = params[7:].rstrip(b'\x00').decode('utf-8', errors='replace')
                if TARGET_MAC_STR.lower() in bd_addr.lower():
                    print(f"[PKT {r['num']}] [{r['ts_str']}] REMOTE NAME: {bd_addr} = '{name}'")

            # Link Key Request (0x17)
            elif evt_code == 0x17 and len(params) >= 6:
                bd_addr_bytes = params[0:6]
                bd_addr = mac_to_str(bd_addr_bytes[::-1])
                if TARGET_MAC_STR.lower() in bd_addr.lower():
                    print(f"[PKT {r['num']}] [{r['ts_str']}] LINK KEY REQUEST from {bd_addr}")

            # Link Key Notification (0x18)
            elif evt_code == 0x18 and len(params) >= 23:
                bd_addr_bytes = params[0:6]
                bd_addr = mac_to_str(bd_addr_bytes[::-1])
                if TARGET_MAC_STR.lower() in bd_addr.lower():
                    print(f"[PKT {r['num']}] [{r['ts_str']}] LINK KEY NOTIFICATION for {bd_addr}")

            # Simple Pairing (various)
            elif evt_code in (0x31, 0x32, 0x33, 0x34) and len(params) >= 6:
                bd_addr_bytes = params[0:6]
                bd_addr = mac_to_str(bd_addr_bytes[::-1])
                evt_names = {0x31: "IO_CAP_REQ", 0x32: "IO_CAP_RESP", 0x33: "USER_CONFIRM_REQ", 0x34: "USER_PASSKEY_REQ"}
                if TARGET_MAC_STR.lower() in bd_addr.lower():
                    print(f"[PKT {r['num']}] [{r['ts_str']}] {evt_names.get(evt_code, f'EVT_0x{evt_code:02x}')} for {bd_addr}")

    print(f"\nTarget handle for {TARGET_MAC_STR}: {target_handle}")

    if not target_handle and not target_connections:
        print("No connection found to target MAC! Searching for any partial match...")
        # Search in all connection events
        for r in records:
            data = r["data"]
            if not data:
                continue
            # Search raw bytes for MAC pattern (various orderings)
            mac_le = bytes([0x18, 0x33, 0x05, 0x53, 0x03, 0x92])
            mac_be = bytes([0x92, 0x03, 0x53, 0x05, 0x33, 0x18])
            if mac_le in data or mac_be in data:
                print(f"[PKT {r['num']}] [{r['ts_str']}] Found MAC in raw data! flags={r['flags']}")
                print(f"  hex: {data[:min(40,len(data))].hex()}")

    return target_handle, handle_to_mac

def analyze_l2cap_rfcomm(records, target_handle, handle_to_mac):
    print("\n" + "="*70)
    print(f"PHASE 2: L2CAP/RFCOMM analysis for handle 0x{target_handle:04x}" if target_handle else "PHASE 2: No target handle found")
    print("="*70)

    if not target_handle:
        # Try to find ANY handle that has RFCOMM traffic
        print("Searching all handles for RFCOMM traffic...")
        rfcomm_handles = set()
        for r in records:
            data = r["data"]
            if not data or data[0] != HCI_ACL:
                continue
            acl = parse_hci_acl(data)
            if not acl:
                continue
            l2cap = parse_l2cap(acl["data"])
            if not l2cap:
                continue
            if l2cap["cid"] == 0x0001:  # L2CAP signaling
                pass
            elif 0x0040 <= l2cap["cid"] <= 0x007F:  # RFCOMM range
                rfcomm_handles.add(acl["handle"])
        print(f"Handles with RFCOMM traffic: {[hex(h) for h in rfcomm_handles]}")
        if rfcomm_handles:
            target_handle = list(rfcomm_handles)[0]
            print(f"Using handle 0x{target_handle:04x}")

    if not target_handle:
        return

    rfcomm_channel = None
    rfcomm_pkts = []
    l2cap_signaling = []

    for r in records:
        data = r["data"]
        if not data or data[0] != HCI_ACL:
            continue

        acl = parse_hci_acl(data)
        if not acl or acl["handle"] != target_handle:
            continue

        direction = "HOST->CTRL" if (r["flags"] & 1) == 0 else "CTRL->HOST"

        l2cap = parse_l2cap(acl["data"])
        if not l2cap:
            continue

        # L2CAP signaling channel
        if l2cap["cid"] == 0x0001:
            payload = l2cap["payload"]
            if payload:
                cmd_code = payload[0]
                cmd_id = payload[1] if len(payload) > 1 else 0
                cmd_names = {
                    0x01: "CMD_REJECT",
                    0x02: "CONN_REQ",
                    0x03: "CONN_RSP",
                    0x04: "CFG_REQ",
                    0x05: "CFG_RSP",
                    0x06: "DISCONN_REQ",
                    0x07: "DISCONN_RSP",
                    0x08: "ECHO_REQ",
                    0x09: "ECHO_RSP",
                    0x0A: "INFO_REQ",
                    0x0B: "INFO_RSP",
                }
                cmd_name = cmd_names.get(cmd_code, f"0x{cmd_code:02x}")

                info = f"[PKT {r['num']}] [{r['ts_str']}] L2CAP SIG {direction} {cmd_name}"

                # Connection Request
                if cmd_code == 0x02 and len(payload) >= 8:
                    psm = struct.unpack("<H", payload[4:6])[0]
                    scid = struct.unpack("<H", payload[6:8])[0]
                    info += f" PSM=0x{psm:04x} SCID=0x{scid:04x}"
                    if psm == 0x0003:
                        info += " [RFCOMM!]"
                        rfcomm_channel = scid
                # Connection Response
                elif cmd_code == 0x03 and len(payload) >= 12:
                    dcid = struct.unpack("<H", payload[4:6])[0]
                    scid = struct.unpack("<H", payload[6:8])[0]
                    result = struct.unpack("<H", payload[8:10])[0]
                    status = struct.unpack("<H", payload[10:12])[0]
                    result_names = {0: "SUCCESS", 1: "PENDING", 2: "PSM_REFUSED", 3: "SECURITY_BLOCK", 4: "NO_RESOURCES"}
                    info += f" DCID=0x{dcid:04x} SCID=0x{scid:04x} result={result_names.get(result, result)} status={status}"

                l2cap_signaling.append(info)
                print(info)

        # RFCOMM channel (CID >= 0x0040)
        elif l2cap["cid"] >= 0x0040:
            rfcomm = parse_rfcomm(l2cap["payload"])
            if rfcomm:
                info = f"[PKT {r['num']}] [{r['ts_str']}] RFCOMM {direction} DLCI={rfcomm['dlci']} {rfcomm['frame_name']} len={rfcomm['length']}"
                if rfcomm["payload"]:
                    info += f"\n  data: {rfcomm['payload'].hex()}"
                    # Try to decode as ASCII
                    try:
                        ascii_str = rfcomm["payload"].decode('ascii', errors='replace')
                        printable = ''.join(c if 32 <= ord(c) < 127 else '.' for c in ascii_str)
                        if any(32 <= ord(c) < 127 for c in ascii_str):
                            info += f"\n  ascii: {printable}"
                    except:
                        pass
                rfcomm_pkts.append({
                    "pkt": r["num"],
                    "ts": r["ts_str"],
                    "direction": direction,
                    "rfcomm": rfcomm,
                    "raw": l2cap["payload"].hex(),
                })
                print(info)

    return rfcomm_pkts

def analyze_spp_data(rfcomm_pkts):
    print("\n" + "="*70)
    print("PHASE 3: SPP Data Analysis (SJBT protocol)")
    print("="*70)

    phone_to_device = []
    device_to_phone = []

    for pkt in rfcomm_pkts:
        rfcomm = pkt["rfcomm"]
        # UIH frames with DLCI != 0 carry actual data
        if rfcomm["frame_name"] == "UIH" and rfcomm["dlci"] != 0 and rfcomm["payload"]:
            payload = rfcomm["payload"]
            if "HOST->CTRL" in pkt["direction"]:
                phone_to_device.append(pkt)
            else:
                device_to_phone.append(pkt)

    print(f"\nPhone->Device SPP packets: {len(phone_to_device)}")
    for pkt in phone_to_device[:20]:
        payload = pkt["rfcomm"]["payload"]
        print(f"  [PKT {pkt['pkt']}] {payload.hex()}")
        # Check for SJBT
        if len(payload) >= 4 and payload[0] == 0x30:
            cmd_id = struct.unpack(">H", payload[2:4])[0] if len(payload) >= 4 else 0
            print(f"    SJBT: head=0x{payload[0]:02x} (MATCH!), cmdId=0x{cmd_id:04x}")
        elif len(payload) >= 2:
            print(f"    head=0x{payload[0]:02x} (expected 0x30)")

    print(f"\nDevice->Phone SPP packets: {len(device_to_phone)}")
    for pkt in device_to_phone[:20]:
        payload = pkt["rfcomm"]["payload"]
        print(f"  [PKT {pkt['pkt']}] {payload.hex()}")

    return phone_to_device, device_to_phone

def scan_for_mac_anywhere(records):
    """Scan every packet for the target MAC bytes."""
    print("\n" + "="*70)
    print("PHASE 0: Scanning ALL packets for target MAC bytes")
    print("="*70)

    mac_le = bytes([0x18, 0x33, 0x05, 0x53, 0x03, 0x92])  # little-endian
    mac_be = bytes([0x92, 0x03, 0x53, 0x05, 0x33, 0x18])  # big-endian

    found = []
    for r in records:
        data = r["data"]
        if mac_le in data or mac_be in data:
            found.append(r)
            direction = "HOST->CTRL" if (r["flags"] & 1) == 0 else "CTRL->HOST"
            cmd_evt = "CMD/EVT" if (r["flags"] & 2) else "DATA"
            pkt_type = data[0] if data else 0
            type_names = {0x01: "HCI_CMD", 0x02: "HCI_ACL", 0x03: "HCI_SCO", 0x04: "HCI_EVT"}
            print(f"[PKT {r['num']}] [{r['ts_str']}] {direction} {cmd_evt} {type_names.get(pkt_type, hex(pkt_type))}")
            print(f"  hex[0:40]: {data[:40].hex()}")

    print(f"\nTotal packets containing target MAC: {len(found)}")
    return found

def dump_all_connection_events(records):
    """Dump all HCI connection-related events."""
    print("\n" + "="*70)
    print("PHASE 0b: All HCI Connection/Auth/Encryption events")
    print("="*70)

    interesting_evts = {
        0x01: "INQUIRY_COMPLETE",
        0x02: "INQUIRY_RESULT",
        0x03: "CONN_COMPLETE",
        0x04: "CONN_REQUEST",
        0x05: "DISCONN_COMPLETE",
        0x06: "AUTH_COMPLETE",
        0x07: "REMOTE_NAME_COMPLETE",
        0x08: "ENCRYPT_CHANGE",
        0x09: "CHANGE_LINK_KEY_COMPLETE",
        0x0B: "MASTER_LINK_KEY_COMPLETE",
        0x0C: "READ_REMOTE_FEATURES_COMPLETE",
        0x17: "LINK_KEY_REQUEST",
        0x18: "LINK_KEY_NOTIFICATION",
        0x1B: "MAX_SLOTS_CHANGE",
        0x1F: "PAGE_SCAN_MODE_CHANGE",
        0x22: "INQUIRY_RESULT_WITH_RSSI",
        0x2F: "EXTENDED_INQUIRY_RESULT",
        0x30: "ENCRYPT_KEY_REFRESH_COMPLETE",
        0x31: "IO_CAPABILITY_REQUEST",
        0x32: "IO_CAPABILITY_RESPONSE",
        0x33: "USER_CONFIRM_REQUEST",
        0x34: "USER_PASSKEY_REQUEST",
        0x35: "REMOTE_OOB_DATA_REQUEST",
        0x36: "SIMPLE_PAIRING_COMPLETE",
        0x3B: "USER_PASSKEY_NOTIFICATION",
        0x3C: "KEYPRESS_NOTIFICATION",
    }

    for r in records:
        data = r["data"]
        if not data:
            continue
        if data[0] == HCI_EVT and len(data) >= 2:
            evt_code = data[1]
            if evt_code in interesting_evts:
                params = data[3:] if len(data) > 3 else b""
                direction = "HOST->CTRL" if (r["flags"] & 1) == 0 else "CTRL->HOST"
                print(f"[PKT {r['num']}] [{r['ts_str']}] {interesting_evts[evt_code]} (0x{evt_code:02x}) {direction}")
                # For connection/MAC events, show first few bytes
                if len(params) >= 6 and evt_code in (0x03, 0x04, 0x05, 0x06, 0x07, 0x17, 0x18, 0x08):
                    print(f"  params[0:12]: {params[:12].hex()}")

def analyze_all_rfcomm(records, handle_to_mac):
    """Analyze RFCOMM on all ACL handles, not just target."""
    print("\n" + "="*70)
    print("PHASE 2b: RFCOMM/L2CAP on ALL handles")
    print("="*70)

    seen_handles = {}
    rfcomm_data = {}

    for r in records:
        data = r["data"]
        if not data or data[0] != HCI_ACL:
            continue

        acl = parse_hci_acl(data)
        if not acl:
            continue

        h = acl["handle"]
        if h not in seen_handles:
            mac = handle_to_mac.get(h, "unknown")
            seen_handles[h] = mac

        l2cap = parse_l2cap(acl["data"])
        if not l2cap:
            continue

        direction = "HOST->CTRL" if (r["flags"] & 1) == 0 else "CTRL->HOST"

        # L2CAP signaling
        if l2cap["cid"] == 0x0001:
            payload = l2cap["payload"]
            if payload and payload[0] in (0x02, 0x03):  # CONN_REQ or CONN_RSP
                cmd_code = payload[0]
                if cmd_code == 0x02 and len(payload) >= 8:
                    psm = struct.unpack("<H", payload[4:6])[0]
                    scid = struct.unpack("<H", payload[6:8])[0]
                    mac = seen_handles.get(h, "unknown")
                    print(f"[PKT {r['num']}] [{r['ts_str']}] L2CAP CONN_REQ handle=0x{h:04x} ({mac}) PSM=0x{psm:04x} SCID=0x{scid:04x} {direction}")
                elif cmd_code == 0x03 and len(payload) >= 12:
                    dcid = struct.unpack("<H", payload[4:6])[0]
                    scid = struct.unpack("<H", payload[6:8])[0]
                    result = struct.unpack("<H", payload[8:10])[0]
                    mac = seen_handles.get(h, "unknown")
                    result_names = {0: "SUCCESS", 1: "PENDING", 2: "PSM_REFUSED", 3: "SECURITY_BLOCK", 4: "NO_RESOURCES"}
                    print(f"[PKT {r['num']}] [{r['ts_str']}] L2CAP CONN_RSP handle=0x{h:04x} ({mac}) DCID=0x{dcid:04x} SCID=0x{scid:04x} result={result_names.get(result,result)} {direction}")

        # RFCOMM
        elif l2cap["cid"] >= 0x0040:
            rfcomm = parse_rfcomm(l2cap["payload"])
            if rfcomm:
                mac = seen_handles.get(h, "unknown")
                key = (h, l2cap["cid"])
                if key not in rfcomm_data:
                    rfcomm_data[key] = {"mac": mac, "pkts": []}
                rfcomm_data[key]["pkts"].append({
                    "pkt": r["num"],
                    "direction": direction,
                    "rfcomm": rfcomm,
                    "ts": r["ts_str"],
                })

    print(f"\nACL handles seen: {[(hex(h), mac) for h, mac in seen_handles.items()]}")
    print(f"\nRFCOMM flows: {[(hex(k[0]), hex(k[1]), v['mac']) for k, v in rfcomm_data.items()]}")

    for key, flow in rfcomm_data.items():
        handle, cid = key
        mac = flow["mac"]
        pkts = flow["pkts"]
        print(f"\n--- RFCOMM handle=0x{handle:04x} CID=0x{cid:04x} MAC={mac} ---")
        print(f"    Total packets: {len(pkts)}")
        for pkt in pkts[:30]:
            rfcomm = pkt["rfcomm"]
            print(f"  [PKT {pkt['pkt']}] {pkt['direction']} DLCI={rfcomm['dlci']} {rfcomm['frame_name']} len={rfcomm['length']}", end="")
            if rfcomm["payload"]:
                print(f"\n    data: {rfcomm['payload'].hex()}")
                # SJBT check
                p = rfcomm["payload"]
                if len(p) >= 1:
                    print(f"    head=0x{p[0]:02x}", end="")
                    if p[0] == 0x30:
                        print(" [SJBT MATCH!]", end="")
                        if len(p) >= 4:
                            cmd_id = struct.unpack(">H", p[2:4])[0]
                            print(f" cmdId=0x{cmd_id:04x}", end="")
                    print()
            else:
                print()

    return rfcomm_data

def main():
    records = parse_btsnoop(BTSNOOP_FILE)

    # Phase 0: scan for MAC
    scan_for_mac_anywhere(records)

    # Phase 0b: all connection events
    dump_all_connection_events(records)

    # Phase 1: connection events
    target_handle, handle_to_mac = analyze_records(records)

    # Phase 2b: all RFCOMM
    rfcomm_data = analyze_all_rfcomm(records, handle_to_mac)

    # Phase 2: L2CAP/RFCOMM for target
    if target_handle:
        rfcomm_pkts = analyze_l2cap_rfcomm(records, target_handle, handle_to_mac)
        if rfcomm_pkts:
            analyze_spp_data(rfcomm_pkts)
    else:
        print("\nNo target handle - skipping targeted RFCOMM analysis")
        # Still do SPP analysis on all RFCOMM data
        if rfcomm_data:
            print("\n" + "="*70)
            print("PHASE 3: SPP Data in All RFCOMM flows")
            print("="*70)
            for key, flow in rfcomm_data.items():
                for pkt in flow["pkts"]:
                    rfcomm = pkt["rfcomm"]
                    if rfcomm["frame_name"] == "UIH" and rfcomm["dlci"] != 0 and rfcomm["payload"]:
                        p = rfcomm["payload"]
                        print(f"  [PKT {pkt['pkt']}] {pkt['direction']} DLCI={rfcomm['dlci']} data={p.hex()}")
                        if p[0] == 0x30:
                            print(f"    *** SJBT head=0x30 MATCH! ***")

if __name__ == "__main__":
    main()
