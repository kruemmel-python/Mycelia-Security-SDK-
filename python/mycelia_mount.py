#!/usr/bin/env python3
from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import errno
import os
import platform
import threading
import time
from dataclasses import dataclass

# =============================================================================
# Windows-only guard
# =============================================================================
if not platform.system().lower().startswith("win"):
    raise SystemExit("Dieses Script ist Windows-only (WinFsp/winfspy).")

# =============================================================================
# Windows: os.pread Shim (thread-safe)
# =============================================================================
_windows_read_lock = threading.Lock()

def pread_windows_shim(fd: int, size: int, offset: int) -> bytes:
    with _windows_read_lock:
        os.lseek(fd, offset, os.SEEK_SET)
        return os.read(fd, size)

os.pread = pread_windows_shim  # type: ignore[attr-defined]

# =============================================================================
# winfspy Import (0.8.4)
# =============================================================================
import importlib
import importlib.util

winfspy_spec = importlib.util.find_spec("winfspy")
if winfspy_spec is None:
    raise SystemExit(
        "FEHLER: winfspy ist nicht importierbar. Installiere in deiner venv:\n"
        "  python -m pip install winfspy\n"
    )
winfspy_module = importlib.import_module("winfspy")
FileSystem = winfspy_module.FileSystem
BaseFileSystemOperations = winfspy_module.BaseFileSystemOperations

# =============================================================================
# SDDL -> SECURITY_DESCRIPTOR bytes
# =============================================================================
_advapi32 = ctypes.WinDLL("Advapi32.dll", use_last_error=True)
_kernel32 = ctypes.WinDLL("Kernel32.dll", use_last_error=True)

_ConvertStringSecurityDescriptorToSecurityDescriptorW = (
    _advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW
)
_ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [
    wintypes.LPCWSTR,
    wintypes.DWORD,
    ctypes.POINTER(ctypes.c_void_p),
    ctypes.POINTER(wintypes.DWORD),
]
_ConvertStringSecurityDescriptorToSecurityDescriptorW.restype = wintypes.BOOL

_LocalFree = _kernel32.LocalFree
_LocalFree.argtypes = [ctypes.c_void_p]
_LocalFree.restype = ctypes.c_void_p

SDDL_REVISION_1 = 1

def sddl_to_sd_bytes(sddl: str) -> bytes:
    sd_ptr = ctypes.c_void_p()
    sd_size = wintypes.DWORD(0)
    ok = _ConvertStringSecurityDescriptorToSecurityDescriptorW(
        sddl, SDDL_REVISION_1, ctypes.byref(sd_ptr), ctypes.byref(sd_size)
    )
    if not ok:
        err = ctypes.get_last_error()
        raise OSError(err, f"ConvertStringSecurityDescriptorToSecurityDescriptorW failed: {err}")
    try:
        return ctypes.string_at(sd_ptr, sd_size.value)
    finally:
        _LocalFree(sd_ptr)

# =============================================================================
# Mycelia constants
# =============================================================================
M_OK = 0

# =============================================================================
# Bindings: CC_OpenCl.dll
# =============================================================================
class MyceliaBindings:
    def __init__(self, lib_path: str):
        self._lock = threading.Lock()
        print(f"[Mount] Lade DLL: {lib_path}")
        self.lib = ctypes.CDLL(lib_path)

        self.lib.mycelia_init_all.argtypes = [ctypes.c_uint64]
        self.lib.mycelia_init_all.restype = ctypes.c_int

        self.lib.mycelia_fs_map_logical_to_physical.argtypes = [
            ctypes.c_uint64,
            ctypes.POINTER(ctypes.c_uint64),
        ]
        self.lib.mycelia_fs_map_logical_to_physical.restype = ctypes.c_int

        self.lib.mycelia_get_noise_epoch.argtypes = []
        self.lib.mycelia_get_noise_epoch.restype = ctypes.c_uint32

    def init_all(self, seed: int) -> int:
        with self._lock:
            return int(self.lib.mycelia_init_all(ctypes.c_uint64(seed)))

    def map_logical(self, logical_id: int) -> tuple[int, int]:
        with self._lock:
            out_pos = ctypes.c_uint64(0)
            status = int(
                self.lib.mycelia_fs_map_logical_to_physical(
                    ctypes.c_uint64(logical_id),
                    ctypes.byref(out_pos),
                )
            )
            return status, int(out_pos.value)

    def get_noise_epoch(self) -> int:
        with self._lock:
            return int(self.lib.mycelia_get_noise_epoch())

