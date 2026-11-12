#!/usr/bin/python3
"""Desktop window for SWIS pre-pair. Runs unprivileged; the Bluetooth work runs through pkexec."""

import json
import os
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import ttk

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
import procon  # noqa: E402

BACKEND = os.path.join(HERE, "swis_prepair.py")
CONFIG = os.path.join(os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")), "swis-prepair.json")

INTRO = (
    "Makes your Switch 2 remember your phone, so the SWIS Controller app can connect to it.\n"
    "1. Turn the phone's Bluetooth OFF (the computer borrows its address for a minute).\n"
    "2. Enter the phone's Bluetooth address: Settings -> About phone -> Status information.\n"
    "3. Press Start, enter your password, then open Controllers -> Change Grip/Order on the Switch 2."
)

AFTER = (
    "Done. Now: turn the phone's Bluetooth back on, keep Change Grip/Order open on the Switch 2, "
    "and in SWIS Controller tap \"Start controller and pair\"."
)


def load_config():
    try:
        with open(CONFIG) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_config(data):
    try:
        os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
        with open(CONFIG, "w") as f:
            json.dump(data, f)
    except OSError:
        pass


class App:
    def __init__(self, root):
        self.root = root
        self.proc = None
        root.title("SWIS Pre-pair")
        root.minsize(560, 460)

        frame = ttk.Frame(root, padding=16)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="SWIS Pre-pair", font=("TkDefaultFont", 16, "bold")).pack(anchor="w")
        ttk.Label(frame, text=INTRO, justify="left", wraplength=620).pack(anchor="w", pady=(6, 12))

        row = ttk.Frame(frame)
        row.pack(fill="x")
        ttk.Label(row, text="Phone Bluetooth address").pack(side="left")
        self.address = tk.StringVar(value=load_config().get("phone", ""))
        entry = ttk.Entry(row, textvariable=self.address, width=20, font=("TkFixedFont", 11))
        entry.pack(side="left", padx=8)
        entry.bind("<Return>", lambda _: self.start())

        buttons = ttk.Frame(frame)
        buttons.pack(fill="x", pady=10)
        self.start_btn = ttk.Button(buttons, text="Start", command=self.start)
        self.start_btn.pack(side="left")
        self.stop_btn = ttk.Button(buttons, text="Stop", command=self.stop, state="disabled")
        self.stop_btn.pack(side="left", padx=6)
        self.restore_btn = ttk.Button(buttons, text="Restore Bluetooth", command=self.restore)
        self.restore_btn.pack(side="right")

        self.status = tk.StringVar(value="Ready.")
        self.status_label = tk.Label(frame, textvariable=self.status, anchor="w", justify="left",
                                     wraplength=620, font=("TkDefaultFont", 11, "bold"))
        self.status_label.pack(fill="x", pady=(0, 8))

        self.log = tk.Text(frame, height=14, wrap="word", font=("TkFixedFont", 9), state="disabled")
        self.log.pack(fill="both", expand=True)

        root.protocol("WM_DELETE_WINDOW", self.close)

    # ---- running the backend

    def start(self):
        raw = procon.parse_address(self.address.get())
        if raw is None:
            self.set_status("That is not a Bluetooth address. It looks like AA:BB:CC:DD:EE:FF.", "#b00020")
            return
        phone = procon.format_address(raw)
        self.address.set(phone)
        save_config({"phone": phone})
        self.launch(["run", "--phone", phone, "--stdin-control"], "Asking for your password…")

    def restore(self):
        self.launch(["restore"], "Restoring Bluetooth…")

    def launch(self, args, headline):
        if self.proc is not None:
            return
        self.clear_log()
        self.set_status(headline, None)
        self.busy(True)
        try:
            self.proc = subprocess.Popen(
                ["pkexec", BACKEND] + args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, bufsize=1,
            )
        except FileNotFoundError:
            self.proc = None
            self.busy(False)
            self.set_status("pkexec is missing. Run the installer again.", "#b00020")
            return
        threading.Thread(target=self.read_output, args=(self.proc,), daemon=True).start()

    def read_output(self, proc):
        verdict = None
        for line in proc.stdout:
            line = line.rstrip("\n")
            if line.startswith("RESULT "):
                verdict = line[7:]
            self.root.after(0, self.on_line, line)
        code = proc.wait()
        self.root.after(0, self.on_exit, verdict, code)

    def on_line(self, line):
        if line.startswith("STATUS "):
            self.set_status(line[7:], None)
            self.append("— " + line[7:])
        elif line.startswith("RESULT "):
            pass
        else:
            self.append(line)

    def on_exit(self, verdict, code):
        self.proc = None
        self.busy(False)
        if verdict is None:
            if code in (126, 127):
                self.set_status("Password prompt was cancelled.", "#b00020")
            else:
                self.set_status("The helper stopped unexpectedly. Press Restore Bluetooth to be safe.", "#b00020")
        elif verdict.startswith("ok "):
            self.set_status(AFTER, "#1b7f3b")
            self.append("Console address: " + verdict[3:])
        elif verdict == "ok":
            self.set_status("Bluetooth restored.", "#1b7f3b")
        else:
            self.set_status(verdict[5:] if verdict.startswith("fail ") else verdict, "#b00020")

    def stop(self):
        if self.proc is not None and self.proc.stdin:
            try:
                self.proc.stdin.write("stop\n")
                self.proc.stdin.close()
            except OSError:
                pass
            self.set_status("Stopping and restoring Bluetooth…", None)

    def close(self):
        if self.proc is not None:
            self.stop()
            self.root.after(300, self.close_when_done)
        else:
            self.root.destroy()

    def close_when_done(self):
        if self.proc is None:
            self.root.destroy()
        else:
            self.root.after(300, self.close_when_done)

    # ---- widgets

    def busy(self, running):
        self.start_btn.configure(state="disabled" if running else "normal")
        self.restore_btn.configure(state="disabled" if running else "normal")
        self.stop_btn.configure(state="normal" if running else "disabled")

    def set_status(self, text, colour):
        self.status.set(text)
        self.status_label.configure(fg=colour or ttk.Style().lookup("TLabel", "foreground") or "black")

    def append(self, line):
        self.log.configure(state="normal")
        self.log.insert("end", line + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def clear_log(self):
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")


def main():
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
