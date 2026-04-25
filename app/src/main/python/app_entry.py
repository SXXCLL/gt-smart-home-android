import os
import threading
import json
import time
import traceback
import urllib.parse
import urllib.request
import urllib.error
from os.path import dirname
from os.path import join

from bemfa_bridge_embedded import BemfaBridgeRunner
from bridge_status import set_bridge_connected
from runtime_logger import clear_log, log_event
from smart_home_zigbee import Gateway, LightController, ACController, FloorHeatingController, load_config

_stop_event = threading.Event()
_runner = None


def _ensure_runtime_config() -> str:
    home = os.environ["HOME"]
    config_path = join(home, "config.json")

    if not os.path.exists(config_path):
        example_path = join(dirname(__file__), "config.example.json")
        if os.path.exists(example_path):
            with open(example_path, "r", encoding="utf-8") as src, open(config_path, "w", encoding="utf-8") as dst:
                dst.write(src.read())
        else:
            with open(config_path, "w", encoding="utf-8") as f:
                f.write("{}")

    return config_path


def reset_config_from_example() -> str:
    """Overwrite config.json with config.example.json."""
    home = os.environ["HOME"]
    config_path = join(home, "config.json")
    example_path = join(dirname(__file__), "config.example.json")
    if not os.path.exists(example_path):
        raise FileNotFoundError(f"Missing config.example.json: {example_path}")

    with open(example_path, "r", encoding="utf-8") as src, open(config_path, "w", encoding="utf-8") as dst:
        dst.write(src.read())

    log_event("CFG", f"config reset from example: {config_path}")
    return config_path


def run_forever(gateway_ip: str, bemfa_key: str) -> None:
    global _runner
    _stop_event.clear()
    clear_log()
    set_bridge_connected(False, "service_start")
    log_event("LIFE", f"Service start gateway_ip={gateway_ip}, bemfa_key_len={len(bemfa_key)}")
    config_path = _ensure_runtime_config()
    log_event("CFG", f"Using config path: {config_path}")
    _runner = BemfaBridgeRunner(_stop_event)
    try:
        _runner.run(config_path, gateway_ip=gateway_ip, bemfa_key=bemfa_key)
    except Exception as e:
        log_event("ERR", f"run_forever crashed: {e}")
        log_event("ERR", traceback.format_exc())
        raise


def stop() -> None:
    global _runner
    _stop_event.set()
    log_event("LIFE", "Service stop requested")
    set_bridge_connected(False, "service_stop")
    if _runner is not None:
        _runner.stop()
        _runner = None


def control_device(gateway_ip: str, device_name: str, on: bool) -> None:
    config_path = _ensure_runtime_config()
    config = load_config(config_path)
    if gateway_ip:
        config.gateway.ip = gateway_ip

    action = "on" if on else "off"
    log_event("API", f"call control_device(target={device_name}, action={action}) from manual button")
    with Gateway(config.gateway.ip, config.gateway.port) as gw:
        lights = LightController(gw, config.lights)
        if any(d.name == device_name for d in config.lights):
            if on:
                lights.on(device_name)
            else:
                lights.off(device_name)
            return

        if any(d.name == device_name for d in config.acs):
            ac = ACController(gw, config.acs)
            if on:
                ac.on(device_name)
            else:
                ac.off(device_name)
            return

        if any(d.name == device_name for d in config.heats):
            heat = FloorHeatingController(gw, config.heats)
            if on:
                heat.on(device_name)
            else:
                heat.off(device_name)
            return

        raise ValueError(f"Unknown device: {device_name}")


def control_climate(gateway_ip: str, device_type: str, device_name: str, action: str, value: str = "") -> None:
    """Control AC/heat advanced actions from Android side."""
    config_path = _ensure_runtime_config()
    config = load_config(config_path)
    if gateway_ip:
        config.gateway.ip = gateway_ip

    device_type = (device_type or "").strip().lower()
    action = (action or "").strip().lower()
    log_event("API", f"call control_climate(type={device_type}, target={device_name}, action={action}, value={value})")

    with Gateway(config.gateway.ip, config.gateway.port) as gw:
        if device_type == "ac":
            ac = ACController(gw, config.acs)
            if action == "on":
                ac.on(device_name)
            elif action == "off":
                ac.off(device_name)
            elif action == "temp":
                ac.set_temp(device_name, int(value))
            elif action == "mode":
                ac.set_mode(device_name, value)
            elif action == "speed":
                ac.set_speed(device_name, value)
            else:
                raise ValueError(f"Unsupported AC action: {action}")
            return

        if device_type == "heat":
            heat = FloorHeatingController(gw, config.heats)
            if action == "on":
                heat.on(device_name)
            elif action == "off":
                heat.off(device_name)
            elif action == "temp":
                heat.set_temp(device_name, int(value))
            else:
                raise ValueError(f"Unsupported heat action: {action}")
            return

        raise ValueError(f"Unsupported device type: {device_type}")


