import os
import time
from os.path import join

MAX_LOG_LINES = 1000


def _log_path() -> str:
    home = os.environ.get("HOME", ".")
    return join(home, "runtime.log")


def clear_log() -> None:
    with open(_log_path(), "w", encoding="utf-8") as f:
        f.write("")


def _line_count() -> int:
    path = _log_path()
    if not os.path.exists(path):
        return 0
    count = 0
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            count += chunk.count(b"\n")
    return count


def log_event(tag: str, message: str) -> None:
    if _line_count() > MAX_LOG_LINES:
        clear_log()
    line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} [{tag}] {message}\n"
    with open(_log_path(), "a", encoding="utf-8") as f:
        f.write(line)

