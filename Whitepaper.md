# Whitepaper: Mycelia Minecraft – Emergent Worlds aus GPU-Chaos

## 1. Einzigartigkeit des Ansatzes

* **Hardware-gebundene Seeds:** Welt-DNA entsteht aus VRAM-Chaos (OpenCL-Treiber) statt statischen Zahlen in `server.properties`. Die Seeds verlassen nie den GPU-Kontext; nur abgeleitete Metadaten landen im Plugin-Cache.
* **Deterministisches Emergent-Design:** Die gleichen Treiber-Inputs erzeugen reproduzierbare Welten, aber mit organischen Abweichungen (Biome-Paletten, Ore-Adern, Lore-Strings). Damit wird Welt-Generierung zu einem kontrollierten „Biotop“ statt zu starren Noise-Parametern.
* **Mehrschichtiger Sicherheitsfokus:** Das Mycelia-Kernprojekt wurde als VRAM-Kryptosystem gebaut. Die Minecraft-Integration profitiert von denselben Schutzmechanismen (z. B. manipulationsresistente Seeds, Timeout-/Fallback-Pfade, Persistent-Driver-Ping).
* **API-Erweiterbarkeit:** Über `MyceliaAPI` können Server-Owner eigene Biome-Modifikatoren, Noise-Sampler oder Story-Hooks einschleusen, ohne den Kern zu forken.

## 2. Was das System heute leistet

### 2.1 Treiber-Workflow

1. **Seed-Erzeugung:** Der externe Treiber liefert pro Aufruf eine JSON-Zeile mit Seed, Blöcken, Skala und Meeresspiegel (oder fällt auf sicheren Fallback zurück). 
2. **Parsing & Sanitizing:** `MyceliaDriver` akzeptiert JSON, reinen Seed oder `key=value`-Listen, prüft Materialnamen und Skalen und ersetzt ungültige Werte automatisch.
3. **DNA-Persistenz:** Welt-DNA und -Metadaten werden in `worlds.yml` gespeichert und beim Serverstart rekonstruiert.
4. **Asynchron + Persistent:** Treiber-Calls laufen asynchron; optional wird ein persistent laufender Dienst gepingt und bevorzugt genutzt.

### 2.2 In-Game Features

* **Kommandos:** `/myceliaworld` (Erzeugen/Listen/Teleport/Löschen/Info) und `/mycelia` (attune/stabilize/corrupt/dream/lore/otoc/timewarp/debug/health).
* **Terrain & Strukturen:** Simplex-Noise-Generator mit vertikalem Gradienten, Myzel-Dungeons (2 % pro Chunk) und unterirdische Amethyst-Kammern (3 %).
* **Evolution & Lore:** Bias-Änderungen mutieren die DNA (Seed-Drift), Lore-Strings stammen aus Symbolic-Abstraction-Kern oder aus Welt-Signaturen.
* **Caching & Telemetrie:** Dream-State-Cache, OTOC/Chaos-Faktor, Treiber-Laufzeiten, Fallback-Zähler und Persistent-Ping im Debug-Befehl.

## 3. Funktionsweise im Detail

### 3.1 Datenfluss

```
Player Command -> Bukkit Async Task -> MyceliaDriver (JSON/Seed/KV) ->
Sanitize -> MyceliaWorldData + DNA -> WorldCreator + MyceliaChunkGenerator ->
Persist to worlds.yml -> Evolution & Caches -> Optional Lore/Narrative
```

### 3.2 Treiber-Output (Beispiel)

```json
{ "seed": 987654321, "baseBlock": "DEEPSLATE", "surfaceBlock": "MYCELIUM", "oreBlock": "AMETHYST_BLOCK", "scale": 0.031, "seaLevel": 46 }
```

### 3.3 Konfiguration (Auszug)

```yaml
driver:
  command: "python ../python/mein_subqg_seed_script.py"
  timeoutSeconds: 35
  warmupTimeoutSeconds: 300
  persistent:
    enabled: true
    command: "python ../python/mein_subqg_seed_script.py --persistent"

world:
  baseBlock: DEEPSLATE
  surfaceBlock: MYCELIUM
  oreBlock: AMETHYST_BLOCK
  seaLevel: 45
  scale: 0.025
```

## 4. Beispiele für Erweiterungen heute

### 4.1 Eigene Biome-Paletten injizieren

```java
// Beispiel: zusätzlicher Biome-Modifikator mit dem öffentlich exponierten API
import com.mycelia.mc.api.Mycelia;
import com.mycelia.mc.generation.MyceliaBiomeProfile;

public final class BiomeModBootstrap {
    public static void install() {
        Mycelia.get().registerBiomeModifier(biomes -> {
            MyceliaBiomeProfile corruptedJungle = new MyceliaBiomeProfile(
                "corrupted-jungle",
                0.8f, 0.9f,               // humidity, temperature
                "MANGROVE_ROOTS",        // base
                "MUD",                   // surface
                "ANCIENT_DEBRIS"         // ore veins
            );
            biomes.add(corruptedJungle);
        });
    }
}
```

