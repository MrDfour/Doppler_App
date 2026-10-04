"""Tests for the Sandman Doppler cloud transport.

These deliberately import no Home Assistant symbols so they run without the full HA
test harness. Every test here corresponds to a *measured* fact about real hardware,
recorded in ``SANDMAN_DOPPLER_KNOWLEDGE.md``.
"""

from __future__ import annotations

import asyncio
import importlib.util
from pathlib import Path
import sys
from typing import Any

import pytest

# cloud.py is loaded by path rather than as `custom_components.sandman_doppler.cloud`,
# because importing that package executes its __init__.py, which imports Home Assistant
# and doppyler. Home Assistant cannot be imported on Windows at all: runner.py imports
# the Unix-only `fcntl`. Loading the module directly keeps these transport tests runnable
# here, which is the whole reason cloud.py imports nothing from Home Assistant.
_MODULE_PATH = (
    Path(__file__).resolve().parent.parent
    / "custom_components"
    / "sandman_doppler"
    / "cloud.py"
)
_spec = importlib.util.spec_from_file_location("sandman_cloud_under_test", _MODULE_PATH)
assert _spec is not None and _spec.loader is not None
cloud = importlib.util.module_from_spec(_spec)
# Register before executing: on Python 3.14 @dataclass resolves sys.modules[cls.__module__]
# while the class body runs, and raises AttributeError if the module is absent.
sys.modules[_spec.name] = cloud
_spec.loader.exec_module(cloud)

UNAVAILABLE_ENDPOINTS = cloud.UNAVAILABLE_ENDPOINTS
CloudTransport = cloud.CloudTransport
DopplerAuthError = cloud.DopplerAuthError
DopplerConnectionError = cloud.DopplerConnectionError
EndpointUnavailableError = cloud.EndpointUnavailableError
is_endpoint_available = cloud.is_endpoint_available

DSN = "Doppler-10caaebb"


class FakeResponse:
    """Minimal stand-in for an aiohttp response used as an async context manager."""

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
    """Records requests and replays a scripted mapping of (METHOD, url) to responses.

    A key may map to a single response or to a list consumed in order, which is how the
    refresh-then-retry behaviour is exercised.
    """

    def __init__(self, script: dict[tuple[str, str], Any]) -> None:
        self.script = script
        self.calls: list[tuple[str, str, Any]] = []

    def request(self, method: str, url: str, **kwargs: Any) -> FakeResponse:
        self.calls.append((method, url, kwargs.get("json")))
        entry = self.script.get((method, url))
        if entry is None:
            return FakeResponse(404, {"error": "unscripted"})
        if isinstance(entry, list):
            if not entry:
                return FakeResponse(404, {"error": "script exhausted"})
            return FakeResponse(*entry.pop(0))
        return FakeResponse(*entry)

    def urls(self) -> list[str]:
        return [url for _, url, _ in self.calls]


def make_transport(session: FakeSession, **kwargs: Any) -> CloudTransport:
    """Build a transport bound to a fake session."""
    return CloudTransport(
        email="user@example.com",
        password="secret",
        dsn=DSN,
        session=session,  # type: ignore[arg-type]
        **kwargs,
    )


LOGIN_URL = "https://api.sandmandoppler.bycopilot.com/v4/auth/login"
LOGIN_OK = (201, {"accessToken": "tok", "refreshToken": "ref", "expiresIn": 3000})

# Discovery authenticates itself rather than trusting the caller to have done it, so
# every discovery test has to script the login.
AUTH = {("POST", LOGIN_URL): LOGIN_OK}


# --------------------------------------------------------------- availability


def test_use_leading_zero_is_not_treated_as_unavailable() -> None:
    """Regression: it answers 4 times out of 6, so it must still be polled.

    The original six "dead reads" included this path, and seeding it as unavailable
    switched off a feature that works two times in three. A single failed poll must
    never be enough to disable a path.
    """
    assert "software/use-leading-zero" not in UNAVAILABLE_ENDPOINTS
    assert is_endpoint_available("software/use-leading-zero")


def test_consistently_dead_paths_are_skipped() -> None:
    """The nine paths measured 0/6 or 0/3 are skipped rather than waited on."""
    assert UNAVAILABLE_ENDPOINTS == {
        "hardware/wifi-status",
        "software/use-colon",
        "software/colon-blink",
        "software/use-fade-time",
        "software/display-seconds",
        "software/use-rainbow-display",
        "hardware/button-last-message",
        "software/snooze-length",
        "software/use-snooze-display",
    }


