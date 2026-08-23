#!/usr/bin/env python3
"""
site_monitor.py — keep every plugin in this repo pointing at a live site.

What it does
------------
1. Reads the provider list from scripts/site_providers.json (one entry per
   plugin: which Kotlin file holds the hardcoded default domain and where the
   fallback candidate list lives in domains.json).
2. Probes the hardcoded domain of every plugin in parallel (browser-like
   headers, redirect following, marker check so a parked/copycat page is not
   mistaken for the real site).
3. If the hardcoded domain is dead but a candidate from domains.json is
   alive, it flags a "domain-change". With --fix it rewrites the Kotlin
   source and domains.json to the first live candidate.
4. Optionally reports the state of the CloudStream 3 app repos
   (recloudstream/cloudstream etc.) so the daily report also tracks what the
   CloudStream app side is doing.

The script is pure Python stdlib so it runs anywhere (GitHub Actions, any
Linux box) without installing anything.

Exit codes
----------
0  all providers healthy ("ok")
1  at least one provider is degraded / domain-change / down (expected state,
   not a crash — the report still contains all findings)
2  environment/config error (missing file, bad config, ...)

Typical CI usage
----------------
python3 scripts/site_monitor.py --cloudstream --fix \
    --out site-health.json --md-out site-health.md
"""

import argparse
import concurrent.futures
import datetime as _dt
import json
import os
import re
import sys
import urllib.error
import urllib.request

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

# CloudStream 3 app/toolchain repos worth tracking. The original
# LagradOst/CloudStream-3 repo is DMCA-blocked; the active community
# toolchain this repo's build uses (recloudstream gradle plugin,
# com.lagradost:cloudstream3:pre-release) lives in these repos.
CLOUDSTREAM_REPOS = ["recloudstream/cloudstream", "phisher98/cloudstream"]


def log(msg: str) -> None:
    print(f"[site-monitor] {msg}", file=sys.stderr)


# --------------------------------------------------------------------------
# HTTP helpers
# --------------------------------------------------------------------------

def http_get(url: str, timeout: float, json_api: bool = False, body_limit: int = 600_000):
    """GET a URL. Returns a dict with status/length/ok (+body for pages)."""
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/json" if json_api else "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Connection": "close",
    }
    if json_api:
        headers["Accept"] = "application/vnd.github+json, application/json"
        headers["User-Agent"] = "KatMovieHD-CS-site-monitor"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(body_limit)
            status = resp.status
            final_url = resp.url
            return {
                "status": status,
                "length": len(raw),
                "final_url": final_url,
                "ok": 200 <= status < 400,
                "body": raw,
                "error": None,
            }
    except urllib.error.HTTPError as e:
        try:
            raw = e.read(100_000)
        except Exception:
            raw = b""
        return {
            "status": e.code,
            "length": len(raw),
            "final_url": url,
            "ok": 200 <= e.code < 400,
            "body": raw,
            "error": f"HTTP {e.code}",
        }
    except Exception as e:
        return {
            "status": 0,
            "length": 0,
            "final_url": url,
            "ok": False,
            "body": b"",
            "error": f"{type(e).__name__}: {e}"[:160],
        }


def probe_site(url: str, timeout: float, markers, min_length: int) -> dict:
    """Probe one site URL. `markers` (list of lowercase substrings) must
    contain at least one of them when provided (else a parked/copycat page
    could pass)."""
    r = http_get(url, timeout)
    ok = r["ok"] and r["length"] >= min_length
    if ok and markers:
        body = r["body"][:300_000].decode("utf-8", "replace").lower()
        ok = any(m.lower() in body for m in markers)
    r["ok"] = ok
    r.pop("body", None)
    return r


def probe_api(url: str, timeout: float) -> dict:
    """Probe a JSON API endpoint (no page markers, small body is fine)."""
    r = http_get(url, timeout, json_api=True, body_limit=200_000)
    ok = r["ok"] and r["length"] >= 1
    r["ok"] = ok
    r.pop("body", None)
    return r


