#!/usr/bin/env python3
"""Print the Fatal/Error findings from an Android lint XML report.

GitHub does not expose Actions step logs to unauthenticated clients, but it does
expose check-run annotations. CI therefore pipes this output into `::error::`
lines so the exact lint findings can be read back after a failed release build.

Usage: lint_issues.py <path-to-lint-results.xml>
"""

import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: lint_issues.py <path-to-lint-results.xml>", file=sys.stderr)
        return 2

    path = sys.argv[1]
    if not os.path.exists(path):
        return 0

    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print("could not parse %s: %s" % (path, exc), file=sys.stderr)
        return 0

    for issue in root.findall("issue"):
        severity = (issue.get("severity") or "").lower()
        if severity not in ("fatal", "error"):
            continue
        location = issue.find("location")
        where = ""
        if location is not None:
            where = "%s:%s" % (location.get("file", "?"), location.get("line", "?"))
        message = " ".join((issue.get("message") or "").split())
        print("%s | %s | %s" % (issue.get("id"), where, message))

    return 0


if __name__ == "__main__":
    sys.exit(main())
