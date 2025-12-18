"""
In-memory encryption engine for chat scenarios using the Mycelia V4 GPU logic.
"""
import ctypes
import os
import struct
import sys
import threading

import numpy as np


def resource_path(relative_path: str) -> str:
    """Return absolute path to resource, works for dev and PyInstaller."""
    try:
        base_path = sys._MEIPASS  # type: ignore[attr-defined]
    except Exception:
        base_path = os.path.abspath(".")
    return os.path.join(base_path, relative_path)


DLL_NAME = "./bin/CC_OpenCl.dll" if os.name == "nt" else "./bin/CC_OpenCl.so"
LIB_PATH = resource_path(DLL_NAME)
if not os.path.exists(LIB_PATH):
    if os.path.exists(os.path.join("build", DLL_NAME)):
        LIB_PATH = os.path.join("build", DLL_NAME)
    elif os.path.exists(DLL_NAME):
        LIB_PATH = os.path.abspath(DLL_NAME)

try:
    if os.name == "nt":
        cl = ctypes.CDLL(LIB_PATH, winmode=0)
    else:
        cl = ctypes.CDLL(LIB_PATH)
except OSError as exc:
    raise RuntimeError(f"Konnte {LIB_PATH} nicht laden: {exc}") from exc


class HPIOAgent(ctypes.Structure):
    _fields_ = [("x", ctypes.c_float), ("y", ctypes.c_float), ("energy", ctypes.c_float), ("coupling", ctypes.c_float)]


cl.initialize_gpu.argtypes = [ctypes.c_int]
cl.subqg_set_deterministic_mode.argtypes = [ctypes.c_int, ctypes.c_ulonglong]
cl.subqg_initialize_state.argtypes = [ctypes.c_int, ctypes.c_float, ctypes.c_float, ctypes.c_float, ctypes.c_float]
cl.subqg_inject_agents.argtypes = [ctypes.c_int, ctypes.POINTER(HPIOAgent), ctypes.c_int]
cl.subqg_simulation_step.argtypes = [
    ctypes.c_int,
    ctypes.c_float,
    ctypes.c_float,
    ctypes.c_float,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_void_p,
    ctypes.c_int,
]
cl.subqg_debug_read_channel.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_float), ctypes.c_int]
try:
    cl.cc_get_last_error.restype = ctypes.c_char_p
except Exception:
    pass

GRID_SIZE = 256 * 256
C_LOCK = threading.Lock()


class MyceliaChatEngine:
    def __init__(self, gpu_index: int = 0):
        self.gpu = gpu_index
        cl.initialize_gpu(gpu_index)
        print("[Engine] GPU Chat Core ready.")

    def _generate_keystream(self, seed: int) -> np.ndarray:
        """Generate a 64KB keystream block in VRAM based on a deterministic seed."""
        with C_LOCK:
            cl.subqg_set_deterministic_mode(1, ctypes.c_ulonglong(seed))
            cl.subqg_initialize_state(self.gpu, 0.5, 0.5, 0.005, 0.5)
            cl.subqg_simulation_step(
                self.gpu,
                0.5,
                0.5,
                0.5,
                None,
                None,
                None,
                None,
                None,
                None,
                None,
                0,
            )
            raw_buffer = np.zeros(GRID_SIZE, dtype=np.float32)
            cl.subqg_debug_read_channel(
                self.gpu,
                0,
                raw_buffer.ctypes.data_as(ctypes.c_void_p),
                GRID_SIZE,
            )

            key_int = raw_buffer.view(np.uint32)
            key_int = (key_int ^ (key_int >> 16)) * 0x45D9F3B
            key_bytes = (key_int & 0xFF).astype(np.uint8)
            return key_bytes

    def encrypt_text(self, text: str) -> bytes:
        """Encrypt a string and return [Seed(8) + Length(4) + Ciphertext] packet."""
        msg_seed = os.urandom(8)
        seed_int = struct.unpack("Q", msg_seed)[0]

        keystream = self._generate_keystream(seed_int)

        data = text.encode("utf-8")
        data_len = len(data)
        if data_len > len(keystream):
            data = data[: len(keystream)]

        np_data = np.frombuffer(data, dtype=np.uint8)
        np_key = keystream[: len(np_data)]
        encrypted = np.bitwise_xor(np_data, np_key).tobytes()

        packet = struct.pack("Q", seed_int) + struct.pack("I", len(encrypted)) + encrypted
        return packet

    def decrypt_packet(self, packet: bytes) -> str:
        """Decrypt a packet produced by `encrypt_text` back into clear text."""
        try:
            seed_int = struct.unpack("Q", packet[:8])[0]
            msg_len = struct.unpack("I", packet[8:12])[0]
            encrypted_data = packet[12 : 12 + msg_len]

            keystream = self._generate_keystream(seed_int)

            np_data = np.frombuffer(encrypted_data, dtype=np.uint8)
            np_key = keystream[: len(np_data)]
            decrypted = np.bitwise_xor(np_data, np_key).tobytes()

            return decrypted.decode("utf-8")
        except Exception as exc:
            return f"[Decryption Error: {exc}]"