# --------------------------------------------------------------------------
# CloudStream app repos
# --------------------------------------------------------------------------

def cloudstream_info(timeout: float) -> dict:
    out = {"repos": []}
    for repo in CLOUDSTREAM_REPOS:
        entry = {"repo": repo}
        try:
            info = json.loads(http_get(f"https://api.github.com/repos/{repo}", timeout, json_api=True)["body"])
            if "full_name" not in info:
                raise RuntimeError(info.get("message", "API error"))
            entry["pushed_at"] = info.get("pushed_at")
            entry["stars"] = info.get("stargazers_count")
            entry["url"] = info.get("html_url")
            rel = http_get(f"https://api.github.com/repos/{repo}/releases/latest", timeout, json_api=True)
            try:
                latest = json.loads(rel["body"])
                if isinstance(latest, dict) and latest.get("tag_name"):
                    entry["latest_release"] = {
                        "tag": latest.get("tag_name"),
                        "published_at": latest.get("published_at"),
                        "url": latest.get("html_url"),
                    }
                else:
                    raise ValueError("no latest release")
            except Exception:
                tags = json.loads(http_get(f"https://api.github.com/repos/{repo}/tags?per_page=1", timeout, json_api=True)["body"])
                if isinstance(tags, list) and tags:
                    entry["latest_tag"] = tags[0].get("name")
        except Exception as e:
            entry["error"] = str(e)[:160]
        out["repos"].append(entry)
    out["note"] = (
        "build.gradle.kts pins com.lagradost:cloudstream3:pre-release, a floating "
        "version re-resolved on every build. The weekly 'Auto Rebuild & Publish' "
        "workflow therefore picks up CloudStream app/library updates automatically "
        "and publishes fresh plugin builds to the builds branch."
    )
    return out


# --------------------------------------------------------------------------
# Core
# --------------------------------------------------------------------------

def read_file(root: str, rel: str) -> str:
    with open(os.path.join(root, rel), "r", encoding="utf-8") as f:
        return f.read()


def extract_url(text: str, url_regex: str):
    m = re.search(url_regex, text)
    return m.group(1) if m else None


def normalize(url: str) -> str:
    return url.strip().rstrip("/")


def candidate_order(provider: dict, domains: dict, current: str) -> list:
    key = provider.get("candidates_key")
    cands = []
    if key and isinstance(domains.get(key), list):
        cands.extend(domains[key])
    top = key and domains.get(key)
    if isinstance(top, str):
        cands.insert(0, top)
    if current:
        cands.insert(0, current)
    seen, ordered = set(), []
    for c in cands:
        n = normalize(c)
        if n and n not in seen:
            seen.add(n)
            ordered.append(n)
    return ordered


