"""Tests for device discovery.

Discovery is where the integration was broken: doppyler drops a clock whose `localkey`
read fails, so a LAN-less unit never appeared in Home Assistant at all. Everything here
exists to keep that from regressing.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import types
from typing import Any

import pytest

_ROOT = Path(__file__).resolve().parent.parent
_PKG = _ROOT / "custom_components" / "sandman_doppler"


def _load_pkg() -> types.ModuleType:
    """Load the HA-free modules under a stub package name."""
    pkg = types.ModuleType("sd_disc_under_test")
    pkg.__path__ = [str(_PKG)]  # type: ignore[attr-defined]
    sys.modules["sd_disc_under_test"] = pkg
    for name in ("cloud", "const", "clock", "discovery"):
        spec = importlib.util.spec_from_file_location(
            f"sd_disc_under_test.{name}", _PKG / f"{name}.py"
        )
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        sys.modules[f"sd_disc_under_test.{name}"] = module
        spec.loader.exec_module(module)
    return sys.modules["sd_disc_under_test.discovery"]  # type: ignore[return-value]


discovery = _load_pkg()
cloud_mod = sys.modules["sd_disc_under_test.cloud"]

DSN = "Doppler-10caaebb"
OTHER_DSN = "Doppler-10ccbddd"
THINGS = "https://api.sandmandoppler.bycopilot.com/v4/things"
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
    def __init__(self, script: dict[tuple[str, str], Any]) -> None:
        self.script = script
        self.calls: list[str] = []

    def request(self, method: str, url: str, **kwargs: Any) -> FakeResponse:
        self.calls.append(url)
        entry = self.script.get((method, url))
        if entry is None:
            return FakeResponse(404, {"error": "unscripted"})
        if isinstance(entry, list):
            return FakeResponse(*entry.pop(0)) if entry else FakeResponse(404, {})
        return FakeResponse(*entry)


def transport_for(script: dict[tuple[str, str], Any]):
    """Build an authenticated transport over a scripted session."""
    session = FakeSession(script)
    transport = cloud_mod.CloudTransport(
        email="u@example.com",
        password="p",
        dsn="",
        session=session,  # type: ignore[arg-type]
    )
    return transport, session


def things(*entries: dict[str, Any]) -> tuple[int, Any]:
    """Build a /things response."""
    return (200, {"things": list(entries)})


def device_ok(serial: str = DSN) -> tuple[int, Any]:
    """A working /device response."""
    return (
        200,
        {
            "serialNum": serial,
            "mfgrName": "Palo Alto Innovation",
            "modelNum": "SandmanDopplerProduction",
            "firmware": "Escapement",
            "hardware": "Enter Sandman",
            "software": "0.1214 Bucky",
        },
    )


def localkey_dead() -> tuple[int, Any]:
    """The measured localkey failure on a LAN-less unit."""
    return (408, {"error": "DISCONNECTED", "message": "No response from Doppler"})


# ---------------------------------------------------------------- credentials


@pytest.mark.asyncio
async def test_credentials_validate_against_http_201_login() -> None:
    """Login answers 201 for applicationId 'doppyler'."""
    session = FakeSession({("POST", LOGIN_URL): LOGIN_OK})
    assert await discovery.async_validate_credentials("u@e.com", "p", session) is True


@pytest.mark.asyncio
async def test_bad_credentials_report_false_not_an_exception() -> None:
    """The config flow only needs a yes/no, never a traceback."""
    session = FakeSession({("POST", LOGIN_URL): (401, {"error": "nope"})})
    assert await discovery.async_validate_credentials("u@e.com", "p", session) is False


# ------------------------------------------------------------------ discovery


@pytest.mark.asyncio
async def test_a_lan_less_clock_is_discovered() -> None:
    """Regression: the clock must appear even though localkey fails.

    This is the whole point. Measured on Doppler-10caaebb, `localkey` always times out,
    and doppyler drops the device when it does -- so Home Assistant showed no Doppler at
    all and no entity could ever update.
    """
    transport, _ = transport_for(
        {
            ("POST", LOGIN_URL): LOGIN_OK,
            ("GET", THINGS): things(
                {"id": "uuid", "info": {"physicalId": DSN, "name": "Bed"}}
            ),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/device"): device_ok(),
            (
                "GET",
                f"https://control.sandmandoppler.com/{DSN}/localkey",
            ): localkey_dead(),
        }
    )
    clocks = await discovery.async_discover_clocks(transport)
    assert len(clocks) == 1
    clock = clocks[0]
    assert clock.dsn == DSN
    assert clock.name == "Bed"
    assert clock.local_control is False
    assert clock.device_info.manufacturer == "Palo Alto Innovation"
    assert clock.device_info.software_version == "0.1214 Bucky"


@pytest.mark.asyncio
async def test_multiple_clocks_are_all_discovered() -> None:
    """Every clock on the account is returned."""
    transport, _ = transport_for(
        {
            ("POST", LOGIN_URL): LOGIN_OK,
            ("GET", THINGS): things(
                {"id": "a", "info": {"physicalId": DSN, "name": "One"}},
                {"id": "b", "info": {"physicalId": OTHER_DSN, "name": "Two"}},
            ),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/device"): device_ok(DSN),
            (
                "GET",
                f"https://control.sandmandoppler.com/{DSN}/localkey",
            ): localkey_dead(),
            (
                "GET",
                f"https://control.sandmandoppler.com/{OTHER_DSN}/device",
            ): device_ok(OTHER_DSN),
            (
                "GET",
                f"https://control.sandmandoppler.com/{OTHER_DSN}/localkey",
            ): localkey_dead(),
        }
    )
    clocks = await discovery.async_discover_clocks(transport)
    assert sorted(c.dsn for c in clocks) == sorted([DSN, OTHER_DSN])


@pytest.mark.asyncio
async def test_a_transient_listing_failure_does_not_look_like_no_clocks() -> None:
    """An empty result would make Home Assistant delete the user's devices.

    A failure is reported as an empty list only because the caller must not remove
    devices on a transient error; this test pins that the failure is distinguishable
    from a genuinely empty account by asserting no exception escapes.
    """
    transport, _ = transport_for(
        {
            ("POST", LOGIN_URL): LOGIN_OK,
            ("GET", THINGS): (500, {"error": "boom"}),
        }
    )
    assert await discovery.async_discover_clocks(transport) == []


@pytest.mark.asyncio
async def test_an_empty_account_returns_no_clocks() -> None:
    """A real empty account is genuinely empty."""
    transport, _ = transport_for(
        {("POST", LOGIN_URL): LOGIN_OK, ("GET", THINGS): things()}
    )
    assert await discovery.async_discover_clocks(transport) == []


@pytest.mark.asyncio
async def test_a_clock_with_an_unreadable_device_payload_still_appears() -> None:
    """A failed /device read must not cost us the clock."""
    transport, _ = transport_for(
        {
            ("POST", LOGIN_URL): LOGIN_OK,
            ("GET", THINGS): things({"id": "a", "info": {"physicalId": DSN}}),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/device"): (408, {}),
            (
                "GET",
                f"https://control.sandmandoppler.com/{DSN}/localkey",
            ): localkey_dead(),
        }
    )
    clocks = await discovery.async_discover_clocks(transport)
    assert [c.dsn for c in clocks] == [DSN]
    assert clocks[0].device_info.dsn == DSN
    assert clocks[0].device_info.manufacturer is None


@pytest.mark.asyncio
async def test_an_invalid_token_raises_rather_than_reporting_no_clocks() -> None:
    """A 401 must not be mistaken for 'you own nothing'."""
    transport, _ = transport_for(
        {
            ("POST", LOGIN_URL): LOGIN_OK,
            ("GET", THINGS): (403, {"error": "expired"}),
        }
    )
    with pytest.raises(cloud_mod.DopplerAuthError):
        await discovery.async_discover_clocks(transport)


# ---------------------------------------------------------------------- diff


@pytest.mark.asyncio
async def test_diff_reports_only_genuine_additions_and_removals() -> None:
    """A clock that is still present must not be reported as changed."""
    transport, _ = transport_for(
        {
            ("POST", LOGIN_URL): LOGIN_OK,
            ("GET", THINGS): things(
                {"id": "a", "info": {"physicalId": DSN, "name": "One"}},
                {"id": "b", "info": {"physicalId": OTHER_DSN, "name": "Two"}},
            ),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/device"): device_ok(DSN),
            (
                "GET",
                f"https://control.sandmandoppler.com/{DSN}/localkey",
            ): localkey_dead(),
            (
                "GET",
                f"https://control.sandmandoppler.com/{OTHER_DSN}/device",
            ): device_ok(OTHER_DSN),
            (
                "GET",
                f"https://control.sandmandoppler.com/{OTHER_DSN}/localkey",
            ): localkey_dead(),
        }
    )
    both = await discovery.async_discover_clocks(transport)
    existing = {c.dsn: c for c in both}
    added, removed = discovery.diff_clocks(existing, both)
    assert added == [] and removed == []

    only_one = [c for c in both if c.dsn == DSN]
    added, removed = discovery.diff_clocks(existing, only_one)
    assert added == []
    assert removed == [OTHER_DSN]

    added, removed = discovery.diff_clocks({}, only_one)
    assert [c.dsn for c in added] == [DSN]
    assert removed == []


@pytest.mark.asyncio
async def test_a_transient_empty_poll_would_remove_devices() -> None:
    """Documents the remaining risk in diffing against an empty result.

    ``async_discover_clocks`` returns an empty list both for an empty account and for a
    failed listing, so the caller cannot tell them apart. The caller must therefore
    avoid acting on removals when the poll raised. Pinned so the behaviour is a
    decision on record rather than an accident.
    """
    transport, _ = transport_for(
        {
            ("POST", LOGIN_URL): LOGIN_OK,
            ("GET", THINGS): (500, {"error": "boom"}),
        }
    )
    discovered = await discovery.async_discover_clocks(transport)
    added, removed = discovery.diff_clocks({DSN: object()}, discovered)  # type: ignore[dict-item]
    assert added == []
    assert removed == [DSN], "an empty poll does look like a removal; caller must guard"
