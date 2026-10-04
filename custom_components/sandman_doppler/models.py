"""Value types for Sandman Doppler.

These replace the ones that came from ``doppyler==0.0.20``. Field names, enum members and
wire conversions are kept identical, because the platforms bind to them directly -- for
example ``light.py`` reads ``.red``/``.green``/``.blue`` off a :class:`Color` and
``select.py`` matches a :class:`WeatherMode` against its option list.

Nothing here performs I/O, so the whole module is importable and testable without Home
Assistant or a network.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import time, timedelta
from enum import Enum, IntEnum
from typing import Any, TypedDict


class ColorDict(TypedDict):
    """Representation of a colour dictionary."""

    red: int
    green: int
    blue: int


@dataclass
class Color:
    """An RGB colour.

    Colour endpoints answer ``{"color": [r, g, b]}``, so both a list and an object form
    are needed on the wire, while platforms read named channels.
    """

    red: int
    green: int
    blue: int

    def to_list(self) -> list[int]:
        """Convert to a list of ints."""
        return [self.red, self.green, self.blue]

    # Kept as an alias because clock.py grew up using as_list().
    as_list = to_list

    def to_dict(self) -> ColorDict:
        """Convert to a dictionary."""
        return ColorDict(red=self.red, green=self.green, blue=self.blue)

    @staticmethod
    def from_list(color_list: Any) -> "Color | None":
        """Convert a wire list to a Color, or ``None`` if it is not a valid triple.

        Returns ``None`` rather than raising: a malformed colour must not take down a
        whole coordinator cycle.
        """
        if not isinstance(color_list, (list, tuple)) or len(color_list) != 3:
            return None
        parts: list[int] = []
        for value in color_list:
            if isinstance(value, bool) or not isinstance(value, int):
                return None
            parts.append(value)
        return Color(*parts)

    @staticmethod
    def from_dict(color_dict: Any) -> "Color | None":
        """Convert a wire dictionary to a Color, or ``None`` if a channel is missing."""
        if not isinstance(color_dict, dict):
            return None
        parts: list[int] = []
        for channel in ("red", "green", "blue"):
            if channel not in color_dict:
                return None
            value = color_dict[channel]
            if isinstance(value, bool) or not isinstance(value, int):
                return None
            parts.append(value)
        return Color(*parts)


# ------------------------------------------------------------------- weather


class WeatherMode(IntEnum):
    """Weather mode.

    The number encodes provider, statistic and unit at once, so it means nothing on its
    own. Modes 1-12 resolve worldwide; 13-17 are US National Weather Service only.
    """

    OFF = 0
    FAHRENHEIT_SCALE = 1  # Daily high, weatherapi.com
    CELSIUS_SCALE = 2  # Daily high
    HUMIDITY_SCALE = 3  # Daily average humidity
    AQI_SCALE = 4  # Daily AQI
    FAHRENHEIT_SCALE_MIN = 5  # Daily low
    CELSIUS_SCALE_MIN = 6  # Daily low
    HUMIDITY_SCALE_MIN = 7  # Daily minimum humidity
    HUMIDITY_SCALE_MAX = 8  # Daily maximum humidity
    FAHRENHEIT_SCALE_HOURLY = 9  # Hourly temperature
    CELSIUS_SCALE_HOURLY = 10  # Hourly temperature
    HUMIDITY_SCALE_HOURLY = 11  # Hourly humidity
    AQI_SCALE_HOURLY = 12  # Hourly AQI
    NWS_DAILY_FORECAST_FAHRENHEIT_SCALE = 13  # US NWS only
    NWS_DAILY_FORECAST_CELSIUS_SCALE = 14  # US NWS only
    NWS_HOURLY_OBSERVATION_FAHRENHEIT_SCALE = 15  # US NWS only
    NWS_HOURLY_OBSERVATION_CELSIUS_SCALE = 16  # US NWS only
    NWS_HOURLY_OBSERVATION_HUMIDITY_SCALE = 17  # US NWS only


class WeatherConfigurationDict(TypedDict):
    """Weather configuration as it appears on the wire."""

    wsonoff: bool
    location: str
    wsmode: int


@dataclass
class WeatherConfiguration:
    """Weather settings, as the platforms consume them."""

    enabled: bool | None
    location: str | None
    mode: Any

    def to_dict(self) -> WeatherConfigurationDict:
        """Convert to the wire form."""
        return WeatherConfigurationDict(
            wsonoff=bool(self.enabled),
            location=str(self.location),
            wsmode=int(self.mode),
        )


# ---------------------------------------------------------------------- sound


class SoundPreset(Enum):
    """Audio preset as named by the clock."""

    BALANCED = "PRESET1"
    BASS_BOOST = "PRESET2"
    HIGH_BOOST = "PRESET3"
    MID_BOOST = "PRESET4"
    UNTUNED = "PRESET5"


# ---------------------------------------------------------------------- alarms

#: Alarm status as the clock reports it. Note 10 is the *active* value on this firmware:
#: modelling only 1 as enabled makes every real alarm render as switched off.
STATUS_LOOKUP: dict[int, str] = {
    0: "unset",
    1: "set",
    2: "ready",
    3: "activating",
    4: "active",
    5: "snoozing",
    6: "snoozed",
    7: "stopping",
    8: "stopped",
    9: "completed",
    10: "unarmed",
    11: "auto_arming",
}
_STATUS_BY_NAME = {name: value for value, name in STATUS_LOOKUP.items()}


class RepeatDayOfWeek(str, Enum):
    """Day of the week on which an alarm repeats, using the clock's own short codes."""

    MONDAY = "Mo"
    TUESDAY = "Tu"
    WEDNESDAY = "We"
    THURSDAY = "Th"
    FRIDAY = "Fr"
    SATURDAY = "Sa"
    SUNDAY = "Su"