def analyze_provider(provider: dict, domains: dict, root: str, timeout: float, min_length: int):
    entry = {
        "key": provider["key"],
        "plugin": provider.get("plugin", provider["key"]),
        "current": None,
        "status": "config-error",
        "proposed": None,
        "reason": None,
        "checks": {},
    }
    src_rel = provider.get("source")
    url_regex = provider.get("url_regex")
    if not src_rel or not url_regex:
        entry["reason"] = "provider config missing 'source' or 'url_regex'"
        return entry
    try:
        text = read_file(root, src_rel)
    except Exception as e:
        entry["reason"] = f"cannot read {src_rel}: {e}"
        return entry

    current = extract_url(text, url_regex)
    if not current:
        entry["reason"] = f"URL regex did not match in {src_rel}"
        return entry
    current = normalize(current)
    entry["current"] = current

    markers = provider.get("markers") or []
    urls = candidate_order(provider, domains, current)

    # Probe everything (current + candidates) concurrently.
    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(probe_site, u, timeout, markers, min_length): u for u in urls}
        for fut in concurrent.futures.as_completed(futures):
            u = futures[fut]
            try:
                results[u] = fut.result()
            except Exception as e:
                results[u] = {"status": 0, "length": 0, "ok": False, "error": str(e)[:160], "final_url": u}

    entry["checks"] = {u: results[u] for u in urls}

    # Extra API endpoints (reported, never auto-fixed).
    extras = provider.get("extra_checks") or []
    if extras:
        extra_results = {}
        for ex in extras:
            u = extract_url(text, ex.get("url_regex", ""))
            if not u:
                extra_results[ex["label"]] = {"url": None, "ok": False, "error": "regex did not match"}
                continue
            try:
                extra_results[ex["label"]] = dict(probe_api(normalize(u), timeout), url=normalize(u))
            except Exception as e:
                extra_results[ex["label"]] = {"url": normalize(u), "ok": False, "error": str(e)[:160]}
        entry["extra_checks"] = extra_results

    current_ok = results.get(current, {}).get("ok", False)
    live = [u for u in urls[1:] if results.get(u, {}).get("ok")]
    extra_dead = [k for k, v in (entry.get("extra_checks") or {}).items() if not v.get("ok")]

    if current_ok:
        entry["status"] = "ok" if not extra_dead else "degraded"
        if extra_dead:
            entry["reason"] = f"site ok but extra check(s) failing: {', '.join(extra_dead)}"
    elif live:
        entry["status"] = "domain-change"
        entry["proposed"] = live[0]
        entry["reason"] = f"{current} unreachable; first live candidate: {live[0]}"
    else:
        entry["status"] = "down"
        entry["reason"] = f"no live domain found among {len(urls)} candidates"
        if extra_dead:
            entry["reason"] += f"; extra check(s) failing: {', '.join(extra_dead)}"
    return entry


def apply_fix(entry: dict, provider: dict, root: str) -> list:
    """Rewrite Kotlin source + domains.json for a domain-change entry."""
    if entry["status"] != "domain-change" or not entry.get("proposed"):
        return []
    proposed = entry["proposed"]
    changed_files = []

    src_path = os.path.join(root, provider["source"])
    text = read_file(root, provider["source"])
    m = re.search(provider["url_regex"], text)
    if m:
        new_text = text[: m.start(1)] + proposed + text[m.end(1):]
        if new_text != text:
            with open(src_path, "w", encoding="utf-8") as f:
                f.write(new_text)
            changed_files.append(provider["source"])

    domains_path = os.path.join(root, "domains.json")
    with open(domains_path, "r", encoding="utf-8") as f:
        domains = json.load(f)
    domains[provider["key"]] = proposed
    ck = provider.get("candidates_key")
    if ck:
        old = domains.get(ck) or []
        new_list = [proposed] + [c for c in old if normalize(c) != normalize(proposed)]
        domains[ck] = new_list
    domains["_updated"] = _dt.date.today().isoformat()
    with open(domains_path, "w", encoding="utf-8") as f:
        json.dump(domains, f, indent=2, ensure_ascii=False)
        f.write("\n")
    changed_files.append("domains.json")

    return [{"key": entry["key"], "from": entry["current"], "to": proposed, "files": changed_files}]


