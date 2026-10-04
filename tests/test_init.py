"""Home Assistant level tests for the integration's setup path.

These are the tests that verify __init__.py itself, which cannot be exercised from the
transport tests because those deliberately never import Home Assistant.

They only run where the Home Assistant harness is usable. The harness imports
``homeassistant.runner``, which imports the Unix-only ``fcntl``, so this file is skipped
on Windows and runs under WSL.

The behaviour being pinned is the one that made this integration useless on a LAN-less
clock: doppyler dropped the device when ``localkey`` failed, so Home Assistant never
registered the clock and no entity could update. Here it must register anyway.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import patch

import pytest

# This file exercises Home Assistant itself, which cannot be imported on Windows:
# homeassistant.runner imports the Unix-only fcntl. The harness is installed under WSL,
# so these tests run there and skip cleanly elsewhere.
pytest.importorskip(
    "homeassistant", reason="Home Assistant is not importable on this platform"
)
pytest.importorskip(
    "pytest_homeassistant_custom_component", reason="HA test harness not installed"
)

# These follow importorskip on purpose: the skip has to happen before Home Assistant is
# imported, which necessarily puts the imports below other statements.
from homeassistant.const import CONF_EMAIL, CONF_PASSWORD  # noqa: E402
from homeassistant.core import HomeAssistant  # noqa: E402
from homeassistant.helpers import device_registry as dr  # noqa: E402

from pytest_homeassistant_custom_component.common import (  # noqa: E402
    MockConfigEntry,
)

from custom_components.sandman_doppler.const import (  # noqa: E402
    ATTR_COLON_BLINK,
    ATTR_CONNECTED_TO_ALEXA,
    ATTR_IS_IN_DAY_MODE,
    ATTR_TIME_MODE,
    ATTR_VOLUME_LEVEL,
    ATTR_WIFI,
    DOMAIN,
)

DSN = "Doppler-10caaebb"
THINGS = "https://api.sandmandoppler.bycopilot.com/v4/things"
LOGIN_URL = "https://api.sandmandoppler.bycopilot.com/v4/auth/login"
LOGIN_OK = (201, {"accessToken": "tok", "refreshToken": "ref", "expiresIn": 3000})

# `hass_client_no_auth` brings up the http component, which async_setup needs in order
# to register the smart button webhook view.
pytestmark = pytest.mark.usefixtures(
    "enable_custom_integrations", "hass_client_no_auth"
)


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
    """Serves the recorded live responses for this clock."""

    def __init__(self, localkey_status: int = 408) -> None:
        self.localkey_status = localkey_status
        self.gets: list[str] = []
        self.puts: list[str] = []

    def request(self, method: str, url: str, **kwargs: Any) -> FakeResponse:
        if url == LOGIN_URL:
            return FakeResponse(*LOGIN_OK)
        if method != "GET":
            self.puts.append(url)
            return FakeResponse(200, {})
        path = url.split(f"/{DSN}/", 1)[1] if f"/{DSN}/" in url else url
        self.gets.append(path)
        if url == THINGS:
            return FakeResponse(
                200,
                {
                    "things": [
                        {"id": "uuid", "info": {"physicalId": DSN, "name": "Bedroom"}}
                    ]
                },
            )
        return FakeResponse(*self._body_for(path))

    def _body_for(self, path: str) -> tuple[int, Any]:
        table: dict[str, tuple[int, Any]] = {
            "device": (
                200,
                {
                    "serialNum": DSN,
                    "mfgrName": "Palo Alto Innovation",
                    "modelNum": "SandmanDopplerProduction",
                    "firmware": "Escapement",
                    "hardware": "Enter Sandman",
                    "software": "0.1214 Bucky",
                },
            ),
            "localkey": (self.localkey_status, {"error": "No response from Doppler"}),
            "software/time-mode": (200, {"timeMode": 12}),
            "hardware/volume": (200, {"volume": 76}),
            "hardware/day-mode": (200, {"isDayMode": True}),
            "alexa/lwa-status": (200, {"status": False, "timestamp": 15}),
            "hardware/light-sensor": (200, {"sensor": 1414}),
            "doptime/timezone": (200, {"timezone": "America/Chihuahua"}),
            "doptime/offset": (200, {"offset": 0}),
            "alarms": (200, {"alarms": [{"id": 2}, {"id": 3}]}),
            "alarms/sounds": (200, {"sounds": ["Harp.mp3"]}),
            "hardware/button1": (200, {"color": [1, 1, 1]}),
            "hardware/button2": (200, {"color": [1, 1, 1]}),
        }
        # Anything else is a measured-dead path or an unscripted 404.
        return table.get(path, (408, {"error": "No response from Doppler"}))


def _entry(hass: HomeAssistant) -> MockConfigEntry:
    """Register a config entry for the account."""
    entry = MockConfigEntry(
        domain=DOMAIN,
        data={CONF_EMAIL: "owner@example.com", CONF_PASSWORD: "secret"},
        unique_id="owner@example.com",
        title="owner@example.com",
    )
    entry.add_to_hass(hass)
    return entry


async def _ensure_http(hass: HomeAssistant) -> None:
    """Bring up the http component so ``hass.http`` exists.

    ``async_setup`` registers the smart button webhook view, which needs ``hass.http``.
    The harness does not stand this up by default.
    """
    from homeassistant.setup import async_setup_component

    await async_setup_component(hass, "http", {})


async def _setup(hass: HomeAssistant, session: FakeSession | None = None):
    """Set the entry up through Home Assistant's own flow.

    Calling ``async_setup_entry`` directly skips the config entry state machine, and HA
    refuses to forward platform setup for an entry that is not yet LOADED. Driving it
    through ``hass.config_entries.async_setup`` exercises the real path a user's install
    takes.
    """
    session = session or FakeSession()
    entry = _entry(hass)
    await _ensure_http(hass)
    with patch(
        "custom_components.sandman_doppler.async_get_clientsession",
        return_value=session,
    ):
        assert await hass.config_entries.async_setup(entry.entry_id)
        await hass.async_block_till_done()
    return entry, session


async def test_a_lan_less_clock_is_registered(hass: HomeAssistant) -> None:
    """The headline regression: a clock with no LAN must still appear.

    ``localkey`` answers 408 here, which is the measured behaviour on this unit and the
    single reason the integration previously showed nothing at all.
    """
    await _setup(hass, FakeSession(localkey_status=408))
    registry = dr.async_get(hass)
    device = registry.async_get_device({(DOMAIN, DSN)})
    assert device is not None, "the clock was dropped because localkey failed"
    assert device.manufacturer == "Palo Alto Innovation"
    assert device.model == "SandmanDopplerProduction"
    assert device.sw_version == "0.1214 Bucky"
    assert device.hw_version == "Escapement"


async def test_coordinator_completes_and_populates_state(hass: HomeAssistant) -> None:
    """A full cycle must finish and produce real values.

    Under doppyler this never happened: the first dead endpoint aborted the gather, so
    every entity stayed unavailable.
    """
    entry, _ = await _setup(hass)
    coordinator = hass.data[DOMAIN][entry.entry_id][DSN]
    data = coordinator.data
    assert data, "the coordinator produced no data at all"
    assert data[ATTR_VOLUME_LEVEL] == 76
    assert data[ATTR_TIME_MODE] == 12
    assert data[ATTR_IS_IN_DAY_MODE] is True
    # Measured live: Alexa is not currently paired.
    assert data[ATTR_CONNECTED_TO_ALEXA] is False


async def test_dead_endpoints_do_not_abort_the_cycle(hass: HomeAssistant) -> None:
    """A failing endpoint costs its own attribute and nothing else."""
    entry, _ = await _setup(hass)
    coordinator = hass.data[DOMAIN][entry.entry_id][DSN]
    data = coordinator.data
    # These paths never answer on firmware 1214 and must read as None...
    assert data[ATTR_WIFI] is None
    assert data[ATTR_COLON_BLINK] is None
    # ...while the rest of the cycle survives, which is the actual fix.
    assert data[ATTR_VOLUME_LEVEL] == 76
    assert data[ATTR_CONNECTED_TO_ALEXA] is False


async def test_skipped_endpoints_are_never_requested(hass: HomeAssistant) -> None:
    """Measured-dead paths are skipped, not waited on.

    This is what brings a cycle back inside the 60s interval; polling all nine would cost
    roughly 90 seconds of pure timeout.
    """
    _, session = await _setup(hass)
    for dead in (
        "hardware/wifi-status",
        "software/use-colon",
        "software/colon-blink",
        "software/use-fade-time",
        "software/display-seconds",
        "software/use-rainbow-display",
        "hardware/button-last-message",
        "software/snooze-length",
        "software/use-snooze-display",
    ):
        assert dead not in session.gets, f"{dead} should have been skipped"


async def test_intermittent_endpoint_is_still_polled(hass: HomeAssistant) -> None:
    """software/use-leading-zero answers about two times in three, so it is polled.

    It belongs to the original "six dead reads" and was seeded as permanently
    unavailable, which switched off a feature that does work.
    """
    _, session = await _setup(hass)
    assert "software/use-leading-zero" in session.gets


async def test_bad_credentials_leave_the_entry_not_loaded(hass: HomeAssistant) -> None:
    """A rejected token must be reported as a setup failure, not as zero clocks."""
    from homeassistant.config_entries import ConfigEntryState

    class RejectingSession(FakeSession):
        def request(self, method: str, url: str, **kwargs: Any) -> FakeResponse:
            if url == LOGIN_URL:
                return FakeResponse(401, {"error": "nope"})
            return super().request(method, url, **kwargs)

    entry = _entry(hass)
    await _ensure_http(hass)
    with patch(
        "custom_components.sandman_doppler.async_get_clientsession",
        return_value=RejectingSession(),
    ):
        assert not await hass.config_entries.async_setup(entry.entry_id)
        await hass.async_block_till_done()
    assert entry.state is ConfigEntryState.SETUP_RETRY
    assert dr.async_get(hass).async_get_device({(DOMAIN, DSN)}) is None
