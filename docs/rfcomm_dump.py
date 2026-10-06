"""Dump RFCOMM payloads from btsnoop HCI logs, with local timestamps.

Usage:
  python3 rfcomm_dump.py [--json] [--full] btsnoop_hci.log.last btsnoop_hci.log

--json  print only the adapter's JSON messages, one per line, Wi-Fi passwords redacted
--full  don't shorten long JSON lines (info replies, log payloads)
"""
import datetime, json, os, re, struct, sys

BTSNOOP_EPOCH_US = 0x00dcddb30f2f8000  # btsnoop timestamps count microseconds from year 0

def records(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'btsnoop\0', 'not a btsnoop file'
    p = 16
    while p + 24 <= len(d):
        olen, ilen, flags, drops, ts = struct.unpack('>IIIIq', d[p:p+24])
        yield flags, ts, d[p+24:p+24+ilen]
        p += 24 + ilen

def l2cap_frames(path):
    # reassemble ACL fragments into L2CAP frames, per handle and direction
    pend = {}
    for flags, ts, pkt in records(path):
        if not pkt or pkt[0] != 0x02:  # H4 ACL
            continue
        hdr, alen = struct.unpack('<HH', pkt[1:5])
        handle, pb = hdr & 0x0fff, (hdr >> 12) & 0x3
        sent = not (flags & 1)  # bit0 clear = host->controller (phone->adapter)
        key, data = (handle, sent), pkt[5:5+alen]
        if pb in (0, 2):
            pend[key] = bytearray(data)
        elif key in pend:
            pend[key] += data
        buf = pend.get(key)
        if buf and len(buf) >= 4 and len(buf) >= 4 + struct.unpack('<H', buf[:2])[0]:
            ln, cid = struct.unpack('<HH', buf[:4])
            yield ts, sent, cid, bytes(buf[4:4+ln])
            del pend[key]

def rfcomm_payload(f):
    if len(f) < 4:
        return None
    addr, ctrl = f[0], f[1]
    dlci = addr >> 2
    if dlci == 0 or (ctrl & 0xEF) != 0xEF:  # skip control channel, keep UIH only
        return None
    if f[2] & 1:
        ln, p = f[2] >> 1, 3
    else:
        ln, p = (f[2] >> 1) | (f[3] << 7), 4
    if ctrl & 0x10:  # PF set on UIH = credit byte present
        p += 1
    return f[p:p+ln] or None

def local_time(ts):
    return datetime.datetime.fromtimestamp((ts - BTSNOOP_EPOCH_US) / 1e6).strftime('%H:%M:%S.%f')[:-3]

def runs(paths):
    """Yield (time, direction, text) for each contiguous run of payload in one direction."""
    cur, start, buf = None, None, []
    for path in paths:
        if not os.path.exists(path):  # btsnoop_hci.log.last only exists after a rotation
            print(f'skipping missing {path}', file=sys.stderr)
            continue
        for ts, sent, cid, f in l2cap_frames(path):
            pl = rfcomm_payload(f) if cid >= 0x40 else None  # skip signalling/fixed channels
            if pl is None:
                continue
            d = 'PHONE->ADAPTER' if sent else 'ADAPTER->PHONE'
            if d != cur:
                if buf:
                    yield start, cur, b''.join(buf).decode('utf-8', 'replace')
                cur, start, buf = d, local_time(ts), []
            buf.append(pl)
    if buf:
        yield start, cur, b''.join(buf).decode('utf-8', 'replace')

def json_messages(text):
    dec, i = json.JSONDecoder(), text.find('{')
    while i != -1:
        try:
            obj, end = dec.raw_decode(text, i)
            yield obj
            i = text.find('{', end)
        except ValueError:
            i = text.find('{', i + 1)

if __name__ == '__main__':
    args = sys.argv[1:]
    only_json, full = '--json' in args, '--full' in args
    paths = [a for a in args if not a.startswith('--')]
    for t, d, text in runs(paths):
        if not only_json:
            print(f'\n=== {t} {d} ===\n{text}', end='')
            continue
        for obj in json_messages(text):
            line = re.sub(r'("wifiPassword":\s*")[^"]*', r'\1<REDACTED>', json.dumps(obj))
            print(f'{t} {d[:5]:5} {line if full else line[:300]}')
    print()
