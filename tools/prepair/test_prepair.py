"""Tests for the pre-pair helper. Run: python3 -m unittest discover -s tools/prepair"""

import os
import socket
import sys
import threading
import time
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import procon  # noqa: E402
import swis_prepair  # noqa: E402

ADDRESS = bytes((0x11, 0x22, 0x33, 0x44, 0x55, 0x66))


def controller():
    return procon.ProController(ADDRESS, clock_ms=lambda: 0, pick_vibrator=lambda: 0xB0)


def subcommand(sub, *args):
    data = bytearray(48)
    data[0] = 0x05
    data[9] = sub
    data[10:10 + len(args)] = bytes(args)
    return bytes(data)


def ask(pc, sub, *args):
    return pc.handle_output_report(0x01, subcommand(sub, *args)).payload


class ProtocolMatchesTheApp(unittest.TestCase):
    """Same expected bytes as app/src/test/.../ProControllerProtocolTest.kt."""

    def test_no_state_before_identification(self):
        r = controller().build_input_report(procon.Pad(buttons=procon.BUTTON_A, lx=1.0))
        self.assertEqual(bytes(48), r)

    def test_device_info(self):
        pc = controller()
        r = ask(pc, 0x02)
        self.assertEqual(48, len(r))
        self.assertEqual(0x90, r[1])
        self.assertEqual(bytes((0x6F, 0xC8, 0x77)), r[5:8])
        self.assertEqual(bytes((0x16, 0xD8, 0x7D)), r[8:11])
        self.assertEqual(0xB0, r[11])
        self.assertEqual(bytes((0x82, 0x02, 0x03, 0x8B, 0x03, 0x02, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x01, 0x01)),
                         r[12:26])
        self.assertEqual(bytes(22), r[26:48])
        self.assertTrue(pc.device_info_queried)

    def test_acknowledgements(self):
        pc = controller()
        self.assertEqual(bytes((0x80, 0x08)), ask(pc, 0x08)[12:14])
        self.assertEqual(bytes((0x80, 0x03)), ask(pc, 0x03, 0x30)[12:14])
        self.assertEqual(0x30, pc.input_mode)
        self.assertEqual(bytes((0x83, 0x04)), ask(pc, 0x04)[12:14])
        self.assertEqual(bytes((0x80, 0x40)), ask(pc, 0x40, 0x01)[12:14])
        self.assertTrue(pc.imu_enabled)
        self.assertEqual(bytes((0x82, 0x48)), ask(pc, 0x48, 0x01)[12:14])
        self.assertEqual(bytes((0x80, 0x30)), ask(pc, 0x30, 0x01)[12:14])
        self.assertEqual(bytes((0x80, 0x22)), ask(pc, 0x22, 0x01)[12:14])

    def test_nfc_ir_config(self):
        r = ask(controller(), 0x21, 0x21, 0x00, 0x00)
        self.assertEqual(bytes((0xA0, 0x21, 0x01, 0x00, 0xFF, 0x00, 0x08, 0x00, 0x1B, 0x01)), r[12:22])
        self.assertEqual(0xC8, r[47])

    def test_handshake_and_player_lights(self):
        pc = controller()
        ask(pc, 0x02)
        ask(pc, 0x48, 0x01)
        self.assertFalse(pc.handshake_complete)
        ask(pc, 0x30, 0x03)
        self.assertTrue(pc.handshake_complete)
        self.assertEqual(2, pc.player)
        for pattern, player in {0x01: 1, 0x10: 1, 0x07: 3, 0x70: 3, 0x0F: 4, 0xF0: 4}.items():
            p = controller()
            ask(p, 0x30, pattern)
            self.assertEqual(player, p.player)

    def test_spi_reads(self):
        def spi(offset, length):
            r = ask(controller(), 0x10, offset & 0xFF, offset >> 8 & 0xFF, 0, 0, length)
            self.assertEqual(bytes((0x90, 0x10, offset & 0xFF, offset >> 8 & 0xFF, 0, 0, length)), r[12:19])
            return r[19:19 + length]
        self.assertEqual(b"\xff" * 16, spi(0x6000, 0x10))
        self.assertEqual(bytes((0x82, 0x82, 0x82, 0x0F, 0x0F, 0x0F)) + b"\xff" * 7, spi(0x6050, 0x0D))
        self.assertEqual(bytes((0x50, 0xFD, 0x00, 0x00, 0xC6, 0x0F)) + bytes(procon.SpiFlash.STICK_PARAMS),
                         spi(0x6080, 0x18))

    def test_unknown_subcommand_is_not_answered(self):
        reply = controller().handle_output_report(0x01, subcommand(0x50))
        self.assertIsNone(reply.payload)

    def test_buttons_land_in_the_right_bytes(self):
        pc = controller()
        ask(pc, 0x02)
        r = pc.build_input_report(procon.Pad(buttons=procon.BUTTON_L | procon.BUTTON_R))
        self.assertEqual((0x40, 0x00, 0x40), (r[2], r[3], r[4]))

    def test_address_parsing(self):
        self.assertEqual(ADDRESS, procon.parse_address("11-22-33-44-55-66"))
        self.assertEqual("11:22:33:44:55:66", procon.format_address(procon.parse_address("112233445566")))
        self.assertIsNone(procon.parse_address("11:22:33"))
        self.assertIsNone(procon.parse_address("GG:22:33:44:55:66"))


