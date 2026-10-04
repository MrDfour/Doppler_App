"""Device discovery and credential checks, with no Home Assistant imports.

Kept separate from ``__init__.py`` on purpose. All the decisions that matter --
which clocks exist, whether credentials work, whether LAN is required -- live here so
they can be unit tested without Home Assistant, which cannot be imported on Windows at
all (``homeassistant/runner.py`` imports the Unix-only ``fcntl``).

``__init__.py`` is left as a thin adapter over this module.
"""

from __future__ import annotations

import logging
from typing import Any

from .clock import ClockDeviceInfo, CloudDoppler
from .cloud import CloudTransport, DopplerAuthError, DopplerError

_LOGGER = logging.getLogger(__name__)


def build_transport(
    email: str, password: str, session: Any, timeout: int | None = None
) -> CloudTransport:
    """Build an unauthenticated transport for an account.

    Args:
        email: Account email address.
        password: Account password.
        session: An ``aiohttp.ClientSession``, normally Home Assistant's shared one.
        timeout: Optional per-request deadline override.
    """
    if timeout is None:
        return CloudTransport(email=email, password=password, dsn="", session=session)
    return CloudTransport(
        email=email, password=password, dsn="", session=session, timeout=timeout
    )


async def async_validate_credentials(email: str, password: str, session: Any) -> bool:
    """Return whether the credentials can obtain a token.

    Replaces ``DopplerClient(...).get_token()``. Returns ``False`` rather than raising,
    because the config flow only needs a yes/no.

    Args:
        email: Account email address.
        password: Account password.
        session: An ``aiohttp.ClientSession``.
    """
    transport = build_transport(email, password, session)
    try:
        await transport.async_login()
    except DopplerError as err:
        _LOGGER.debug("Credential check failed: %s", err)
        return False
    return True


async def async_discover_clocks(transport: CloudTransport) -> list[CloudDoppler]:
    """Return every clock on the authenticated account.

    A clock is included even when its ``localkey`` read fails, which is the normal case
    for a LAN-less unit. ``doppyler`` dropped the device in that situation, so a
    cloud-only clock never appeared in Home Assistant at all.

    Args:
        transport: An authenticated transport.

    Returns:
        One :class:`CloudDoppler` per clock found. Empty if authentication failed.

    Raises:
        DopplerAuthError: The account token is no longer valid.
    """
    try:
        descriptors = await transport.async_get_devices()
    except DopplerAuthError:
        raise
    except DopplerError as err:
        # A transient failure here must not look like "you own no clocks", which would
        # make Home Assistant remove the existing devices.
        _LOGGER.warning("Could not list Doppler clocks: %s", err)
        return []

    clocks: list[CloudDoppler] = []
    for descriptor in descriptors:
        info = ClockDeviceInfo.from_wire(descriptor.device_info, descriptor.dsn)
        clocks.append(
            CloudDoppler(
                transport=transport,
                dsn=descriptor.dsn,
                name=descriptor.name,
                device_info=info,
                local_control=descriptor.local_available,
            )
        )
        _LOGGER.debug(
            "Discovered %s (%s), local control %s",
            descriptor.name,
            descriptor.dsn,
            "available" if descriptor.local_available else "unavailable",
        )
    return clocks


def diff_clocks(
    existing: dict[str, CloudDoppler], discovered: list[CloudDoppler]
) -> tuple[list[CloudDoppler], list[str]]:
    """Work out which clocks appeared and which went away.

    Args:
        existing: Currently tracked clocks, keyed by DSN.
        discovered: Clocks found by the latest poll.

    Returns:
        ``(added, removed_dsns)``. A clock that is still present is not reported as
        changed even if its metadata moved, so the coordinator is left alone.
    """
    found = {clock.dsn: clock for clock in discovered}
    added = [clock for dsn, clock in found.items() if dsn not in existing]
    removed = [dsn for dsn in existing if dsn not in found]
    return added, removed