def markdown_summary(report: dict) -> str:
    lines = [f"# 🌐 Site health report — {report['generated_at']}", ""]
    lines.append("| Provider | Plugin | Hardcoded domain | Status | Proposed | Detail |")
    lines.append("|---|---|---|---|---|---|")
    icon = {"ok": "✅ ok", "domain-change": "🔁 domain change", "down": "❌ down",
            "degraded": "⚠️ degraded", "config-error": "⚙️ config error"}
    for p in report["providers"]:
        detail = (p.get("reason") or "").replace("|", "\\|")
        proposed = p.get("proposed") or "—"
        lines.append(
            f"| {p['key']} | {p['plugin']} | `{p.get('current') or '—'}` "
            f"| {icon.get(p['status'], p['status'])} | {proposed} | {detail} |"
        )
    if report.get("changes_applied"):
        lines += ["", "## 🔧 Changes applied (--fix)"]
        for c in report["changes_applied"]:
            lines.append(f"- **{c['key']}**: `{c['from']}` → `{c['to']}` (files: {', '.join(c['files'])})")
    cs = report.get("cloudstream")
    if cs:
        lines += ["", "## ☁️ CloudStream app/toolchain"]
        for r in cs.get("repos", []):
            if r.get("error"):
                lines.append(f"- `{r['repo']}`: error — {r['error']}")
                continue
            bit = f"- `{r['repo']}`"
            if r.get("latest_release"):
                lr = r["latest_release"]
                bit += f": latest release **{lr['tag']}** ({lr.get('published_at') or 'unknown'})"
            elif r.get("latest_tag"):
                bit += f": latest tag **{r['latest_tag']}**"
            bit += f" — last push {r.get('pushed_at') or 'unknown'}"
            lines.append(bit)
        lines.append(f"- ℹ️ {cs.get('note', '')}")
    healthy = "🟢 all providers healthy" if report["healthy"] else "🔴 at least one provider needs attention"
    lines += ["", f"---", f"**Overall:** {healthy}"]
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    help="repository root (default: parent of scripts/)")
    ap.add_argument("--config", default=None,
                    help="provider config JSON (default: <root>/scripts/site_providers.json)")
    ap.add_argument("--domains", default=None, help="domains.json path (default: <root>/domains.json)")
    ap.add_argument("--out", default=None, help="write the JSON report to this file")
    ap.add_argument("--md-out", default=None, help="write a Markdown summary to this file")
    ap.add_argument("--cloudstream", action="store_true", help="also check CloudStream 3 app repos")
    ap.add_argument("--fix", action="store_true", help="rewrite sources + domains.json for domain changes")
    ap.add_argument("--timeout", type=float, default=15.0, help="per-URL timeout in seconds (default 15)")
    ap.add_argument("--min-length", type=int, default=1000,
                    help="minimum page bytes for a hit to count as the real site (default 1000)")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    config_path = args.config or os.path.join(root, "scripts", "site_providers.json")
    domains_path = args.domains or os.path.join(root, "domains.json")

    try:
        with open(config_path, "r", encoding="utf-8") as f:
            config = json.load(f)
        providers = [p for p in config.get("providers", []) if "key" in p]
    except Exception as e:
        log(f"FATAL: cannot load provider config {config_path}: {e}")
        return 2

    try:
        with open(domains_path, "r", encoding="utf-8") as f:
            domains = json.load(f)
    except Exception as e:
        log(f"FATAL: cannot load domains file {domains_path}: {e}")
        return 2

    if not providers:
        log("FATAL: no providers configured")
        return 2

    log(f"probing {len(providers)} providers ...")
    entries = [analyze_provider(p, domains, root, args.timeout, args.min_length) for p in providers]

    changes = []
    if args.fix:
        for p, e in zip(providers, entries):
            changes.extend(apply_fix(e, p, root))

    healthy = all(e["status"] == "ok" for e in entries)
    report = {
        "generated_at": _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
        "healthy": healthy,
        "changes_applied": changes,
        "providers": entries,
    }
    if args.cloudstream:
        log("checking CloudStream app repos ...")
        report["cloudstream"] = cloudstream_info(args.timeout)

    json_text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(json_text + "\n")
    else:
        print(json_text)
    if args.md_out:
        with open(args.md_out, "w", encoding="utf-8") as f:
            f.write(markdown_summary(report))

    for e in entries:
        log(f"{e['key']:<14} {e['status']:<14} {e.get('current') or '-'}"
            + (f" -> {e['proposed']}" if e.get("proposed") else ""))
    if changes:
        log(f"applied {len(changes)} domain change(s)")
    return 0 if healthy else 1


if __name__ == "__main__":
    sys.exit(main())
