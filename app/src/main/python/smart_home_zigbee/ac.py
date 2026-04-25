"""
Air Conditioner Controller
===========================

Controls central air conditioning units: on/off, temperature, mode, and wind speed.
"""

import logging
from typing import Optional

from .gateway import Gateway
from .config import ACDevice
from .protocol import (
    CMD_ON, CMD_OFF, CMD_TEMP, CMD_MODE, CMD_WIND_SPEED,
    CMD_READ_ROOM_TEMP,
    AC_MODE_MAP, WIND_SPEED_MAP,
    build_ac_packet, encode_temp, decode_temp,
)

log = logging.getLogger(__name__)


class ACController:
    """Controls air conditioning units through the Zigbee gateway."""

    def __init__(self, gateway: Gateway, devices: list[ACDevice]):
        self.gateway = gateway
        self.devices = {d.name: d for d in devices}

    def _get(self, name: str) -> ACDevice:
        dev = self.devices.get(name)
        if dev is None:
            raise ValueError(
                f"Unknown AC '{name}'. Available: {list(self.devices.keys())}"
            )
        return dev

    def on(self, name: str) -> bool:
        dev = self._get(name)
        pkt = build_ac_packet(dev.dev_no, dev.dev_ch, CMD_ON)
        ok = self.gateway.send(pkt)
        log.info("  %s %s -> ON", "ok" if ok else "FAILED", name)
        return ok

    def off(self, name: str) -> bool:
        dev = self._get(name)
        pkt = build_ac_packet(dev.dev_no, dev.dev_ch, CMD_OFF)
        ok = self.gateway.send(pkt)
        log.info("  %s %s -> OFF", "ok" if ok else "FAILED", name)
        return ok

    def set_temp(self, name: str, temp: int) -> bool:
        if not 16 <= temp <= 32:
            raise ValueError(f"Temperature must be 16-32, got {temp}")
        dev = self._get(name)
        p1, p2 = encode_temp(temp)
        pkt = build_ac_packet(dev.dev_no, dev.dev_ch, CMD_TEMP, p1, p2)
        ok = self.gateway.send(pkt)
        log.info("  %s %s -> %dC", "ok" if ok else "FAILED", name, temp)
        return ok

    def set_mode(self, name: str, mode: str) -> bool:
        mode_val = AC_MODE_MAP.get(mode)
        if mode_val is None:
            raise ValueError(
                f"Invalid mode '{mode}'. Valid: {list(AC_MODE_MAP.keys())}"
            )
        dev = self._get(name)
        pkt = build_ac_packet(dev.dev_no, dev.dev_ch, CMD_MODE, 0x00, mode_val)
        ok = self.gateway.send(pkt)
        log.info("  %s %s -> mode %s", "ok" if ok else "FAILED", name, mode)
        return ok

    def set_speed(self, name: str, speed: str) -> bool:
        speed_val = WIND_SPEED_MAP.get(speed)
        if speed_val is None:
            raise ValueError(
                f"Invalid speed '{speed}'. Valid: {list(WIND_SPEED_MAP.keys())}"
            )
        dev = self._get(name)
        pkt = build_ac_packet(dev.dev_no, dev.dev_ch, CMD_WIND_SPEED, 0x00, speed_val)
        ok = self.gateway.send(pkt)
        log.info("  %s %s -> speed %s", "ok" if ok else "FAILED", name, speed)
        return ok

    def read_room_temp(self, name: str) -> Optional[float]:
        dev = self._get(name)
        pkt = build_ac_packet(dev.dev_no, dev.dev_ch, CMD_READ_ROOM_TEMP)
        resp = self.gateway.send_and_recv(pkt)
        if resp and len(resp) >= 2:
            return decode_temp(resp[-2], resp[-1])
        return None

    def list_devices(self) -> list[ACDevice]:
        return list(self.devices.values())
