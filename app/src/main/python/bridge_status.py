import json
import os
import time
from os.path import join


def _status_path() -> str:
    home = os.environ.get("HOME", ".")
    return join(home, "bridge_status.json")


def set_bridge_connected(connected: bool, reason: str = "") -> None:
    payload = {
        "connected": bool(connected),
        "updated_at": int(time.time()),
        "reason": reason,
    }
    with open(_status_path(), "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False)