# =============================================================================
# Hash / XOR stream
# =============================================================================
def fnv1a_64(data: bytes) -> int:
    h = 14695981039346656037
    fnv_prime = 1099511628211
    for b in data:
        h ^= b
        h = (h * fnv_prime) & 0xFFFFFFFFFFFFFFFF
    return h


def xor_stream(data: bytes, seed: int) -> bytes:
    out = bytearray(data)
    state = seed & 0xFFFFFFFFFFFFFFFF
    for i in range(len(out)):
        state ^= (state << 13) & 0xFFFFFFFFFFFFFFFF
        state ^= (state >> 7) & 0xFFFFFFFFFFFFFFFF
        state ^= (state << 17) & 0xFFFFFFFFFFFFFFFF
        out[i] ^= state & 0xFF
    return bytes(out)

# =============================================================================
# File context singletons (critical for winfspy/cffi stability)
# =============================================================================
@dataclass(frozen=True, slots=True)
class FileCtx:
    path: str

# =============================================================================
# Proper OSError helpers
# =============================================================================
def _enoent(path: str) -> OSError:
    return FileNotFoundError(errno.ENOENT, "not found", path)


def _eperm(path: str) -> OSError:
    return PermissionError(errno.EPERM, "permission denied", path)


def _eio(path: str) -> OSError:
    return OSError(errno.EIO, "I/O error", path)

# =============================================================================
# WinFsp operations
# =============================================================================
class MyceliaFSImpl(BaseFileSystemOperations):
    _SDDL_READONLY = "O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FR;;;WD)"

    FILE_ATTRIBUTE_DIRECTORY = 0x10
    FILE_ATTRIBUTE_NORMAL = 0x80
    GENERIC_WRITE = 0x40000000

    def __init__(self, bindings: MyceliaBindings, seed: int, container_path: str):
        super().__init__()
        self.bindings = bindings
        self.seed = seed

        if not os.path.exists(container_path):
            raise FileNotFoundError(container_path)

        flags = os.O_RDONLY
        if hasattr(os, "O_BINARY"):
            flags |= os.O_BINARY  # type: ignore[attr-defined]

        self.fd = os.open(container_path, flags)
        self._size = os.path.getsize(container_path)

        self._sd_bytes = sddl_to_sd_bytes(self._SDDL_READONLY)
        self._sd_size = len(self._sd_bytes)

        # SINGLETON contexts: do NOT allocate per open
        self._ctx_root = FileCtx("\\")
        self._ctx_file = FileCtx("\\mycelia.bin")

    # Volume info
    def get_volume_info(self):
        return {"total_size": self._size, "free_size": 0, "volume_label": "MyceliaFS"}

    # Security (MUST return 3 values)
    def get_security_by_name(self, file_name):
        if file_name == "\\":
            fa = self.FILE_ATTRIBUTE_DIRECTORY
        elif file_name == "\\mycelia.bin":
            fa = self.FILE_ATTRIBUTE_NORMAL
        else:
            raise _enoent(file_name)
        return fa, self._sd_bytes, self._sd_size

    # File info
    def get_file_info(self, file_context_or_name):
        path = file_context_or_name.path if isinstance(file_context_or_name, FileCtx) else str(file_context_or_name)
        if path == "\\":
            return {"file_attributes": self.FILE_ATTRIBUTE_DIRECTORY, "file_size": 0}
        if path == "\\mycelia.bin":
            return {"file_attributes": self.FILE_ATTRIBUTE_NORMAL, "file_size": self._size}
        raise _enoent(path)

    # Directory listing
    def read_directory(self, file_context_or_name, marker):
        path = file_context_or_name.path if isinstance(file_context_or_name, FileCtx) else str(file_context_or_name)
        if path != "\\":
            raise _enoent(path)

        entries = [
            {"file_name": ".", "file_attributes": self.FILE_ATTRIBUTE_DIRECTORY, "file_size": 0},
            {"file_name": "..", "file_attributes": self.FILE_ATTRIBUTE_DIRECTORY, "file_size": 0},
            {"file_name": "mycelia.bin", "file_attributes": self.FILE_ATTRIBUTE_NORMAL, "file_size": self._size},
        ]
        for entry in entries:
            if marker and entry["file_name"] <= marker:
                continue
            yield entry

    # Open (read-only)
    def open(self, file_name, create_options, granted_access):
        if granted_access & self.GENERIC_WRITE:
            raise _eperm(file_name)

        if file_name == "\\":
            return self._ctx_root
        if file_name == "\\mycelia.bin":
            return self._ctx_file

        raise _enoent(file_name)

    def close(self, file_context):
        return

    def cleanup(self, file_context, file_name, flags):
        return

    # Read
    def read(self, file_context, offset, length):
        if not isinstance(file_context, FileCtx) or file_context is not self._ctx_file:
            raise _enoent("\\mycelia.bin")

        if offset >= self._size:
            return b""

        length = max(0, min(int(length), self._size - int(offset)))

        logical_material = f"{file_context.path}:{offset}".encode("utf-8")
        logical_id = fnv1a_64(logical_material) ^ self.seed

        status, physical = self.bindings.map_logical(logical_id)
        if status != M_OK:
            raise _eio(file_context.path)

        phys_off = int(physical % self._size)
        data = os.pread(self.fd, length, phys_off)

        epoch = self.bindings.get_noise_epoch()
        return xor_stream(data, logical_id ^ self.seed ^ epoch)

    def shutdown(self):
        try:
            if getattr(self, "fd", None) is not None:
                os.close(self.fd)
                self.fd = None
        except Exception:
            pass

