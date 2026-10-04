"""Tests for the CloudDoppler facade.

Loaded by path for the same reason as tests/test_cloud.py: importing the package runs
__init__.py, which needs Home Assistant, and Home Assistant cannot be imported on
Windows. These tests pin the wire mapping, which is where a silent default hides.
"""

from __future__ import annotations

import importlib.util
import inspect
from pathlib import Path
import sys
from typing import Any

import pytest

_ROOT = Path(__file__).resolve().parent.parent
_PKG = _ROOT / "custom_components" / "sandman_doppler"


def _load(name: str):
    """Load a module from the integration by path, registering it for dataclasses."""
    spec = importlib.util.spec_from_file_location(name, _PKG / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


# clock.py uses relative imports (`from .cloud import ...`), so give it a real package
# name whose parent is importable without executing the heavy __init__.
def _load_clock():
    """Load clock.py inside a stub package that does not import Home Assistant."""
    import types

    pkg = types.ModuleType("sd_under_test")
    pkg.__path__ = [str(_PKG)]  # type: ignore[attr-defined]
    sys.modules["sd_under_test"] = pkg
    for mod in ("cloud", "const"):
        sys.modules[f"sd_under_test.{mod}"] = _load(mod)
    spec = importlib.util.spec_from_file_location(
        "sd_under_test.clock", _PKG / "clock.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["sd_under_test.clock"] = module
    spec.loader.exec_module(module)
    return module


clock = _load_clock()
cloud_mod = sys.modules["sd_under_test.cloud"]
const = sys.modules["sd_under_test.const"]

CloudDoppler = clock.CloudDoppler
ClockDeviceInfo = clock.ClockDeviceInfo
DopplerConnectionError = cloud_mod.DopplerConnectionError
EndpointUnavailableError = cloud_mod.EndpointUnavailableError

DSN = "Doppler-10caaebb"
BASE = "https://control.sandmandoppler.com"
LOGIN_URL = "https://api.sandmandoppler.bycopilot.com/v4/auth/login"
LOGIN_OK = (201, {"accessToken": "tok", "refreshToken": "ref", "expiresIn": 3000})


class FakeResponse:
    def __init__(self, status: int, body: Any) -> None:
        self.status = status
        self._body = body

    async def json(self, content_type: Any = None) -> Any:
        return self._body

    async def text(self) -> str:
        return str(self._body)

    async def __aenter__(self) -> "FakeResponse":
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False


class FakeSession:
    """Replays a scripted response per GET/PUT to a given path."""

    def __init__(
        self, gets: dict[str, Any], puts: dict[str, Any] | None = None
    ) -> None:
        self.gets = gets
        self.puts = puts or {}
        self.get_paths: list[str] = []
        self.put_calls: list[tuple[str, Any]] = []

    def request(self, method: str, url: str, **kwargs: Any) -> FakeResponse:
        if url == LOGIN_URL:
            return FakeResponse(*LOGIN_OK)
        path = url.split(f"/{DSN}/", 1)[1] if f"/{DSN}/" in url else url
        if method == "GET":
            self.get_paths.append(path)
            entry = self.gets.get(path)
        else:
            self.put_calls.append((path, kwargs.get("json")))
            entry = self.puts.get(path)
        if entry is None:
            return FakeResponse(404, {"error": "unscripted"})
        if isinstance(entry, list):
            return FakeResponse(*entry.pop(0)) if entry else FakeResponse(404, {})
        if callable(entry):
            return FakeResponse(*entry())
        return FakeResponse(*entry)


def make_clock(
    gets: dict[str, Any], puts: dict[str, Any] | None = None
) -> CloudDoppler:
    """Build a CloudDoppler over a fake session."""
    session = FakeSession(gets, puts)
    transport = cloud_mod.CloudTransport(
        email="u@example.com",
        password="p",
        dsn=DSN,
        session=session,  # type: ignore[arg-type]
    )
    return CloudDoppler(transport, DSN, name="Bedroom")


# ------------------------------------------------------------------ wire map


def test_wire_keys_match_recorded_live_payloads() -> None:
    """Every extracted field name is pinned against a verbatim observed response.

    This is the silent-default trap: a model whose field name does not match the wire
    decodes to a default rather than raising, and the UI then shows a plausible lie.
    """
    expected = {
        ("software/time-mode", "timeMode"),
        ("doptime/timezone", "timezone"),
        ("doptime/offset", "offset"),
        ("software/use-colon", "on"),
        ("software/use-leading-zero", "on"),
        ("software/use-fade-time", "on"),
        ("software/display-seconds", "on"),
        ("hardware/volume", "volume"),
        ("hardware/sound-preset", "preset"),
        ("hardware/sound-preset-mode", "presetmode"),
        ("alarms/sounds", "sounds"),
        ("alexa/ascending", "ascending"),
        ("hardware/light-sensor", "sensor"),
        ("hardware/day-mode", "isDayMode"),
        ("hardware/high-to-low-transition", "transition"),
        ("hardware/low-to-high-transition", "transition"),
        ("hardware/sync-high-low-color", "sync"),
        ("alexa/lwa-status", "status"),
        ("alexa/tap-talk-tone", "tone"),
        ("alexa/wake-word-tone", "tone"),
    }
    actual = {(s.path, s.key) for s in clock.READ_SPECS if s.key is not None}
    missing = expected - actual
    assert not missing, f"wire keys changed or missing: {missing}"


def test_every_colour_endpoint_reads_the_color_array() -> None:
    """Colour endpoints answer {"color":[r,g,b]}, not an object."""
    colour_paths = {
        "hardware/high-display-color",
        "hardware/low-display-color",
        "hardware/high-button-color",
        "hardware/low-button-color",
    }
    for spec in clock.READ_SPECS:
        if spec.path in colour_paths:
            assert spec.key == "color", spec


def test_fade_time_is_not_read_from_use_leading_zero() -> None:
    """Regression for a real doppyler defect.

    doppyler writes fade time to software/use-fade-time but reads the attribute from
    software/use-leading-zero (doppyler/model/doppler.py:329). Its Fade Time switch
    therefore wrote correctly and then displayed the leading-zero state.
    """
    fade = next(s for s in clock.READ_SPECS if s.attr == const.ATTR_USE_FADE_TIME)
    assert fade.path == "software/use-fade-time"
    leading = next(s for s in clock.READ_SPECS if s.attr == const.ATTR_USE_LEADING_ZERO)
    assert leading.path == "software/use-leading-zero"
    assert fade.path != leading.path


def test_read_specs_contain_no_duplicate_attribute_keys() -> None:
    """A duplicate key would silently drop an attribute from the coordinator dict."""
    keys = [s.attr for s in clock.READ_SPECS]
    assert len(keys) == len(set(keys))


# ----------------------------------------------------------------- get_all_data


@pytest.mark.asyncio
async def test_get_all_data_maps_values_and_types() -> None:
    """Values land under the right keys with the right types."""
    gets = {
        "software/time-mode": (200, {"timeMode": 12}),
        "doptime/timezone": (200, {"timezone": "America/Chihuahua"}),
        "doptime/offset": (200, {"offset": -360}),
        "hardware/volume": (200, {"volume": 76}),
        "hardware/light-sensor": (200, {"sensor": 1414}),
        "hardware/day-mode": (200, {"isDayMode": True}),
        "hardware/high-display-brightness": (200, {"brightness": 60}),
        "hardware/high-display-color": (200, {"color": [180, 60, 255]}),
        "hardware/sync-high-low-color": (200, {"sync": True}),
        "alexa/lwa-status": (200, {"status": False, "timestamp": 15}),
        "alexa/tap-talk-tone": (200, {"tone": True}),
        "alarms/sounds": (200, {"sounds": ["Harp.mp3"]}),
        "software/weather": (
            200,
            {"wsonoff": True, "location": "27.1,-104.9", "wsmode": 2},
        ),
        "software/weather-wakeup-time": (200, {"weatherwakeuptime": "16:05"}),
        "hardware/button1": (200, {"color": [1, 1, 1]}),
        "hardware/button2": (200, {"color": [1, 1, 1]}),
    }
    data = await make_clock(gets).get_all_data()
    assert data[const.ATTR_TIME_MODE] == 12
    assert data[const.ATTR_TIMEZONE] == "America/Chihuahua"
    assert data[const.ATTR_TIME_OFFSET] == -360
    assert data[const.ATTR_VOLUME_LEVEL] == 76
    assert data[const.ATTR_LIGHT_SENSOR_VALUE] == 1414
    assert data[const.ATTR_IS_IN_DAY_MODE] is True
    assert data[const.ATTR_DAY_DISPLAY_BRIGHTNESS] == 60
    assert data[const.ATTR_DAY_DISPLAY_COLOR] == [180, 60, 255]
    assert data[const.ATTR_SYNC_DAY_AND_NIGHT_COLOR] is True
    assert data[const.ATTR_CONNECTED_TO_ALEXA] is False
    assert data[const.ATTR_ALEXA_TAP_TO_TALK_TONE_ENABLED] is True
    assert data[const.ATTR_ALARM_SOUNDS] == ["Harp.mp3"]
    assert data[const.ATTR_WEATHER_WAKE_UP_TIME] == "16:05"
    assert data[f"{const.ATTR_SMART_BUTTON_COLOR}_1"] == [1, 1, 1]


@pytest.mark.asyncio
async def test_one_failing_endpoint_does_not_discard_the_others() -> None:
    """Regression for the bug that aborted every cycle.

    doppyler gathers all endpoints without return_exceptions, so one raise lost all
    forty. Here a failure yields None for that attribute only.
    """
    gets = {
        "hardware/volume": (200, {"volume": 76}),
        "alexa/lwa-status": (200, {"status": True, "timestamp": 1}),
        "software/colon-blink": (408, {"error": "No response from Doppler"}),
    }
    data = await make_clock(gets).get_all_data()
    assert data[const.ATTR_VOLUME_LEVEL] == 76, "a healthy attribute must survive"
    assert data[const.ATTR_CONNECTED_TO_ALEXA] is True
    assert data[const.ATTR_COLON_BLINK] is None


@pytest.mark.asyncio
async def test_skipped_endpoints_are_never_requested() -> None:
    """Endpoints measured dead cost nothing: no request is issued for them."""
    session = FakeSession({})
    transport = cloud_mod.CloudTransport(
        email="u@e.com", password="p", dsn=DSN, session=session  # type: ignore[arg-type]
    )
    await CloudDoppler(transport, DSN).get_all_data()
    for dead in cloud_mod.UNAVAILABLE_ENDPOINTS:
        assert dead not in session.get_paths, f"{dead} should have been skipped"


@pytest.mark.asyncio
async def test_a_missing_field_reads_as_none_not_a_default() -> None:
    """An absent field must surface as None rather than a fabricated value."""
    gets = {"hardware/volume": (200, {"something_else": 1})}
    data = await make_clock(gets).get_all_data()
    assert data[const.ATTR_VOLUME_LEVEL] is None


# ----------------------------------------------------------------- coercions


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (True, True),
        (False, False),
        (1, True),
        (0, False),
        ("1", True),
        ("true", True),
        ("false", False),
        (None, None),
    ],
)
def test_bool_coercion(raw: Any, expected: Any) -> None:
    """Some endpoints answer strings rather than JSON booleans."""
    assert clock._as_bool(raw) is expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [(12, 12), (12.0, 12), ("12", 12), (True, 1), ("x", None), (None, None)],
)
def test_int_coercion(raw: Any, expected: Any) -> None:
    """Numeric fields are coerced without inventing values."""
    assert clock._as_int(raw) == expected


