#!/usr/bin/env python3
"""
mein_subqg_seed_script.py

- Genau 1 JSON-Zeile auf STDOUT (flush=True)
- Diagnose/Logs ausschließlich auf STDERR
- Timeout kommt bevorzugt aus Env MYCELIA_DRIVER_TIMEOUT (vom Plugin gesetzt),
  optional überschreibbar via --timeout.
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
import time
from multiprocessing import get_context
from multiprocessing.connection import Connection
from typing import Final

RESULT_SUCCESS: Final[int] = 0
DLL_NAME: Final[str] = "CC_OpenCl.dll"


def _u64(n: int) -> int:
    return n & 0xFFFFFFFFFFFFFFFF


def _i64_from_u64(n: int) -> int:
    raw = _u64(n)
    return struct.unpack("<q", struct.pack("<Q", raw))[0]


def _prepare_windows_dll_search(dll_dir: pathlib.Path) -> None:
    if os.name != "nt":
        return
    try:
        os.add_dll_directory(str(dll_dir))
    except Exception:
        pass
    current_path = os.environ.get("PATH", "")
    dll_dir_str = str(dll_dir)
    if dll_dir_str and dll_dir_str not in current_path:
        os.environ["PATH"] = dll_dir_str + os.pathsep + current_path


def _load_library(script_dir: pathlib.Path) -> ctypes.CDLL:
    dll_dir = (script_dir / "bin").resolve()
    candidate = (dll_dir / DLL_NAME).resolve()
    if not candidate.exists():
        raise FileNotFoundError(f"DLL nicht gefunden: {candidate}")

    if os.name == "nt":
        _prepare_windows_dll_search(dll_dir)
        return ctypes.WinDLL(str(candidate))
    return ctypes.CDLL(str(candidate))


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


def _gpu_seed_worker(script_dir: pathlib.Path, gpu_index: int, base_seed: int, conn: Connection) -> None:
    try:
        lib = _load_library(script_dir)
        _bind_functions(lib)

        if lib.myc_init() != RESULT_SUCCESS:
            raise RuntimeError(f"Init fail: {_last_error(lib)}")

        ctx = ctypes.c_void_p()
        if lib.myc_create_context(gpu_index, ctypes.byref(ctx)) != RESULT_SUCCESS:
            raise RuntimeError(f"Context fail: {_last_error(lib)}")

        try:
            lib.myc_set_seed(ctx, ctypes.c_uint64(_u64(base_seed)))
            buf = (ctypes.c_uint8 * 8)()
            rc = lib.myc_process_buffer(ctx, buf, ctypes.c_size_t(8), ctypes.c_size_t(0))
            if rc != RESULT_SUCCESS:
                raise RuntimeError(f"process_buffer fail: {_last_error(lib)}")

            seed_u64 = struct.unpack("<Q", bytes(buf))[0]
            conn.send(("ok", int(seed_u64)))
        finally:
            lib.myc_destroy_context(ctx)

    except Exception as exc:
        try:
            conn.send(("err", f"{type(exc).__name__}: {exc}"))
        except Exception:
            pass
    finally:
        try:
            conn.close()
        except Exception:
            pass


def _effective_timeout_seconds(cli_timeout: float | None) -> float:
    """
    Timeout-Quelle in Priorität:
    1) --timeout
    2) MYCELIA_DRIVER_TIMEOUT (vom Plugin gesetzt)
    3) Default 90

    Zusätzlich: Sicherheitsmarge, damit der Parent noch JSON drucken kann,
    falls Java-Prozess ebenfalls ein hartes Timeout hat.
    """
    if cli_timeout is not None:
        return max(1.0, float(cli_timeout))

    env = os.environ.get("MYCELIA_DRIVER_TIMEOUT")
    if env:
        try:
            java_limit = float(env)
        except ValueError:
            java_limit = 90.0
    else:
        java_limit = 90.0

    margin = 5.0
    return max(1.0, java_limit - margin)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, help="Basis-Seed (int)")
    ap.add_argument("--gpu", type=int, default=0, help="GPU-Index")
    ap.add_argument("--timeout", type=float, default=None, help="GPU-Timeout (überschreibt Env)")
    ap.add_argument("--unsigned", action="store_true", help="Unsigned Output (u64 statt i64)")
    args = ap.parse_args()

    # DIAG (optional, später entfernen)
    # sys.stderr.write(f"[Mycelia-DIAG] CWD={os.getcwd()}\n")
    # sys.stderr.write(f"[Mycelia-DIAG] __file__={__file__}\n")
    # sys.stderr.write(f"[Mycelia-DIAG] MYCELIA_DRIVER_TIMEOUT={os.environ.get('MYCELIA_DRIVER_TIMEOUT','')}\n")
    sys.stderr.flush()

    timeout_s = _effective_timeout_seconds(args.timeout)

    script_dir = pathlib.Path(__file__).resolve().parent
    base_seed = args.seed if args.seed is not None else secrets.randbits(64)

    ctx = get_context("spawn") if os.name == "nt" else get_context()
    parent_conn, child_conn = ctx.Pipe(duplex=False)

    p = ctx.Process(
        target=_gpu_seed_worker,
        args=(script_dir, args.gpu, base_seed, child_conn),
        daemon=True,
    )

    t0 = time.monotonic()
    p.start()
    child_conn.close()

    final_seed_u64 = secrets.randbits(64)
    status_msg: str | None = None

    try:
        if parent_conn.poll(timeout_s):
            status, payload = parent_conn.recv()
            if status == "ok":
                final_seed_u64 = int(payload)
            else:
                status_msg = f"[Mycelia] Treiber-Fehler: {payload} - Fallback genutzt."
        else:
            status_msg = f"[Mycelia] Timeout ({timeout_s:.1f}s) - Fallback genutzt."
            try:
                p.kill()
            except Exception:
                try:
                    p.terminate()
                except Exception:
                    pass
    finally:
        try:
            p.join(timeout=0.25)
        except Exception:
            pass
        try:
            parent_conn.close()
        except Exception:
            pass

    if status_msg:
        sys.stderr.write(status_msg + "\n")

    palettes = [
        {"name": "Myzel-Invasion", "base": "DEEPSLATE", "surface": "MYCELIUM", "ore": "AMETHYST_BLOCK", "scale": 0.02},
        {"name": "Eis-Oede", "base": "PACKED_ICE", "surface": "SNOW_BLOCK", "ore": "BLUE_ICE", "scale": 0.035},
        {"name": "Vulkanisch", "base": "BLACKSTONE", "surface": "BASALT", "ore": "MAGMA_BLOCK", "scale": 0.015},
        {"name": "Verdorbener Wald", "base": "NETHERRACK", "surface": "WARPED_NYLIUM", "ore": "NETHER_WART_BLOCK", "scale": 0.025},
        {"name": "Ueberwuchert", "base": "MOSSY_COBBLESTONE", "surface": "MOSS_BLOCK", "ore": "RAW_GOLD_BLOCK", "scale": 0.03},
    ]

    random.seed(final_seed_u64)
    theme = random.choice(palettes)

    result = {
        "seed": final_seed_u64 if args.unsigned else _i64_from_u64(final_seed_u64),
        "baseBlock": theme["base"],
        "surfaceBlock": theme["surface"],
        "oreBlock": theme["ore"],
        "scale": theme["scale"],
        "seaLevel": 62,
    }

    print(json.dumps(result), flush=True)

    dt = time.monotonic() - t0
    sys.stderr.write(f"[Mycelia] Welt-Typ generiert: {theme['name']} (total {dt:.3f}s)\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
