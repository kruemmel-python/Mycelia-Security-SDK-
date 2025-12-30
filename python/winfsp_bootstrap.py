#!/usr/bin/env python3
import argparse
import os
import platform
import subprocess
import sys
import tempfile
import urllib.request

if platform.system().lower().startswith("win"):
    import winreg
else:
    winreg = None


WINFSP_DEFAULT_URL = "https://github.com/winfsp/winfsp/releases/download/v2.0/winfsp-2.0.23075.msi"


def is_windows() -> bool:
    return platform.system().lower().startswith("win")


def winfsp_installed() -> bool:
    if winreg is None:
        return False
    program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
    candidate = os.path.join(program_files, "WinFsp", "bin", "winfsp-x64.dll")
    if os.path.exists(candidate):
        return True
    registry_paths = [
        r"SOFTWARE\WinFsp",
        r"SOFTWARE\WOW6432Node\WinFsp",
    ]
    for root in (winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER):
        for key_path in registry_paths:
            try:
                with winreg.OpenKey(root, key_path):
                    return True
            except FileNotFoundError:
                continue
    return False


def download_winfsp(url: str, destination: str) -> None:
    with urllib.request.urlopen(url) as response, open(destination, "wb") as target:
        target.write(response.read())


def install_winfsp(msi_path: str) -> None:
    subprocess.check_call(
        ["msiexec", "/i", msi_path, "/passive", "/norestart"],
        stdout=sys.stdout,
        stderr=sys.stderr,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="WinFSP bootstrap installer")
    parser.add_argument("--url", default=WINFSP_DEFAULT_URL, help="WinFSP MSI download URL")
    parser.add_argument("--force", action="store_true", help="Install even if WinFSP is detected")
    args = parser.parse_args()

    if not is_windows():
        raise RuntimeError("WinFSP bootstrap is only supported on Windows.")

    if winfsp_installed() and not args.force:
        print("WinFSP already installed. Skipping.")
        return

    with tempfile.TemporaryDirectory() as temp_dir:
        msi_path = os.path.join(temp_dir, "winfsp.msi")
        print(f"Downloading WinFSP from {args.url}...")
        download_winfsp(args.url, msi_path)
        print("Installing WinFSP (may require elevation)...")
        install_winfsp(msi_path)

    print("WinFSP installation complete.")


if __name__ == "__main__":
    main()
