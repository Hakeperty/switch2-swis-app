"""Pro Controller (first generation) protocol, controller side.

A line-for-line port of the app's ``core/ProControllerProtocol.kt`` so the console sees the
same answers from this helper as it later sees from the phone. Payloads exclude the HID
transaction header (0xA1 / 0xA2) and the report ID, exactly as in the Kotlin code.
"""

import time

INPUT_FULL = 0x30
INPUT_SUBCOMMAND_REPLY = 0x21
OUTPUT_RUMBLE_AND_SUBCOMMAND = 0x01
REPORT_PAYLOAD_SIZE = 48

SUB_DEVICE_INFO = 0x02
SUB_SET_INPUT_MODE = 0x03
SUB_TRIGGER_BUTTONS = 0x04
SUB_SET_SHIPMENT = 0x08
SUB_SPI_READ = 0x10
SUB_SET_NFC_IR_CONFIG = 0x21
SUB_SET_NFC_IR_STATE = 0x22
SUB_SET_PLAYER_LIGHTS = 0x30
SUB_ENABLE_IMU = 0x40
SUB_ENABLE_VIBRATION = 0x48

NAMES = {
    SUB_DEVICE_INFO: "request device info",
    SUB_SET_INPUT_MODE: "set input report mode",
    SUB_TRIGGER_BUTTONS: "trigger buttons elapsed time",
    SUB_SET_SHIPMENT: "set shipment state",
    SUB_SPI_READ: "SPI flash read",
    SUB_SET_NFC_IR_CONFIG: "set NFC/IR config",
    SUB_SET_NFC_IR_STATE: "set NFC/IR state",
    SUB_SET_PLAYER_LIGHTS: "set player lights",
    SUB_ENABLE_IMU: "enable motion sensor",
    SUB_ENABLE_VIBRATION: "enable vibration",
}

OFFSET_BODY = 12
OFFSET_SUBCOMMAND_IN = 9
MAX_SPI_READ = 0x1D
BATTERY_FULL_PRO = 0x90
VIBRATOR_BYTES = (0xA0, 0xB0, 0xC0, 0x90)
NFC_IR_CONFIG_REPLY = bytes((0x01, 0x00, 0xFF, 0x00, 0x08, 0x00, 0x1B, 0x01))
IMU_IDLE = bytes((
    0x75, 0xFD, 0xFD, 0xFF, 0x09, 0x10, 0x21, 0x00, 0xD5, 0xFF, 0xE0, 0xFF,
    0x72, 0xFD, 0xF9, 0xFF, 0x0A, 0x10, 0x22, 0x00, 0xD5, 0xFF, 0xE0, 0xFF,
    0x76, 0xFD, 0xFC, 0xFF, 0x09, 0x10, 0x23, 0x00, 0xD5, 0xFF, 0xE0, 0xFF,
))

PLAYER_LIGHTS = {0x01: 1, 0x10: 1, 0x03: 2, 0x30: 2, 0x07: 3, 0x70: 3, 0x0F: 4, 0xF0: 4}

# Button bits inside the 24-bit button field (same as core/PadState.kt).
BUTTON_A = 1 << 3
BUTTON_R = 1 << 6
BUTTON_L = 1 << 22

HID_DESCRIPTOR_HEX = (
    "05010905a1010601ff8521092175089530810285300930750895308102853109317508966901"
    "810285320932750896690181028533093375089669018102853f0509190129101500250175"
    "0195108102050109391500250775049501814205097504950181010501093009310933093416"
    "000027ffff00007510950481020601ff850109017508953091028510091075089530910285"
    "11091175089530910285120912750895309102c0"
)

# Stick calibration, identical to StickCodec in the app.
_LEFT = (2159, 1466, 1517, 1916, 1583, 1465)   # x centre, above, below; y centre, above, below
_RIGHT = (2070, 1414, 1522, 2013, 1510, 1531)


def parse_address(text):
    """'AA:BB:CC:DD:EE:FF' (any separator, any case) -> 6 bytes, or None."""
    digits = "".join(c for c in text if c.isalnum())
    if len(digits) != 12:
        return None
    try:
        return bytes.fromhex(digits)
    except ValueError:
        return None


def format_address(raw):
    return ":".join("%02X" % b for b in raw)


def _axis(v, centre, above, below):
    v = max(-1.0, min(1.0, v))
    raw = centre + int(round(v * (above if v >= 0 else below)))
    return max(0, min(0xFFF, raw))


def _pack(x, y):
    return bytes((x & 0xFF, ((y & 0xF) << 4) | ((x >> 8) & 0xF), (y >> 4) & 0xFF))


def encode_stick(cal, x, y):
    return _pack(_axis(x, cal[0], cal[1], cal[2]), _axis(y, cal[3], cal[4], cal[5]))