class AlarmSource(IntEnum):
    """Where an alarm originated."""

    SYSTEM = 0
    APP = 1
    ALEXA = 2


class AlarmDict(TypedDict):
    """An alarm as it appears on the wire."""

    id: int
    name: str
    time_hr: int
    time_min: int
    repeat: str
    color: ColorDict
    volume: int
    status: int
    src: int
    sound: str


@dataclass
class Alarm:
    """One alarm.

    ``status`` is the status *name* rather than the wire integer, because that is what
    the siren and switch platforms compare against.
    """

    id: int
    name: str
    time: time
    repeat: list[RepeatDayOfWeek]
    color: Color
    volume: int
    status: str
    src: AlarmSource
    sound: str

    def update(self, alarm: "Alarm") -> None:
        """Overwrite every field except the id, which the clock owns."""
        self.name = alarm.name
        self.time = alarm.time
        self.repeat = alarm.repeat
        self.color = alarm.color
        self.volume = alarm.volume
        self.status = alarm.status
        self.src = alarm.src
        self.sound = alarm.sound

    def to_dict(self) -> AlarmDict:
        """Convert to the wire form."""
        return AlarmDict(
            id=self.id,
            name=self.name,
            time_hr=self.time.hour,
            time_min=self.time.minute,
            repeat="".join(day.value for day in self.repeat),
            color=self.color.to_dict(),
            volume=self.volume,
            status=_STATUS_BY_NAME.get(self.status, 0),
            src=int(self.src),
            sound=self.sound,
        )

    @staticmethod
    def from_dict(alarm_dict: Any) -> "Alarm | None":
        """Build from a wire alarm, or ``None`` if required fields are missing."""
        if not isinstance(alarm_dict, dict):
            return None
        try:
            hour = int(alarm_dict["time_hr"])
            minute = int(alarm_dict["time_min"])
            volume = int(alarm_dict["volume"])
        except (KeyError, TypeError, ValueError):
            return None
        status_value = alarm_dict.get("status", 0)
        try:
            status = STATUS_LOOKUP[int(status_value)]
        except (KeyError, TypeError, ValueError):
            status = "unset"
        try:
            src = AlarmSource(int(alarm_dict.get("src", 0)))
        except (TypeError, ValueError):
            src = AlarmSource.SYSTEM
        return Alarm(
            id=int(alarm_dict.get("id", 0)),
            name=str(alarm_dict.get("name", "")),
            time=time(hour=hour, minute=minute),
            repeat=parse_repeat(alarm_dict.get("repeat", "")),
            color=Color.from_dict(alarm_dict.get("color")) or Color(0, 0, 0),
            volume=volume,
            status=status,
            src=src,
            sound=str(alarm_dict.get("sound", "")),
        )


def parse_repeat(value: Any) -> list[RepeatDayOfWeek]:
    """Parse the clock's repeat string into day codes.

    The wire format is a concatenation of two-character codes, e.g. ``"MoWe"``. A literal
    ``"0"`` means no repeat and is stripped before parsing, which is what the clock sends
    for its own system alarm. Unknown chunks are ignored rather than raising, so a future
    code cannot take down a whole read.
    """
    if not isinstance(value, str):
        return []
    cleaned = value.replace("0", "")
    known = {day.value: day for day in RepeatDayOfWeek}
    days: list[RepeatDayOfWeek] = []
    for index in range(0, len(cleaned) - 1, 2):
        chunk = cleaned[index : index + 2]
        if chunk in known:
            days.append(known[chunk])
    return days


# ----------------------------------------------------------------- light bar


class Sparkle(Enum):
    """Sparkle intensity for a light bar effect."""

    NONE = "none"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    DEFAULT = NONE


class Direction(Enum):
    """Direction a light bar effect travels."""

    BOUNCE = "bounce"
    LEFT = "left"
    RIGHT = "right"
    DEFAULT = RIGHT


