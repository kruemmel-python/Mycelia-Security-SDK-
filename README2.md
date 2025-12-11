
# **Mycelia Security SDK – V4 Enterprise**

### *GPU-basiertes Kryptografie-Framework mit deterministischem Chaos, Zero-Key-Footprint und End-to-End-Streaming-Verschlüsselung.*

---

## **Inhalt**

1. [Einführung](#einführung)
2. [Hauptkomponenten](#hauptkomponenten)
3. [Funktionsprinzip](#funktionsprinzip)
4. [Features der Enterprise-Edition](#features-der-enterprise-edition)
5. [Systemanforderungen](#systemanforderungen)
6. [Installation & Ausführung](#installation--ausführung)
7. [Ordnerstruktur](#ordnerstruktur)
8. [Screenshots & Visual Documentation](#screenshots--visual-documentation)
9. [Architektur & Whitepaper](#architektur--whitepaper)
10. [API-Referenz](#api-referenz)
11. [Beispielcode](#beispielcode)
12. [Bekannte Eigenschaften & Hinweise](#bekannte-eigenschaften--hinweise)
13. [Lizenz & Kontakt](#lizenz--kontakt)

---

# **Einführung**

Das **Mycelia Security SDK – V4 Enterprise** ist ein neuartiges Kryptografie-Framework, das deterministische Chaos-Simulationen im **VRAM der GPU** verwendet, um dynamische Schlüsselströme zu erzeugen.

Das System eliminiert vollständig die klassischen Schwachstellen statischer Schlüssel:

* **keine Keyfiles**,
* **keine gespeicherten privaten Schlüssel**,
* **keine wiederverwendbaren Seeds**.

Stattdessen entsteht jeder Schlüssel **on demand** durch die proprietäre Chaos-Engine **CC_OpenCl.dll**, die in GPU-Speicher operiert.

Damit ist Mycelia eine völlig neue Kryptografie-Klasse:
**Emergent GPU Cryptography (EGC).**

---

# **Hauptkomponenten**

### **1. Mycelia Vault (Desktop-App)**

GPU-beschleunigte Datei-Verschlüsselung mit:

* Streaming-Modus (CTR-ähnlich)
* Auto-Decryption
* Zlib-Kompression
* Integritätsprüfung (CRC32)
* deterministischen Keystreams

### **2. Mycelia Encrypted Chat**

End-to-End-Messenger basierend auf:

* Seed-Maskierung (XOR Hash Layer)
* Zlib-komprimierten Paketen
* deterministischer GPU-Entschlüsselung
* Dateitransfer in Echtzeit
* Zero-Knowledge Relay Server

### **3. MCP Relay Server**

Leitet Pakete blind weiter:

* sieht keine Schlüssel
* sieht keinen Klartext
* sieht nur Masked Seeds + Längenfelder

---

# **Funktionsprinzip**

### **1. Biological Seed (64-bit Integer)**

Startzustand für deterministisches Chaos.

### **2. Seed-Maskierung**

```
MaskedSeed = Seed ⊕ SHA256(passwort)[:8]
```

### **3. VRAM-Chaos-Engine**

Die DLL erzeugt mehrere VRAM-Felder, Agenten und Noise-Layer, die deterministisch interagieren.

### **4. XOR-Keystream-Cipher**

```
Cipher = Plain XOR Chaos(Seed)
```

### **5. Bio-CTR Mode**

Große Dateien → in Blöcken verschlüsselt, GPU-optimiert.

---

# **Features der Enterprise-Edition**

* GPU-basierte Schlüsselstrom-Simulation
* deterministisch reproduzierbarer Keystream
* Zero-Key-Footprint (keine gespeicherten Keys)
* Seed-Masking Layer
* Zlib Auto-Compression
* Integritätsprüfung per CRC32/Adler32
* Multi-GPU-Support (OpenCL)
* 3 vollständige Desktop-Programme
* SDK für C, Python, C#
* Kernel-Cache für schnellen Startup
* PyInstaller Self-Contained Executables

---

# **Systemanforderungen**

| Komponente | Minimum                                  |
| ---------- | ---------------------------------------- |
| OS         | Windows 10/11 x64 oder Linux x64         |
| GPU        | OpenCL 1.2 kompatibel (AMD/NVIDIA/Intel) |
| RAM        | 4 GB                                     |
| VRAM       | 2 GB                                     |
| CPU        | x64 Dual Core                            |
| Sonstiges  | GPU-Treiber installiert                  |

---

# **Installation & Ausführung**

1. Ordner `Mycelia-Security-SDK/` entpacken
2. Programme im `tools/` Ordner starten:

```
MyceliaVault_Enterprise.exe
MyceliaChat_Client.exe
MyceliaChat_Server.exe
```

3. Beim ersten Start kompiliert die Chaos-Engine GPU-Kernel und legt sie ab in:

```
tools/build/kernel_cache/
```

---

# **Ordnerstruktur**

```
Mycelia-Security-SDK/
│
├── tools/
│   ├── MyceliaChat_Client.exe           # Chat-Client (DLL embedded)
│   ├── MyceliaChat_Server.exe           # Relay Server (pure Python → exe)
│   ├── MyceliaVault_Enterprise.exe      # Vault GUI (DLL embedded)
│   └── build/
│       └── kernel_cache/                # Automatisch erstellter GPU-Kernel-Cache
│           ├── gfx90c_*_kernel1.bin
│           ├── gfx90c_*_kernel2.bin
│           ├── gfx90c_*_...             # ~100 JIT-optimierte Kernels
│           └── ...
│
├── Doku/
│   ├── Mycelia_Visual_Documentation.md
│   ├── architecture.md
│   └── API Reference.md
│
└── bin/
    └── CC_OpenCl.dll   # Original DLL, nur für Entwickler (nicht benötigt in Runtime)
```

---

# **Screenshots & Visual Documentation**

Die vollständige Bilddokumentation ist hier verfügbar:

📄 **[Mycelia Visual Documentation](Doku/Mycelia_Visual_Documentation.md)**

Sie enthält:

* Vault-Screenshots
* Chat-Screenshots
* Dateiübertragung
* Server-Ansicht
* Integritätsprüfung

---

# **Architektur & Whitepaper**

📄 **[architecture.md](Doku/architecture.md)** – erklärt:

* Chaos-Engine
* Seed-Mechaniken
* Bio-CTR-Mode
* VRAM-Simulation
* Sicherheitsprinzipien

---

# **API-Referenz**

📄 **[API Reference.md](Doku/API%20Reference.md)** – für:

* `myc_create_context`
* `myc_set_seed`
* `myc_process_buffer`
* Error Handling
* Integration in C / Python / C#

---

# **Beispielcode**

### **Python**

```python
from ctypes import cdll, c_uint64, c_void_p

lib = cdll.LoadLibrary("CC_OpenCl.dll")
ctx = lib.myc_create_context(0)

seed = 123456789
lib.myc_set_seed(ctx, c_uint64(seed))

data = bytearray(b"Hallo Mycelia")
lib.myc_process_buffer(ctx, data, len(data), 0)
```

### **C**

```c
myc_context_t* ctx = myc_create_context(0);
myc_set_seed(ctx, 123456789ULL);
myc_process_buffer(ctx, buffer, size, 0);
```

---

# **Bekannte Eigenschaften & Hinweise**

* Beim ersten Start → Kernel-Cache wird erstellt
* Cache ist GPU-abhängig (z. B. gfx90c)
* Seeds werden nie gespeichert
* Server sieht keinen Klartext
* DLL ist in EXE eingebettet
* Vollständig portabel (keine Installation nötig)

---

# **Lizenz & Kontakt**

**Mycelia Security SDK – V4 Enterprise**
Proprietäre Technologie
Alle Rechte vorbehalten.

Kontakt für Partnerschaften, Forschung, Enterprise-Lizenzierung:
**ralf.kruemmel@outlook.de**

---
