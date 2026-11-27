"""Total network bytes of one app uid in `dumpsys netstats --uid` (stdlib only).

Sums rb (received) and tb (sent) over every history bucket of the "UID stats"
section whose key has the uid, any network and set, tag 0x0 (the whole uid).
Run `dumpsys netstats --poll` first so the latest traffic is in the dump.

Usage: python3 -I netstats_uid.py <dump.txt> <uid>
"""
import re
import sys

KEY = re.compile(r"\buid=(-?\d+)\b.*\btag=(0x[0-9a-fA-F]+)")
BYTES = re.compile(r"\b(rb|tb)=(\d+)")


def total(text, uid):
    in_uid_stats = False
    counting = False
    n = 0
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("UID stats:"):
            in_uid_stats = True
            continue
        if in_uid_stats and stripped.startswith("UID tag stats:"):
            break
        if not in_uid_stats:
            continue
        key = KEY.search(stripped)
        if key and "ident=" in stripped:
            counting = int(key.group(1)) == uid and int(key.group(2), 16) == 0
            continue
        if counting and stripped.startswith("st="):
            n += sum(int(v) for _, v in BYTES.findall(stripped))
    return n


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        print(total(f.read(), int(argv[2])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