class Mode(Enum):
    """Light bar effect style."""

    SET = "set"
    SET_EACH = "set-each"
    BLINK = "blink"
    PULSE = "pulse"
    COMET = "comet"
    SWEEP = "sweep"


@dataclass
class LightBarDisplayEffect:
    """A one-shot light bar animation request."""

    mode: Mode
    duration: timedelta
    colors: list[Color] | None = None
    speed: int | None = None
    sparkle: Sparkle | None = None
    rainbow: bool | None = None
    size: int | None = None
    direction: Direction | None = None
    gap: int | None = None

    def to_dict(self) -> dict[str, Any]:
        """Convert to the wire form.

        Unset attributes are omitted entirely, and the effect-specific values are nested
        under ``attributes`` as strings, which is what the clock expects.
        """
        attributes: dict[str, str] = {"display": self.mode.value}
        if self.sparkle is not None:
            attributes["sparkle"] = self.sparkle.value
        if self.rainbow is not None:
            attributes["rainbow"] = str(self.rainbow).lower()
        if self.size is not None:
            attributes["size"] = str(self.size)
        if self.direction is not None:
            attributes["direction"] = self.direction.value
        if self.gap is not None:
            attributes["gap"] = str(self.gap)

        payload: dict[str, Any] = {
            "duration": int(self.duration.total_seconds()),
            "attributes": attributes,
        }
        if self.colors:
            payload["colors"] = [color.to_list() for color in self.colors]
        if self.speed is not None:
            payload["speed"] = self.speed
        return payload


class RainbowMode(str, Enum):
    """Which half of the display the rainbow animation applies to."""

    DAY = "day"
    NIGHT = "night"
    BOTH = "both"


@dataclass
class RainbowConfiguration:
    """The lightbar's rainbow animation settings."""

    speed: int
    mode: RainbowMode

    def to_dict(self) -> dict[str, Any]:
        """Convert to the wire form."""
        return {"speed": self.speed, "mode": self.mode.value}

    @staticmethod
    def from_dict(payload: Any) -> "RainbowConfiguration | None":
        """Build from a wire payload, or ``None`` if a required field is missing."""
        if (
            not isinstance(payload, dict)
            or "speed" not in payload
            or "mode" not in payload
        ):
            return None
        return RainbowConfiguration(payload["speed"], RainbowMode(payload["mode"]))


# ------------------------------------------------------------- display overrides


@dataclass
class MainDisplayText:
    """A one-shot scrolling text override."""

    text: str
    duration: timedelta
    speed: int
    color: Color

    def to_dict(self) -> dict[str, Any]:
        """Convert to the wire form."""
        return {
            "text": self.text,
            "duration": int(self.duration.total_seconds()),
            "speed": self.speed,
            "color": self.color.to_list(),
        }


@dataclass
class MiniDisplayNumber:
    """A one-shot mini display number override."""

    number: int
    duration: timedelta
    color: Color

    def to_dict(self) -> dict[str, Any]:
        """Convert to the wire form."""
        return {
            "num": self.number,
            "duration": int(self.duration.total_seconds()),
            "color": self.color.to_list(),
        }


# --------------------------------------------------------------- smart button


@dataclass
class SmartButtonConfiguration:
    """A smart button's stored webhook configuration.

    Only the read path is reachable on firmware 1214: the write returns 200 and echoes
    the request without storing it.
    """

    webhook_url: str
    command: str
    color: Color

    def to_dict(self) -> dict[str, Any]:
        """Convert to the wire form."""
        return {
            "url": self.webhook_url,
            "command": self.command,
            "color": self.color.to_list(),
        }

    @staticmethod
    def from_dict(config: Any) -> "SmartButtonConfiguration | None":
        """Build from a wire configuration, or ``None`` if it is unreadable."""
        if not isinstance(config, dict):
            return None
        return SmartButtonConfiguration(
            webhook_url=str(config.get("url", "")),
            command=str(config.get("command", "")),
            color=Color.from_list(config.get("color")) or Color(1, 1, 1),
        )


# ------------------------------------------------------------------------ wifi


@dataclass
class WifiStatus:
    """WiFi diagnostics.

    Only reachable from ``hardware/wifi-status``, which does not answer on firmware
    1214, so every field is optional and the reader yields ``None`` instead.
    """

    uptime: timedelta | None
    ssid: str | None
    signal_strength: str | None

    @staticmethod
    def from_dict(payload: Any) -> "WifiStatus | None":
        """Build from a wire payload, or ``None``."""
        if not isinstance(payload, dict):
            return None
        uptime_ms = payload.get("uptime")
        return WifiStatus(
            uptime=(
                None if uptime_ms is None else timedelta(milliseconds=float(uptime_ms))
            ),
            ssid=payload.get("ssid"),
            signal_strength=payload.get("str"),
        )
