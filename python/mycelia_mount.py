#!/usr/bin/env python3
import argparse
import ctypes
import ctypes.util
import errno
import hashlib
import importlib
import importlib.util
import os
import platform
import threading
import time

FUSE = None
Operations = object
WINFSP_AVAILABLE = False
WinFspFileSystem = None
WinFspBaseFileSystem = object

fuse_spec = importlib.util.find_spec("fuse")
libfuse_present = ctypes.util.find_library("fuse") or ctypes.util.find_library("fuse3")
if fuse_spec and (libfuse_present or platform.system().lower().startswith("win")):
    fuse_mod = importlib.import_module("fuse")
    FUSE = fuse_mod.FUSE
    Operations = fuse_mod.Operations

if FUSE is None and platform.system().lower().startswith("win"):
    winfsp_spec = importlib.util.find_spec("winfspy")
    if winfsp_spec:
        winfsp_mod = importlib.import_module("winfspy")
        WinFspFileSystem = winfsp_mod.FileSystem
        WinFspBaseFileSystem = winfsp_mod.BaseFileSystem
        WINFSP_AVAILABLE = True


M_OK = 0
M_ERR_DESYNC = -5


def fnv1a_64(data: bytes) -> int:
    fnv_offset = 14695981039346656037
    fnv_prime = 1099511628211
    h = fnv_offset
    for byte in data:
        h ^= byte
        h = (h * fnv_prime) & 0xFFFFFFFFFFFFFFFF
    return h


class MyceliaBindings:
    def __init__(self, lib_path: str):
        self.lib = ctypes.CDLL(lib_path)
        self.lib.mycelia_init_all.argtypes = [ctypes.c_uint64]
        self.lib.mycelia_init_all.restype = ctypes.c_int
        self.lib.mycelia_fs_map_logical_to_physical.argtypes = [
            ctypes.c_uint64,
            ctypes.POINTER(ctypes.c_uint64),
        ]
        self.lib.mycelia_fs_map_logical_to_physical.restype = ctypes.c_int
        self.lib.mycelia_cycle_update.argtypes = []
        self.lib.mycelia_cycle_update.restype = ctypes.c_int
        self.lib.mycelia_get_noise_epoch.argtypes = []
        self.lib.mycelia_get_noise_epoch.restype = ctypes.c_uint32

    def init_all(self, seed: int) -> int:
        return self.lib.mycelia_init_all(ctypes.c_uint64(seed))

    def map_logical(self, logical_id: int) -> tuple[int, int]:
        out_pos = ctypes.c_uint64(0)
        status = self.lib.mycelia_fs_map_logical_to_physical(
            ctypes.c_uint64(logical_id),
            ctypes.byref(out_pos),
        )
        return status, out_pos.value

    def cycle_update(self) -> int:
        return self.lib.mycelia_cycle_update()

    def get_noise_epoch(self) -> int:
        return int(self.lib.mycelia_get_noise_epoch())


class MyceliaFuse(Operations):
    def __init__(self, bindings: MyceliaBindings, seed: int, container_path: str, mountpoint: str):
        self.bindings = bindings
        self.seed = seed
        self.container_path = container_path
        self.mountpoint = mountpoint
        self.fd = os.open(container_path, os.O_RDONLY)
        self._alive = True

    def destroy(self, path):
        self._alive = False
        if self.fd:
            os.close(self.fd)
            self.fd = None

    def getattr(self, path, fh=None):
        if path == "/":
            return dict(st_mode=(0o40555), st_nlink=2)
        if path == "/mycelia.bin":
            return dict(st_mode=(0o100444), st_nlink=1, st_size=os.path.getsize(self.container_path))
        raise OSError(errno.ENOENT, "not found")

    def readdir(self, path, fh):
        return [".", "..", "mycelia.bin"]

    def open(self, path, flags):
        if path != "/mycelia.bin":
            raise OSError(errno.ENOENT, "not found")
        if flags & (os.O_WRONLY | os.O_RDWR):
            raise OSError(errno.EPERM, "read-only")
        return 0

    def read(self, path, size, offset, fh):
        if path != "/mycelia.bin":
            raise OSError(errno.ENOENT, "not found")
        logical_material = f"{path}:{offset}".encode("utf-8")
        logical_id = fnv1a_64(logical_material) ^ self.seed
        status, physical = self.bindings.map_logical(logical_id)
        if status != M_OK:
            emergency_unmount(self.mountpoint)
            raise OSError(errno.EIO, "mycelia desync")

        data = os.pread(self.fd, size, physical % os.path.getsize(self.container_path))
        noise_epoch = self.bindings.get_noise_epoch()
        return xor_stream(data, logical_id ^ self.seed ^ noise_epoch)


def xor_stream(data: bytes, seed: int) -> bytes:
    out = bytearray(data)
    state = seed & 0xFFFFFFFFFFFFFFFF
    for i in range(len(out)):
        state ^= (state << 13) & 0xFFFFFFFFFFFFFFFF
        state ^= (state >> 7) & 0xFFFFFFFFFFFFFFFF
        state ^= (state << 17) & 0xFFFFFFFFFFFFFFFF
        out[i] ^= state & 0xFF
    return bytes(out)


