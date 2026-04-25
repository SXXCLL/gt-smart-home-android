"""
Command-line interface for smart-home-zigbee.
"""

import sys
import logging
import argparse

from .config import load_config
from .gateway import Gateway
from .light import LightController
from .scene import SceneController
from .fresh_air import FreshAirController
from .config import FreshAirConfig


def setup_logging(verbose: bool = False):
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )


def cmd_on_off(args, gw, config):
    lights = LightController(gw, config.lights)
    target = args.target or "all"

    if args.command == "on":
        results = lights.on(target)
    else:
        results = lights.off(target)

    if not results:
        print(f"No lights matched: {target}")
        print("Use 'smz list' to see available devices")
        return 1

    ok_count = sum(1 for _, ok in results if ok)
    total = len(results)
    action = "ON" if args.command == "on" else "OFF"
    print(f"\n{action}: {ok_count}/{total} lights OK")
    return 0 if ok_count == total else 1


def cmd_scene(args, gw, config):
    lights = LightController(gw, config.lights)
    scenes = SceneController(
        gw, config.hardware_scenes, config.software_scenes, lights
    )

    if not args.name:
        print("Available scenes:")
        for s in scenes.list_scenes():
            print(f"  {s}")
        return 0

    on = not args.off
    ok = scenes.execute(args.name, on=on)
    return 0 if ok else 1


def cmd_fresh_air(args, gw, config):
    if not config.fresh_airs:
        print("No fresh-air devices configured. Check fresh_airs in config.json")
        return 1
    dev = config.fresh_airs[0]
    fa_cfg = FreshAirConfig(
        dev_type=0x08,
        dev_no=dev.dev_no,
        ch_type=dev.ch_type,
        dev_ch=dev.dev_ch,
        default_speed=dev.default_speed,
    )
    fa = FreshAirController(gw, fa_cfg)

    if args.action == "on":
        ok = fa.on(speed=args.speed)
    elif args.action == "off":
        ok = fa.off()
    elif args.action == "speed":
        if not args.speed:
            print("Error: --speed is required for 'speed' action")
            return 1
        ok = fa.set_speed(args.speed)
    else:
        print(f"Unknown action: {args.action}")
        return 1

    return 0 if ok else 1


def cmd_list(args, gw, config):
    what = args.what or "lights"

    if what == "lights":
        lights = LightController(gw, config.lights)
        devices = lights.list_devices()
        if not devices:
            print("No lights configured. Check your config.json")
            return 0

        print(f"Gateway: {config.gateway.ip}:{config.gateway.port}")
        print(f"Lights: {len(devices)}")
        print()
        print(f"  {'Name':<14} {'DevNo':<8} {'DevCh':<8} {'Zone':<8}")
        print(f"  {'─'*14} {'─'*8} {'─'*8} {'─'*8}")
        for d in devices:
            print(f"  {d.name:<14} 0x{d.dev_no:02X}     0x{d.dev_ch:02X}     {d.zone}")
        print()
        zones = sorted(set(d.zone for d in devices if d.zone))
        print(f"Zones: {', '.join(zones)}")

    elif what == "scenes":
        lights = LightController(gw, config.lights)
        scenes = SceneController(
            gw, config.hardware_scenes, config.software_scenes, lights
        )
        print("Scenes:")
        for s in scenes.list_scenes():
            print(f"  {s}")

    else:
        print(f"Unknown list target: {what}")
        print("Available: lights, scenes")
        return 1

    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="smz",
        description="DNAKE Zigbee Smart Home Controller",
        epilog="If this project helps you, consider supporting the author: "
               "https://github.com/jupiter2021/smart-home-zigbee#打赏支持",
    )
    parser.add_argument("-c", "--config", help="Path to config.json")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")

    sub = parser.add_subparsers(dest="command")

    p_on = sub.add_parser("on", help="Turn on lights")
    p_on.add_argument("target", nargs="?", help="Light name, zone name, or 'all'")
    p_off = sub.add_parser("off", help="Turn off lights")
    p_off.add_argument("target", nargs="?", help="Light name, zone name, or 'all'")

    p_scene = sub.add_parser("scene", help="Execute a scene")
    p_scene.add_argument("name", nargs="?", help="Scene name (omit to list)")
    p_scene.add_argument("--off", action="store_true",
                         help="For software scenes: turn OFF instead of ON")

    p_fa = sub.add_parser("fresh-air", help="Control fresh air system")
    p_fa.add_argument("action", choices=["on", "off", "speed"],
                      help="Action to perform")
    p_fa.add_argument("--speed", choices=["low", "mid", "high"],
                      help="Wind speed (default: config default)")

    p_list = sub.add_parser("list", help="List devices or scenes")
    p_list.add_argument("what", nargs="?", choices=["lights", "scenes"],
                        help="What to list (default: lights)")

    args = parser.parse_args(argv)
    setup_logging(args.verbose)

    if not args.command:
        parser.print_help()
        return 0

    try:
        config = load_config(args.config)
    except (FileNotFoundError, ImportError) as e:
        print(f"Error: {e}")
        return 1

    if args.command == "list":
        gw = Gateway(config.gateway.ip, config.gateway.port)
        return cmd_list(args, gw, config)

    gw = Gateway(config.gateway.ip, config.gateway.port)
    if not gw.connect():
        print(f"Error: Cannot connect to gateway at {config.gateway.ip}:{config.gateway.port}")
        return 1

    try:
        if args.command in ("on", "off"):
            return cmd_on_off(args, gw, config)
        if args.command == "scene":
            return cmd_scene(args, gw, config)
        if args.command == "fresh-air":
            return cmd_fresh_air(args, gw, config)
    finally:
        gw.close()

    return 0


if __name__ == "__main__":
    sys.exit(main())

