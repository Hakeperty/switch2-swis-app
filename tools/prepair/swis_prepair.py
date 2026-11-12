#!/usr/bin/python3
"""Pre-pair a Switch 2 with an Android phone's Bluetooth address, from a Linux computer.

The console only pairs new controllers whose Bluetooth device class says "gamepad", and an
unrooted phone cannot change its class. A Linux computer can: this helper lends its adapter
the phone's address, the gamepad class and the name "Pro Controller", pairs with the console
the way the SWIS Controller app would, then puts the adapter back. Afterwards the console
already knows the phone's address.

Commands (run as root; the desktop app does that through pkexec):
  swis_prepair.py run --phone AA:BB:CC:DD:EE:FF [--adapter hci0] [--timeout 180]
  swis_prepair.py restore        put the adapter and bluetoothd back after a crash
  swis_prepair.py check          report what is missing, change nothing

Output lines starting with "STATUS " are headlines and "RESULT " is the final verdict; the
desktop app keys off those. Everything else is log.
"""

import argparse
import errno
import os
import queue
import re
import select
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import procon  # noqa: E402

RUN_DIR = "/run/swis-prepair"
CONF = RUN_DIR + "/main.conf"
DROPIN_DIR = "/run/systemd/system/bluetooth.service.d"
DROPIN = DROPIN_DIR + "/swis-prepair.conf"
STATE_DIR = "/var/lib/swis-prepair"
HID_UUID = "00001124-0000-1000-8000-00805f9b34fb"
PROFILE_PATH = "/app/swis/prepair"
PSM_CTRL, PSM_ITR = 17, 19
GAMEPAD_CLASS = 0x0508  # major "peripheral", minor "gamepad"

MAIN_CONF = """# Written by SWIS pre-pair; removed when it finishes.
[General]
Name = Pro Controller
Class = 0x002508
DeviceID = usb:057E:2009:0001
DiscoverableTimeout = 0
PairableTimeout = 0
ControllerMode = bredr
JustWorksRepairing = always
"""

CANCEL = threading.Event()


class Fail(Exception):
    pass


def log(msg):
    print(msg, flush=True)


def status(msg):
    print("STATUS " + msg, flush=True)


def run(cmd, check=True, timeout=20):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Fail("%s did not finish" % " ".join(cmd))
    out = (p.stdout or "") + (p.stderr or "")
    if check and p.returncode != 0:
        raise Fail("%s failed: %s" % (" ".join(cmd), out.strip()))
    return out


# ---------------------------------------------------------------- adapter (btmgmt) --------

def parse_btmgmt_info(text):
    """{index: {"addr": "AA:..", "class": int, "name": str}} from `btmgmt info` output."""
    adapters, current = {}, None
    for line in text.splitlines():
        m = re.match(r"^hci(\d+):", line)
        if m:
            current = int(m.group(1))
            adapters[current] = {"addr": None, "class": None, "name": ""}
            continue
        if current is None:
            continue
        m = re.search(r"\baddr ([0-9A-Fa-f:]{17})", line)
        if m:
            adapters[current]["addr"] = m.group(1).upper()
        m = re.search(r"\bclass 0x([0-9A-Fa-f]+)", line)
        if m:
            adapters[current]["class"] = int(m.group(1), 16)
        m = re.match(r"^\s+name (.*)$", line)
        if m:
            adapters[current]["name"] = m.group(1).strip()
    return adapters


def adapters():
    return parse_btmgmt_info(run(["btmgmt", "info"], check=False))


def adapter_addr(index):
    return adapters().get(index, {}).get("addr")


def set_public_address(index, addr):
    run(["btmgmt", "--index", str(index), "power", "off"], check=False)
    out = run(["btmgmt", "--index", str(index), "public-addr", addr], check=False)
    if re.search(r"fail|not supported|invalid|rejected", out, re.I):
        raise Fail("The adapter refused a new address (%s). " % out.strip()
                   + "Intel, Broadcom and Qualcomm adapters usually allow it; most Realtek ones do not. "
                   + "A cheap USB dongle with one of those chips works.")
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if adapter_addr(index) == addr:
            return
        time.sleep(0.3)
    raise Fail("The adapter still reports %s instead of %s; it does not support changing its address."
               % (adapter_addr(index), addr))


# ---------------------------------------------------------------- bluetoothd ---------------

