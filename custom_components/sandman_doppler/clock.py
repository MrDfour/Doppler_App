"""A single Sandman Doppler clock, addressed through :mod:`.cloud`.

This is a stand-in for ``doppyler.model.doppler.Doppler``. It exposes the same
surface the platforms actually use -- ``get_all_data``, ``get_all_alarms`` and the
``set_*`` methods behind ``set_value_func_name`` -- so no platform needs to change
while the transport underneath is replaced.

Two behaviours differ from doppyler on purpose:

* **A failed read costs one attribute, not the whole cycle.** ``get_all_data``
  gathers every endpoint with no ``return_exceptions``, which is why a cycle aborted
  on this unit. Here each attribute is fetched defensively and a failure yields
  ``None`` for that attribute alone.

* **Fade time is read from ``software/use-fade-time``.** doppyler writes to that
  endpoint but *reads* ``software/use-leading-zero`` for the same attribute
  (doppyler/model/doppler.py:329), so its Fade Time switch writes correctly and then
  displays the leading-zero state. The two are mapped to the same endpoint here.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import timedelta
import logging
from typing import Any, Callable

from .cloud import (
    CloudTransport,
    DopplerConnectionError,
    EndpointUnavailableError,
    is_endpoint_available,
)
from .const import (
    ATTR_ALARM_SOUNDS,
    ATTR_ALARMS,
    ATTR_ALEXA_TAP_TO_TALK_TONE_ENABLED,
    ATTR_ALEXA_USE_ASCENDING_ALARMS,
    ATTR_ALEXA_WAKE_WORD_TONE_ENABLED,
    ATTR_COLON_BLINK,
    ATTR_CONNECTED_TO_ALEXA,
    ATTR_DAY_BUTTON_BRIGHTNESS,
    ATTR_DAY_BUTTON_COLOR,
    ATTR_DAY_DISPLAY_BRIGHTNESS,
    ATTR_DAY_DISPLAY_COLOR,
    ATTR_DAY_TO_NIGHT_TRANSITION_VALUE,
    ATTR_DISPLAY_SECONDS,
    ATTR_IS_IN_DAY_MODE,
    ATTR_LIGHT_SENSOR_VALUE,
    ATTR_NIGHT_BUTTON_BRIGHTNESS,
    ATTR_NIGHT_BUTTON_COLOR,
    ATTR_NIGHT_DISPLAY_BRIGHTNESS,
    ATTR_NIGHT_DISPLAY_COLOR,
    ATTR_NIGHT_TO_DAY_TRANSITION_VALUE,
    ATTR_SMART_BUTTON_COLOR,
    ATTR_SOUND_PRESET,
    ATTR_SOUND_PRESET_MODE,
    ATTR_SYNC_BUTTON_AND_DISPLAY_BRIGHTNESS,
    ATTR_SYNC_BUTTON_AND_DISPLAY_COLOR,
    ATTR_SYNC_DAY_AND_NIGHT_COLOR,
    ATTR_TIME_MODE,
    ATTR_TIME_OFFSET,
    ATTR_TIMEZONE,
    ATTR_USE_COLON,
    ATTR_USE_FADE_TIME,
    ATTR_USE_LEADING_ZERO,
    ATTR_VOLUME_LEVEL,
    ATTR_WEATHER,
    ATTR_WEATHER_WAKE_UP_TIME,
    ATTR_WIFI,
)

_LOGGER = logging.getLogger(__name__)


@dataclass
class ClockDeviceInfo:
    """Identity of a clock, as shown in the Home Assistant device registry.

    Field names match what ``__init__.py`` reads off ``doppler.device_info``.
    """

    dsn: str
    manufacturer: str | None = None
    model_number: str | None = None
    firmware_version: str | None = None
    hardware_version: str | None = None
    software_version: str | None = None

    @classmethod
    def from_wire(cls, payload: dict[str, Any] | None, dsn: str) -> "ClockDeviceInfo":
        """Build from a ``GET /device`` body.

        Args:
            payload: Decoded response, or ``None`` if that read failed.
            dsn: Serial number, used when the payload omits it.
        """
        payload = payload or {}
        return cls(
            dsn=payload.get("serialNum") or dsn,
            manufacturer=payload.get("mfgrName"),
            model_number=payload.get("modelNum"),
            firmware_version=payload.get("firmware"),
            hardware_version=payload.get("hardware"),
            software_version=payload.get("software"),
        )


def _as_int(value: Any) -> int | None:
    """Coerce a wire value to int, tolerating the several shapes seen on this device."""
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str) and value.strip():
        try:
            return int(float(value))
        except ValueError:
            return None
    return None


def _as_bool(value: Any) -> bool | None:
    """Coerce a wire value to bool. Strings are accepted because some endpoints
    answer ``"1"``/``"0"`` rather than a JSON boolean."""
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in ("true", "1", "yes", "on"):
            return True
        if lowered in ("false", "0", "no", "off"):
            return False
    return None


@dataclass(frozen=True)
class Color:
    """An RGB colour as the platforms consume it.

    The colour endpoints answer ``{"color": [r, g, b]}``, but ``light.py`` reads
    ``.red``/``.green``/``.blue`` off the coordinator value. Returning the raw list
    therefore breaks the light platform with ``'list' object has no attribute 'red'``.
    """

    red: int
    green: int
    blue: int

    @classmethod
    def from_wire(cls, value: Any) -> "Color | None":
        """Build from a wire value, returning ``None`` for anything unusable.

        Args:
            value: Expected to be ``[r, g, b]``. Short, long or malformed lists and
                non-lists all yield ``None`` rather than raising.
        """
        if isinstance(value, (list, tuple)):
            parts = [_as_int(v) for v in value]
            if len(parts) == 3 and all(p is not None for p in parts):
                return cls(parts[0], parts[1], parts[2])  # type: ignore[arg-type]
        return None

    def as_list(self) -> list[int]:
        """Return the colour as ``[r, g, b]``."""
        return [self.red, self.green, self.blue]


def _as_timedelta_minutes(value: Any) -> Any:
    """Convert a wire offset in minutes to a ``timedelta``.

    ``number.py`` renders the offset with ``x.total_seconds() // 60``, so it needs a
    ``timedelta`` rather than the bare integer the wire carries.
    """
    minutes = _as_int(value)
    return None if minutes is None else timedelta(minutes=minutes)


@dataclass(frozen=True)
class WeatherConfiguration:
    """Weather settings as the platforms consume them.

    ``select.py`` reads ``.mode`` and passes it through ``normalize_enum_name``, so
    ``mode`` should be a ``doppyler.model.weather.WeatherMode`` member for the option
    list to line up. That import is deferred and optional: this module is imported by
    tests that run on Windows, where doppyler is not installed, and there the raw integer
    is kept instead.
    """

    enabled: bool | None
    location: str | None
    mode: Any


def _as_weather_config(value: Any) -> "WeatherConfiguration | None":
    """Build a :class:`WeatherConfiguration` from a ``software/weather`` body."""
    if not isinstance(value, dict):
        return None
    mode = _as_int(value.get("wsmode"))
    resolved: Any = mode
    if mode is not None:
        try:  # pragma: no cover - depends on the environment
            from doppyler.model.weather import WeatherMode

            resolved = WeatherMode(mode)
        except Exception:  # pragma: no cover - doppyler absent, or unknown mode
            resolved = mode
    return WeatherConfiguration(
        enabled=_as_bool(value.get("wsonoff")),
        location=value.get("location"),
        mode=resolved,
    )


@dataclass(frozen=True)
class ReadSpec:
    """How to turn one endpoint's response into one coordinator attribute.

    Attributes:
        attr: Key published in ``get_all_data``.
        path: Endpoint path.
        key: Field to read, or ``None`` to publish the whole payload.
        convert: Optional coercion applied to the extracted value.
    """

    attr: str
    path: str
    key: str | None = None
    convert: Callable[[Any], Any] | None = None


#: Every coordinator attribute, in the order doppyler requests them. Built from the
#: live wire payloads recorded in the knowledge doc, not guessed from names.
READ_SPECS: tuple[ReadSpec, ...] = (
    ReadSpec(ATTR_WIFI, "hardware/wifi-status"),
    ReadSpec(ATTR_TIME_MODE, "software/time-mode", "timeMode", _as_int),
    ReadSpec(ATTR_TIMEZONE, "doptime/timezone", "timezone"),
    ReadSpec(ATTR_TIME_OFFSET, "doptime/offset", "offset", _as_timedelta_minutes),
    ReadSpec(ATTR_USE_COLON, "software/use-colon", "on", _as_bool),
    ReadSpec(ATTR_COLON_BLINK, "software/colon-blink", "blink", _as_bool),
    ReadSpec(ATTR_USE_LEADING_ZERO, "software/use-leading-zero", "on", _as_bool),
    # NOT use-leading-zero: doppyler reads the wrong endpoint here. See module docstring.
    ReadSpec(ATTR_USE_FADE_TIME, "software/use-fade-time", "on", _as_bool),
    ReadSpec(ATTR_DISPLAY_SECONDS, "software/display-seconds", "on", _as_bool),
    ReadSpec(ATTR_VOLUME_LEVEL, "hardware/volume", "volume", _as_int),
    ReadSpec(ATTR_SOUND_PRESET, "hardware/sound-preset", "preset"),
    ReadSpec(
        ATTR_SOUND_PRESET_MODE, "hardware/sound-preset-mode", "presetmode", _as_bool
    ),
    ReadSpec(ATTR_ALARMS, "alarms", "alarms"),
    ReadSpec(ATTR_ALARM_SOUNDS, "alarms/sounds", "sounds"),
    ReadSpec(ATTR_ALEXA_USE_ASCENDING_ALARMS, "alexa/ascending", "ascending", _as_bool),
    ReadSpec(ATTR_LIGHT_SENSOR_VALUE, "hardware/light-sensor", "sensor", _as_int),
    ReadSpec(ATTR_IS_IN_DAY_MODE, "hardware/day-mode", "isDayMode", _as_bool),
    ReadSpec(
        ATTR_DAY_TO_NIGHT_TRANSITION_VALUE,
        "hardware/high-to-low-transition",
        "transition",
        _as_int,
    ),
    ReadSpec(
        ATTR_NIGHT_TO_DAY_TRANSITION_VALUE,
        "hardware/low-to-high-transition",
        "transition",
        _as_int,
    ),
    ReadSpec(
        ATTR_DAY_DISPLAY_BRIGHTNESS,
        "hardware/high-display-brightness",
        "brightness",
        _as_int,
    ),
    ReadSpec(
        ATTR_DAY_BUTTON_BRIGHTNESS,
        "hardware/high-button-brightness",
        "brightness",
        _as_int,
    ),
    ReadSpec(
        ATTR_NIGHT_DISPLAY_BRIGHTNESS,
        "hardware/low-display-brightness",
        "brightness",
        _as_int,
    ),
    ReadSpec(
        ATTR_NIGHT_BUTTON_BRIGHTNESS,
        "hardware/low-button-brightness",
        "brightness",
        _as_int,
    ),
    ReadSpec(
        ATTR_SYNC_BUTTON_AND_DISPLAY_BRIGHTNESS,
        "hardware/sync-button-display-brightness",
        "sync",
        _as_bool,
    ),
    ReadSpec(
        ATTR_SYNC_BUTTON_AND_DISPLAY_COLOR,
        "hardware/sync-button-display-color",
        "sync",
        _as_bool,
    ),
    ReadSpec(
        ATTR_SYNC_DAY_AND_NIGHT_COLOR, "hardware/sync-high-low-color", "sync", _as_bool
    ),
    ReadSpec(
        ATTR_DAY_DISPLAY_COLOR, "hardware/high-display-color", "color", Color.from_wire
    ),
    ReadSpec(
        ATTR_DAY_BUTTON_COLOR, "hardware/high-button-color", "color", Color.from_wire
    ),
    ReadSpec(
        ATTR_NIGHT_DISPLAY_COLOR, "hardware/low-display-color", "color", Color.from_wire
    ),
    ReadSpec(
        ATTR_NIGHT_BUTTON_COLOR, "hardware/low-button-color", "color", Color.from_wire
    ),
    ReadSpec(ATTR_WEATHER, "software/weather", None, _as_weather_config),
    ReadSpec(
        ATTR_WEATHER_WAKE_UP_TIME, "software/weather-wakeup-time", "weatherwakeuptime"
    ),
    ReadSpec(ATTR_CONNECTED_TO_ALEXA, "alexa/lwa-status", "status", _as_bool),
    ReadSpec(
        ATTR_ALEXA_TAP_TO_TALK_TONE_ENABLED, "alexa/tap-talk-tone", "tone", _as_bool
    ),
    ReadSpec(
        ATTR_ALEXA_WAKE_WORD_TONE_ENABLED, "alexa/wake-word-tone", "tone", _as_bool
    ),
)


class CloudDoppler:
    """One clock, reachable over the cloud.

    Args:
        transport: Authenticated transport shared across the account.
        dsn: Serial number.
        name: Owner-assigned name, if any.
        device_info: Identity for the device registry.
        local_control: Whether LAN control was negotiated. Purely informational --
            nothing here requires it.
    """

    def __init__(
        self,
        transport: CloudTransport,
        dsn: str,
        name: str | None = None,
        device_info: ClockDeviceInfo | None = None,
        local_control: bool = False,
    ) -> None:
        """Initialise the clock facade."""
        self._transport = transport
        self.dsn = dsn
        self.name = name or dsn
        self.device_info = device_info or ClockDeviceInfo(dsn=dsn)
        self.local_control = local_control

    def __repr__(self) -> str:
        """Return representation."""
        return f"CloudDoppler(dsn={self.dsn}, name={self.name!r})"

    # ------------------------------------------------------------------ reads

    async def _read(self, spec: ReadSpec) -> Any:
        """Fetch one attribute, returning ``None`` on any recoverable failure."""
        if not is_endpoint_available(spec.path):
            # Skipped outright: no request, no timeout, no wasted second.
            return None
        try:
            body = await self._transport.async_get(spec.path, dsn=self.dsn)
        except EndpointUnavailableError:
            return None
        except DopplerConnectionError as err:
            _LOGGER.debug("%s: %s unavailable: %s", self.dsn, spec.path, err)
            return None
        if not isinstance(body, dict):
            return None
        value = body if spec.key is None else body.get(spec.key)
        if spec.convert is not None:
            return spec.convert(value)
        return value

    async def get_all_data(self) -> dict[str, Any]:
        """Fetch every coordinator attribute.

        Unlike doppyler, a single failing endpoint yields ``None`` for that attribute
        and leaves the rest intact. Endpoints measured never to answer are skipped
        without a request, which is what brings a cycle back inside the 60s interval.

        Returns:
            A mapping of attribute key to value, with ``None`` where a read failed.
        """
        data: dict[str, Any] = {}
        for spec in READ_SPECS:
            data[spec.attr] = await self._read(spec)
        for index in (1, 2):
            data[f"{ATTR_SMART_BUTTON_COLOR}_{index}"] = await self._read(
                ReadSpec(
                    f"{ATTR_SMART_BUTTON_COLOR}_{index}",
                    f"hardware/button{index}",
                    "color",
                    Color.from_wire,
                )
            )
        return data

    async def get_all_alarms(self) -> dict[int, Any]:
        """Fetch every alarm, keyed by id.

        Returns:
            Alarms in id order, or an empty dict if the clock could not be read.
        """
        try:
            body = await self._transport.async_get("alarms", dsn=self.dsn)
        except (DopplerConnectionError, EndpointUnavailableError) as err:
            _LOGGER.debug("%s: alarms unavailable: %s", self.dsn, err)
            return {}
        alarms = body.get("alarms") if isinstance(body, dict) else None
        if not isinstance(alarms, list):
            return {}
        # Sorted by id: the clock returns the list unsorted, with new alarms first.
        return {a["id"]: a for a in sorted(alarms, key=lambda a: a.get("id", 0))}

    async def get_smart_button_configuration(self, button_num: int) -> dict[str, Any]:
        """Read one smart button's stored configuration.

        Returns:
            The stored configuration, or an empty dict if it could not be read.
        """
        try:
            return await self._transport.async_get(
                f"hardware/button{button_num}", dsn=self.dsn
            )
        except (DopplerConnectionError, EndpointUnavailableError):
            return {}

    async def set_smart_button_configuration(
        self,
        button_num: int,
        url: str | None = None,
        command: str | None = None,
        color: Any = None,
    ) -> dict[str, Any]:
        """Update one smart button's webhook configuration.

        Mirrors doppyler: read the current configuration, overwrite only the supplied
        fields, and send the complete object back. Sending a partial body makes the
        clock fail with an oatpp null-pointer.

        Note:
            On firmware 1214 this write is accepted with a 200 and the response echoes
            the submitted body, but the stored value does not change. Always read back;
            never treat the response as confirmation.
        """
        config = await self.get_smart_button_configuration(button_num)
        if not config:
            _LOGGER.warning(
                "Smart button %s config unreadable; skipping write on %s",
                button_num,
                self.dsn,
            )
            return {}
        if url is not None:
            config["url"] = url
        if command is not None:
            config["command"] = command
        if color is not None:
            config["color"] = list(color)
        try:
            response = await self._transport.async_write(
                "PUT", f"hardware/button{button_num}", config
            )
        except (DopplerConnectionError, EndpointUnavailableError) as err:
            _LOGGER.debug("Smart button %s write failed: %s", button_num, err)
            return {}

        # Verify. The 200 and its echoed body are not evidence: on this firmware the
        # response repeats exactly what was submitted while storage is untouched, so a
        # caller checking only the response would believe it succeeded.
        stored = await self.get_smart_button_configuration(button_num)
        if stored.get("url") != config.get("url"):
            _LOGGER.warning(
                "Smart button %s on %s accepted the configuration but did not store it "
                "(requested url=%r, stored url=%r). This clock's firmware does not "
                "support smart button webhooks; the buttons will not fire.",
                button_num,
                self.dsn,
                config.get("url"),
                stored.get("url"),
            )
        return response

    # ----------------------------------------------------------------- writes

    async def _set_flag(
        self,
        path: str,
        key: str,
        value: Any,
        convert: Callable[[Any], Any] | None = None,
    ) -> Any:
        """PUT a one-field document and return the value the clock echoed back."""
        try:
            body = await self._transport.async_write("PUT", path, {key: value})
        except (DopplerConnectionError, EndpointUnavailableError) as err:
            _LOGGER.debug("PUT %s failed: %s", path, err)
            return None
        if not isinstance(body, dict) or key not in body:
            return None
        echoed = body[key]
        return convert(echoed) if convert is not None else echoed

    async def set_volume_level(self, level: int) -> int | None:
        """Set the clock volume."""
        return await self._set_flag("hardware/volume", "volume", level, _as_int)

    async def set_use_colon_mode(self, on: bool) -> bool | None:
        """Show or hide the colon."""
        return await self._set_flag("software/use-colon", "on", on, _as_bool)

    async def set_colon_blink_mode(self, on: bool) -> bool | None:
        """Enable or disable colon blinking."""
        return await self._set_flag("software/colon-blink", "blink", on, _as_bool)

    async def set_use_leading_zero_mode(self, on: bool) -> bool | None:
        """Show or hide the leading zero in 24-hour mode."""
        return await self._set_flag("software/use-leading-zero", "on", on, _as_bool)

    async def set_use_fade_time(self, on: bool) -> bool | None:
        """Enable or disable the one-minute display fade.

        Writes and reads ``software/use-fade-time``; doppyler reads
        ``software/use-leading-zero`` here, which is why its switch showed the wrong
        state after a successful write.
        """
        return await self._set_flag("software/use-fade-time", "on", on, _as_bool)

    async def set_display_seconds_mode(self, on: bool) -> bool | None:
        """Show or hide seconds on the mini display."""
        return await self._set_flag("software/display-seconds", "on", on, _as_bool)

    async def set_sound_preset_mode(self, on: bool) -> bool | None:
        """Enable or disable volume-dependent EQ."""
        return await self._set_flag(
            "hardware/sound-preset-mode", "presetmode", on, _as_bool
        )

    async def set_sync_day_night_color(self, on: bool) -> bool | None:
        """Keep day and night colours identical."""
        return await self._set_flag(
            "hardware/sync-high-low-color", "sync", on, _as_bool
        )

    async def set_sync_button_display_color(self, on: bool) -> bool | None:
        """Keep button and display colours identical."""
        return await self._set_flag(
            "hardware/sync-button-display-color", "sync", on, _as_bool
        )

    async def set_sync_button_display_brightness(self, on: bool) -> bool | None:
        """Keep button and display brightness identical."""
        return await self._set_flag(
            "hardware/sync-button-display-brightness", "sync", on, _as_bool
        )

    async def set_alexa_ascending_alarms_mode(self, on: bool) -> bool | None:
        """List alarms in ascending time order."""
        return await self._set_flag("alexa/ascending", "ascending", on, _as_bool)

    async def set_alexa_tap_to_talk_tone_enabled(self, on: bool) -> bool | None:
        """Play a tone when the tap-to-talk button is pressed."""
        return await self._set_flag("alexa/tap-talk-tone", "tone", on, _as_bool)

    async def set_alexa_wake_word_tone_enabled(self, on: bool) -> bool | None:
        """Play a tone when the wake word is heard."""
        return await self._set_flag("alexa/wake-word-tone", "tone", on, _as_bool)

    async def set_day_to_night_transition_value(self, value: int) -> int | None:
        """Set the light level at which the clock switches to night mode."""
        return await self._set_flag(
            "hardware/high-to-low-transition", "transition", value, _as_int
        )

    async def set_night_to_day_transition_value(self, value: int) -> int | None:
        """Set the light level at which the clock switches to day mode."""
        return await self._set_flag(
            "hardware/low-to-high-transition", "transition", value, _as_int
        )

    async def set_display_day_brightness(self, value: int) -> int | None:
        """Set day-time display brightness."""
        return await self._set_flag(
            "hardware/high-display-brightness", "brightness", value, _as_int
        )

    async def set_display_night_brightness(self, value: int) -> int | None:
        """Set night-time display brightness."""
        return await self._set_flag(
            "hardware/low-display-brightness", "brightness", value, _as_int
        )

    async def set_buttons_day_brightness(self, value: int) -> int | None:
        """Set day-time button brightness."""
        return await self._set_flag(
            "hardware/high-button-brightness", "brightness", value, _as_int
        )

    async def set_buttons_night_brightness(self, value: int) -> int | None:
        """Set night-time button brightness."""
        return await self._set_flag(
            "hardware/low-button-brightness", "brightness", value, _as_int
        )