class HostSide(unittest.TestCase):
    def test_btmgmt_info(self):
        text = (
            "Index list with 2 items\n"
            "hci1:\tPrimary controller\n"
            "\taddr 00:1A:7D:DA:71:13 version 6 manufacturer 10 class 0x000000\n"
            "\tsupported settings: powered connectable\n"
            "\tname old\n"
            "\tshort name \n"
            "hci0:\tPrimary controller\n"
            "\taddr a4:c3:f0:11:22:33 version 10 manufacturer 2 class 0x0c010c\n"
            "\tname laptop\n"
        )
        info = swis_prepair.parse_btmgmt_info(text)
        self.assertEqual({0, 1}, set(info))
        self.assertEqual("A4:C3:F0:11:22:33", info[0]["addr"])
        self.assertEqual(0x0C010C, info[0]["class"])
        self.assertEqual("old", info[1]["name"])

    def test_sdp_record_is_valid_xml_with_descriptor(self):
        root = ET.fromstring(procon.sdp_record_xml())
        ids = {a.get("id") for a in root.findall("attribute")}
        self.assertTrue({"0x0001", "0x0004", "0x000d", "0x0202", "0x0206"} <= ids)
        texts = [t.get("value") for t in root.iter("text") if t.get("encoding") == "hex"]
        self.assertEqual([procon.HID_DESCRIPTOR_HEX], texts)

    def test_descriptor_matches_the_app(self):
        kt = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "java", "app", "swis",
                          "controller", "core", "ProControllerProtocol.kt")
        if not os.path.exists(kt):
            self.skipTest("app source not next to the helper")
        source = open(kt).read()
        start = source.index("val HID_DESCRIPTOR")
        chunk = source[start:source.index(")", start)]
        joined = "".join(part for part in chunk.split('"')[1::2])
        self.assertEqual(procon.HID_DESCRIPTOR_HEX, joined)

    def test_dropin_frees_hid_ports(self):
        text = swis_prepair.dropin_text("/usr/libexec/bluetooth/bluetoothd")
        self.assertIn("ExecStart=\n", text)
        self.assertIn("--noplugin=input", text)
        self.assertIn(swis_prepair.CONF, text)

    def test_controller_identity_in_main_conf(self):
        self.assertIn("Class = 0x002508", swis_prepair.MAIN_CONF)
        self.assertIn("Name = Pro Controller", swis_prepair.MAIN_CONF)
        self.assertIn("DeviceID = usb:057E:2009:0001", swis_prepair.MAIN_CONF)


class SimulatedConsole(unittest.TestCase):
    """Drives Session over socket pairs the way a console would during Change Grip/Order."""

    def test_full_setup_then_l_plus_r(self):
        ctrl, console_ctrl = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        itr, console_itr = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        replies, inputs = [], []
        seen_lr = threading.Event()

        def console():
            console_itr.settimeout(0.5)
            setup = [(0x02,), (0x08, 0x00), (0x10, 0x00, 0x60, 0, 0, 0x10), (0x03, 0x30),
                     (0x40, 0x01), (0x48, 0x01), (0x30, 0x10)]
            for i, cmd in enumerate(setup):
                console_itr.send(bytes((0xA2, 0x01)) + subcommand(*cmd))
                while True:
                    data = console_itr.recv(64)
                    if data[1] == 0x21:
                        replies.append(data[2:])
                        break
            console_ctrl.send(bytes((0x52, 0x01)))       # a SET_REPORT on the control channel
            self.assertEqual(b"\x00", console_ctrl.recv(8))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    data = console_itr.recv(64)
                except socket.timeout:
                    continue
                if data[1] == 0x30:
                    inputs.append(data[2:])
                    if data[2 + 2] & 0x40 and data[2 + 4] & 0x40:   # R and L held
                        seen_lr.set()
                        console_itr.send(bytes((0xA2, 0x01)) + subcommand(0x30, 0x01))
                        break

        t = threading.Thread(target=console, daemon=True)
        t.start()
        session = swis_prepair.Session(ctrl, itr, procon.ProController(ADDRESS))
        start = time.monotonic()
        self.assertTrue(session.run())
        t.join(2)
        self.assertTrue(seen_lr.is_set())
        self.assertTrue(session.lights_after_press)
        self.assertEqual(7, len(replies))
        self.assertEqual(0x02, replies[0][13])
        self.assertEqual(ADDRESS, replies[0][18:24])
        self.assertLess(time.monotonic() - start, 10)
        for s in (ctrl, console_ctrl, itr, console_itr):
            s.close()


if __name__ == "__main__":
    unittest.main()
