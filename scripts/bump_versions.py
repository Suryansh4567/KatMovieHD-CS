#!/usr/bin/env python3
"""
bump_versions.py — bump `version = N` in every enabled plugin module.

Used by the weekly "Auto Rebuild & Publish" workflow so that every scheduled
rebuild produces a new versionCode for each plugin. CloudStream 3 offers
"extension update available" to the user whenever the versionCode in
plugins.json is higher than the installed one, so a regular bump is what
actually delivers updates into users' apps.

The list of plugin modules is read from settings.gradle.kts (the `enabled`
allow-list) so new providers are picked up automatically.

Usage:
    python3 scripts/bump_versions.py [--increment 1] [--dry-run]
"""

import argparse
import os
import re
import sys


def enabled_plugins(root: str):
    settings = os.path.join(root, "settings.gradle.kts")
    with open(settings, "r", encoding="utf-8") as f:
        text = f.read()
    m = re.search(r"val enabled = setOf\(([^)]*)\)", text)
    if not m:
        print("error: could not find `val enabled = setOf(...)` in settings.gradle.kts", file=sys.stderr)
        sys.exit(2)
    return re.findall(r'"([^"]+)"', m.group(1))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    help="repository root (default: parent of scripts/)")
    ap.add_argument("--increment", type=int, default=1, help="amount to add to each version (default 1)")
    ap.add_argument("--dry-run", action="store_true", help="print changes without writing")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    updated = 0
    for name in enabled_plugins(root):
        build = os.path.join(root, name, "build.gradle.kts")
        if not os.path.exists(build):
            print(f"skip   {name}: no build.gradle.kts")
            continue
        with open(build, "r", encoding="utf-8") as f:
            text = f.read()
        m = re.search(r"(?m)^(version\s*=\s*)(\d+)\s*$", text)
        if not m:
            print(f"skip   {name}: no top-level `version = N` line")
            continue
        old = int(m.group(2))
        new = old + args.increment
        if args.dry_run:
            print(f"bump   {name}: version {old} -> {new} (dry run)")
        else:
            new_text = text[: m.start(2)] + str(new) + text[m.end(2):]
            with open(build, "w", encoding="utf-8") as f:
                f.write(new_text)
            print(f"bump   {name}: version {old} -> {new}")
        updated += 1

    verb = "would update" if args.dry_run else "updated"
    print(f"{verb} {updated} plugin(s)")


if __name__ == "__main__":
    main()