class SpiFlash:
    """The slice of SPI flash the console reads during setup; the rest reads as 0xFF."""

    STICK_PARAMS = (
        0x0F, 0x30, 0x61, 0x96, 0x30, 0xF3, 0xD4, 0x14, 0x54,
        0x41, 0x15, 0x54, 0xC7, 0x79, 0x9C, 0x33, 0x36, 0x63,
    )

    def __init__(self):
        self.regions = {
            0x6020: (0xD3, 0xFF, 0xD5, 0xFF, 0x55, 0x01, 0x00, 0x40, 0x00, 0x40, 0x00, 0x40,
                     0x19, 0x00, 0xDD, 0xFF, 0xDC, 0xFF, 0x3B, 0x34, 0x3B, 0x34, 0x3B, 0x34),
            0x603D: (0xBA, 0xF5, 0x62, 0x6F, 0xC8, 0x77, 0xED, 0x95, 0x5B),
            0x6046: (0x16, 0xD8, 0x7D, 0xF2, 0xB5, 0x5F, 0x86, 0x65, 0x5E),
            0x6050: (0x82, 0x82, 0x82, 0x0F, 0x0F, 0x0F),
            0x6080: (0x50, 0xFD, 0x00, 0x00, 0xC6, 0x0F),
            0x6086: self.STICK_PARAMS,
            0x6098: self.STICK_PARAMS,
        }

    def read(self, offset, length):
        out = bytearray(b"\xff" * length)
        for start, data in self.regions.items():
            for i, b in enumerate(data):
                at = start + i - offset
                if 0 <= at < length:
                    out[at] = b
        return bytes(out)


class Pad:
    """Buttons (24-bit field) and sticks in [-1, 1], +y up."""

    def __init__(self, buttons=0, lx=0.0, ly=0.0, rx=0.0, ry=0.0):
        self.buttons, self.lx, self.ly, self.rx, self.ry = buttons, lx, ly, rx, ry


NEUTRAL = Pad()


class Reply:
    def __init__(self, subcommand, payload, description):
        self.subcommand, self.payload, self.description = subcommand, payload, description


