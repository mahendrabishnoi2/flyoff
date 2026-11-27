"""Find things in a `uiautomator dump` file for the emulator smoke test (stdlib only).

A node matches when its `text` or its `content-desc` matches the regular
expression (Python `re.search`; anchor it with ^ and $ for an exact match).
Compose puts a merged element's words in one or the other, so both are tried.

Usage: python3 -I ui_find.py <command> <ui.xml> <regex> [attribute]

  has     exit 0 if any node matches, else 1
  center  print "x y", the centre of the first matching node, for `adb shell input tap`
  number  print the regex's first group from the first match, as a plain number
          ("5,540" gives 5540, "9.5" gives 9.5)
  attr    print the first matching node's [attribute], such as checked or enabled

Exits 1 with a message on stderr when nothing matches, 2 on a bad file.
"""
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def nodes(path):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as e:
        print(f"{path}: not a uiautomator dump ({e})", file=sys.stderr)
        sys.exit(2)
    return root.iter("node")


def matches(path, pattern):
    """(node, match) for each node whose text or content-desc matches, in document order."""
    regex = re.compile(pattern)
    for node in nodes(path):
        for key in ("text", "content-desc"):
            m = regex.search(node.get(key, ""))
            if m:
                yield node, m
                break


def first(path, pattern):
    for found in matches(path, pattern):
        return found
    print(f"nothing matches '{pattern}' in {path}", file=sys.stderr)
    sys.exit(1)


def center(node):
    m = BOUNDS.fullmatch(node.get("bounds", ""))
    if not m:
        print(f"node has no bounds: {node.attrib}", file=sys.stderr)
        sys.exit(2)
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def number(text):
    plain = text.replace(",", "").replace(" ", "").strip()
    value = float(plain)
    return str(int(value)) if value.is_integer() else str(value)


def main(argv):
    if len(argv) < 4 or argv[1] not in ("has", "center", "number", "attr"):
        print(__doc__, file=sys.stderr)
        return 2
    command, path, pattern = argv[1:4]
    if command == "has":
        return 0 if any(True for _ in matches(path, pattern)) else 1
    node, m = first(path, pattern)
    if command == "center":
        print("%d %d" % center(node))
    elif command == "number":
        if not m.groups():
            print(f"'{pattern}' has no group to read", file=sys.stderr)
            return 2
        print(number(m.group(1)))
    else:
        if len(argv) < 5:
            print("attr needs an attribute name", file=sys.stderr)
            return 2
        print(node.get(argv[4], ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
