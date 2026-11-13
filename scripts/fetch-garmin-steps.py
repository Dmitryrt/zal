#!/usr/bin/env python3
"""Pull recent daily steps from Garmin Personal API into garmin-steps.json."""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = "https://personal.connectapi.garmin.com"
TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
STEPS_PATH = "/usersummary-service/stats/steps/daily/{start}/{end}"
OUT_FILE = os.path.join(os.path.dirname(__file__), "..", "garmin-steps.json")
MSK = timezone(timedelta(hours=3))
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def request(method: str, url: str, headers: dict[str, str]) -> str:
    req = urllib.request.Request(url, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=45) as res:
            return res.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        raise SystemExit(f"Garmin {error.code} {method} {url}: {detail}") from error


def bearer_token(api_key: str) -> str:
    body = request(
        "POST",
        BASE + TOKEN_PATH,
        {"Accept": "*/*", "Authorization": f"ApiKey {api_key}"},
    ).strip().strip('"')
    if body.startswith("{"):
        data = json.loads(body)
        token = data.get("access_token") or data.get("accessToken")
        if not token:
            raise SystemExit("Garmin token response had no access_token")
        return str(token)
    if "." not in body:
        raise SystemExit("Garmin token response was not a JWT")
    return body


def parse_steps(payload: object) -> dict[str, int]:
    rows: list[object] = []
    if isinstance(payload, list):
        rows = payload
    elif isinstance(payload, dict):
        values = payload.get("values")
        if isinstance(values, list):
            rows = values
        elif isinstance(values, dict):
            days = {}
            for key, value in values.items():
                steps = value if isinstance(value, (int, float)) else (
                    value.get("totalSteps") or value.get("total_steps") or value.get("steps")
                    if isinstance(value, dict) else None
                )
                put_day(days, key, steps)
            return days
        elif "calendarDate" in payload or "calendar_date" in payload:
            rows = [payload]
    days: dict[str, int] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        key = row.get("calendarDate") or row.get("calendar_date") or row.get("date")
        steps = row.get("totalSteps") or row.get("total_steps") or row.get("steps")
        put_day(days, key, steps)
    return days


def put_day(days: dict[str, int], key: object, steps: object) -> None:
    if not isinstance(key, str) or not DATE_RE.match(key):
        return
    if steps is None:
        return
    try:
        count = int(round(float(steps)))
    except (TypeError, ValueError):
        return
    if count < 0 or count > 200000:
        return
    days[key] = count


def fetch_range(token: str, start: str, end: str) -> dict[str, int]:
    raw = request(
        "GET",
        BASE + STEPS_PATH.format(start=start, end=end),
        {"Accept": "application/json", "Authorization": f"Bearer {token}"},
    )
    return parse_steps(json.loads(raw))


def main() -> None:
    api_key = (os.environ.get("GARMIN_PERSONAL_API_KEY") or "").strip()
    if not api_key:
        raise SystemExit("Set GARMIN_PERSONAL_API_KEY")

    today = datetime.now(MSK).date()
    token = bearer_token(api_key)
    days: dict[str, int] = {}
    last_error = None
    for span in (27, 20, 13):
        start = (today - timedelta(days=span)).isoformat()
        try:
            days = fetch_range(token, start, today.isoformat())
            last_error = None
            break
        except SystemExit as error:
            last_error = error
    if last_error:
        raise last_error

    payload = {
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "days": dict(sorted(days.items())),
    }
    path = os.path.abspath(OUT_FILE)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"Wrote {len(days)} days to {path}", file=sys.stderr)


if __name__ == "__main__":
    main()
