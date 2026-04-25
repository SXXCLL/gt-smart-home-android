"""
Light Controller
=================

Control individual lights or groups of lights by name / zone / "all".
"""

import time
import logging
from typing import Optional

from .gateway import Gateway
from .config import LightDevice
from .protocol import build_light_packet

log = logging.getLogger(__name__)

DEFAULT_MULTI_CMD_DELAY = 0.15


class LightController:
    def __init__(
        self,
        gateway: Gateway,
        devices: list[LightDevice],
        cmd_delay: float = DEFAULT_MULTI_CMD_DELAY,
    ):
        self.gateway = gateway
        self.devices = devices
        self.cmd_delay = cmd_delay

    def on(self, target: Optional[str] = None) -> list[tuple[str, bool]]:
        return self._control(target, on=True)

    def off(self, target: Optional[str] = None) -> list[tuple[str, bool]]:
        return self._control(target, on=False)

    def match(self, target: Optional[str] = None) -> list[LightDevice]:
        if target is None or target == "all":
            return list(self.devices)

        exact = [d for d in self.devices if d.name == target]
        if exact:
            return exact

        zone = [d for d in self.devices if d.zone == target]
        if zone:
            return zone

        partial = [d for d in self.devices if target in d.name]
        if partial:
            return partial

        return []

    def list_devices(self) -> list[LightDevice]:
        return list(self.devices)

    def _control(self, target: Optional[str], on: bool) -> list[tuple[str, bool]]:
        matched = self.match(target)
        if not matched:
            log.warning("No lights matched: %s", target)
            return []

        action = "ON" if on else "OFF"
        results = []

        for device in matched:
            packet = build_light_packet(device.dev_no, device.dev_ch, on)
            ok = self.gateway.send(packet)
            status = "ok" if ok else "FAILED"
            log.info("  %s %s (0x%02X:0x%02X) -> %s",
                     status, device.name, device.dev_no, device.dev_ch, action)
            results.append((device.name, ok))

            if len(matched) > 1:
                time.sleep(self.cmd_delay)

        return results

