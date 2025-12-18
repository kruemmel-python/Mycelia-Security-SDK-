# /myceliaworld In-Game Hilfe

## Basisbefehle
- `/myceliaworld list` – Zeigt alle aktuell geladenen Welten an.
- `/myceliaworld tp <world>` – Teleportiert dich zum Spawn der angegebenen geladenen Welt. (Nur Spieler)
- `/myceliaworld remove <world> [--force]` – Entlädt und löscht die Welt sicher. Mit `--force` auch, wenn Spieler darin sind.
- `/myceliaworld <worldName> [--seed <long>]` – Erstellt eine neue Welt mit Daten aus dem Mycelia-Treiber (oder Fallback), optional mit explizitem Seed.
- `/myceliaworld info` – Zeigt gespeicherte Mycelia-Metadaten der aktuellen Welt an (Seed, Blöcke, Skalierung, Meeresspiegel).

## Hinweise
- Der Treiber wird asynchron kontaktiert; bei Fehlern werden sichere Fallback-Daten genutzt.
- Entfernen löscht den Weltenordner asynchron, nachdem Spieler in die Hauptwelt zurückgebracht wurden.
- Die Weltgenerierung nutzt Mycelia-spezifische Blöcke, Erzadern und optionale Strukturen (Pilzdungeon und unterirdische Kammern) basierend auf Treiber-/Konfigurationswerten.
