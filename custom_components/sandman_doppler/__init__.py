"""The Sandman Doppler integration."""

from __future__ import annotations

import asyncio
from datetime import timedelta
import functools
import logging
from typing import Any

from homeassistant.config_entries import ConfigEntry, ConfigEntryNotReady
from homeassistant.const import (
    CONF_EMAIL,
    CONF_PASSWORD,
    Platform,
)
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import device_registry as dr, entity_registry as er
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.helpers.network import get_url
from homeassistant.helpers.typing import ConfigType
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .clock import CloudDoppler
from .cloud import DopplerAuthError, DopplerError
from .const import DOMAIN
from .discovery import async_discover_clocks, build_transport, diff_clocks
from .http import DopplerWebhookView
from .services import DopplerServices

SCAN_INTERVAL = timedelta(seconds=60)

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.LIGHT,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SIREN,
    Platform.SWITCH,
]


async def _refresh_devices(store: dict[str, Any], _now: Any = None) -> None:
    """Re-poll for clocks so additions and removals are picked up.

    Every decision lives in :mod:`.discovery`; this only wires the result to the device
    registry. Keeping the logic out of here is what makes it testable without Home
    Assistant, which cannot be imported on Windows at all.

    Args:
        store: Per-entry state holding the transport, tracked clocks and callbacks.
        _now: Unused datetime supplied by async_track_time_interval.
    """
    try:
        discovered = await async_discover_clocks(store["transport"])
    except DopplerAuthError:
        _LOGGER.warning("Account token rejected while polling for clocks")
        return
    except DopplerError as err:
        _LOGGER.warning("Error getting devices: %s", err)
        return
    added, removed = diff_clocks(store["clocks"], discovered)
    for clock in added:
        store["add"](clock)
    for dsn in removed:
        store["remove"](dsn)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Set up the Sandman Doppler component."""
    hass.http.register_view(DopplerWebhookView())
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up config entry."""
    hass.data.setdefault(DOMAIN, {}).setdefault(entry.entry_id, {})
    per_entry = hass.data[DOMAIN][entry.entry_id]

    session = async_get_clientsession(hass)
    transport = build_transport(
        entry.data[CONF_EMAIL], entry.data[CONF_PASSWORD], session
    )

    # A rejected token is a setup problem the user can fix by re-authenticating, so
    # raise rather than pretending the account has no clocks.
    try:
        await transport.async_login()
    except DopplerError as err:
        raise ConfigEntryNotReady(
            f"Could not authenticate with Sandman Doppler: {err}"
        ) from err

    dev_reg = dr.async_get(hass)
    ent_reg = er.async_get(hass)

    if not per_entry.get("platform_setup_complete"):
        per_entry["platform_setup_complete"] = True
        await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    @callback
    def async_add_clock(clock: CloudDoppler) -> None:
        """Register a newly discovered clock and start polling it."""
        _LOGGER.debug("Adding Doppler clock: %s (%s)", clock.name, clock.dsn)
        clocks[clock.dsn] = clock
        if clock.dsn in per_entry:
            return
        dev_entry = dev_reg.async_get_or_create(
            config_entry_id=entry.entry_id,
            identifiers={(DOMAIN, clock.dsn)},
            manufacturer=clock.device_info.manufacturer,
            model=clock.device_info.model_number,
            sw_version=clock.device_info.software_version,
            hw_version=clock.device_info.firmware_version,
            name=clock.name,
        )
        coordinator = DopplerDataUpdateCoordinator(
            hass, entry, transport, clock, dev_entry
        )
        per_entry[clock.dsn] = coordinator
        hass.async_create_task(coordinator.async_refresh())

    @callback
    def async_remove_clock(dsn: str) -> None:
        """Forget a clock that has disappeared from the account."""
        _LOGGER.debug("Removing Doppler clock: %s", dsn)
        clocks.pop(dsn, None)
        if (dev_entry := dev_reg.async_get_device({(DOMAIN, dsn)})) is not None:
            dev_reg.async_remove_device(dev_entry.id)
        per_entry.pop(dsn, None)

    clocks: dict[str, CloudDoppler] = {}
    store: dict[str, Any] = {
        "transport": transport,
        "clocks": clocks,
        "add": async_add_clock,
        "remove": async_remove_clock,
    }
    per_entry["_store"] = store

    # Populate immediately so the entities exist without waiting for the first interval.
    try:
        for clock in await async_discover_clocks(transport):
            async_add_clock(clock)
    except DopplerError as err:
        raise ConfigEntryNotReady(
            f"Could not list Sandman Doppler clocks: {err}"
        ) from err

    # Every five minutes, look for clocks that appeared or went away.
    entry.async_on_unload(
        async_track_time_interval(
            hass, functools.partial(_refresh_devices, store), timedelta(minutes=5)
        )
    )

    # Services resolve a target device id to a clock through this registry, which is kept
    # in step by the add and remove callbacks above.
    DopplerServices(hass, ent_reg, dev_reg, clocks).async_register()

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Handle removal of an entry."""
    unloaded = all(
        await asyncio.gather(
            *[
                hass.config_entries.async_forward_entry_unload(entry, platform)
                for platform in PLATFORMS
            ]
        )
    )
    if unloaded:
        hass.data[DOMAIN].pop(entry.entry_id)

    return unloaded


async def async_reload_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Reload config entry."""
    await async_unload_entry(hass, entry)
    await async_setup_entry(hass, entry)


class DopplerDataUpdateCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Class to manage fetching data from the API."""

    def __init__(
        self,
        hass: HomeAssistant,
        entry: ConfigEntry,
        transport: Any,
        doppler: CloudDoppler,
        dev_entry: dr.DeviceEntry,
    ) -> None:
        """Initialize."""
        super().__init__(
            hass, _LOGGER, name=f"{DOMAIN}_{doppler.dsn}", update_interval=SCAN_INTERVAL
        )
        self.data: dict[str, Any] = {}
        self.transport = transport
        self.doppler = doppler
        self._entry = entry
        self._entities_created = False
        base_url = get_url(
            self.hass,
            require_ssl=False,
            require_standard_port=False,
            allow_internal=True,
            allow_external=True,
            allow_cloud=True,
            allow_ip=True,
            prefer_external=False,
            prefer_cloud=False,
        )
        self._webhook_url = (
            f"{base_url}/api/sandman_doppler/smart_button/{dev_entry.id}"
        )

    async def _reschedule_refresh(self) -> None:
        """Reschedule refresh due to failure."""
        _LOGGER.debug("Update failed, scheduling a new one in 15 seconds")
        await asyncio.sleep(15)
        await self.async_refresh()

    async def _async_update_data(self) -> dict[str, Any]:
        """Update data via library."""
        _LOGGER.debug(
            "Getting update for device %s (%s)", self.doppler.name, self.doppler.dsn
        )
        try:
            data = await self.doppler.get_all_data()
        except DopplerError as exc:
            _LOGGER.debug(
                "Exception received during update for device %s (%s): %s: %s",
                self.doppler.name,
                self.doppler.dsn,
                type(exc).__name__,
                exc,
            )
            if not self._entities_created:
                self.hass.async_create_task(self._reschedule_refresh())
            raise UpdateFailed() from exc
        else:
            _LOGGER.debug(
                "Finished getting update for device %s (%s)",
                self.doppler.name,
                self.doppler.dsn,
            )
        if not self.data:
            self._entities_created = True
            await asyncio.gather(
                *[
                    self.doppler.set_smart_button_configuration(
                        button_num, url=self._webhook_url, command="HA"
                    )
                    for button_num in range(1, 3)
                ]
            )
            async_dispatcher_send(
                self.hass, f"{DOMAIN}_{self._entry.entry_id}_device_added", self.doppler
            )
        return data
