#!/usr/bin/env python3
"""
mein_subqg_seed_script.py

Liest einen 64-Bit-Seed aus dem Mycelia-Treiber (CC_OpenCl.dll) und gibt ihn auf STDOUT aus.
Gedacht als Plug-in-Bridge für mc_mycelia (SubQG/Mycel-Pipeline).

Ablauf:
- Lädt CC_OpenCl.dll aus ../bin relativ zum Skript.
- Initialisiert Mycelia, erstellt einen Context auf einer GPU, setzt einen Basis-Seed
  (explizit via --seed oder zufällig) und generiert 8 Bytes Keystream via myc_process_buffer.
- Interpretiert die 8 Bytes als signed 64-bit little-endian und schreibt den Wert als Dezimalzahl.
- Bei Fehlern fällt auf einen kryptografisch sicheren OS-Random-Seed zurück.

Beispiele:
    python mein_subqg_seed_script.py
    python mein_subqg_seed_script.py --seed 123456789 --gpu 0

Exit-Code 0 bedeutet: Seed wurde (auch bei Fallback) erfolgreich ausgegeben.
Exit-Code 1 bedeutet: Schwerer Fehler (z.B. DLL nicht gefunden).
"""

import argparse
import ctypes
import os
import pathlib
import secrets
import struct
import sys

RESULT_SUCCESS = 0


def _load_library(script_dir: pathlib.Path) -> ctypes.CDLL:
    dll_name = "CC_OpenCl.dll"
    candidate = (script_dir.parent / "bin" / dll_name).resolve()
    if not candidate.exists():
        raise FileNotFoundError(f"DLL nicht gefunden: {candidate}")
    if os.name == "nt":
        return ctypes.WinDLL(str(candidate))
    return ctypes.CDLL(str(candidate))


def _bind_functions(lib: ctypes.CDLL):
    lib.myc_init.restype = ctypes.c_int

    lib.myc_get_device_count.restype = ctypes.c_int

    lib.myc_create_context.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_void_p)]
    lib.myc_create_context.restype = ctypes.c_int

    lib.myc_set_seed.argtypes = [ctypes.c_void_p, ctypes.c_uint64]
    lib.myc_set_seed.restype = ctypes.c_int

    lib.myc_process_buffer.argtypes = [
        ctypes.c_void_p,  # ctx
        ctypes.POINTER(ctypes.c_uint8),  # data
        ctypes.c_size_t,  # len
        ctypes.c_size_t,  # stream_offset
    ]
    lib.myc_process_buffer.restype = ctypes.c_int

    lib.myc_destroy_context.argtypes = [ctypes.c_void_p]
    lib.myc_destroy_context.restype = None

    lib.myc_get_last_error.restype = ctypes.c_char_p


def _as_signed_64(value: int) -> int:
    masked = value & 0xFFFFFFFFFFFFFFFF
    if masked & (1 << 63):
        return -((~masked & 0xFFFFFFFFFFFFFFFF) + 1)
    return masked


def _generate_seed(lib: ctypes.CDLL, gpu_index: int, base_seed: int) -> int:
    ctx = ctypes.c_void_p()
    result = lib.myc_create_context(gpu_index, ctypes.byref(ctx))
    if result != RESULT_SUCCESS or not ctx:
        raise RuntimeError(f"myc_create_context fehlgeschlagen (Code {result})")

    try:
        if lib.myc_set_seed(ctx, ctypes.c_uint64(base_seed)) != RESULT_SUCCESS:
            raise RuntimeError("myc_set_seed fehlgeschlagen")

        buffer = (ctypes.c_uint8 * 8)()
        process_result = lib.myc_process_buffer(
            ctx,
            buffer,
            ctypes.c_size_t(8),
            ctypes.c_size_t(0),
        )
        if process_result != RESULT_SUCCESS:
            raise RuntimeError(f"myc_process_buffer fehlgeschlagen (Code {process_result})")

        raw = bytes(buffer)
        return struct.unpack("<q", raw)[0]
    finally:
        lib.myc_destroy_context(ctx)


def main() -> int:
    parser = argparse.ArgumentParser(description="Generiert einen Mycelia-Seed über CC_OpenCl.dll")
    parser.add_argument("--seed", type=int, help="Basis-Seed für den Mycelia-Treiber (64-bit)")
    parser.add_argument("--gpu", type=int, default=0, help="GPU-Index für myc_create_context (Default: 0)")
    args = parser.parse_args()

    script_dir = pathlib.Path(__file__).resolve().parent

    try:
        lib = _load_library(script_dir)
    except Exception as exc:  # noqa: BLE001 - wir wollen hier bewusst jeden Fehler melden
        sys.stderr.write(f"[mein_subqg_seed_script] DLL konnte nicht geladen werden: {exc}\n")
        return 1

    _bind_functions(lib)

    if lib.myc_init() != RESULT_SUCCESS:
        sys.stderr.write("[mein_subqg_seed_script] myc_init fehlgeschlagen.\n")
        return 1

    device_count = lib.myc_get_device_count()
    if device_count <= 0:
        sys.stderr.write("[mein_subqg_seed_script] Keine GPUs verfügbar (myc_get_device_count <= 0).\n")
        return 1

    base_seed = args.seed if args.seed is not None else secrets.randbits(64)

    try:
        world_seed = _generate_seed(lib, args.gpu, base_seed)
        print(world_seed)
        return 0
    except Exception as exc:  # noqa: BLE001 - Fallback auf sicheren Seed
        sys.stderr.write(f"[mein_subqg_seed_script] Fehler, wechsle auf OS-Seed: {exc}\n")
        fallback = _as_signed_64(secrets.randbits(64))
        print(fallback)
        return 0


if __name__ == "__main__":
    sys.exit(main())
