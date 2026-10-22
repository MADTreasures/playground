# playground

## Fluency – Live-Übersetzer für Android, vollständig offline

- **App installieren**: [`dist/Fluency-1.8.0.apk`](dist/Fluency-1.8.0.apk). Die Übersetzung läuft wie
  in 1.2.0, die Änderungen von 1.3.0 bis 1.6.0 an der Übersetzung sind zurückgenommen. Dazu:
  - **Doppeldeutige Wörter ersetzen**: Unter einer Übersetzung erscheinen Knöpfe wie
    „news → messages“. Finger drauf markiert das Wort in der Übersetzung, Loslassen ersetzt es.
  - **Wortlisten nach Sprachpaar** in den Optionen: Sprache der Übersetzung (eine Datei pro Sprache)
    → Sprache des Originals → Seite mit allen Doppeldeutungen, z. B. Deutsch → Englisch
    „Bank: bench ↔ bank“. Favoriten (Stern) stehen oben. Vorbefüllt für sechs Sprachen, alles
    lässt sich ergänzen und bearbeiten.
  - Wieder drin: die **schnellere Schweizerdeutsch-Erkennung** aus 1.3.0.
- **Projekt für Android Studio**: [`dist/Fluency-AndroidStudio-1.8.0.zip`](dist/Fluency-AndroidStudio-1.8.0.zip)
  (entpacken → in Android Studio öffnen → Run). Alternativ funktioniert auch „Code → Download ZIP“
  dieses Repos; dann den Ordner `fluency` öffnen.
- **Im Quellcode, noch ohne fertige APK: 1.9.0** mit Untertiteln auf der Brille Even Realities G2
  (über die Uhr-App G2 Watch, Ton vom Mikrofon der Uhr oder des Handys). Optionen → „Brille (G2 Watch)“;
  die Gegenstücke auf Uhr und Handy fehlen noch, siehe [`fluency/README.md`](fluency/README.md), Funktion 9.
- Beschreibung, Modelle, Build und Tests: [`fluency/README.md`](fluency/README.md)

llama.cpp (CPU + Adreno-GPU + Hexagon-NPU) + sherpa-onnx + whisper.cpp · Hy-MT2-1.8B / MiLMMT-46 / Parakeet-TDT-0.6B-v3 / Whisper.
