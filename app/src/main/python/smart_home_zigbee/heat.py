"""
Floor Heating Controller
=========================

Controls floor heating units: on/off and temperature setting.
"""

import logging

from .gateway import Gateway
from .config import HeatDevice
from .protocol import (
    CMD_ON, CMD_OFF, CMD_TEMP,
    build_heat_packet, encode_temp,
)

log = logging.getLogger(__name__)


class FloorHeatingController:
    """Controls floor heating through the Zigbee gateway."""

    def __init__(self, gateway: Gateway, devices: list[HeatDevice]):
        self.gateway = gateway
        self.devices = {d.name: d for d in devices}

    def _get(self, name: str) -> HeatDevice:
        dev = self.devices.get(name)
        if dev is None:
            raise ValueError(
                f"Unknown heat '{name}'. Available: {list(self.devices.keys())}"
            )
        return dev

    def on(self, name: str) -> bool:
        dev = self._get(name)
        pkt = build_heat_packet(dev.dev_no, dev.dev_ch, CMD_ON)
        ok = self.gateway.send(pkt)
        log.info("  %s %s -> ON", "ok" if ok else "FAILED", name)
        return ok

    def off(self, name: str) -> bool:
        dev = self._get(name)
        pkt = build_heat_packet(dev.dev_no, dev.dev_ch, CMD_OFF)
        ok = self.gateway.send(pkt)
        log.info("  %s %s -> OFF", "ok" if ok else "FAILED", name)
        return ok

    def set_temp(self, name: str, temp: int) -> bool:
        if not 16 <= temp <= 32:
            raise ValueError(f"Temperature must be 16-32, got {temp}")
        dev = self._get(name)
        p1, p2 = encode_temp(temp)
        pkt = build_heat_packet(dev.dev_no, dev.dev_ch, CMD_TEMP, p1, p2)
        ok = self.gateway.send(pkt)
        log.info("  %s %s -> %dC", "ok" if ok else "FAILED", name, temp)
        return ok

    def list_devices(self) -> list[HeatDevice]:
        return list(self.devices.values())
