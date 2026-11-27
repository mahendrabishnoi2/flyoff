"""Read the tracking service and its notification from dumpsys output (stdlib only).

Usage:
  python3 -I tracking_dumps.py service <dump.txt> <package> <ServiceClass>
      <dump.txt> is `adb shell dumpsys activity services <package>`. Prints the
      service's state: "foreground-location" (a foreground service whose types
      include location, 0x8), "foreground" (any other type), "started" (running,
      not in the foreground) or "absent".
  python3 -I tracking_dumps.py notification <dump.txt> <package> <channel> [regex]
      <dump.txt> is `adb shell dumpsys notification --noredact`. Prints how many
      of the package's posted notifications are on <channel> (and, with
      [regex], have a line in their record that matches it, such as the title).

Exits 2 when the file isn't the expected dump, so a failed dumpsys never reads
as "absent" or "0".
"""
import re
import sys

LOCATION_TYPE = 0x8


def indent(line):
    return len(line) - len(line.lstrip(" "))


def service_state(lines, package, service_class):
    """The state of [package]'s [service_class] in a services dump; None if it isn't one."""
    lines = list(lines)
    if not any("ACTIVITY MANAGER SERVICES" in line for line in lines):
        return None
    for i, line in enumerate(lines):
        text = line.strip()
        if not text.startswith("* ServiceRecord{"):
            continue
        component = next((w for w in text.split() if "/" in w), "")
        owner, _, name = component.partition("/")
        if owner != package or not (name == service_class or name.endswith("." + service_class)):
            continue
        block = []
        for nxt in lines[i + 1:]:
            if nxt.strip() and indent(nxt) <= indent(line):
                break
            block.append(nxt)
        body = "\n".join(block)
        if not re.search(r"\bisForeground=true\b", body):
            return "started"
        types = re.search(r"\b(?:types|foregroundServiceType)=0x([0-9a-fA-F]+)", body)
        if types and int(types.group(1), 16) & LOCATION_TYPE:
            return "foreground-location"
        return "foreground"
    return "absent"


def notifications(lines, package, channel, pattern=None):
    """How many of [package]'s posted notifications are on [channel] (matching [pattern]); None if not a notification dump."""
    lines = list(lines)
    if not any("NotificationRecord(" in l or "Notification List:" in l or "Notification Manager state" in l for l in lines):
        return None
    regex = re.compile(pattern) if pattern else None
    count = 0
    for i, line in enumerate(lines):
        text = line.strip()
        if not text.startswith("NotificationRecord(") or f"pkg={package} " not in text:
            continue
        if not re.search(rf"\bchannel={re.escape(channel)}\b", text):
            continue
        if regex:
            block = [line]
            for nxt in lines[i + 1:]:
                if nxt.strip() and indent(nxt) <= indent(line):
                    break
                block.append(nxt)
            if not any(regex.search(b) for b in block):
                continue
        count += 1
    return count


def main(argv):
    if len(argv) == 5 and argv[1] == "service":
        with open(argv[2], encoding="utf-8", errors="replace") as f:
            result = service_state(f, argv[3], argv[4])
    elif len(argv) in (5, 6) and argv[1] == "notification":
        with open(argv[2], encoding="utf-8", errors="replace") as f:
            result = notifications(f, argv[3], argv[4], argv[5] if len(argv) == 6 else None)
    else:
        print(__doc__, file=sys.stderr)
        return 2
    if result is None:
        print(f"{argv[2]}: not a dumpsys {'activity services' if argv[1] == 'service' else 'notification'} dump", file=sys.stderr)
        return 2
    print(result)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
