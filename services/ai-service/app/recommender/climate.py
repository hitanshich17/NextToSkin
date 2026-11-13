"""A city's climate from Open-Meteo, cached in Redis for a day per city (docs/routine-rules.md, section 6).

Ported from the Java catalog service (OpenMeteoClient, ClimateService). The cache keys and stored JSON are the same
as the Java service's, so both read one cache while the move is in progress.

Climate only fine-tunes a routine, so any failure (Open-Meteo or Redis down) gives "no climate" instead of an error.
"""

from __future__ import annotations

import json
import logging
import math
import re

import httpx

from app.recommender.models import Climate

log = logging.getLogger(__name__)

DAYS = 30
NOT_FOUND = "not-found"  # cached too, so repeated typos don't call Open-Meteo again

# US state abbreviations, so "Austin, TX" can be matched against Open-Meteo's "Texas".
US_STATES = {
    "AL": "Alabama",
    "AK": "Alaska",
    "AZ": "Arizona",
    "AR": "Arkansas",
    "CA": "California",
    "CO": "Colorado",
    "CT": "Connecticut",
    "DE": "Delaware",
    "DC": "District of Columbia",
    "FL": "Florida",
    "GA": "Georgia",
    "HI": "Hawaii",
    "ID": "Idaho",
    "IL": "Illinois",
    "IN": "Indiana",
    "IA": "Iowa",
    "KS": "Kansas",
    "KY": "Kentucky",
    "LA": "Louisiana",
    "ME": "Maine",
    "MD": "Maryland",
    "MA": "Massachusetts",
    "MI": "Michigan",
    "MN": "Minnesota",
    "MS": "Mississippi",
    "MO": "Missouri",
    "MT": "Montana",
    "NE": "Nebraska",
    "NV": "Nevada",
    "NH": "New Hampshire",
    "NJ": "New Jersey",
    "NM": "New Mexico",
    "NY": "New York",
    "NC": "North Carolina",
    "ND": "North Dakota",
    "OH": "Ohio",
    "OK": "Oklahoma",
    "OR": "Oregon",
    "PA": "Pennsylvania",
    "RI": "Rhode Island",
    "SC": "South Carolina",
    "SD": "South Dakota",
    "TN": "Tennessee",
    "TX": "Texas",
    "UT": "Utah",
    "VT": "Vermont",
    "VA": "Virginia",
    "WA": "Washington",
    "WV": "West Virginia",
    "WI": "Wisconsin",
    "WY": "Wyoming",
    "PR": "Puerto Rico",
}


class OpenMeteoClient:
    """Reads a US city's last 30 days of weather and air quality (free, no API key, non-commercial use; data
    CC BY 4.0, so the results page credits Open-Meteo)."""

    def __init__(self, http: httpx.Client, geocoding_url: str, forecast_url: str, air_quality_url: str):
        self._http = http
        self._geocoding_url = geocoding_url
        self._forecast_url = forecast_url
        self._air_quality_url = air_quality_url

    def climate(self, city_and_state: str) -> Climate | None:
        """The climate for "Austin", "Austin, TX" or "Austin, Texas", or None if the city isn't found in the US."""
        place = self.find(city_and_state)
        return None if place is None else self._climate(*place)

    def find(self, city_and_state: str) -> tuple[str, str, float, float] | None:
        city, _, state = (part.strip() for part in city_and_state.partition(","))
        state = US_STATES.get(state.upper(), state) if state else None
        results = self._get(self._geocoding_url, {"name": city, "count": 10, "countryCode": "US",
                                                   "language": "en"}).get("results", [])
        # Results come most populous first, so "Portland" alone means Portland, Oregon.
        for r in results:
            if state is None or state.lower() == str(r.get("admin1", "")).lower():
                return r.get("name", ""), r.get("admin1", ""), float(r["latitude"]), float(r["longitude"])
        return None

    def _climate(self, name: str, state: str, lat: float, lon: float) -> Climate:
        at = {"latitude": lat, "longitude": lon, "past_days": DAYS, "forecast_days": 1}
        daily = self._get(self._forecast_url, {**at, "daily": "uv_index_max,dew_point_2m_mean,relative_humidity_2m_mean",
                                                "timezone": "auto"}).get("daily", {})
        hourly = self._get(self._air_quality_url, {**at, "hourly": "pm2_5"}).get("hourly", {})
        uv, dew, humidity = (_average(daily.get(k)) for k in ("uv_index_max", "dew_point_2m_mean",
                                                                "relative_humidity_2m_mean"))
        if uv is None or dew is None or humidity is None:
            raise ValueError("Open-Meteo returned no weather")
        return Climate(name, state, uv, dew, humidity, _average(hourly.get("pm2_5")))

    def _get(self, url: str, params: dict) -> dict:
        response = self._http.get(url, params=params)
        response.raise_for_status()
        return response.json()


def _average(values) -> float | None:
    """Average of the numbers, rounded half up to one decimal (like Java); the newest days can still be null."""
    numbers = [v for v in values or [] if isinstance(v, (int, float)) and not isinstance(v, bool)]
    if not numbers:
        return None
    return math.floor(sum(numbers) / len(numbers) * 10 + 0.5) / 10


class ClimateService:
    """A city's climate, cached in Redis for a day: many quiz takers share a city, and 30-day averages barely move
    within a day, which keeps us far below Open-Meteo's free-tier limits."""

    def __init__(self, open_meteo: OpenMeteoClient, redis, cache_seconds: int = 24 * 3600):
        self._open_meteo = open_meteo
        self._redis = redis
        self._cache_seconds = cache_seconds

    def climate_for(self, city: str | None) -> Climate | None:
        if not city or not city.strip():
            return None
        key = "climate:" + re.sub(r"\s+", " ", city.strip().lower())
        cached = self._read(key)
        if cached is not None:
            return None if cached == NOT_FOUND else _parse(cached)
        try:
            climate = self._open_meteo.climate(city)
        except Exception as e:  # noqa: BLE001 - any failure means "no climate", never a broken routine
            log.warning("Climate lookup failed for %r: %s", city, e)
            return None  # not cached: try again next time
        self._write(key, NOT_FOUND if climate is None else _to_json(climate))
        return climate

    def _read(self, key: str) -> str | None:
        try:
            value = self._redis.get(key)
            return value.decode() if isinstance(value, bytes) else value
        except Exception as e:  # noqa: BLE001
            log.warning("Climate cache unavailable: %s", e)
            return None

    def _write(self, key: str, value: str) -> None:
        try:
            self._redis.set(key, value, ex=self._cache_seconds)
        except Exception as e:  # noqa: BLE001
            log.warning("Climate cache unavailable: %s", e)


# The same JSON the Java service stores, so the cache is shared during the move.
def _to_json(c: Climate) -> str:
    return json.dumps({"city": c.city, "state": c.state, "uvIndexMax": c.uv_index_max, "dewPointC": c.dew_point_c,
                       "humidity": c.humidity, "pm25": c.pm25})


def _parse(cached: str) -> Climate | None:
    try:
        d = json.loads(cached)
        return Climate(d["city"], d["state"], d["uvIndexMax"], d["dewPointC"], d["humidity"], d.get("pm25"))
    except (ValueError, KeyError):
        return None