### 4.2 Lore/Chaos auf UI oder Discord spiegeln

```java
double chaos = plugin.getDriver().requestGlobalOTOC();
String lore = plugin.getDriver().requestEmergentLorePhrase(player.getWorld());
discordWebhook.send("Chaos: " + chaos + " | Lore: " + lore);
```

### 4.3 Treiber-Skript mit Themenpaletten

```python
# mein_subqg_seed_script.py (vereinfacht)
import json, random, sys

PALETTES = [
    ("myzel-invasion", "DEEPSLATE", "MYCELIUM", "AMETHYST_BLOCK"),
    ("ashen-rift", "BASALT", "BLACKSTONE", "NETHERITE_BLOCK"),
]

seed = random.randint(1, 2**31 - 1)
theme = PALETTES[seed % len(PALETTES)]
payload = {
    "seed": seed,
    "baseBlock": theme[1],
    "surfaceBlock": theme[2],
    "oreBlock": theme[3],
    "scale": 0.029,
    "seaLevel": 42
}
print(json.dumps(payload))
```

## 5. Zukunftsvisionen – neue Spielerlebnisse

### 5.1 RPG-Schichten über die Welt-DNA
* **Dynamische Fraktionen:** Fraktionen leiten Aggression/Freundlichkeit aus Welt-DNA ab (höherer Chaos-Faktor = feindseligere NPCs).
* **Seed-gebundene Quests:** Quest-Pools werden aus dem Seed deterministisch abgeleitet, sodass jede Welt eigene Quest-Ketten hat.

### 5.2 Dungeon-Evolution mit intelligenten NPCs
* **Adaptive Layouts:** Dungeon-Räume regenerieren anhand des Dream-State-Gradienten; häufiger besuchte Räume mutieren (Fallen, Spawner-Typen).
* **NPC-Lernen:** NPCs beziehen Pfadwahl aus Treiber-Rauschfeldern (Noise-Sampler) und passen Loot oder Taktik an den OTOC-Wert an.

### 5.3 Intelligente Feinde & Ökosysteme
* **Chaos-gekoppelte KI:** Gegner nutzen `requestNoise(x,z)` als „Gefahrenkarte“ und vermeiden/konzentrieren sich auf Zonen mit hohem Chaos-Faktor.
* **Biologische Symbiose:** Ores/Pflanzen wachsen nach, wenn die Treiber-Persistenz stabil bleibt (wenige Fallbacks), und vergehen bei vielen Fallbacks.

### 5.4 Zeit- und Raum-Anomalien
* **Time-Warp-Zonen:** Bereits vorhanden, kann erweitert werden zu progressiven Effekten (Aging-Effekte, beschleunigte Pflanzen, langsame Projektile).
* **Chunk-Phasenverschiebung:** Chunks bekommen periodische „Phasenwechsel“ basierend auf Treiber-OTOC; Blöcke/Spawnlisten rotieren temporär.

### 5.5 Cross-Game/Out-of-Game Hooks
* **GPU-Backed Events:** Externe Chaos-Metriken (z. B. aus dem Mycelia-Vault) fließen live in die Welt ein und ändern Biome oder Lore.
* **Story-Streaming:** Lore-Strings werden per Webhook an Streaming-Overlays gesendet; Zuschauer sehen den Live-Myzelzustand.

## 6. Roadmap-Vorschläge

1. **Persistent Driver v2:** GRPC/JSON-RPC-Schnittstelle mit Heartbeat und Rate-Limits; Streaming von OTOC/Dream-State statt Polling.
2. **Biome DSL:** YAML/JSON-Schema für Biome & Strukturen, die der Treiber direkt ausgibt und das Plugin unverändert übernimmt.
3. **AI-NPC Layer:** Schnittstelle für externe KI-Services (z. B. Pfadfindung + Dialog) mit Seed-basiertem Determinismus zur Reproduzierbarkeit.
4. **Replayable Seeds:** Export/Import von Welt-DNA + Treiber-Logs, um Events in Testservern deterministisch nachzustellen.
5. **Client-Side Visualization:** Optionale Map-Renderer, die Dream-State-Gradient und Chaos-Faktor pro Chunk visualisieren.

---

Mycelia Minecraft verbindet VRAM-basiertes Chaos mit der Flexibilität eines Paper-Plugins. Das Resultat ist ein modulares, deterministisches, aber lebendiges Ökosystem, das von Kryptografie-Roots bis hin zu emergenten RPG-Mechaniken skaliert. Dieses Whitepaper soll als Ausgangspunkt für weitere Experimente, Patches und Community-Ideen dienen.
