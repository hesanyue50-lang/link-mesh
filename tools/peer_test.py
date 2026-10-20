#!/usr/bin/env python3
"""LanMesh 协议对端模拟器（测试用，不属于 App 交付物）。

用途：在只有一台真机的情况下，从沙箱侧实现一个协议对端，
验证 App 的服务端握手、信任门禁、文本收发、文件接收、断点续传与 SHA-256 校验。

用法：
  python3 peer_test.py text
  python3 peer_test.py file <本地文件> [远端名] [fileId] [在发送第N块后断开]
"""
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = int(os.environ.get("PORT", "45900"))
DEVID = "11111111-2222-3333-4444-555555555555"
NICK = "测试对端"
CHUNK = 128 * 1024

T_HELLO = 1
T_HELLO_ACK = 2
T_TEXT = 3
T_TEXT_ACK = 4
T_FILE_OFFER = 5
T_FILE_ACCEPT = 6
T_FILE_REJECT = 7
T_CHUNK = 8
T_FILE_DONE = 9
T_FILE_RESULT = 10
T_CANCEL = 11
T_PING = 12
T_PONG = 13
T_TRUST_REQUEST = 14
T_TRUST_DENIED = 15
T_BYE = 16

NAMES = {v: k for k, v in list(globals().items()) if k.startswith("T_")}


def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError("peer closed")
        buf += chunk
    return buf


def read_frame(s):
    hdr = recv_exact(s, 5)
    length, ftype = struct.unpack(">iB", hdr)
    payload = recv_exact(s, length) if length else b""
    return ftype, payload


def write_frame(s, ftype, payload):
    s.sendall(struct.pack(">iB", len(payload), ftype) + payload)


def write_json(s, ftype, obj):
    write_frame(s, ftype, json.dumps(obj, ensure_ascii=False).encode("utf-8"))


def log(*a):
    print(*a, flush=True)


def connect():
    s = socket.create_connection((HOST, PORT), 8)
    s.settimeout(70)
    write_json(s, T_HELLO, {
        "magic": "LMSH", "proto": 1, "deviceId": DEVID,
        "nickname": NICK, "port": 0,
    })
    return s


def wait_handshake(s, timeout=70):
    end = time.time() + timeout
    while time.time() < end:
        t, p = read_frame(s)
        log("<<", NAMES.get(t, t), p[:200].decode("utf-8", "replace"))
        if t == T_HELLO_ACK:
            return True
        if t == T_TRUST_DENIED:
            return False
        if t == T_TRUST_REQUEST:
            log("!! 等待真机上的信任确认（60 秒）")
    return False


def send_text(s, text):
    write_json(s, T_TEXT, {
        "msgId": "test-" + str(time.time()),
        "fromDeviceId": DEVID, "fromNickname": NICK,
        "timestamp": int(time.time() * 1000), "text": text,
    })
    t, p = read_frame(s)
    log("<<", NAMES.get(t, t), p[:200].decode("utf-8", "replace"))
    return t == T_TEXT_ACK


def parse_bitset(b64):
    """Java BitSet.toByteArray 是小端位序：bit i = byte[i//8] >> (i%8) & 1。"""
    if not b64:
        return set()
    raw = base64.b64decode(b64)
    out = set()
    for bi, byte in enumerate(raw):
        for j in range(8):
            if (byte >> j) & 1:
                out.add(bi * 8 + j)
    return out


def send_file(s, path, remote_name=None, file_id=None, interrupt_at=0, delay=0.0):
    size = os.path.getsize(path)
    file_id = file_id or ("testfile-" + str(int(time.time())))
    name = remote_name or os.path.basename(path)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    digest = h.hexdigest()
    total = (size + CHUNK - 1) // CHUNK
    log("local sha256", digest, "size", size, "chunks", total, "fileId", file_id)
    write_json(s, T_FILE_OFFER, {
        "fileId": file_id, "fileName": name, "fileSize": size,
        "chunkSize": CHUNK, "transferMode": "local", "sha256": digest,
    })
    t, p = read_frame(s)
    if t != T_FILE_ACCEPT:
        log("!! 未收到 ACCEPT:", NAMES.get(t, t), p[:200])
        return False
    acc = json.loads(p.decode("utf-8"))
    have = parse_bitset(acc.get("bitmap", ""))
    log("ACCEPT 已收块数", len(have), "/", total, "（%d%%）" % (len(have) * 100 // max(1, total)))

    sent = 0
    skipped = 0
    t0 = time.time()
    with open(path, "rb") as f:
        for idx in range(total):
            b = f.read(CHUNK)
            if not b:
                break
            if idx in have:
                skipped += len(b)
                continue
            write_frame(s, T_CHUNK, struct.pack(">i", idx) + b)
            sent += len(b)
            if delay:
                time.sleep(delay)
            if interrupt_at and (idx + 1) >= interrupt_at:
                log("!! 主动断开于第", idx + 1, "块，已发", round(sent / 1024 / 1024, 1), "MB")
                try:
                    s.close()
                except Exception:
                    pass
                return None
    log("all chunks sent, sent", round(sent / 1024 / 1024, 1), "MB, skipped",
        round(skipped / 1024 / 1024, 1), "MB, elapsed", round(time.time() - t0, 2), "s")
    write_json(s, T_FILE_DONE, {"fileId": file_id, "sha256": digest, "size": size})
    s.settimeout(300)
    t, p = read_frame(s)
    log("<<", NAMES.get(t, t), p[:300].decode("utf-8", "replace"))
    return t == T_FILE_RESULT and json.loads(p.decode("utf-8")).get("ok")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "text"
    s = connect()
    log("connected", HOST, PORT)
    ok = wait_handshake(s)
    log("handshake", ok)
    if not ok:
        return 1
    if mode == "text":
        send_text(s, "来自沙箱对端的测试消息 ✅ " + time.strftime("%H:%M:%S"))
    elif mode == "file":
        path = sys.argv[2]
        name = sys.argv[3] if len(sys.argv) > 3 else None
        fid = sys.argv[4] if len(sys.argv) > 4 else None
        cut = int(sys.argv[5]) if len(sys.argv) > 5 else 0
        delay = float(sys.argv[6]) if len(sys.argv) > 6 else 0.0
        send_file(s, path, name, fid, cut, delay)
    try:
        write_frame(s, T_BYE, b"")
        s.close()
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
