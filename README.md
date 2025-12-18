# Mycelia Security SDK

**Hardware-Bound VRAM Cryptography & Emergent Security**

Mycelia Security liefert eine OpenCL-basierte Chaos-Engine, die Schlüsselströme direkt im VRAM erzeugt. Schlüssel werden nicht auf Disk oder im Hauptspeicher abgelegt, sondern nur zur Laufzeit erzeugt und verworfen.

---

## 🔥 Kern-Features

* **Keyless Cryptography:** deterministischer Keystream aus GPU-Simulationen, gesteuert über einen Seed.
* **OpenCL Host-Treiber:** C-Implementierung (`src/`, `include/`) mit Header `mycelia.h` für eigene Integrationen.
* **Python Vault GUI:** Referenz-GUI (`python/mycelia_gui_v4.py`) und Vault-Logik (`python/mycelia_vault_v4.py`) nutzen die DLL direkt.
* **Minecraft-Integration:** eigenes Bukkit/Paper-Plugin (`mc_mycelia/`) erzeugt Welten basierend auf Treiber-Seeds und bietet Lore-, Dream-State- und Time-Warp-Mechaniken.
* **Beiliegende Binaries:** `bin/CC_OpenCl.dll` und ein kleines Testprogramm (`test_sdk.exe`) für schnelle Validierung unter Windows.

---

## 📂 Projektstruktur

* `src/`, `include/`, `CL/`: OpenCL-Host-Code und Header für den Mycelia-Kern.
* `python/`: Vault-GUI, Vault-Engine und Beispiel-Treiber-Skript (`mein_subqg_seed_script.py`).
* `mc_mycelia/`: Paper-Plugin (Java 21, API 1.21) mit Treiber-Anbindung und Weltgenerator.
* `bin/`, `lib/`: vorgebaute DLL/Import-Lib für Windows.
* `build_scripts/`: Build-Skripte (z. B. `build_win.bat`).
* `samples/`: Beispielcode zur C-Integration.

---

## 🚀 Quick Start (Vault GUI, Windows)

**Voraussetzungen:** OpenCL-fähige GPU + Treiber, Python 3.12+.

1) DLL bereitstellen: `bin/CC_OpenCl.dll` muss neben den Python-Skripten oder im aktuellen Arbeitsverzeichnis liegen.
2) GUI starten:
```bash
cd python
python mycelia_gui_v4.py
```
3) Alternativ Vault-Logik direkt verwenden (ohne GUI) via `mycelia_vault_v4.py`.

---

## 🛠️ Build-Hinweise (OpenCL-Kern)

**Windows (MinGW-w64):**
```bash
g++ -std=c++17 -O3 -march=native -ffast-math -funroll-loops -fstrict-aliasing \
    -DNDEBUG -DCL_TARGET_OPENCL_VERSION=120 -DCL_FAST_OPTS -DMYCELIA_EXPORTS \
    -shared ./src/mycelia_core.c -o ./bin/CC_OpenCl.dll \
    -I"./include" -I"./src" -I"./CL" -L"./CL" -lOpenCL "-Wl,--out-implib,./lib/libCC_OpenCl.a" \
    -static-libstdc++ -static-libgcc
```
`build_scripts/build_win.bat` ruft diese Flags gebündelt auf.

**Hinweis:** Die mitgelieferte DLL ist für schnelle Tests gedacht. Für angepasste Toolchains oder andere Plattformen sollte der Build neu ausgeführt werden (OpenCL 1.2+ erforderlich).

---

## 💻 Integration Guide (C)

Header: `include/mycelia.h`

```c
#include "mycelia.h"

myc_init();
myc_context_t ctx;
myc_create_context(0, &ctx);
myc_set_seed(ctx, 123456789ULL);
myc_process_buffer(ctx, buffer, len, 0); // In-Place Encrypt/Decrypt
myc_destroy_context(ctx);
```

## 🔗 Weiterführende Module

* **Minecraft-Plugin:** Siehe `mc_mycelia/README_MCW.md` für Befehle, Treiberformat und Build.
* **Visual Documentation:** Architekturskizzen in `Mycelia_Visual_Documentation.md` und `architecture.md`.

---

## ⚠️ Troubleshooting

* **`DLL load failed`**: Liegt `CC_OpenCl.dll` im selben Ordner wie das Python-Skript? Stimmt die OpenCL-Runtime?
* **OpenCL-Fehlercodes**: `myc_get_last_error()` liefert die letzte Fehlermeldung aus dem Host-Treiber.
* **Minecraft-Treiber-Timeouts**: `mc_mycelia` setzt `driver.timeoutSeconds`/`warmupTimeoutSeconds` in `mc_mycelia/src/main/resources/config.yml`.

## 📜 License

MIT License – Copyright (c) 2025 Mycelia Security
