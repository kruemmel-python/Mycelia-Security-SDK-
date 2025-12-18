#!/usr/bin/env python3
"""
mein_subqg_seed_script.py (hardened & intelligent)

Funktion:
- Berechnet einen Seed via GPU (CC_OpenCl.dll), wenn verfügbar.
- Wählt basierend auf dem Seed eine thematische Material-Palette.
- Gibt ein JSON-Objekt für das mc_mycelia Plugin aus (einzige Zeile auf STDOUT).
"""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import pathlib
import random
import secrets
import struct
import sys
from multiprocessing import Process, Queue
from typing import Final

RESULT_SUCCESS: Final[int] = 0


# ---------- GPU / DLL Helpers ----------
def _load_library(script_dir: pathlib.Path) -> ctypes.CDLL:
    dll_name = "CC_OpenCl.dll"
    candidate = (script_dir.parent / "bin" / dll_name).resolve()
    if not candidate.exists():
        raise FileNotFoundError(f"DLL nicht gefunden: {candidate}")
    return ctypes.WinDLL(str(candidate)) if os.name == "nt" else ctypes.CDLL(str(candidate))


def _bind_functions(lib: ctypes.CDLL) -> None:
    lib.myc_init.restype = ctypes.c_int
    lib.myc_get_device_count.restype = ctypes.c_int
    lib.myc_create_context.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_void_p)]
    lib.myc_create_context.restype = ctypes.c_int
    lib.myc_set_seed.argtypes = [ctypes.c_void_p, ctypes.c_uint64]
    lib.myc_set_seed.restype = ctypes.c_int
    lib.myc_process_buffer.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_uint8),
        ctypes.c_size_t,
        ctypes.c_size_t,
    ]
    lib.myc_process_buffer.restype = ctypes.c_int
    lib.myc_destroy_context.argtypes = [ctypes.c_void_p]
    lib.myc_destroy_context.restype = None
    lib.myc_get_last_error.restype = ctypes.c_char_p


def _last_error(lib: ctypes.CDLL) -> str:
    try:
        msg = lib.myc_get_last_error()
        return msg.decode("utf-8", errors="replace") if msg else ""
    except Exception:
        return ""


def _u64(n: int) -> int:
    return n & 0xFFFFFFFFFFFFFFFF


def _i64_from_u64(n: int) -> int:
    raw = _u64(n)
    return struct.unpack("<q", struct.pack("<Q", raw))[0]


def _generate_seed_inner(dll_path_dir: pathlib.Path, gpu_index: int, base_seed: int, q: Queue) -> None:
    try:
        lib = _load_library(dll_path_dir)
        _bind_functions(lib)
        if lib.myc_init() != RESULT_SUCCESS:
            raise RuntimeError(f"Init fail: {_last_error(lib)}")

        ctx = ctypes.c_void_p()
        if lib.myc_create_context(gpu_index, ctypes.byref(ctx)) != RESULT_SUCCESS:
            raise RuntimeError(f"Context fail: {_last_error(lib)}")

        try:
            lib.myc_set_seed(ctx, ctypes.c_uint64(_u64(base_seed)))
            buf = (ctypes.c_uint8 * 8)()
            lib.myc_process_buffer(ctx, buf, ctypes.c_size_t(8), ctypes.c_size_t(0))
            q.put(("ok", struct.unpack("<Q", bytes(buf))[0]))
        finally:
            lib.myc_destroy_context(ctx)
    except Exception as exc:
        q.put(("err", str(exc)))


# ---------- Main logic ----------
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, help="Basis-Seed")
    ap.add_argument("--gpu", type=int, default=0, help="GPU-Index")
    # Timeout kann per Arg oder Umgebungsvariable MYCELIA_DRIVER_TIMEOUT gesetzt werden
    env_timeout = os.environ.get("MYCELIA_DRIVER_TIMEOUT")
    default_timeout = float(env_timeout) if env_timeout else 5.0
    ap.add_argument("--timeout", type=float, default=default_timeout, help="Timeout")
    ap.add_argument("--unsigned", action="store_true", help="Unsigned Output")
    args = ap.parse_args()

    script_dir = pathlib.Path(__file__).resolve().parent
    base_seed = args.seed if args.seed is not None else secrets.randbits(64)

    q: Queue = Queue()
    p = Process(target=_generate_seed_inner, args=(script_dir, args.gpu, base_seed, q), daemon=True)
    p.start()
    p.join(timeout=args.timeout)

    # Seed-Ermittlung (GPU oder Fallback)
    final_seed_raw = secrets.randbits(64)
    if p.is_alive():
        p.kill()
        sys.stderr.write(f"[Mycelia] Timeout ({args.timeout}s) - Fallback genutzt.\n")
    elif not q.empty():
        status, payload = q.get()
        if status == "ok":
            final_seed_raw = payload
        else:
            sys.stderr.write(f"[Mycelia] Treiber-Fehler: {payload} - Fallback genutzt.\n")

    # --- INTELLIGENTE PALETTEN-LOGIK ---
    palettes = [
        {"name": "Myzel-Invasion", "base": "DEEPSLATE", "surface": "MYCELIUM", "ore": "AMETHYST_BLOCK", "scale": 0.02},
        {"name": "Eis-Oede", "base": "PACKED_ICE", "surface": "SNOW_BLOCK", "ore": "BLUE_ICE", "scale": 0.035},
        {"name": "Vulkanisch", "base": "BLACKSTONE", "surface": "BASALT", "ore": "MAGMA_BLOCK", "scale": 0.015},
        {"name": "Verdorbener Wald", "base": "NETHERRACK", "surface": "WARPED_NYLIUM", "ore": "NETHER_WART_BLOCK", "scale": 0.025},
        {"name": "Ueberwuchert", "base": "MOSSY_COBBLESTONE", "surface": "MOSS_BLOCK", "ore": "RAW_GOLD_BLOCK", "scale": 0.03},
    ]

    random.seed(final_seed_raw)
    theme = random.choice(palettes)

    result = {
        "seed": final_seed_raw if args.unsigned else _i64_from_u64(final_seed_raw),
        "baseBlock": theme["base"],
        "surfaceBlock": theme["surface"],
        "oreBlock": theme["ore"],
        "scale": theme["scale"],
        "seaLevel": 62,
    }

    print(json.dumps(result))
    sys.stderr.write(f"[Mycelia] Welt-Typ generiert: {theme['name']}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
