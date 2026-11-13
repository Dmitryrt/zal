#!/usr/bin/env python3
"""Pull recent daily steps, distance, calories and mapped workouts from Garmin."""

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
ACTIVITIES_PATH = "/activitylist-service/activities/search/activities"
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


KIND_BY_TYPE = {
    "cycling": "bike",
    "road_biking": "bike",
    "road_cycling": "bike",
    "mountain_biking": "bike",
    "downhill_mountain_biking": "bike",
    "enduro_mountain_biking": "bike",
    "e_enduro_mountain_biking": "bike",
    "e_biking": "bike",
    "e_mountain_biking": "bike",
    "gravel_cycling": "bike",
    "cyclocross": "bike",
    "bmx": "bike",
    "indoor_cycling": "bike",
    "virtual_ride": "bike",
    "virtual_cycling": "bike",
    "track_cycling": "bike",
    "recumbent_cycling": "bike",
    "handcycling": "bike",
    "indoor_handcycling": "bike",
    "motocross": "enduro",
    "lap_swimming": "pool",
    "pool_swim": "pool",
    "resort_snowboarding": "snowboard",
    "backcountry_snowboarding": "snowboard",
    "wakeboarding": "wakeboard",
    "strength_training": "gym",
    "stand_up_paddleboarding": "sup",
    "running": "run",
    "street_running": "run",
    "trail_running": "run",
    "treadmill_running": "run",
    "track_running": "run",
    "indoor_running": "run",
    "virtual_running": "run",
    "ultra_running": "run",
    "obstacle_course_racing": "run",
    "walking": "walk",
    "casual_walking": "walk",
    "speed_walking": "walk",
}
LAP_TYPES = {"snowboard", "wakeboard", "enduro", "pool"}


def activity_rows(payload: object) -> list[object]:
    if isinstance(payload, list):
        return payload
    if not isinstance(payload, dict):
        return []
    for key in ("activities", "activityList", "items"):
        value = payload.get(key)
        if isinstance(value, list):
            return value
    return []


def activity_type_key(row: dict) -> str:
    for field in ("activityType", "activityTypeDTO"):
        value = row.get(field)
        if isinstance(value, dict):
            key = value.get("typeKey") or value.get("type_key")
            if isinstance(key, str):
                return key
        elif isinstance(value, str):
            return value
    key = row.get("activityTypeKey") or row.get("typeKey")
    return key if isinstance(key, str) else ""


def activity_day(row: dict) -> str | None:
    local = row.get("startTimeLocal") or row.get("startTimeGMT") or ""
    if isinstance(local, str) and DATE_RE.match(local[:10]):
        return local[:10]
    return None


def activity_clock(row: dict) -> str | None:
    local = row.get("startTimeLocal") or ""
    if isinstance(local, str) and len(local) >= 16 and re.fullmatch(r"\d{2}:\d{2}", local[11:16]):
        return local[11:16]
    return None


def garmin_id(row: dict) -> str:
    value = row.get("activityId")
    if value is None:
        value = row.get("activity_id")
    if isinstance(value, bool) or value is None:
        return ""
    if isinstance(value, float):
        value = int(value)
    text = str(value).strip()
    if not text.isdigit():
        return ""
    return text


def as_float(value: object, lo: float, hi: float) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number < lo or number > hi:
        return None
    return round(number, 2)


def activity_stats(row: dict, type_id: str) -> dict[str, object]:
    stats: dict[str, object] = {}
    clock = activity_clock(row)
    if clock:
        stats["start"] = clock
    duration = as_int(row.get("duration") or row.get("movingDuration"), 1, 86400)
    if duration:
        stats["durationSec"] = duration
    meters = as_int(row.get("distance"), 1, 500000)
    if meters:
        stats["distanceM"] = meters
    calories = as_int(row.get("calories"), 1, 20000)
    if calories:
        stats["calories"] = calories
    avg_hr = as_int(row.get("averageHR"), 30, 230)
    if avg_hr:
        stats["avgHr"] = avg_hr
    max_hr = as_int(row.get("maxHR"), 30, 230)
    if max_hr:
        stats["maxHr"] = max_hr
    avg_speed = as_float(row.get("averageSpeed"), 0.1, 120)
    if avg_speed:
        stats["avgSpeed"] = avg_speed
    max_speed = as_float(row.get("maxSpeed"), 0.1, 120)
    if max_speed:
        stats["maxSpeed"] = max_speed
    gain = as_int(row.get("elevationGain"), 1, 20000)
    if gain:
        stats["elevationGainM"] = gain
    loss = as_int(row.get("elevationLoss"), 1, 20000)
    if loss:
        stats["elevationLossM"] = loss
    if type_id in LAP_TYPES:
        laps = as_int(row.get("lapCount"), 1, 500)
        if laps:
            stats["laps"] = laps
    return stats


def compact_activities(payload: object) -> list[dict[str, object]]:
    seen: set[str] = set()
    activities: list[dict[str, object]] = []
    for row in activity_rows(payload):
        if not isinstance(row, dict):
            continue
        type_id = KIND_BY_TYPE.get(activity_type_key(row))
        day = activity_day(row)
        activity_id = garmin_id(row)
        if not type_id or not day or not activity_id or activity_id in seen:
            continue
        seen.add(activity_id)
        item: dict[str, object] = {
            "garminId": activity_id,
            "date": day,
            "typeId": type_id,
        }
        stats = activity_stats(row, type_id)
        if stats:
            item["stats"] = stats
        activities.append(item)
    activities.sort(key=lambda item: (str(item["date"]), str(item["garminId"])))
    return activities


def fetch_activities(token: str, start: str, end: str) -> list[dict[str, object]]:
    rows: list[object] = []
    start_index = 0
    limit = 20
    while start_index < 200:
        query = urllib.parse.urlencode({
            "start": start_index,
            "limit": limit,
            "startDate": start,
            "endDate": end,
        })
        raw = request(
            "GET",
            f"{BASE}{ACTIVITIES_PATH}?{query}",
            {"Accept": "application/json", "Authorization": f"Bearer {token}"},
        )
        page = activity_rows(json.loads(raw))
        rows.extend(page)
        if len(page) < limit:
            break
        start_index += limit
    return compact_activities(rows)


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

    activities: list[dict[str, object]] = []
    try:
        start = min(steps) if steps else (today - timedelta(days=27)).isoformat()
        activities = fetch_activities(token, start, today.isoformat())
    except SystemExit as error:
        print(f"Workouts skipped: {error}", file=sys.stderr)

    payload = {
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "days": merge_days(steps, calories, distance),
        "activities": activities,
    }
    path = os.path.abspath(OUT_FILE)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(
        f"Wrote {len(steps)} step days, {len(distance)} distance days, {len(calories)} calorie days and {len(activities)} workouts to {path}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