# --------------------------------------------------------------------- login


@pytest.mark.asyncio
async def test_login_accepts_http_201() -> None:
    """The login endpoint answers 201 when applicationId is 'doppyler'.

    Measured on a real account: treating anything but 200 as failure breaks login.
    """
    url = LOGIN_URL
    session = FakeSession({("POST", url): LOGIN_OK})
    transport = make_transport(session)
    await transport.async_login()
    assert transport._access_token == "tok"


@pytest.mark.asyncio
async def test_login_rejects_bad_credentials() -> None:
    """A rejected login raises rather than returning a half-built transport."""
    url = LOGIN_URL
    session = FakeSession({("POST", url): (401, {"error": "nope"})})
    transport = make_transport(session)
    with pytest.raises(DopplerAuthError):
        await transport.async_login()


@pytest.mark.asyncio
async def test_login_posts_the_doppyler_application_id() -> None:
    """The applicationId must stay 'doppyler'; the cloud rejects other values."""
    url = LOGIN_URL
    session = FakeSession({("POST", url): LOGIN_OK})
    transport = make_transport(session)
    await transport.async_login()
    _, _, sent = session.calls[0]
    assert sent["authenticationDetails"]["applicationId"] == "doppyler"


# ------------------------------------------------------------------ discovery


@pytest.mark.asyncio
async def test_device_is_discovered_even_when_localkey_times_out() -> None:
    """Regression for the blocker that made the integration useless on this unit.

    Measured: `localkey` always times out here, and doppyler drops the whole device
    when it does, so the clock never appeared in Home Assistant at all.
    """
    session = FakeSession(
        {
            **AUTH,
            ("GET", "https://api.sandmandoppler.bycopilot.com/v4/things"): (
                200,
                {
                    "things": [
                        {"id": "uuid-1", "info": {"physicalId": DSN, "name": "Bedroom"}}
                    ]
                },
            ),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/device"): (
                200,
                {"serialNum": DSN},
            ),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/localkey"): (
                408,
                {"message": "No response from Doppler"},
            ),
        }
    )
    transport = make_transport(session)
    devices = await transport.async_get_devices()

    assert len(devices) == 1, "a localkey failure must not drop the device"
    device = devices[0]
    assert device.dsn == DSN
    assert device.name == "Bedroom"
    assert device.device_info == {"serialNum": DSN}
    assert device.local_info is None
    assert device.local_available is False


@pytest.mark.asyncio
async def test_device_is_discovered_when_localkey_raises_a_timeout() -> None:
    """A transport timeout is handled the same as a 408, and is also not fatal."""

    class TimingOutSession(FakeSession):
        def request(self, method: str, url: str, **kwargs: Any) -> FakeResponse:
            if url.endswith("/localkey"):
                raise asyncio.TimeoutError()
            return super().request(method, url, **kwargs)

    session = TimingOutSession(
        {
            **AUTH,
            ("GET", "https://api.sandmandoppler.bycopilot.com/v4/things"): (
                200,
                {"things": [{"id": "u", "info": {"physicalId": DSN}}]},
            ),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/device"): (
                200,
                {"serialNum": DSN},
            ),
        }
    )
    transport = make_transport(session)
    devices = await transport.async_get_devices()
    assert [d.dsn for d in devices] == [DSN]
    assert devices[0].local_info is None


@pytest.mark.asyncio
async def test_dsn_comes_from_physical_id_not_thing_id() -> None:
    """info.physicalId is the serial number; the thing `id` is a UUID.

    Using `id` yields a device that can never be addressed.
    """
    session = FakeSession(
        {
            **AUTH,
            ("GET", "https://api.sandmandoppler.bycopilot.com/v4/things"): (
                200,
                {"things": [{"id": "not-a-dsn", "info": {"physicalId": DSN}}]},
            ),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/device"): (
                200,
                {"serialNum": DSN},
            ),
            ("GET", f"https://control.sandmandoppler.com/{DSN}/localkey"): (
                200,
                {"localkey": "k"},
            ),
        }
    )
    transport = make_transport(session)
    devices = await transport.async_get_devices()
    assert devices[0].dsn == DSN
    assert devices[0].local_available is True


@pytest.mark.asyncio
async def test_thing_without_physical_id_is_skipped() -> None:
    """A malformed thing is skipped, not turned into a broken device."""
    session = FakeSession(
        {
            **AUTH,
            ("GET", "https://api.sandmandoppler.bycopilot.com/v4/things"): (
                200,
                {"things": [{"id": "orphan", "info": {}}]},
            ),
        }
    )
    transport = make_transport(session)
    assert await transport.async_get_devices() == []