class ProController:
    def __init__(self, address, clock_ms=None, pick_vibrator=None):
        if len(address) != 6:
            raise ValueError("Bluetooth address must be 6 bytes")
        self.address = bytes(address)
        self.flash = SpiFlash()
        self._clock = clock_ms or (lambda: time.monotonic_ns() // 1_000_000)
        self._pick = pick_vibrator or (lambda: VIBRATOR_BYTES[time.monotonic_ns() & 3])
        self.reset()

    def reset(self):
        self.device_info_queried = False
        self.vibration_enabled = False
        self.imu_enabled = False
        self.player = None
        self.input_mode = 0
        self._timer = 0
        self._last_timer_at = -1
        self._vibrator = 0xA0

    @property
    def handshake_complete(self):
        return self.vibration_enabled and self.player is not None

    def build_input_report(self, pad):
        r = bytearray(REPORT_PAYLOAD_SIZE)
        self._fill_standard(r, pad)
        if self.imu_enabled:
            r[OFFSET_BODY:OFFSET_BODY + len(IMU_IDLE)] = IMU_IDLE
        return bytes(r)

    def handle_output_report(self, report_id, data, pad=NEUTRAL):
        if report_id != OUTPUT_RUMBLE_AND_SUBCOMMAND or len(data) < OFFSET_SUBCOMMAND_IN + 1:
            return None
        sub = data[OFFSET_SUBCOMMAND_IN]

        def arg(i):
            j = OFFSET_SUBCOMMAND_IN + 1 + i
            return data[j] if j < len(data) else 0

        # Unknown subcommands stay unanswered: a NACK makes the console retry forever.
        if sub not in NAMES:
            return Reply(sub, None, "0x%02X unknown (ignored)" % sub)

        if sub == SUB_DEVICE_INFO:
            self.device_info_queried = True
        self._vibrator = self._pick() & 0xFF

        r = bytearray(REPORT_PAYLOAD_SIZE)
        self._fill_standard(r, pad)
        r[OFFSET_BODY + 1] = sub

        if sub == SUB_DEVICE_INFO:
            r[OFFSET_BODY] = 0x82
            r[14:18] = bytes((0x03, 0x8B, 0x03, 0x02))  # firmware 3.139, Pro Controller
            r[18:24] = self.address
            r[24] = 0x01
            r[25] = 0x01
        elif sub == SUB_SET_SHIPMENT:
            r[OFFSET_BODY] = 0x80
        elif sub == SUB_SPI_READ:
            offset = arg(0) | (arg(1) << 8) | (arg(2) << 16) | (arg(3) << 24)
            length = min(arg(4), MAX_SPI_READ)
            r[OFFSET_BODY] = 0x90
            r[14:19] = bytes((arg(0), arg(1), arg(2), arg(3), length))
            r[19:19 + length] = self.flash.read(offset, length)
        elif sub == SUB_SET_INPUT_MODE:
            self.input_mode = arg(0)
            r[OFFSET_BODY] = 0x80
        elif sub == SUB_TRIGGER_BUTTONS:
            r[OFFSET_BODY] = 0x83
        elif sub == SUB_ENABLE_IMU:
            self.imu_enabled = arg(0) == 0x01
            r[OFFSET_BODY] = 0x80
        elif sub == SUB_ENABLE_VIBRATION:
            self.vibration_enabled = True
            r[OFFSET_BODY] = 0x82
        elif sub == SUB_SET_PLAYER_LIGHTS:
            self.player = PLAYER_LIGHTS.get(arg(0), self.player)
            r[OFFSET_BODY] = 0x80
        elif sub == SUB_SET_NFC_IR_STATE:
            r[OFFSET_BODY] = 0x80
        elif sub == SUB_SET_NFC_IR_CONFIG:
            r[OFFSET_BODY] = 0xA0
            r[14:22] = NFC_IR_CONFIG_REPLY
            r[47] = 0xC8
        return Reply(sub, bytes(r), "0x%02X %s" % (sub, NAMES[sub]))

    def _fill_standard(self, r, pad):
        r[0] = self._next_timer()
        if not self.device_info_queried:
            return
        r[1] = BATTERY_FULL_PRO
        r[2] = pad.buttons & 0xFF
        r[3] = (pad.buttons >> 8) & 0xFF
        r[4] = (pad.buttons >> 16) & 0xFF
        r[5:8] = encode_stick(_LEFT, pad.lx, pad.ly)
        r[8:11] = encode_stick(_RIGHT, pad.rx, pad.ry)
        r[11] = self._vibrator

    def _next_timer(self):
        now = self._clock()
        if self._last_timer_at < 0:
            self._last_timer_at = now
            return self._timer
        ticks = (now - self._last_timer_at) // 5
        if ticks > 0:
            self._timer = (self._timer + ticks) & 0xFF
            self._last_timer_at += ticks * 5
        return self._timer


def sdp_record_xml():
    """SDP record of a Pro Controller, as published by NXBT and joycontrol."""
    return f"""<?xml version="1.0" encoding="UTF-8" ?>
<record>
  <attribute id="0x0001"><sequence><uuid value="0x1124" /></sequence></attribute>
  <attribute id="0x0004">
    <sequence>
      <sequence><uuid value="0x0100" /><uint16 value="0x0011" /></sequence>
      <sequence><uuid value="0x0011" /></sequence>
    </sequence>
  </attribute>
  <attribute id="0x0005"><sequence><uuid value="0x1002" /></sequence></attribute>
  <attribute id="0x0006">
    <sequence><uint16 value="0x656e" /><uint16 value="0x006a" /><uint16 value="0x0100" /></sequence>
  </attribute>
  <attribute id="0x0009">
    <sequence><sequence><uuid value="0x1124" /><uint16 value="0x0101" /></sequence></sequence>
  </attribute>
  <attribute id="0x000d">
    <sequence>
      <sequence>
        <sequence><uuid value="0x0100" /><uint16 value="0x0013" /></sequence>
        <sequence><uuid value="0x0011" /></sequence>
      </sequence>
    </sequence>
  </attribute>
  <attribute id="0x0100"><text value="Wireless Gamepad" /></attribute>
  <attribute id="0x0101"><text value="Gamepad" /></attribute>
  <attribute id="0x0102"><text value="Nintendo" /></attribute>
  <attribute id="0x0201"><uint16 value="0x0111" /></attribute>
  <attribute id="0x0202"><uint8 value="0x08" /></attribute>
  <attribute id="0x0203"><uint8 value="0x00" /></attribute>
  <attribute id="0x0204"><boolean value="true" /></attribute>
  <attribute id="0x0205"><boolean value="true" /></attribute>
  <attribute id="0x0206">
    <sequence>
      <sequence><uint8 value="0x22" /><text encoding="hex" value="{HID_DESCRIPTOR_HEX}" /></sequence>
    </sequence>
  </attribute>
  <attribute id="0x0207">
    <sequence><sequence><uint16 value="0x0409" /><uint16 value="0x0100" /></sequence></sequence>
  </attribute>
  <attribute id="0x020b"><uint16 value="0x0100" /></attribute>
  <attribute id="0x020c"><uint16 value="0x0c80" /></attribute>
  <attribute id="0x020d"><boolean value="false" /></attribute>
  <attribute id="0x020e"><boolean value="false" /></attribute>
  <attribute id="0x020f"><uint16 value="0x0640" /></attribute>
  <attribute id="0x0210"><uint16 value="0x0320" /></attribute>
</record>
"""
