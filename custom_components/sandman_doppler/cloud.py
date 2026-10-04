"""Cloud transport for the Sandman Doppler.

Replaces the transport assumptions baked into ``doppyler==0.0.20``, which cannot work
against a cloud-only unit. This module deliberately imports **nothing** from Home
Assistant so that it can be unit tested on its own.

Three measured facts drive the design (see ``SANDMAN_DOPPLER_KNOWLEDGE.md``):

1. ``doppyler`` refuses to add a device when ``GET /localkey`` fails
   (``client.py:266`` catches ``DopplerException`` and returns). On this unit
   ``localkey`` always times out, so the clock never appeared in Home Assistant at all.
   **Here, LAN is an optional upgrade, never a precondition for discovery.**

2. ``doppyler.get_all_data`` gathers every endpoint with no ``return_exceptions``, so one
   failing read discards the other 40. Measured: 10 of 43 endpoints raise, which aborted
   every cycle. **Here, a failed read yields ``None`` for that attribute only.**

3. ``doppyler`` uses a 10 s client timeout, which fires *before* the relay's 15 s 408 and
   so never observes the status code. **Here, dead paths are skipped rather than waited
   on**, and the measured total for a full sweep drops from 131 s to well under the
   coordinator's 60 s interval.

A fourth lesson, learned the hard way in this repository: a path must never be disabled
because it failed once. ``software/use-leading-zero`` was seeded as permanently unavailable
and then measured answering 4 times out of 6, so it is **not** in
:data:`UNAVAILABLE_ENDPOINTS` despite being one of the original six.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import logging
from typing import Any

import aiohttp

_LOGGER = logging.getLogger(__name__)

BASE_SANDMAN_API_URL = "https://control.sandmandoppler.com"
BASE_COPILOT_API_URL = "https://api.sandmandoppler.bycopilot.com/v4"

#: ``doppyler`` sends this as ``applicationId``; the endpoint answers **201**, not 200.
DEFAULT_APPLICATION_ID = "doppyler"

#: Per-request deadline. Kept well under the relay's 15 s stall so a slow path cannot
#: consume the coordinator's whole interval.
DEFAULT_TIMEOUT = 8

#: Endpoints measured as never answering on firmware 1214 (0/6 or 0/3 attempts, every
#: attempt a full timeout). They are skipped rather than polled.
#:
#: ``software/use-leading-zero`` is intentionally **absent** despite belonging to the
#: original "six dead reads": it answers 200 on roughly two attempts in three, so polling
#: it is worthwhile. Do not re-add it on the strength of a single failure.
UNAVAILABLE_ENDPOINTS: frozenset[str] = frozenset(
    {
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
)


class DopplerError(Exception):
    """Base error for the Sandman Doppler transport."""


class DopplerAuthError(DopplerError):
    """Raised when the cloud credentials are rejected and cannot be refreshed."""


class DopplerConnectionError(DopplerError):
    """Raised when a request cannot be completed at all."""


class EndpointUnavailableError(DopplerError):
    """Raised when an endpoint is known not to exist on this firmware.

    Distinct from a transport failure: a caller should treat this as "this device cannot
    do this", not "try again later".
    """


def is_endpoint_available(path: str) -> bool:
    """Return whether *path* is worth polling on this firmware.

    Args:
        path: Endpoint path relative to the device, e.g. ``hardware/volume``.
    """
    return path not in UNAVAILABLE_ENDPOINTS


@dataclass
class DeviceDescriptor:
    """A clock discovered through the cloud.

    Attributes:
        dsn: Serial number, taken from ``info.physicalId`` and never from ``id``.
        name: Owner-assigned name, if any.
        device_info: Contents of ``GET /device``, or ``None`` if that read failed.
        local_info: Contents of ``GET /localkey``, or ``None``. **Its absence is normal
            on a cloud-only unit and must never prevent the device being used.**
    """

    dsn: str
    name: str | None = None
    device_info: dict[str, Any] | None = None
    local_info: dict[str, Any] | None = None

    @property
    def local_available(self) -> bool:
        """Return whether LAN control appears to be available for this device."""
        return bool(self.local_info)


@dataclass
class CloudTransport:
    """Talks to the Sandman Doppler cloud API.

    One instance per authenticated account. The account token is shared across every
    device on it, so a single instance should serve all of them.

    Args:
        email: Account email address.
        password: Account password.
        dsn: Serial number of the device this transport is bound to.
        session: An ``aiohttp.ClientSession``. Injected so tests can supply a fake.
        timeout: Per-request deadline in seconds.
        semaphore_limit: Maximum concurrent requests. The relay is not the bottleneck --
            the single-threaded clock is -- but this bounds burstiness.
    """

    email: str
    password: str
    dsn: str
    session: aiohttp.ClientSession
    timeout: int = DEFAULT_TIMEOUT
    semaphore_limit: int = 1

    _access_token: str | None = field(default=None, init=False, repr=False)
    _refresh_token: str | None = field(default=None, init=False, repr=False)
    _expires_at: float | None = field(default=None, init=False, repr=False)
    _semaphore: asyncio.Semaphore = field(init=False, repr=False)
    _refresh_lock: asyncio.Lock = field(
        default_factory=asyncio.Lock, init=False, repr=False
    )
    _refresh_disabled: bool = field(default=False, init=False, repr=False)

    def __post_init__(self) -> None:
        """Create the semaphore and refresh lock."""
        self._semaphore = asyncio.Semaphore(self.semaphore_limit)

    # ---------------------------------------------------------------- auth

    async def async_login(self) -> None:
        """Authenticate and store the account token.

        Raises:
            DopplerAuthError: The credentials were rejected.
            DopplerConnectionError: The login request could not be completed.
        """
        payload = {
            "authenticationDetails": {
                "applicationId": DEFAULT_APPLICATION_ID,
                "email": self.email,
                "password": self.password,
            },
            "deviceDetails": {
                "applicationVersion": "0.0.20",
                "deviceId": "home_assistant",
                "deviceModel": "home_assistant",
                "deviceType": "PHONE",
                "osType": "ANDROID",
                "osVersion": "13",
            },
        }
        status, body = await self._raw_request(
            "POST", f"{BASE_COPILOT_API_URL}/auth/login", data=payload
        )
        # NOTE: this endpoint answers 201 when applicationId is "doppyler". Treating
        # anything but 200 as failure is a bug that was measured on this account.
        if status not in (200, 201):
            raise DopplerAuthError(f"Login failed with HTTP {status}")
        self._access_token = body.get("accessToken")
        self._refresh_token = body.get("refreshToken")
        expires_in = body.get("expiresIn")
        if expires_in is not None:
            loop = asyncio.get_running_loop()
            self._expires_at = loop.time() + float(expires_in)

    async def _ensure_token(self) -> str:
        """Return a usable access token, refreshing or logging in as needed.

        A failed refresh disables further attempts for the life of this transport. That
        fail-fast matters: a dead refresh token used to cost a failed round trip on
        *every* subsequent request, turning one bad token into a stall storm.
        """
        if self._access_token is None:
            await self.async_login()
            assert self._access_token is not None
            return self._access_token

        loop = asyncio.get_running_loop()
        if self._expires_at is not None and loop.time() >= self._expires_at:
            await self._async_refresh()
        return self._access_token

    async def _async_refresh(self) -> None:
        """Refresh the access token, at most once concurrently.

        Raises:
            DopplerAuthError: The refresh token is dead.
        """
        async with self._refresh_lock:
            # Another coroutine may have refreshed while this one waited.
            loop = asyncio.get_running_loop()
            if self._expires_at is not None and loop.time() < self._expires_at:
                return
            if self._refresh_disabled or not self._refresh_token:
                self._access_token = None
                await self.async_login()
                return
            status, body = await self._raw_request(
                "POST",
                f"{BASE_COPILOT_API_URL}/auth/refresh",
                data={"refreshToken": self._refresh_token},
            )
            if status not in (200, 201):
                # Do not retry this for the rest of the session.
                self._refresh_disabled = True
                self._access_token = None
                raise DopplerAuthError(f"Token refresh failed with HTTP {status}")
            self._access_token = body.get("accessToken")
            expires_in = body.get("expiresIn")
            if expires_in is not None:
                self._expires_at = loop.time() + float(expires_in)

    # ------------------------------------------------------------ requests

    async def _raw_request(
        self, method: str, url: str, data: dict[str, Any] | None = None
    ) -> tuple[int, Any]:
        """Perform one HTTP request and return ``(status, decoded_body)``.

        Does not raise on a non-2xx status: the caller decides what each code means.
        This is deliberate, because a 404 and a 408 are very different facts.
        """
        headers = {"content-type": "application/json"}
        if self._access_token:
            headers["Authorization"] = f"Bearer {self._access_token}"
        try:
            async with self.session.request(
                method,
                url,
                json=data,
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=self.timeout),
                ssl=False,
            ) as response:
                try:
                    body = await response.json(content_type=None)
                except (aiohttp.ContentTypeError, ValueError):
                    body = await response.text()
                return response.status, body
        except asyncio.TimeoutError as err:
            raise DopplerConnectionError(
                f"Timeout after {self.timeout}s: {method} {url}"
            ) from err
        except aiohttp.ClientError as err:
            raise DopplerConnectionError(f"{method} {url} failed: {err}") from err

    async def async_get(self, path: str, dsn: str | None = None) -> dict[str, Any]:
        """GET a device endpoint and return its decoded body.

        Args:
            path: Path relative to the device, e.g. ``hardware/volume``.
            dsn: Override the transport's device, for account-wide calls.

        Returns:
            The decoded JSON body.

        Raises:
            EndpointUnavailableError: The endpoint is known not to answer on this
                firmware. Callers should surface this as "unavailable", not as an error.
            DopplerAuthError: Credentials are no longer valid.
            DopplerConnectionError: The request timed out or the transport failed.
        """
        if not is_endpoint_available(path):
            raise EndpointUnavailableError(
                f"{path} does not answer on this firmware and will not be polled"
            )
        target = dsn or self.dsn
        # The semaphore serialises requests so a slow read cannot monopolise the link.
        async with self._semaphore:
            for attempt in (1, 2):
                await self._ensure_token()
                status, body = await self._raw_request(
                    "GET", f"{BASE_SANDMAN_API_URL}/{target}/{path}"
                )
                if status in (200, 201):
                    return body
                if status in (401, 403) and attempt == 1:
                    _LOGGER.debug(
                        "Refreshing token after HTTP %s from %s", status, path
                    )
                    self._expires_at = 0.0
                    await self._async_refresh()
                    continue
                if status == 408:
                    raise DopplerConnectionError(
                        f"{path} timed out on the clock after {self.timeout}s"
                    )
                if status == 404:
                    raise EndpointUnavailableError(
                        f"{path} is not routed on this model"
                    )
                raise DopplerConnectionError(f"{path} returned HTTP {status}: {body!r}")
        raise DopplerConnectionError(f"{path} could not be read")

    # ----------------------------------------------------------- discovery

    async def async_get_devices(self) -> list[DeviceDescriptor]:
        """Discover every clock on the account.

        **A ``localkey`` failure does not drop the device.** This is the single most
        important behaviour in this module: ``doppyler`` treats LAN as a precondition and
        therefore finds nothing at all on a cloud-only unit.

        Returns:
            One descriptor per clock, each possibly with ``local_info`` of ``None``.
        """
        # Authenticate here rather than relying on the caller to have done it. A missing
        # token turns every read into an opaque 404, which is a miserable thing to
        # debug from a Home Assistant log.
        await self._ensure_token()
        status, body = await self._raw_request("GET", f"{BASE_COPILOT_API_URL}/things")
        if status in (401, 403):
            raise DopplerAuthError(f"Listing things failed with HTTP {status}")
        if status not in (200, 201):
            raise DopplerConnectionError(f"Listing things returned HTTP {status}")

        devices: list[DeviceDescriptor] = []
        for thing in (body or {}).get("things", []):
            info = thing.get("info") or {}
            # The serial number lives in info.physicalId. Using `id` silently produces
            # an unusable device.
            dsn = info.get("physicalId")
            if not dsn:
                _LOGGER.debug("Skipping thing with no physicalId: %s", thing.get("id"))
                continue
            devices.append(
                DeviceDescriptor(
                    dsn=dsn,
                    name=info.get("name"),
                    device_info=await self._optional_get(dsn, "device"),
                    local_info=await self._optional_get(dsn, "localkey"),
                )
            )
        return devices

    async def _optional_get(self, dsn: str, path: str) -> dict[str, Any] | None:
        """GET *path*, returning ``None`` instead of raising.

        ``localkey`` and ``device`` are enrichment, not requirements. A unit whose LAN
        daemon is absent still answers ``device`` over the cloud and must still appear.
        """
        try:
            return await self.async_get(path, dsn=dsn)
        except EndpointUnavailableError:
            return None
        except DopplerConnectionError as err:
            _LOGGER.debug("Optional read %s/%s unavailable: %s", dsn, path, err)
            return None
        except DopplerAuthError:
            raise