# ---------------------------------------------------------------------- reads


@pytest.mark.asyncio
async def test_unavailable_endpoint_raises_before_any_request() -> None:
    """Skipped paths cost nothing: no request is made at all."""
    url = LOGIN_URL
    session = FakeSession({("POST", url): LOGIN_OK})
    transport = make_transport(session)
    await transport.async_login()
    before = len(session.calls)
    with pytest.raises(EndpointUnavailableError):
        await transport.async_get("software/use-colon")
    assert len(session.calls) == before, "a skipped path must not hit the network"


@pytest.mark.asyncio
async def test_408_surfaces_as_a_connection_error() -> None:
    """408 means the clock stalled, not that the route is missing."""
    url = LOGIN_URL
    target = f"https://control.sandmandoppler.com/{DSN}/doptime/offset"
    session = FakeSession(
        {("POST", url): LOGIN_OK, ("GET", target): (408, {"error": "x"})}
    )
    transport = make_transport(session)
    await transport.async_login()
    with pytest.raises(DopplerConnectionError):
        await transport.async_get("doptime/offset")


@pytest.mark.asyncio
async def test_404_surfaces_as_endpoint_unavailable() -> None:
    """404 is conclusive evidence of absence, and is reported as such."""
    url = LOGIN_URL
    target = f"https://control.sandmandoppler.com/{DSN}/software/nope"
    session = FakeSession(
        {("POST", url): LOGIN_OK, ("GET", target): (404, {"error": "no route"})}
    )
    transport = make_transport(session)
    await transport.async_login()
    with pytest.raises(EndpointUnavailableError):
        await transport.async_get("software/nope")


@pytest.mark.asyncio
async def test_successful_read_returns_decoded_body() -> None:
    """The happy path returns the decoded body unchanged."""
    url = LOGIN_URL
    target = f"https://control.sandmandoppler.com/{DSN}/hardware/volume"
    session = FakeSession(
        {("POST", url): LOGIN_OK, ("GET", target): (200, {"volume": 76})}
    )
    transport = make_transport(session)
    await transport.async_login()
    assert await transport.async_get("hardware/volume") == {"volume": 76}


@pytest.mark.asyncio
async def test_a_401_triggers_one_refresh_and_retry() -> None:
    """An expired token is refreshed once and the read is retried, not abandoned."""
    login = LOGIN_URL
    refresh = "https://api.sandmandoppler.bycopilot.com/v4/auth/refresh"
    target = f"https://control.sandmandoppler.com/{DSN}/hardware/volume"
    session = FakeSession(
        {
            ("POST", login): LOGIN_OK,
            ("GET", target): [(401, {"error": "expired"}), (200, {"volume": 50})],
            ("POST", refresh): (200, {"accessToken": "tok2", "expiresIn": 3000}),
        }
    )
    transport = make_transport(session)
    await transport.async_login()
    assert await transport.async_get("hardware/volume") == {"volume": 50}
    assert session.urls().count(refresh) == 1


@pytest.mark.asyncio
async def test_a_dead_refresh_token_does_not_retry_forever() -> None:
    """A failing refresh disables itself rather than stalling every later request.

    A dead refresh token used to cost a failed round trip on every subsequent request.
    """
    login = LOGIN_URL
    refresh = "https://api.sandmandoppler.bycopilot.com/v4/auth/refresh"
    target = f"https://control.sandmandoppler.com/{DSN}/hardware/volume"
    session = FakeSession(
        {
            ("POST", login): LOGIN_OK,
            ("GET", target): (401, {"error": "expired"}),
            ("POST", refresh): (400, {"error": "refresh token revoked"}),
        }
    )
    transport = make_transport(session)
    await transport.async_login()
    with pytest.raises(DopplerAuthError):
        await transport.async_get("hardware/volume")
    assert session.urls().count(refresh) == 1
    assert transport._refresh_disabled is True


@pytest.mark.asyncio
async def test_requests_are_serialised_by_the_semaphore() -> None:
    """The link is not allowed to be hit concurrently.

    The clock's daemon is single-threaded, so overlapping reads buy nothing and make
    latency worse.
    """
    url = LOGIN_URL
    session = FakeSession({("POST", url): LOGIN_OK})
    transport = make_transport(session, semaphore_limit=1)
    await transport.async_login()
    assert transport._semaphore._value == 1
