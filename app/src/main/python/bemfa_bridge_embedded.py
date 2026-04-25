import logging
import time
import re
import socket

import paho.mqtt.client as mqtt

from smart_home_zigbee import Gateway, LightController, ACController, FloorHeatingController, load_config
from smart_home_zigbee.fresh_air import FreshAirController
from smart_home_zigbee.config import FreshAirConfig
from smart_home_zigbee.protocol import build_scene_packet
from bridge_status import set_bridge_connected
from runtime_logger import log_event

log = logging.getLogger("bemfa_bridge")

TOPIC_SCENE_MAP = {
    "quankai006": "全开",
    "ketingcj006": "客厅",
    "cantingcj006": "餐厅",
    "zhuwocj006": "主卧",
    "huike006": "会客",
    "yingbin006": "迎宾",
    "wanan006": "晚安",
}

HOME_AWAY_SCENES = ("回家", "离家", "回家模式", "离家模式")

def parse_bemfa_command(payload: str, is_light: bool = True):
    payload = payload.strip().lower()
    if is_light:
        if payload.startswith("on"):
            return True
        if payload == "off":
            return False
    else:
        if payload == "on":
            return True
        if payload == "off":
            return False
    return None


def parse_temp_value(payload: str):
    """从 payload 中提取 16-32 的整数温度。"""
    m = re.search(r"(\d{2})", payload or "")
    if not m:
        return None
    temp = int(m.group(1))
    if 16 <= temp <= 32:
        return temp
    return None


def normalize_bemfa_topic(topic: str) -> str:
    """兼容 cloud 发布时附带 /set 或 /up 的情况。"""
    t = (topic or "").strip().lower()
    if t.endswith("/set") or t.endswith("/up"):
        return t.rsplit("/", 1)[0]
    return t


def bemfa_subscribe_variants(base_topic: str) -> list[str]:
    """
    巴法云常见约定：控制台/接口向设备下发控制时，主题常带 `/set` 后缀。
    标准 MQTT 下订阅 `foo` 不会收到发布到 `foo/set` 的消息，因此需同时订阅两者。
    """
    b = normalize_bemfa_topic(base_topic)
    if not b:
        return []
    out = [b]
    if not b.endswith("/set"):
        out.append(b + "/set")
    return out


def unique_subscribe_topic_list(*topic_groups: list[str]) -> list[str]:
    """合并多组 topic，按巴法习惯展开为 基名 + /set，去重并保持顺序。"""
    seen: set[str] = set()
    ordered: list[str] = []
    for group in topic_groups:
        for t in group:
            for v in bemfa_subscribe_variants(t):
                if v not in seen:
                    seen.add(v)
                    ordered.append(v)
    return ordered


def parse_ac_command(payload: str):
    """
    解析巴法云 005 空调指令:
    开关#模式#温度#风速#左右扫风#上下扫风
    例如: on#3#20#2
    """
    text = (payload or "").strip().lower()
    if not text:
        return None

    # 兼容一些平台可能下发的简化风速格式
    if text in ("low", "mid", "high", "auto"):
        return {
            "state": "on",
            "mode": None,
            "temp": None,
            "speed": text,
            "mode_raw": None,
            "speed_raw": None,
        }
    if text.startswith("speed#"):
        maybe_speed = text.split("#", 1)[1].strip()
        if maybe_speed in ("low", "mid", "high", "auto"):
            return {
                "state": "on",
                "mode": None,
                "temp": None,
                "speed": maybe_speed,
                "mode_raw": None,
                "speed_raw": None,
            }
        if maybe_speed.isdigit():
            speed_num = int(maybe_speed)
            speed_map = {
                0: "auto",
                1: "low",
                2: "mid",
                3: "high",
                4: "high",
                5: "high",
                8: "mid",
                9: "high",
            }
            return {
                "state": "on",
                "mode": None,
                "temp": None,
                "speed": speed_map.get(speed_num),
                "mode_raw": None,
                "speed_raw": speed_num,
            }

    parts = [p.strip() for p in text.split("#")]
    state = parts[0] if parts else ""
    if state not in ("on", "off"):
        return None

    mode_num = None
    temp_val = None
    speed_num = None
    if len(parts) > 1 and parts[1].isdigit():
        mode_num = int(parts[1])
    if len(parts) > 2 and parts[2].isdigit():
        temp_val = int(parts[2])
    speed_name = None
    if len(parts) > 3:
        speed_field = parts[3]
        if speed_field.isdigit():
            speed_num = int(speed_field)
        elif speed_field in ("low", "mid", "high", "auto"):
            speed_name = speed_field

    mode_map = {
        1: None,       # 自动模式，网关侧维持当前模式
        2: "cool",
        3: "heat",
        4: "fan",
        5: "dehumid",
        6: None,       # 睡眠模式，当前网关协议未单独支持
        7: None,       # 节能模式，当前网关协议未单独支持
        8: None,       # 净化模式，当前网关协议未单独支持
    }
    speed_map = {
        0: "auto",
        1: "low",
        2: "mid",
        3: "high",
        4: "high",
        5: "high",
        8: "mid",
        9: "high",
    }

    return {
        "state": state,
        "mode": mode_map.get(mode_num),
        "temp": temp_val if temp_val is not None and 16 <= temp_val <= 32 else None,
        "speed": speed_name if speed_name is not None else speed_map.get(speed_num),
        "mode_raw": mode_num,
        "speed_raw": speed_num,
    }