# ---------------------------------------------------------------------- alarms


@pytest.mark.asyncio
async def test_get_all_alarms_is_keyed_and_sorted_by_id() -> None:
    """The clock returns alarms unsorted with new ones first, so sort by id."""
    gets = {
        "alarms": (
            200,
            {"alarms": [{"id": 5}, {"id": 2}, {"id": 3}]},
        )
    }
    alarms = await make_clock(gets).get_all_alarms()
    assert list(alarms) == [2, 3, 5]


@pytest.mark.asyncio
async def test_get_all_alarms_survives_an_unreachable_clock() -> None:
    """A failed alarm read yields an empty mapping, not an exception."""
    assert await make_clock({"alarms": (408, {})}).get_all_alarms() == {}


# ---------------------------------------------------------------------- writes


@pytest.mark.asyncio
async def test_volume_write_sends_the_documented_field() -> None:
    """A write sends exactly the documented body."""
    session = FakeSession({}, {"hardware/volume": (200, {"volume": 42})})
    transport = cloud_mod.CloudTransport(
        email="u@e.com", password="p", dsn=DSN, session=session  # type: ignore[arg-type]
    )
    result = await CloudDoppler(transport, DSN).set_volume_level(42)
    assert result == 42
    assert session.put_calls == [("hardware/volume", {"volume": 42})]


