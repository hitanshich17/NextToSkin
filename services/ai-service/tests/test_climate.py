"""Climate from the city: thresholds, the Open-Meteo client and the Redis cache (ported from the Java tests)."""

import json

import httpx
import pytest

from app.recommender.climate import NOT_FOUND, ClimateService, OpenMeteoClient
from app.recommender.models import Climate, Signal

# ---- Thresholds, on real 30-day averages from Open-Meteo (docs/routine-rules.md, section 6) ----


@pytest.mark.parametrize("climate, signals", [
    (Climate("Miami", "Florida", 6.9, 23.6, 83.3, 6.2), [Signal.HUMID]),
    (Climate("Minneapolis", "Minnesota", 1.5, -14.4, 78.1, 7.0), [Signal.DRY_AIR]),  # cold air is dry
    (Climate("Phoenix", "Arizona", 6.9, 14.5, 38.4, 10.4), [Signal.DRY_AIR, Signal.POLLUTED]),
    (Climate("Seattle", "Washington", 4.2, 10.3, 78.7, 8.9), []),
    (Climate("Honolulu", "Hawaii", 8.0, 19.0, 70.0, None), [Signal.HIGH_UV, Signal.HUMID]),
    (Climate("Austin", "Texas", 7.9, 15.0, 60.0, 9.0), []),  # PM2.5 must be above 9
])
def test_signals(climate, signals):
    assert climate.signals() == signals


# ---- Open-Meteo client ----

PORTLANDS = {"results": [
    {"name": "Portland", "admin1": "Oregon", "latitude": 45.52, "longitude": -122.68},
    {"name": "Portland", "admin1": "Maine", "latitude": 43.66, "longitude": -70.26},
]}


def client(routes: dict) -> tuple[OpenMeteoClient, list]:
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=routes[request.url.host])

    http = httpx.Client(transport=httpx.MockTransport(handler))
    return OpenMeteoClient(http, "https://geo.test/search", "https://wx.test/forecast",
                           "https://air.test/air-quality"), seen


def test_a_city_alone_means_the_most_populous_one():
    c, seen = client({"geo.test": PORTLANDS})
    assert c.find("Portland")[1] == "Oregon"
    assert seen[0].url.params["countryCode"] == "US"


def test_a_state_abbreviation_picks_the_right_city():
    c, _ = client({"geo.test": PORTLANDS})
    assert c.find("Portland, ME")[1] == "Maine"
    assert c.find("portland, maine")[1] == "Maine"


def test_an_unknown_city_has_no_climate():
    c, _ = client({"geo.test": {}})
    assert c.climate("Atlantis") is None


def test_averages_the_last_30_days_skipping_missing_values():
    c, seen = client({
        "geo.test": PORTLANDS,
        "wx.test": {"daily": {"uv_index_max": [4.0, 5.0, None], "dew_point_2m_mean": [10.0, 11.0, None],
                              "relative_humidity_2m_mean": [70.0, 80.0, None]}},
        "air.test": {"hourly": {"pm2_5": [8.0, 9.0, 10.0, None]}},
    })
    assert c.climate("Portland") == Climate("Portland", "Oregon", 4.5, 10.5, 75.0, 9.0)
    assert seen[1].url.params["past_days"] == "30"


def test_averages_round_half_up_like_java():
    c, _ = client({
        "geo.test": PORTLANDS,
        "wx.test": {"daily": {"uv_index_max": [0.25, 0.0], "dew_point_2m_mean": [1.0],
                              "relative_humidity_2m_mean": [50.0]}},
        "air.test": {"hourly": {"pm2_5": [None]}},
    })
    climate = c.climate("Portland")
    assert climate.uv_index_max == 0.1 and climate.pm25 is None  # 0.125 -> 0.1 (Math.round(1.25) = 1)


# ---- Cache ----


class FakeRedis:
    def __init__(self, fail: bool = False):
        self.data, self.ttls, self.fail = {}, {}, fail

    def get(self, key):
        if self.fail:
            raise ConnectionError("redis down")
        return self.data.get(key)

    def set(self, key, value, ex=None):
        if self.fail:
            raise ConnectionError("redis down")
        self.data[key], self.ttls[key] = value, ex


class FakeOpenMeteo:
    def __init__(self, result=None, error=None):
        self.result, self.error, self.calls = result, error, []

    def climate(self, city):
        self.calls.append(city)
        if self.error:
            raise self.error
        return self.result


MIAMI = Climate("Miami", "Florida", 6.9, 23.6, 83.3, 6.2)


def test_a_cached_climate_skips_open_meteo():
    redis = FakeRedis()
    # Written by the Java service: the same key and JSON, so the cache is shared during the move.
    redis.data["climate:miami, fl"] = json.dumps({"city": "Miami", "state": "Florida", "uvIndexMax": 6.9,
                                                  "dewPointC": 23.6, "humidity": 83.3, "pm25": 6.2})
    open_meteo = FakeOpenMeteo()
    assert ClimateService(open_meteo, redis).climate_for("  Miami,  FL ") == MIAMI
    assert open_meteo.calls == []


def test_a_lookup_is_cached_for_a_day():
    redis = FakeRedis()
    ClimateService(FakeOpenMeteo(MIAMI), redis).climate_for("Miami")
    assert json.loads(redis.data["climate:miami"])["dewPointC"] == 23.6
    assert redis.ttls["climate:miami"] == 24 * 3600


def test_an_unknown_city_is_cached_too():
    redis = FakeRedis()
    assert ClimateService(FakeOpenMeteo(None), redis).climate_for("Atlantis") is None
    assert redis.data["climate:atlantis"] == NOT_FOUND


def test_open_meteo_down_means_no_climate_and_nothing_cached():
    redis = FakeRedis()
    assert ClimateService(FakeOpenMeteo(error=httpx.ConnectTimeout("timeout")), redis).climate_for("Miami") is None
    assert redis.data == {}


def test_redis_down_still_looks_up_the_climate():
    assert ClimateService(FakeOpenMeteo(MIAMI), FakeRedis(fail=True)).climate_for("Miami") == MIAMI


@pytest.mark.parametrize("city", [None, "", "   "])
def test_no_city_no_climate(city):
    assert ClimateService(FakeOpenMeteo(MIAMI), FakeRedis()).climate_for(city) is None