def emergency_unmount(mountpoint: str) -> None:
    if platform.system().lower().startswith("linux"):
        os.system(f"fusermount -u {mountpoint}")
    elif platform.system().lower().startswith("darwin"):
        os.system(f"umount {mountpoint}")
    else:
        os._exit(1)


class MyceliaWinFsp(WinFspBaseFileSystem if WINFSP_AVAILABLE else object):
    def __init__(self, bindings: MyceliaBindings, seed: int, container_path: str, mountpoint: str):
        self.bindings = bindings
        self.seed = seed
        self.container_path = container_path
        self.mountpoint = mountpoint
        self.fd = os.open(container_path, os.O_RDONLY)
        self._size = os.path.getsize(container_path)

    def close(self):
        if self.fd:
            os.close(self.fd)
            self.fd = None

    def get_security_by_name(self, file_name):
        if file_name in ("\\", "\\mycelia.bin"):
            return 0, None
        raise FileNotFoundError()

    def get_file_info(self, file_name):
        if file_name == "\\":
            return dict(file_attributes=0x10, file_size=0)
        if file_name == "\\mycelia.bin":
            return dict(file_attributes=0x80, file_size=self._size)
        raise FileNotFoundError()

    def read_directory(self, file_name, marker):
        if file_name != "\\":
            raise FileNotFoundError()
        entries = [
            dict(file_name=".", file_attributes=0x10, file_size=0),
            dict(file_name="..", file_attributes=0x10, file_size=0),
            dict(file_name="mycelia.bin", file_attributes=0x80, file_size=self._size),
        ]
        for entry in entries:
            if marker and entry["file_name"] <= marker:
                continue
            yield entry

    def read(self, file_name, offset, length):
        if file_name != "\\mycelia.bin":
            raise FileNotFoundError()
        logical_material = f"{file_name}:{offset}".encode("utf-8")
        logical_id = fnv1a_64(logical_material) ^ self.seed
        status, physical = self.bindings.map_logical(logical_id)
        if status != M_OK:
            emergency_unmount(self.mountpoint)
            raise OSError(errno.EIO, "mycelia desync")
        data = os.pread(self.fd, length, physical % self._size)
        noise_epoch = self.bindings.get_noise_epoch()
        return xor_stream(data, logical_id ^ self.seed ^ noise_epoch)


def cycle_thread(bindings: MyceliaBindings, mountpoint: str) -> None:
    while True:
        status = bindings.cycle_update()
        if status == M_ERR_DESYNC:
            emergency_unmount(mountpoint)
            return
        time.sleep(0.1)


def resolve_library_path(explicit: str | None) -> str:
    if explicit:
        return explicit
    base = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    candidates = [
        os.path.join(base, "bin", "libmycelia.so"),
        os.path.join(base, "bin", "mycelia.dll"),
        os.path.join(base, "bin", "libmycelia.dylib"),
    ]
    for path in candidates:
        if os.path.exists(path):
            return path
    raise FileNotFoundError("Mycelia library not found; set --lib or MYCELIA_LIB_PATH")


def main() -> None:
    parser = argparse.ArgumentParser(description="MycelFS emergent mount")
    parser.add_argument("--seed", required=True, type=int, help="Seed for Mycelia")
    parser.add_argument("--mount", required=True, help="Mountpoint")
    parser.add_argument("--container", required=True, help="Container/raw device path")
    parser.add_argument("--lib", default=os.getenv("MYCELIA_LIB_PATH"), help="Path to Mycelia shared library")
    args = parser.parse_args()

    if FUSE is None and not WINFSP_AVAILABLE:
        system = platform.system().lower()
        if system.startswith("win"):
            raise RuntimeError(
                "FUSE backend unavailable. Install WinFSP + a Python FUSE shim (e.g. winfspy) "
                "or use a Linux/macOS environment with fusepy."
            )
        raise RuntimeError(
            "FUSE backend unavailable. Install libfuse + fusepy (Linux: libfuse + pip install fusepy)."
        )

    lib_path = resolve_library_path(args.lib)
    bindings = MyceliaBindings(lib_path)
    init_status = bindings.init_all(args.seed)
    if init_status != M_OK:
        raise RuntimeError(f"Mycelia init failed: {init_status}")

    thread = threading.Thread(target=cycle_thread, args=(bindings, args.mount), daemon=True)
    thread.start()

    if WINFSP_AVAILABLE and platform.system().lower().startswith("win"):
        winfsp_fs = MyceliaWinFsp(bindings, args.seed, args.container, args.mount)
        try:
            with WinFspFileSystem(winfsp_fs, args.mount):
                while True:
                    time.sleep(0.5)
        except OSError as exc:
            raise RuntimeError(
                "WinFSP runtime not available. Install WinFSP or run python winfsp_bootstrap.py."
            ) from exc
    else:
        fuse_fs = MyceliaFuse(bindings, args.seed, args.container, args.mount)
        FUSE(fuse_fs, args.mount, foreground=True, ro=True)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as exc:
        raise SystemExit(str(exc)) from exc
    except Exception as exc:
        raise SystemExit(f"Unhandled error: {exc}") from exc
