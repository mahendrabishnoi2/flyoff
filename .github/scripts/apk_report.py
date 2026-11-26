"""Report release APK sizes as Markdown and fail if a per-ABI APK exceeds the budget.

Usage: python3 -I apk_report.py <release-apk-dir> <max-bytes-per-abi-apk>
The universal APK is reported but not checked: the budget is per CPU type.
"""
import pathlib
import sys

apk_dir = pathlib.Path(sys.argv[1])
budget = int(sys.argv[2])
apks = sorted(apk_dir.glob("*.apk"))
if not apks:
    sys.exit(f"No APKs found in {apk_dir}")

over = []
print("### Release APK sizes\n")
print(f"Budget: {budget / 1e6:.1f} MB per CPU type (universal APK not checked)\n")
print("| APK | Size | Within budget |")
print("|---|---|---|")
for apk in apks:
    size = apk.stat().st_size
    universal = "universal" in apk.name
    ok = universal or size <= budget
    if not ok:
        over.append(apk.name)
    mark = "n/a" if universal else ("yes" if ok else "**NO**")
    print(f"| `{apk.name}` | {size / 1e6:.1f} MB | {mark} |")

if over:
    print(f"\nOver budget: {', '.join(over)}", file=sys.stderr)
    sys.exit(1)