def bluetoothd_path():
    out = run(["systemctl", "show", "-p", "ExecStart", "--value", "bluetooth"], check=False)
    m = re.search(r"path=(\S+)", out)
    candidates = ([m.group(1)] if m else []) + [
        "/usr/libexec/bluetooth/bluetoothd", "/usr/lib/bluetooth/bluetoothd",
        "/usr/lib/bluez/bluetooth/bluetoothd", "/usr/sbin/bluetoothd",
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    raise Fail("Could not find bluetoothd. Install BlueZ.")


def dropin_text(bluetoothd):
    # --noplugin=input frees the HID ports (PSM 17 and 19) so this helper can listen on them.
    return "[Service]\nExecStart=\nExecStart=%s -n --noplugin=input -f %s\n" % (bluetoothd, CONF)


def start_controller_bluetoothd():
    os.makedirs(RUN_DIR, exist_ok=True)
    with open(CONF, "w") as f:
        f.write(MAIN_CONF)
    os.makedirs(DROPIN_DIR, exist_ok=True)
    with open(DROPIN, "w") as f:
        f.write(dropin_text(bluetoothd_path()))
    run(["systemctl", "daemon-reload"])
    run(["systemctl", "start", "bluetooth"])


def remove_dropin():
    for p in (DROPIN, CONF):
        try:
            os.remove(p)
        except FileNotFoundError:
            pass
    run(["systemctl", "daemon-reload"], check=False)


def state_file(index):
    return os.path.join(STATE_DIR, "original-address-hci%d" % index)


def restore(index=None):
    """Undo everything: normal bluetoothd, original adapter address. Safe to repeat."""
    status("Putting the Bluetooth adapter back")
    run(["systemctl", "stop", "bluetooth"], check=False)
    remove_dropin()
    indexes = [index] if index is not None else sorted(adapters())
    for i in indexes:
        path = state_file(i)
        if not os.path.exists(path):
            continue
        original = open(path).read().strip()
        try:
            if adapter_addr(i) != original:
                set_public_address(i, original)
            log("Adapter hci%d is back to its own address %s." % (i, original))
            os.remove(path)
        except Fail as e:
            log("Could not restore hci%d's address (%s). Unplugging it or rebooting restores it." % (i, e))
    run(["systemctl", "start", "bluetooth"], check=False)
    log("Normal Bluetooth service restarted.")


# ---------------------------------------------------------------- D-Bus --------------------

class Bluez:
    def __init__(self, index):
        import dbus
        self.dbus = dbus
        self.bus = dbus.SystemBus()
        self.path = "/org/bluez/hci%d" % index
        deadline = time.monotonic() + 15
        while True:
            try:
                obj = self.bus.get_object("org.bluez", self.path)
                self.props = dbus.Interface(obj, "org.freedesktop.DBus.Properties")
                self.props.Get("org.bluez.Adapter1", "Address")
                break
            except dbus.exceptions.DBusException:
                if time.monotonic() > deadline:
                    raise Fail("bluetoothd did not show adapter hci%d." % index)
                time.sleep(0.3)

    def get(self, name):
        return self.props.Get("org.bluez.Adapter1", name)

    def set(self, name, value):
        self.props.Set("org.bluez.Adapter1", name, value)

    def register_profile(self):
        d = self.dbus
        manager = d.Interface(self.bus.get_object("org.bluez", "/org/bluez"), "org.bluez.ProfileManager1")
        manager.RegisterProfile(PROFILE_PATH, HID_UUID, {
            "ServiceRecord": procon.sdp_record_xml(),
            "Role": "server",
            "RequireAuthentication": d.Boolean(False),
            "RequireAuthorization": d.Boolean(False),
        })

    def close(self):
        try:
            self.bus.close()
        except Exception:
            pass


# ---------------------------------------------------------------- HID session --------------

def listen(psm):
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind((socket.BDADDR_ANY, psm))
    except OSError as e:
        s.close()
        if e.errno == errno.EADDRINUSE:
            raise Fail("Another program holds the controller ports (PSM %d). " % psm
                       + "Close other controller emulators and run Restore.")
        raise
    s.listen(1)
    return s


def accept(server, deadline):
    while not CANCEL.is_set():
        left = deadline - time.monotonic()
        if left <= 0:
            return None
        r, _, _ = select.select([server], [], [], min(left, 0.25))
        if r:
            return server.accept()
    return None


class Session:
    """Talks to the console until it has set the controller up and registered L+R."""

    def __init__(self, ctrl, itr, controller):
        self.ctrl, self.itr, self.pc = ctrl, itr, controller
        self.pad = procon.Pad()
        self.lock = threading.Lock()
        self.outbox = queue.Queue()
        self.spoke = False
        self.alive = True
        self.lights_after_press = False

    def send(self, report_id, payload):
        try:
            self.itr.send(bytes((0xA1, report_id)) + payload)
        except OSError:
            self.alive = False

    def pump(self):
        last = 0.0
        while self.alive and not CANCEL.is_set():
            interval = 1 / 15 if self.spoke else 1.0
            try:
                item = self.outbox.get(timeout=max(0.0, last + interval - time.monotonic()))
                if item is not None:
                    self.send(procon.INPUT_SUBCOMMAND_REPLY, item)
                    continue
            except queue.Empty:
                pass
            with self.lock:
                payload = self.pc.build_input_report(self.pad)
            self.send(procon.INPUT_FULL, payload)
            last = time.monotonic()

    def set_buttons(self, buttons):
        with self.lock:
            self.pad = procon.Pad(buttons=buttons)
        self.outbox.put(None)

    def receive(self, timeout):
        r, _, _ = select.select([self.itr, self.ctrl], [], [], timeout)
        for s in r:
            try:
                data = s.recv(512)
            except OSError:
                data = b""
            if not data:
                self.alive = False
                return
            if s is self.ctrl:
                if data[0] >> 4 == 0x5:          # SET_REPORT on the control channel
                    self.ctrl.send(b"\x00")      # HANDSHAKE: successful
                elif data[0] == 0x15:
                    log("Console removed this controller (virtual cable unplug).")
                continue
            if data[0] != 0xA2 or len(data) < 2:
                continue
            self.spoke = True
            with self.lock:
                reply = self.pc.handle_output_report(data[1], data[2:], self.pad)
            if reply is None:
                continue
            log("Console -> " + reply.description)
            if reply.subcommand == procon.SUB_SET_PLAYER_LIGHTS and self.pad.buttons:
                self.lights_after_press = True
            if reply.payload is not None:
                self.outbox.put(reply.payload)

    def run(self):
        threading.Thread(target=self.pump, daemon=True).start()
        deadline = time.monotonic() + 30
        while self.alive and not CANCEL.is_set() and not self.pc.handshake_complete:
            if time.monotonic() > deadline:
                raise Fail("The console connected but did not finish setting up the controller.")
            self.receive(0.1)
        if not self.pc.handshake_complete:
            return False
        status("Console set up the controller as player %d. Pressing L + R" % self.pc.player)

        # In Change Grip/Order the console registers a controller when it presses L + R.
        for _ in range(5):
            self.set_buttons(procon.BUTTON_L | procon.BUTTON_R)
            self._wait(0.6)
            self.set_buttons(0)
            self._wait(0.6)
            if self.lights_after_press or not self.alive:
                break
        if not self.lights_after_press:
            log("The console did not answer L + R; the pairing is usually stored anyway.")
        self._wait(2.0)   # let the console save its controller list
        self.alive = False
        return True

    def _wait(self, seconds):
        end = time.monotonic() + seconds
        while self.alive and not CANCEL.is_set() and time.monotonic() < end:
            self.receive(min(0.05, end - time.monotonic()))


# ---------------------------------------------------------------- main flow ----------------

def preflight(quiet=False):
    problems = []
    if os.geteuid() != 0:
        problems.append("Needs root (the desktop app asks for your password).")
    for tool in ("btmgmt", "bluetoothctl", "systemctl"):
        if not shutil.which(tool):
            problems.append("Missing %s (install BlueZ%s)." % (tool, " utilities" if tool == "btmgmt" else ""))
    if not hasattr(socket, "AF_BLUETOOTH"):
        problems.append("This Python was built without Bluetooth sockets.")
    try:
        import dbus  # noqa: F401
    except ImportError:
        problems.append("Missing the Python D-Bus module (python3-dbus).")
    if shutil.which("btmgmt") and not adapters():
        problems.append("No Bluetooth adapter found.")
    if not quiet:
        for p in problems:
            log("Problem: " + p)
        if not problems:
            for i, a in sorted(adapters().items()):
                log("Adapter hci%d: %s %s" % (i, a["addr"], a["name"]))
    return problems


def prepair(phone, index, timeout):
    problems = preflight(quiet=True)
    if problems:
        raise Fail(" ".join(problems))
    found = adapters()
    if index is None:
        index = min(found)
    if index not in found:
        raise Fail("No adapter hci%d." % index)

    os.makedirs(STATE_DIR, exist_ok=True)
    if not os.path.exists(state_file(index)):
        with open(state_file(index), "w") as f:
            f.write(found[index]["addr"])
    log("Adapter hci%d, own address %s." % (index, open(state_file(index)).read().strip()))

    status("Giving the adapter the phone's address %s" % phone)
    run(["systemctl", "stop", "bluetooth"], check=False)
    set_public_address(index, phone)

    status("Starting Bluetooth as a Pro Controller")
    start_controller_bluetoothd()
    bz = Bluez(index)
    agent = None
    ctrl_srv = itr_srv = ctrl = itr = None
    try:
        bz.set("Powered", bz.dbus.Boolean(True))
        if str(bz.get("Alias")) != "Pro Controller":
            bz.set("Alias", "Pro Controller")
        cls = int(bz.get("Class"))
        if cls & 0x1FFC != GAMEPAD_CLASS:
            run(["btmgmt", "--index", str(index), "class", "5", "8"], check=False)
            cls = int(bz.get("Class"))
        log("Address %s, name \"%s\", device class 0x%06X%s." % (
            bz.get("Address"), bz.get("Alias"), cls,
            " (gamepad)" if cls & 0x1FFC == GAMEPAD_CLASS else " (NOT gamepad)"))
        bz.register_profile()

        # Accept the console's pairing without a prompt.
        agent = subprocess.Popen(["bluetoothctl"], stdin=subprocess.PIPE,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True)
        agent.stdin.write("agent NoInputNoOutput\ndefault-agent\n")
        agent.stdin.flush()

        ctrl_srv, itr_srv = listen(PSM_CTRL), listen(PSM_ITR)
        bz.set("Pairable", bz.dbus.Boolean(True))
        bz.set("Discoverable", bz.dbus.Boolean(True))

        status("Ready. On the Switch 2 open Controllers -> Change Grip/Order now")
        deadline = time.monotonic() + timeout
        got = accept(ctrl_srv, deadline)
        if got is None:
            if CANCEL.is_set():
                raise Fail("Stopped.")
            raise Fail("The console did not connect within %d s. Is Change Grip/Order open, "
                       "and is the phone's own Bluetooth off?" % timeout)
        ctrl, console = got
        log("Console %s connected (control channel)." % console[0])
        got = accept(itr_srv, time.monotonic() + 10)
        if got is None:
            raise Fail("The console opened only half of the connection.")
        itr = got[0]
        status("Console connected, setting up")

        ok = Session(ctrl, itr, procon.ProController(procon.parse_address(phone))).run()
        if not ok:
            raise Fail("Stopped." if CANCEL.is_set() else "The console dropped the connection during setup.")
        with open(os.path.join(STATE_DIR, "console-address"), "w") as f:
            f.write(console[0] + "\n")
        log("Pairing data (link key) kept in /var/lib/bluetooth/%s/%s/info." % (phone, console[0]))
        return console[0]
    finally:
        for s in (itr, ctrl, itr_srv, ctrl_srv):
            if s is not None:
                s.close()
        if agent is not None:
            agent.kill()
        bz.close()


def watch_stdin():
    """The desktop app keeps our stdin open; when it closes it, stop and clean up."""
    def reader():
        for line in sys.stdin:
            if line.strip() == "stop":
                break
        CANCEL.set()
    threading.Thread(target=reader, daemon=True).start()


def main(argv=None):
    ap = argparse.ArgumentParser(description="Pre-pair a Switch 2 with a phone's Bluetooth address.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--phone", required=True, help="the phone's Bluetooth address")
    r.add_argument("--adapter", default=None, help="hci0, hci1, ... (default: the first)")
    r.add_argument("--timeout", type=int, default=180, help="seconds to wait for the console")
    r.add_argument("--stdin-control", action="store_true", help=argparse.SUPPRESS)
    sub.add_parser("restore")
    sub.add_parser("check")
    args = ap.parse_args(argv)

    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, lambda *_: CANCEL.set())

    if args.cmd == "check":
        problems = preflight()
        print("RESULT " + ("ok" if not problems else "fail " + " ".join(problems)), flush=True)
        return 0 if not problems else 1

    if os.geteuid() != 0:
        print("RESULT fail Needs root.", flush=True)
        return 1

    if args.cmd == "restore":
        restore()
        print("RESULT ok", flush=True)
        return 0

    raw = procon.parse_address(args.phone)
    if raw is None:
        print("RESULT fail \"%s\" is not a Bluetooth address (12 hex digits)." % args.phone, flush=True)
        return 1
    phone = procon.format_address(raw)
    index = None
    if args.adapter:
        m = re.fullmatch(r"(?:hci)?(\d+)", args.adapter)
        if not m:
            print("RESULT fail Adapter must look like hci0.", flush=True)
            return 1
        index = int(m.group(1))
    if args.stdin_control:
        watch_stdin()

    code = 1
    try:
        console = prepair(phone, index, args.timeout)
        verdict = "RESULT ok " + console
        code = 0
    except Fail as e:
        verdict = "RESULT fail " + str(e)
    except Exception as e:  # keep the restore below no matter what
        verdict = "RESULT fail Unexpected error: %r" % (e,)
    finally:
        try:
            restore(index)
        except Exception as e:
            log("Restore problem: %r. Run Restore again or reboot." % (e,))
    print(verdict, flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