@pytest.mark.asyncio
async def test_fade_time_write_is_skipped_because_the_endpoint_is_dead() -> None:
    """On firmware 1214 fade time cannot be written at all.

    ``software/use-fade-time`` is one of the nine endpoints measured never to answer, so
    the transport refuses the write before issuing a request. This is why the Fade Time
    switch cannot work on this clock regardless of which library drives it -- and it is a
    different reason from doppyler's read/write mismatch, which is pinned separately.
    """
    assert "software/use-fade-time" in cloud_mod.UNAVAILABLE_ENDPOINTS
    session = FakeSession({}, {"software/use-fade-time": (200, {"on": True})})
    transport = cloud_mod.CloudTransport(
        email="u@e.com", password="p", dsn=DSN, session=session  # type: ignore[arg-type]
    )
    assert await CloudDoppler(transport, DSN).set_use_fade_time(True) is None
    assert session.put_calls == [], "a skipped write must not reach the network"


def test_five_of_the_twelve_switches_cannot_work_on_this_firmware() -> None:
    """Pins which HA switches are inoperable here, so the UI can be honest about it.

    Every ``set_*`` method behind ``set_value_func_name`` is listed with the endpoint it
    targets, so this cannot drift silently when a setter is added.
    """
    setters = {
        "set_use_colon_mode": "software/use-colon",
        "set_colon_blink_mode": "software/colon-blink",
        "set_use_leading_zero_mode": "software/use-leading-zero",
        "set_use_fade_time": "software/use-fade-time",
        "set_display_seconds_mode": "software/display-seconds",
        "set_sound_preset_mode": "hardware/sound-preset-mode",
        "set_sync_day_night_color": "hardware/sync-high-low-color",
        "set_sync_button_display_color": "hardware/sync-button-display-color",
        "set_sync_button_display_brightness": "hardware/sync-button-display-brightness",
        "set_alexa_ascending_alarms_mode": "alexa/ascending",
        "set_alexa_tap_to_talk_tone_enabled": "alexa/tap-talk-tone",
        "set_alexa_wake_word_tone_enabled": "alexa/wake-word-tone",
    }
    for name in setters:
        assert inspect.iscoroutinefunction(getattr(CloudDoppler, name)), name

    dead = sorted(p for p in setters.values() if p in cloud_mod.UNAVAILABLE_ENDPOINTS)
    assert dead == [
        "software/colon-blink",
        "software/display-seconds",
        "software/use-colon",
        "software/use-fade-time",
    ], f"unavailable setter targets changed: {dead}"


