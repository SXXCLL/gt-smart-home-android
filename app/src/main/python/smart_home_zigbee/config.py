"""
Configuration loader for smart-home-zigbee.

Loads device settings from a JSON config file.
"""

import os
import json
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

log = logging.getLogger(__name__)


@dataclass
class GatewayConfig:
    """Zigbee gateway connection settings."""
    ip: str = "192.168.71.12"
    port: int = 4196


@dataclass
class LightDevice:
    """A single light device."""
    name: str
    dev_no: int
    dev_ch: int
    zone: str = ""
    mqtt_topic: str = ""


@dataclass
class HardwareScene:
    """A hardware scene stored in the gateway (read-only, execute only)."""
    name: str
    addr: int
    ch: int
    mqtt_topic: str = ""


@dataclass
class FreshAirConfig:
    """Fresh air system device parameters."""
    dev_type: int = 0x08
    dev_no: int = 0x03
    ch_type: int = 0x59
    dev_ch: int = 0x01
    default_speed: str = "high"


@dataclass
class FreshAirDevice:
    """A fresh-air device modeled as AC-like speed-only device."""
    name: str
    dev_no: int
    dev_ch: int
    ch_type: int = 0x59
    default_speed: str = "high"
    zone: str = ""
    mqtt_topic: str = ""


@dataclass
class ACDevice:
    """A single air conditioner unit."""
    name: str
    dev_no: int
    dev_ch: int
    zone: str = ""
    mqtt_topic: str = ""


@dataclass
class HeatDevice:
    """A single floor heating circuit."""
    name: str
    dev_no: int
    dev_ch: int
    zone: str = ""
    mqtt_topic: str = ""


@dataclass
class BemfaConfig:
    """Bemfa MQTT bridge settings (optional)."""
    enabled: bool = False
    broker: str = "bemfa.com"
    port: int = 9501
    key: str = ""


@dataclass
class Config:
    """Top-level configuration."""
    gateway: GatewayConfig = field(default_factory=GatewayConfig)
    lights: list[LightDevice] = field(default_factory=list)
    hardware_scenes: list[HardwareScene] = field(default_factory=list)
    software_scenes: dict[str, list[str]] = field(default_factory=dict)
    fresh_airs: list[FreshAirDevice] = field(default_factory=list)
    acs: list[ACDevice] = field(default_factory=list)
    heats: list[HeatDevice] = field(default_factory=list)
    bemfa: BemfaConfig = field(default_factory=BemfaConfig)


def _parse_int(value: Any) -> int:
    """Parse integer from JSON value, supporting both 0x51 and 81 formats."""
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        value = value.strip()
        if value.startswith(("0x", "0X")):
            return int(value, 16)
        return int(value)
    return int(value)


def load_config(path: Optional[str] = None) -> Config:
    """
    Load configuration from a JSON file.

    Search order (if path not specified):
      1. ``CONFIG_FILE`` environment variable
      2. ``./config.json``
      3. ``$HOME/config.json`` (Android 默认写入位置)
      4. ``~/.smart-home-zigbee/config.json``
    """
    config_path = _find_config(path)
    log.info("Loading config from: %s", config_path)

    with open(config_path, "r", encoding="utf-8") as f:
        raw = json.load(f) or {}

    return _parse_config(raw)


def _find_config(path: Optional[str] = None) -> Path:
    """Find the config file path."""
    if path:
        p = Path(path)
        if p.exists():
            return p
        raise FileNotFoundError(f"Config file not found: {path}")

    env_path = os.environ.get("CONFIG_FILE")
    if env_path:
        p = Path(env_path)
        if p.exists():
            return p

    p = Path("config.json")
    if p.exists():
        return p

    home = os.environ.get("HOME")
    if home:
        p = Path(home) / "config.json"
        if p.exists():
            return p

    p = Path.home() / ".smart-home-zigbee" / "config.json"
    if p.exists():
        return p

    raise FileNotFoundError(
        "No config.json found. Copy config.example.json to config.json "
        "and fill in your device information.\n"
        "Searched: ./config.json, $HOME/config.json, ~/.smart-home-zigbee/config.json"
    )


def _parse_config(raw: dict) -> Config:
    """Parse raw JSON dict into Config object."""
    config = Config()

    gw = raw.get("gateway", {})
    if gw:
        config.gateway = GatewayConfig(
            ip=gw.get("ip", config.gateway.ip),
            port=int(gw.get("port", config.gateway.port)),
        )

    for item in raw.get("lights", []):
        config.lights.append(LightDevice(
            name=item["name"],
            dev_no=_parse_int(item["dev_no"]),
            dev_ch=_parse_int(item["dev_ch"]),
            zone=item.get("zone", ""),
            mqtt_topic=item.get("MQTTTopic", item.get("mqtt_topic", "")),
        ))

    scenes = raw.get("scenes", {})
    for item in scenes.get("hardware", []):
        config.hardware_scenes.append(HardwareScene(
            name=item["name"],
            addr=_parse_int(item["addr"]),
            ch=_parse_int(item["ch"]),
            mqtt_topic=item.get("MQTTTopic", item.get("mqtt_topic", "")),
        ))
    config.software_scenes = scenes.get("software", {})

    for item in raw.get("fresh_airs", []):
        config.fresh_airs.append(FreshAirDevice(
            name=item.get("name", "新风"),
            dev_no=_parse_int(item.get("dev_no", 0x03)),
            dev_ch=_parse_int(item.get("dev_ch", 0x01)),
            ch_type=_parse_int(item.get("ch_type", 0x59)),
            default_speed=item.get("default_speed", "high"),
            zone=item.get("zone", ""),
            mqtt_topic=item.get("MQTTTopic", item.get("mqtt_topic", "")),
        ))

    for item in raw.get("acs", []):
        config.acs.append(ACDevice(
            name=item["name"],
            dev_no=_parse_int(item["dev_no"]),
            dev_ch=_parse_int(item["dev_ch"]),
            zone=item.get("zone", ""),
            mqtt_topic=item.get("MQTTTopic", item.get("mqtt_topic", "")),
        ))

    for item in raw.get("heats", []):
        config.heats.append(HeatDevice(
            name=item["name"],
            dev_no=_parse_int(item["dev_no"]),
            dev_ch=_parse_int(item["dev_ch"]),
            zone=item.get("zone", ""),
            mqtt_topic=item.get("MQTTTopic", item.get("mqtt_topic", "")),
        ))

    bemfa = raw.get("bemfa", {})
    if bemfa:
        key = bemfa.get("key", "") or os.environ.get("BEMFA_KEY", "")
        config.bemfa = BemfaConfig(
            enabled=bemfa.get("enabled", False),
            broker=bemfa.get("broker", config.bemfa.broker),
            port=int(bemfa.get("port", config.bemfa.port)),
            key=key,
        )

    return config

