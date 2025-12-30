# Explorer.md

## Mycelia-Container per CLI erzeugen

Diese Anleitung zeigt, wie du eine `.bin`-Containerdatei über die Kommandozeile erzeugst, damit `mycelia_mount.py` sie als `--container` verwenden kann.  
Die Containerstruktur folgt dem V4-Format des Vault-Skripts (`python/mycelia_vault_v4.py`) und enthält:

```
[Magic 4][Version 4][Seed 8][FilenameLen 2][Filename][Content...][IntegrityTag]
```

---

## Voraussetzungen

- Python-Umgebung aktiv (`.venv` oder systemweit)
- Repository im aktuellen Working Directory

---

## Schritt 1: Quelldatei vorbereiten

Lege eine Datei an, die in den Container geschrieben werden soll. Beispiel:

```powershell
echo "Mycelia Payload" > payload.txt
```

---

## Schritt 2: Container über CLI erzeugen

Das Vault-Skript ist interaktiv (bzw. erwartete Funktionseinsteiger), aber du kannst es direkt per Python ausführen, indem du eine kleine Ein-Zeilen-Ausführung nutzt:

```powershell
py -c "from mycelia_vault_v4 import MyceliaVaultV4; vault = MyceliaVaultV4(); vault.encrypt('payload.txt', 'mycelia_container.bin')"
```

Das erzeugt eine Datei `mycelia_container.bin` im aktuellen Verzeichnis.

---

## Schritt 3: Container in den Mount-Prozess geben

```powershell
py python/mycelia_mount.py --seed 12345 --mount M: --container .\mycelia_container.bin
```

---

## Optional: Roh-Container erzeugen (Dummy)

Falls du nur testen willst, ob Mount/Mapping funktioniert, kannst du eine einfache Binärdatei erzeugen:

```powershell
fsutil file createnew mycelia_container.bin 10485760
```

Das erzeugt eine 10‑MB Dummy-Datei.  
Sie ist **kein** Vault-Container, wird aber vom Mount als Rohdaten akzeptiert.

---

## Hinweis zu Pfaden

Der Mount erwartet **eine existierende Datei**.  
Wenn der Pfad falsch oder die Datei fehlt, bricht `mycelia_mount.py` sofort ab.