# =============================================================================
# Global pinning (critical)
# =============================================================================
_PINNED_FS_OPS: MyceliaFSImpl | None = None
_PINNED_FS: FileSystem | None = None


def _validate_mount_arg(mount: str) -> str:
    mount = mount.strip().upper()
    if len(mount) != 2 or mount[1] != ":" or not mount[0].isalpha():
        raise ValueError("Mountpoint muss wie 'Z:' aussehen (Drive-Letter).")
    return mount


def main() -> None:
    parser = argparse.ArgumentParser(description="MycelFS emergent mount (Windows/WinFsp/winfspy)")
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--mount", required=True)
    parser.add_argument("--container", required=True)
    parser.add_argument("--lib", required=True)
    args = parser.parse_args()

    mount = _validate_mount_arg(args.mount)

    bindings = MyceliaBindings(args.lib)
    init_status = bindings.init_all(args.seed)
    if init_status != M_OK:
        raise SystemExit(f"FEHLER: GPU/Mycelia Init fehlgeschlagen (Status={init_status}).")

    print(f"[Mount] Starte WinFSP Drive auf {mount}")

    global _PINNED_FS_OPS, _PINNED_FS
    _PINNED_FS_OPS = MyceliaFSImpl(bindings, args.seed, args.container)

    fs_options = {
        "VolumeName": f"MyceliaFS_{os.getpid()}",
        "FileSystemName": "MyceliaFS",
    }

    _PINNED_FS = FileSystem(mount, _PINNED_FS_OPS, fs_options)

    try:
        _PINNED_FS.start()
        print("[Mount] Laufwerk aktiv. STRG+C zum Beenden.")
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[Mount] Stoppe...")
    finally:
        try:
            if _PINNED_FS is not None:
                _PINNED_FS.stop()
        except Exception:
            pass
        try:
            if _PINNED_FS_OPS is not None:
                _PINNED_FS_OPS.shutdown()
        except Exception:
            pass

        _PINNED_FS = None
        _PINNED_FS_OPS = None
        print("[Mount] Beendet.")


if __name__ == "__main__":
    main()
