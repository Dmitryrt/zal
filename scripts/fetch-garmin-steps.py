#!/usr/bin/env python3
"""Pull recent daily steps, distance and active calories from Garmin Personal API."""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = "https://personal.connectapi.garmin.com"
TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
STEPS_PATH = "/usersummary-service/stats/steps/daily/{start}/{end}"
SUMMARIES_PATH = "/usersummary-service/v2/dailysummaries"
OUT_FILE = os.path.join(os.path.dirname(__file__), "..", "garmin-steps.json")
MSK = timezone(timedelta(hours=3))
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
CALORIE_KEYS = (
    "burnedActive",
    "burned_active",
    "activeKilocalories",
    "activeCalories",
    "activeKcal",
)


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


def as_int(value: object, lo: int, hi: int) -> int | None:
    if value is None:
        return None
    try:
        count = int(round(float(value)))
    except (TypeError, ValueError):
        return None
    if count < lo or count > hi:
        return None
    return count


DISTANCE_KEYS = (
    "totalDistanceMeters",
    "totalDistanceInMeters",
    "distanceInMeters",
    "distanceMeters",
    "totalDistance",
    "total_distance",
    "distance",
)


def put_day(days: dict[str, int], key: object, steps: object) -> None:
    if not isinstance(key, str) or not DATE_RE.match(key):
        return
    count = as_int(steps, 0, 200000)
    if count is None:
        return
    days[key] = count


def step_rows(payload: object) -> list[object]:
    if isinstance(payload, list):
        return payload
    if not isinstance(payload, dict):
        return []
    values = payload.get("values")
    if isinstance(values, list):
        return values
    if isinstance(values, dict):
        rows = []
        for key, value in values.items():
            if isinstance(value, dict):
                rows.append({"calendarDate": key, **value})
            else:
                rows.append({"calendarDate": key, "totalSteps": value})
        return rows
    if "calendarDate" in payload or "calendar_date" in payload:
        return [payload]
    return []


def pick_distance_m(row: dict) -> int | None:
    blobs = [row]
    movement = row.get("movement")
    if isinstance(movement, dict):
        blobs.append(movement)
    for blob in blobs:
        for key in DISTANCE_KEYS:
            meters = as_int(blob.get(key), 0, 200000)
            if meters is not None:
                return meters
    return None


def parse_steps_and_distance(payload: object) -> tuple[dict[str, int], dict[str, int]]:
    steps: dict[str, int] = {}
    distance: dict[str, int] = {}
    for row in step_rows(payload):
        if not isinstance(row, dict):
            continue
        key = row.get("calendarDate") or row.get("calendar_date") or row.get("date")
        put_day(steps, key, row.get("totalSteps") or row.get("total_steps") or row.get("steps"))
        if not isinstance(key, str) or not DATE_RE.match(key):
            continue
        meters = pick_distance_m(row)
        if meters is not None:
            distance[key] = meters
    return steps, distance


def pick_calories(row: dict) -> int | None:
    calories = row.get("calories")
    if isinstance(calories, dict):
        for key in CALORIE_KEYS:
            count = as_int(calories.get(key), 0, 20000)
            if count is not None:
                return count
    for key in CALORIE_KEYS:
        count = as_int(row.get(key), 0, 20000)
        if count is not None:
            return count
    return None


def summary_rows(payload: object) -> list[object]:
    if isinstance(payload, list):
        return payload
    if not isinstance(payload, dict):
        return []
    for key in ("data", "dailySummaries", "summaries", "values"):
        value = payload.get(key)
        if isinstance(value, list):
            return value
    if "calendarDate" in payload or "calendar_date" in payload:
        return [payload]
    return []


def parse_calories(payload: object) -> tuple[dict[str, int], dict[str, int]]:
    days: dict[str, int] = {}
    distance: dict[str, int] = {}
    for row in summary_rows(payload):
        if not isinstance(row, dict):
            continue
        key = row.get("calendarDate") or row.get("calendar_date") or row.get("date")
        if not isinstance(key, str) or not DATE_RE.match(key):
            continue
        count = pick_calories(row)
        if count is not None:
            days[key] = count
        meters = pick_distance_m(row)
        if meters is not None:
            distance[key] = meters
    return days, distance


def fetch_steps(token: str, start: str, end: str) -> tuple[dict[str, int], dict[str, int]]:
    raw = request(
        "GET",
        BASE + STEPS_PATH.format(start=start, end=end),
        {"Accept": "application/json", "Authorization": f"Bearer {token}"},
    )
    return parse_steps_and_distance(json.loads(raw))


def fetch_calories(token: str, start: str, end: str) -> tuple[dict[str, int], dict[str, int]]:
    query = urllib.parse.urlencode({"startDate": start, "endDate": end})
    raw = request(
        "GET",
        f"{BASE}{SUMMARIES_PATH}?{query}",
        {"Accept": "application/json", "Authorization": f"Bearer {token}"},
    )
    return parse_calories(json.loads(raw))


def merge_days(
    steps: dict[str, int],
    calories: dict[str, int],
    distance: dict[str, int],
) -> dict[str, dict[str, int]]:
    days: dict[str, dict[str, int]] = {}
    for key, count in steps.items():
        days[key] = {"steps": count}
    for key, count in calories.items():
        days.setdefault(key, {})
        days[key]["activeCalories"] = count
    for key, count in distance.items():
        days.setdefault(key, {})
        days[key]["distanceM"] = count
    return dict(sorted(days.items()))


def main() -> None:
    api_key = (os.environ.get("GARMIN_PERSONAL_API_KEY") or "").strip()
    if not api_key:
        raise SystemExit("Set GARMIN_PERSONAL_API_KEY")

    today = datetime.now(MSK).date()
    token = bearer_token(api_key)
    steps: dict[str, int] = {}
    distance: dict[str, int] = {}
    last_error = None
    for span in (27, 20, 13):
        start = (today - timedelta(days=span)).isoformat()
        try:
            steps, distance = fetch_steps(token, start, today.isoformat())
            last_error = None
            break
        except SystemExit as error:
            last_error = error
    if last_error:
        raise last_error

    calories: dict[str, int] = {}
    try:
        start = min(steps) if steps else (today - timedelta(days=27)).isoformat()
        calories, extra_distance = fetch_calories(token, start, today.isoformat())
        for key, meters in extra_distance.items():
            distance.setdefault(key, meters)
    except SystemExit as error:
        print(f"Calories skipped: {error}", file=sys.stderr)

    payload = {
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "days": merge_days(steps, calories, distance),
    }
    path = os.path.abspath(OUT_FILE)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(
        f"Wrote {len(steps)} step days, {len(distance)} distance days and {len(calories)} calorie days to {path}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
