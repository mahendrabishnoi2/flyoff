"""Find things in a `uiautomator dump` file for the emulator smoke test (stdlib only).

A node matches when its `text` or its `content-desc` matches the regular
expression (Python `re.search`; anchor it with ^ and $ for an exact match).
Compose puts a merged element's words in one or the other, so both are tried.

Usage: python3 -I ui_find.py <command> <ui.xml> <regex> [attribute]

  has     exit 0 if any node matches, else 1
  center  print "x y", the centre of the first matching node, for `adb shell input tap`
  number  print the regex's first group from the first match, as a plain number
          ("5,540" gives 5540, "9.5" gives 9.5)
  attr    print [attribute], such as checked or enabled, of the control the first
          match belongs to: the nearest checkable or clickable node, itself or an
          ancestor (a Compose chip's label is a child TextView that is never checked)
  height  print the height in pixels of the first matching node as drawn: a node
          partly scrolled or clipped out of view reports only its visible part
  top     print the y of the first matching node's top edge

Exits 1 with a message on stderr when nothing matches, 2 on a bad file.
"""
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def load(path):
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as e:
        print(f"{path}: not a uiautomator dump ({e})", file=sys.stderr)
        sys.exit(2)


def matches(root, pattern):
    """(node, match) for each node whose text or content-desc matches, in document order."""
    regex = re.compile(pattern)
    for node in root.iter("node"):
        for key in ("text", "content-desc"):
            m = regex.search(node.get(key, ""))
            if m:
                yield node, m
                break


def first(root, path, pattern):
    for found in matches(root, pattern):
        return found
    print(f"nothing matches '{pattern}' in {path}", file=sys.stderr)
    sys.exit(1)


def control(root, node):
    """The nearest checkable or clickable node from [node] up, or [node] if there is none."""
    parents = {child: parent for parent in root.iter() for child in parent}
    here = node
    while here is not None and here.tag == "node":
        if here.get("checkable") == "true" or here.get("clickable") == "true":
            return here
        here = parents.get(here)
    return node


def bounds(node):
    m = BOUNDS.fullmatch(node.get("bounds", ""))
    if not m:
        print(f"node has no bounds: {node.attrib}", file=sys.stderr)
        sys.exit(2)
    return tuple(map(int, m.groups()))


def center(node):
    x1, y1, x2, y2 = bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2


def number(text):
    plain = text.replace(",", "").replace(" ", "").strip()
    value = float(plain)
    return str(int(value)) if value.is_integer() else str(value)


def main(argv):
    if len(argv) < 4 or argv[1] not in ("has", "center", "number", "attr", "height", "top"):
        print(__doc__, file=sys.stderr)
        return 2
    command, path, pattern = argv[1:4]
    root = load(path)
    if command == "has":
        return 0 if any(True for _ in matches(root, pattern)) else 1
    node, m = first(root, path, pattern)
    if command == "center":
        print("%d %d" % center(node))
    elif command == "number":
        if not m.groups():
            print(f"'{pattern}' has no group to read", file=sys.stderr)
            return 2
        print(number(m.group(1)))
    elif command == "height":
        _, y1, _, y2 = bounds(node)
        print(y2 - y1)
    elif command == "top":
        print(bounds(node)[1])
    else:
        if len(argv) < 5:
            print("attr needs an attribute name", file=sys.stderr)
            return 2
        print(control(root, node).get(argv[4], ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