def build_device_topic_map(devices, device_type: str):
    """从 config 设备列表构建 MQTTTopic -> 设备名映射。"""
    mapping = {}
    for dev in devices:
        topic = normalize_bemfa_topic(getattr(dev, "mqtt_topic", ""))
        if not topic:
            log_event("WARN", f"{device_type} device missing MQTTTopic name={getattr(dev, 'name', '')}")
            continue
        if topic in mapping:
            log_event("WARN", f"Duplicate MQTTTopic topic={topic}, names={mapping[topic]},{dev.name}")
        mapping[topic] = dev.name
    return mapping


def build_hardware_scene_topic_map(hardware_scenes):
    mapping = {}
    for sc in hardware_scenes:
        topic = normalize_bemfa_topic(getattr(sc, "mqtt_topic", ""))
        if not topic:
            continue
        if topic in mapping:
            log_event("WARN", f"Duplicate scene MQTTTopic topic={topic}, names={mapping[topic]},{sc.name}")
        mapping[topic] = sc.name
    return mapping


class BemfaBridgeRunner:
    def __init__(self, stop_event):
        self.stop_event = stop_event
        self.client = None
        self.gateway = None

    def run(self, config_path: str, gateway_ip: str = "", bemfa_key: str = "") -> None:
        set_bridge_connected(False, "runner_start")
        config = load_config(config_path)
        if gateway_ip:
            config.gateway.ip = gateway_ip
        if bemfa_key:
            config.bemfa.key = bemfa_key
        config.bemfa.enabled = True
        log_event(
            "CFG",
            f"Bridge config gateway.ip={config.gateway.ip}, bemfa.broker={config.bemfa.broker}, bemfa.port={config.bemfa.port}"
        )
        if not config.bemfa.enabled:
            log.warning("bemfa.enabled=false，仍将继续尝试连接")
        if not config.bemfa.key:
            raise RuntimeError("bemfa.key 为空，无法连接巴法云")

        self.gateway = Gateway(config.gateway.ip, config.gateway.port)
        if self.gateway.connect():
            log.info("Gateway connected: %s:%d", config.gateway.ip, config.gateway.port)
        else:
            log.warning("Gateway not reachable, will auto-connect on first command")

        lights = LightController(self.gateway, config.lights)
        ac = ACController(self.gateway, config.acs)
        heat = FloorHeatingController(self.gateway, config.heats)
        hardware_scene_by_name = {s.name: s for s in config.hardware_scenes}
        topic_light_map = build_device_topic_map(config.lights, "light")
        topic_hw_scene_map = build_hardware_scene_topic_map(config.hardware_scenes)
        home_away_topic_by_name = {}
        for sc in config.hardware_scenes:
            if sc.name in HOME_AWAY_SCENES:
                t = normalize_bemfa_topic(getattr(sc, "mqtt_topic", ""))
                if t:
                    home_away_topic_by_name[sc.name] = t
        topic_fresh_map = build_device_topic_map(config.fresh_airs, "fresh_air")
        topic_ac_map = build_device_topic_map(config.acs, "ac")
        topic_heat_map = build_device_topic_map(config.heats, "heat")
        fresh_air_map = {}
        for dev in config.fresh_airs:
            fa_cfg = FreshAirConfig(
                dev_type=0x08,
                dev_no=dev.dev_no,
                ch_type=dev.ch_type,
                dev_ch=dev.dev_ch,
                default_speed=dev.default_speed,
            )
            fresh_air_map[dev.name] = FreshAirController(self.gateway, fa_cfg)

        def report_home_away_status_always_off():
            if self.client is None:
                return
            for scene_name, topic in home_away_topic_by_name.items():
                payload = "off"
                up_topic = topic + "/up"
                try:
                    self.client.publish(up_topic, payload, qos=0, retain=False)
                    log_event("TX", f"MQTT publish topic={up_topic}, payload={payload}")
                except Exception as e:
                    log_event("WARN", f"场景状态上报失败 scene={scene_name}, topic={up_topic}, err={e}")

        def on_connect(client, userdata, flags, reason_code, properties=None):
            rc = reason_code if isinstance(reason_code, int) else reason_code.value
            if rc != 0:
                log.error("MQTT connect failed, rc=%s", rc)
                log_event("NET", f"MQTT connect failed rc={rc}")
                set_bridge_connected(False, f"connect_failed_rc_{rc}")
                return
            all_topics = unique_subscribe_topic_list(
                list(topic_light_map),
                list(topic_hw_scene_map),
                list(TOPIC_SCENE_MAP),
                list(topic_fresh_map),
                list(topic_ac_map),
                list(topic_heat_map),
            )
            for topic in all_topics:
                try:
                    client.subscribe(topic, qos=0)
                except Exception as e:
                    log_event("WARN", f"MQTT subscribe exception topic={topic}, err={e}")
                time.sleep(0.1)
            log.info("Subscribed to %d topic filters", len(all_topics))
            log_event(
                "NET",
                f"MQTT connected and subscribed topic_filters={len(all_topics)} "
                f"(each device base + /set where applicable)"
            )
            set_bridge_connected(True, "mqtt_connected")
            report_home_away_status_always_off()

        def on_disconnect(client, userdata, flags, reason_code, properties=None):
            log_event("NET", f"MQTT disconnected rc={reason_code}")
            set_bridge_connected(False, f"mqtt_disconnected_{reason_code}")

        def on_message(client, userdata, msg):
            topic = msg.topic
            base_topic = normalize_bemfa_topic(topic)
            payload = msg.payload.decode("utf-8", errors="ignore").strip()
            log.info("MQTT: %s -> '%s'", topic, payload)
            log_event("RX", f"MQTT message topic={topic}, payload={payload}")

            if base_topic in topic_light_map:
                light_name = topic_light_map[base_topic]
                on = parse_bemfa_command(payload, is_light=True)
                if on is not None:
                    api_name = "LightController.on" if on else "LightController.off"
                    log_event("API", f"call {api_name}(target={light_name})")
                    lights.on(light_name) if on else lights.off(light_name)
                return

            if base_topic in topic_hw_scene_map:
                scene_name = topic_hw_scene_map[base_topic]
                scene = hardware_scene_by_name.get(scene_name)
                if scene is None:
                    log_event("WARN", f"Hardware scene missing name={scene_name}, topic={topic}")
                    return
                packet = build_scene_packet(scene.addr, scene.ch)
                ok = self.gateway.send(packet)
                # 回家/离家按“硬编码触发器”处理：不区分云端下发 on/off，均执行场景。
                log_event(
                    "API",
                    f"call HardwareScene.execute(name={scene_name}, payload={payload}, "
                    f"addr=0x{scene.addr:02X}, ch=0x{scene.ch:02X}, ok={ok})"
                )
                if scene_name in HOME_AWAY_SCENES:
                    report_home_away_status_always_off()
                return

            if base_topic in TOPIC_SCENE_MAP:
                scene_name = TOPIC_SCENE_MAP[base_topic]
                on = parse_bemfa_command(payload, is_light=False)
                if on is not None:
                    scene_lights = config.software_scenes.get(scene_name, [])
                    if scene_lights == ["*"]:
                        api_name = "LightController.on" if on else "LightController.off"
                        log_event("API", f"call {api_name}(target=all) from scene={scene_name}")
                        lights.on() if on else lights.off()
                    else:
                        for name in scene_lights:
                            api_name = "LightController.on" if on else "LightController.off"
                            log_event("API", f"call {api_name}(target={name}) from scene={scene_name}")
                            lights.on(name) if on else lights.off(name)
                return

            if base_topic in topic_fresh_map:
                device_name = topic_fresh_map[base_topic]
                controller = fresh_air_map.get(device_name)
                if controller is None:
                    log_event("WARN", f"FreshAir controller missing for device={device_name}, topic={topic}")
                    return
                # 新风按“空调式 005 payload”解析，但仅执行风量相关
                cmd = parse_ac_command(payload)
                if cmd is None:
                    log_event("WARN", f"Invalid FRESH(AC-like) payload topic={topic}, payload={payload}")
                    return
                if cmd["state"] == "off":
                    log_event("API", f"call FreshAirController.off(target={device_name})")
                    controller.off()
                    return
                speed = cmd["speed"] if cmd["speed"] in ("low", "mid", "high") else None
                log_event("API", f"call FreshAirController.on(target={device_name}, speed={speed})")
                controller.on(speed=speed)
                return

            if base_topic in topic_ac_map:
                device_name = topic_ac_map[base_topic]
                cmd = parse_ac_command(payload)
                if cmd is None:
                    log_event("WARN", f"Invalid AC payload topic={topic}, payload={payload}")
                    return
                log_event("NET", f"AC payload parsed topic={topic}, state={cmd['state']}, mode={cmd['mode']}, temp={cmd['temp']}, speed={cmd['speed']}, mode_raw={cmd['mode_raw']}, speed_raw={cmd['speed_raw']}")

                if cmd["state"] == "off":
                    log_event("API", f"call ACController.off(target={device_name})")
                    ac.off(device_name)
                    return

                log_event("API", f"call ACController.on(target={device_name})")
                ac.on(device_name)

                if cmd["mode"] is not None:
                    log_event("API", f"call ACController.set_mode(target={device_name}, mode={cmd['mode']})")
                    ac.set_mode(device_name, cmd["mode"])
                elif cmd["mode_raw"] is not None and cmd["mode_raw"] not in (1,):
                    log_event("WARN", f"AC mode not mapped mode_raw={cmd['mode_raw']} topic={topic}")

                if cmd["temp"] is not None:
                    log_event("API", f"call ACController.set_temp(target={device_name}, temp={cmd['temp']})")
                    ac.set_temp(device_name, cmd["temp"])

                if cmd["speed"] is not None:
                    log_event("API", f"call ACController.set_speed(target={device_name}, speed={cmd['speed']})")
                    ac.set_speed(device_name, cmd["speed"])
                elif cmd["speed_raw"] is not None:
                    log_event("WARN", f"AC speed not mapped speed_raw={cmd['speed_raw']} topic={topic}")
                return

            if base_topic in topic_heat_map:
                device_name = topic_heat_map[base_topic]
                # 地暖也按 005 空调协议接收，但只执行开关和温度
                cmd = parse_ac_command(payload)
                if cmd is None:
                    log_event("WARN", f"Invalid HEAT(005) payload topic={topic}, payload={payload}")
                    return

                if cmd["state"] == "off":
                    log_event("API", f"call FloorHeatingController.off(target={device_name})")
                    heat.off(device_name)
                    return

                log_event("API", f"call FloorHeatingController.on(target={device_name})")
                heat.on(device_name)

                if cmd["temp"] is not None:
                    log_event("API", f"call FloorHeatingController.set_temp(target={device_name}, temp={cmd['temp']})")
                    heat.set_temp(device_name, cmd["temp"])
                return

        self.client = mqtt.Client(
            client_id=config.bemfa.key,
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        )
        self.client.on_connect = on_connect
        self.client.on_disconnect = on_disconnect
        self.client.on_message = on_message
        self.client.reconnect_delay_set(min_delay=1, max_delay=5)
        connect_retry_sec = 5
        while not self.stop_event.is_set():
            try:
                self.client.connect(config.bemfa.broker, config.bemfa.port, keepalive=60)
                log_event("NET", f"MQTT connecting broker={config.bemfa.broker}:{config.bemfa.port}")
                self.client.loop_start()
                break
            except socket.gaierror as e:
                # 典型场景: 网络刚恢复但 DNS 尚未可用，避免直接导致桥接线程退出。
                set_bridge_connected(False, f"dns_resolve_failed_{e.errno}")
                log_event(
                    "NET",
                    f"MQTT connect DNS失败 broker={config.bemfa.broker}:{config.bemfa.port}, "
                    f"err={e}, {connect_retry_sec}s后重试"
                )
                if self.stop_event.wait(connect_retry_sec):
                    return
            except Exception as e:
                set_bridge_connected(False, f"connect_exception_{type(e).__name__}")
                log_event(
                    "NET",
                    f"MQTT connect异常 broker={config.bemfa.broker}:{config.bemfa.port}, "
                    f"err={e}, {connect_retry_sec}s后重试"
                )
                if self.stop_event.wait(connect_retry_sec):
                    return

        try:
            while not self.stop_event.wait(1):
                pass
        finally:
            log_event("LIFE", "Bridge run loop stopped")
            set_bridge_connected(False, "runner_stopped")
            self.stop()

    def stop(self):
        if self.client is not None:
            try:
                self.client.disconnect()
                log_event("NET", "MQTT disconnected")
                set_bridge_connected(False, "client_disconnect")
            except Exception:
                pass
            try:
                self.client.loop_stop()
            except Exception:
                pass
            self.client = None
        if self.gateway is not None:
            try:
                self.gateway.close()
            except Exception:
                pass
            self.gateway = None