def _http_get_json(url: str, params: dict) -> dict:
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request(
        f"{url}?{query}",
        headers={"User-Agent": "smart-home-zigbee-android/1.0"},
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        raw = resp.read().decode("utf-8", errors="ignore")
        data = json.loads(raw)
        if isinstance(data, dict):
            return data
        return {"code": -1, "message": "unexpected_json_type", "data": data}


def _http_post_json(url: str, payload: dict) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "User-Agent": "smart-home-zigbee-android/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        raw = resp.read().decode("utf-8", errors="ignore")
        data = json.loads(raw)
        if isinstance(data, dict):
            return data
        return {"code": -1, "message": "unexpected_json_type", "data": data}


def _create_topic_with_fallback(payload: dict) -> tuple[dict, str]:
    """
    创建 topic，按候选地址逐一尝试。
    返回 (响应, 实际使用的URL)。
    """
    urls = [
        "https://pro.bemfa.com/v1/createTopic",
        "http://pro.bemfa.com/v1/createTopic",
    ]
    last_err = None
    for url in urls:
        try:
            return _http_post_json(url, payload), url
        except Exception as e:
            last_err = e
    raise RuntimeError(f"all createTopic endpoints failed: {last_err}")


def sync_devices_to_bemfa_cloud(bemfa_key: str = "") -> str:
    """
    同步 config.json 中含 MQTTTopic 的设备到巴法云。
    已存在主题会跳过，不重复创建。
    """
    config_path = _ensure_runtime_config()
    config = load_config(config_path)
    key = (bemfa_key or config.bemfa.key or "").strip()
    if not key:
        raise ValueError("bemfa key 为空，无法同步设备到云")

    log_event("NET", "开始连接巴法云并读取现有主题")
    all_topic_resp = {}
    try:
        all_topic_resp = _http_get_json(
            "http://apis.bemfa.com/vb/api/v2/allTopic",
            {"openID": key, "type": 1},
        )
        if int(all_topic_resp.get("code", -1)) != 0:
            log_event("WARN", f"读取巴法云主题返回异常，将按空主题继续: {all_topic_resp}")
            all_topic_resp = {"data": []}
    except Exception as e:
        log_event("WARN", f"读取巴法云主题失败，将按空主题继续: {e}")
        all_topic_resp = {"data": []}

    existing_topics = set()
    raw_data = all_topic_resp.get("data", [])
    if isinstance(raw_data, list):
        entries = raw_data
    elif isinstance(raw_data, dict):
        entries = list(raw_data.values())
    else:
        entries = []
        log_event("WARN", f"allTopic data is not list/dict, type={type(raw_data).__name__}, value={raw_data}")

    for item in entries:
        topic = ""
        if isinstance(item, dict):
            topic = str(item.get("topic", "")).strip().lower()
        elif isinstance(item, str):
            topic = item.strip().lower()
        if topic:
            existing_topics.add(topic)

    device_items = []
    for dev in config.lights:
        device_items.append(("light", dev.name, (dev.mqtt_topic or "").strip().lower()))
    for sc in config.hardware_scenes:
        device_items.append(("scene_hw", sc.name, (sc.mqtt_topic or "").strip().lower()))
    for dev in config.fresh_airs:
        device_items.append(("fresh_air", dev.name, (dev.mqtt_topic or "").strip().lower()))
    for dev in config.acs:
        device_items.append(("ac", dev.name, (dev.mqtt_topic or "").strip().lower()))
    for dev in config.heats:
        device_items.append(("heat", dev.name, (dev.mqtt_topic or "").strip().lower()))

    added = 0
    skipped = 0
    failed = 0
    detail = []
    seen_topics = set()
    create_interval_sec = 0.3

    for dev_type, name, topic in device_items:
        if not topic:
            skipped += 1
            detail.append({"name": name, "topic": "", "status": "skip_no_topic", "type": dev_type})
            log_event("NET", f"设备上云跳过(no_topic) type={dev_type}, name={name}")
            continue
        if dev_type == "scene_hw" and not topic.endswith("006"):
            skipped += 1
            detail.append({"name": name, "topic": topic, "status": "skip_scene_hw_topic_not_006", "type": dev_type})
            log_event("NET", f"设备上云跳过(scene_hw_topic_not_006) name={name}, topic={topic}")
            continue
        if topic in seen_topics:
            skipped += 1
            detail.append({"name": name, "topic": topic, "status": "skip_duplicate_in_config", "type": dev_type})
            log_event("NET", f"设备上云跳过(duplicate_in_config) type={dev_type}, name={name}, topic={topic}")
            continue
        seen_topics.add(topic)

        if topic in existing_topics:
            skipped += 1
            detail.append({"name": name, "topic": topic, "status": "already_exists", "type": dev_type})
            log_event("NET", f"设备上云跳过(already_exists) type={dev_type}, name={name}, topic={topic}")
            continue

        payload = {
            "uid": key,
            "topic": topic,
            "type": 1,
            "name": name,
        }
        log_event("NET", f"设备上云创建中 type={dev_type}, name={name}, topic={topic}")
        try:
            create_resp, used_url = _create_topic_with_fallback(payload)
            code = int(create_resp.get("code", -1))
            if code == 0:
                added += 1
                existing_topics.add(topic)
                detail.append({"name": name, "topic": topic, "status": "created", "type": dev_type})
                log_event("NET", f"设备上云创建成功 type={dev_type}, name={name}, topic={topic}, url={used_url}")
            elif code == 40006:
                skipped += 1
                existing_topics.add(topic)
                detail.append({"name": name, "topic": topic, "status": "already_exists", "type": dev_type})
                log_event("NET", f"设备上云已存在 type={dev_type}, name={name}, topic={topic}, url={used_url}")
            else:
                failed += 1
                detail.append({"name": name, "topic": topic, "status": f"failed_code_{code}", "type": dev_type})
                log_event("NET", f"设备上云失败 code={code}, type={dev_type}, name={name}, topic={topic}, url={used_url}")
        except Exception as e:
            failed += 1
            detail.append({"name": name, "topic": topic, "status": f"failed_exception_{e}", "type": dev_type})
            log_event("NET", f"设备上云异常 type={dev_type}, name={name}, topic={topic}, error={e}")
        finally:
            time.sleep(create_interval_sec)

    result = {
        "added": added,
        "skipped": skipped,
        "failed": failed,
        "total": len(device_items),
        "detail": detail,
    }
    log_event("NET", f"设备上云完成 added={added}, skipped={skipped}, failed={failed}")
    return json.dumps(result, ensure_ascii=False)

