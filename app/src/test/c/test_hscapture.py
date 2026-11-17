"""Exercises hscapture on the loopback interface (needs root / CAP_NET_RAW).

Build and run:  gcc -O2 -o /tmp/hscapture app/src/main/c/hscapture.c && sudo python3 app/src/test/c/test_hscapture.py /tmp/hscapture
"""
import socket
import struct
import subprocess
import sys
import threading
import time

BIN = sys.argv[1]
failures = []


def check(cond, msg):
    print(("PASS " if cond else "FAIL ") + msg)
    if not cond:
        failures.append(msg)


def run(args, traffic, stop="q", wait=0.6):
    p = subprocess.Popen([BIN, "-i", "lo", *args], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out_chunks, err_lines = [], []
    t1 = threading.Thread(target=lambda: out_chunks.append(p.stdout.read()))
    t2 = threading.Thread(target=lambda: err_lines.extend(p.stderr.read().decode().splitlines()))
    t1.start(); t2.start()
    time.sleep(wait)
    traffic()
    time.sleep(wait)
    if stop == "q":
        p.stdin.write(b"q\n"); p.stdin.flush()
    elif stop == "eof":
        p.stdin.close()
    try:
        p.wait(timeout=5)
    except subprocess.TimeoutExpired:
        p.kill(); failures.append("process did not exit")
    t1.join(); t2.join()
    return p.returncode, b"".join(out_chunks), err_lines


def parse_pcap(data):
    magic, major, minor, _, _, snap, link = struct.unpack("<IHHiIII", data[:24])
    pkts, off = [], 24
    while off + 16 <= len(data):
        _, _, incl, orig = struct.unpack("<IIII", data[off:off + 16])
        pkts.append((data[off + 16:off + 16 + incl], orig))
        off += 16 + incl
    return magic, (major, minor), snap, link, pkts, off == len(data)


def ipv4_pair(frame):
    if len(frame) >= 34 and frame[12:14] == b"\x08\x00":
        return socket.inet_ntoa(frame[26:30]), socket.inet_ntoa(frame[30:34])
    return None


def dns_query(name):
    q = struct.pack(">HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0)
    for label in name.split("."):
        q += bytes([len(label)]) + label.encode()
    return q + b"\x00\x00\x01\x00\x01"


def traffic():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    for _ in range(5):
        s.sendto(b"hello-selected", ("127.0.0.2", 9999))
        s.sendto(b"hello-other", ("127.0.0.3", 9999))
    s.sendto(dns_query("example.com"), ("127.0.0.2", 53))
    s.close()
    t = socket.socket()
    t.settimeout(1)
    try:
        t.connect(("127.0.0.2", 1))  # refused -> SYN then RST
    except OSError:
        pass
    t.close()


# 1. Filtered capture
code, data, err = run(["-a", "127.0.0.2"], traffic)
magic, ver, snap, link, pkts, clean = parse_pcap(data)
check(code == 0, f"filtered run exits 0 (got {code})")
check(magic == 0xa1b2c3d4 and ver == (2, 4) and link == 1, "pcap header is ethernet libpcap 2.4")
check(clean, "pcap records are well-formed to the last byte")
pairs = [ipv4_pair(f) for f, _ in pkts]
check(len(pkts) >= 8, f"captured selected packets ({len(pkts)})")
check(all(p and "127.0.0.2" in p for p in pairs), "every packet involves 127.0.0.2")
check(not any(p and "127.0.0.3" in p for p in pairs), "no packet to the unselected host")
check(any(l.startswith("I 1 lo") for l in err), "start line reported")
check(any(l.startswith("S ") for l in err), "stats line reported")
check(any("DNS? example.com" in l for l in err), "DNS query name summarised")
check(any("TCP" in l and "[S]" in l for l in err), "TCP SYN summarised")
check(any("[R.]" in l or "[R]" in l for l in err), "TCP RST summarised")
xl = [l for l in err if l.startswith("X ")]
check(len(xl) == 1 and xl[0].split()[1] == "stopped" and int(xl[0].split()[2]) == len(pkts),
      f"end line counts match pcap ({xl})")

# 2. Unfiltered capture sees both hosts
code, data, err = run([], traffic)
pairs = [ipv4_pair(f) for f, _ in parse_pcap(data)[4]]
check(any(p and "127.0.0.3" in p for p in pairs), "unfiltered capture includes other host")

# 3. Stdin EOF stops it
code, data, err = run(["-a", "127.0.0.2"], traffic, stop="eof")
check(any(l.startswith("X stopped") for l in err), "stdin EOF stops capture")

# 4. Count limit and snaplen
code, data, err = run(["-a", "127.0.0.2", "-c", "3", "-s", "64"], traffic, stop="none")
_, _, snap, _, pkts, _ = parse_pcap(data)
check(len(pkts) == 3 and any(l.startswith("X count-limit") for l in err), "count limit stops after 3 packets")
check(snap == 64 and all(len(f) <= 64 for f, _ in pkts), "snaplen truncates frames")
check(all(orig >= len(f) for f, orig in pkts), "original length kept")

# 4b. File size limit never leaves a partial record
code, data, err = run(["-a", "127.0.0.2", "-C", "300"], traffic, stop="none")
_, _, _, _, pkts, clean = parse_pcap(data)
check(len(data) <= 300 and clean and len(pkts) >= 1, f"size limit keeps whole records ({len(data)} bytes)")
check(any(l.startswith("X size-limit") for l in err), "size limit reported")

# 5. Duration limit
t0 = time.time()
code, data, err = run(["-n", "-d", "1"], lambda: None, stop="none", wait=0.1)
check(any(l.startswith("X time-limit") for l in err) and time.time() - t0 < 4, "duration limit ends capture")

# 6. Broken output pipe ends capture
p = subprocess.Popen(f"{BIN} -i lo -n 2>{sys.argv[1]}.err | head -c 100 >/dev/null", shell=True)
time.sleep(0.5)
for _ in range(50):
    traffic()
p.wait(timeout=5)
time.sleep(0.5)
check("X output-closed" in open(f"{sys.argv[1]}.err").read(), "closed stdout ends capture")

# 7. Bad arguments
r = subprocess.run([BIN, "-i", "lo", "-a", "not-an-ip"], capture_output=True)
check(r.returncode == 2 and b"E bad" in r.stderr, "rejects bad IP")
r = subprocess.run([BIN, "-i", "nope0"], capture_output=True)
check(r.returncode == 3 and b"E no such interface" in r.stderr, "rejects unknown interface")

print("\nFAILURES:", failures if failures else "none")
sys.exit(1 if failures else 0)
