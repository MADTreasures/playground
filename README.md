# playground

## Fluency – Live-Übersetzer für Android, vollständig offline

- **App installieren**: [`dist/Fluency-1.7.0.apk`](dist/Fluency-1.7.0.apk). Version 1.7.0 baut auf
  1.2.0 auf: Die Übersetzung läuft wieder genau wie dort (die Änderungen von 1.3.0 bis 1.6.0 sind
  zurückgenommen). Neu ist nur:
  - **Doppeldeutige Wörter ersetzen**: Unter einer Übersetzung erscheinen Knöpfe wie
    „news → messages“. Finger drauf markiert das Wort in der Übersetzung, Loslassen ersetzt es.
  - Die Wortlisten stehen in den Optionen, pro Sprache ein eigenes Fenster und eine eigene Datei.
    Sie sind für sechs Sprachen vorbefüllt und lassen sich ergänzen und bearbeiten.
- **Projekt für Android Studio**: [`dist/Fluency-AndroidStudio-1.7.0.zip`](dist/Fluency-AndroidStudio-1.7.0.zip)
  (entpacken → in Android Studio öffnen → Run). Alternativ funktioniert auch „Code → Download ZIP“
  dieses Repos; dann den Ordner `fluency` öffnen.
- Beschreibung, Modelle, Build und Tests: [`fluency/README.md`](fluency/README.md)

llama.cpp (CPU + Adreno-GPU + Hexagon-NPU) + sherpa-onnx + whisper.cpp · Hy-MT2-1.8B / MiLMMT-46 / Parakeet-TDT-0.6B-v3 / Whisper.