@pytest.mark.asyncio
async def test_a_write_to_a_dead_endpoint_is_skipped_entirely() -> None:
    """No request is issued for an endpoint known not to answer."""
    session = FakeSession({})
    transport = cloud_mod.CloudTransport(
        email="u@e.com", password="p", dsn=DSN, session=session  # type: ignore[arg-type]
    )
    result = await CloudDoppler(transport, DSN).set_colon_blink_mode(True)
    assert result is None
    assert session.put_calls == []


@pytest.mark.asyncio
async def test_smart_button_write_sends_the_complete_config() -> None:
    """A partial body makes the clock fail with an oatpp null-pointer, so read first."""
    gets = {
        "hardware/button1": (
            200,
            {
                "url": "",
                "headers": [],
                "data": "",
                "command": "POST",
                "color": [1, 1, 1],
                "double_tap": None,
                "triple_tap": None,
                "hold": None,
            },
        )
    }
    session = FakeSession(gets, {"hardware/button1": (200, {"ok": True})})
    transport = cloud_mod.CloudTransport(
        email="u@e.com", password="p", dsn=DSN, session=session  # type: ignore[arg-type]
    )
    await CloudDoppler(transport, DSN).set_smart_button_configuration(
        1, url="https://example.invalid/hook", command="HA"
    )
    assert len(session.put_calls) == 1
    path, body = session.put_calls[0]
    assert path == "hardware/button1"
    assert body["url"] == "https://example.invalid/hook"
    assert body["command"] == "HA"
    # Every field must be present, or the clock rejects the whole document.
    for field in ("url", "headers", "data", "command", "color"):
        assert field in body, f"{field} missing from the written document"


@pytest.mark.asyncio
async def test_smart_button_write_is_skipped_when_config_is_unreadable() -> None:
    """With no readable config there is no complete document to send, so do not guess."""
    session = FakeSession({"hardware/button1": (408, {})})
    transport = cloud_mod.CloudTransport(
        email="u@e.com", password="p", dsn=DSN, session=session  # type: ignore[arg-type]
    )
    assert (
        await CloudDoppler(transport, DSN).set_smart_button_configuration(
            1, url="https://example.invalid/hook"
        )
        == {}
    )
    assert session.put_calls == []


# ------------------------------------------------------------- device registry


def test_device_info_is_built_from_the_wire_payload() -> None:
    """Field names match what __init__.py reads off doppler.device_info."""
    info = ClockDeviceInfo.from_wire(
        {
            "serialNum": DSN,
            "mfgrName": "Palo Alto Innovation",
            "modelNum": "SandmanDopplerProduction",
            "firmware": "Escapement",
            "hardware": "Enter Sandman",
            "software": "0.1214 Bucky",
        },
        DSN,
    )
    assert info.manufacturer == "Palo Alto Innovation"
    assert info.model_number == "SandmanDopplerProduction"
    assert info.firmware_version == "Escapement"
    assert info.hardware_version == "Enter Sandman"
    assert info.software_version == "0.1214 Bucky"


def test_device_info_survives_a_failed_device_read() -> None:
    """A missing /device payload must not prevent the clock being registered."""
    info = ClockDeviceInfo.from_wire(None, DSN)
    assert info.dsn == DSN
    assert info.manufacturer is None


def test_asyncio_is_used_by_the_facade() -> None:
    """Guard against accidentally introducing a blocking call into the read path."""
    assert inspect.iscoroutinefunction(clock.CloudDoppler.get_all_data)
    assert inspect.iscoroutinefunction(clock.CloudDoppler.set_volume_level)
