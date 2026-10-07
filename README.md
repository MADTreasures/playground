# playground

## Fluency – Live-Übersetzer für Android, vollständig offline

- **App installieren**: [`dist/Fluency-1.2.0.apk`](dist/Fluency-1.2.0.apk). Neu in 1.2.0: Die
  Übersetzung kann auch auf der Hexagon-NPU laufen. Die App misst einmal pro Modell CPU, GPU
  (Adreno) und NPU und nimmt das schnellste Rechenwerk.
- **Projekt für Android Studio**: [`dist/Fluency-AndroidStudio-1.2.0.zip`](dist/Fluency-AndroidStudio-1.2.0.zip)
  (entpacken → in Android Studio öffnen → Run). Alternativ funktioniert auch „Code → Download ZIP“
  dieses Repos; dann den Ordner `fluency` öffnen.
- Beschreibung, Modelle, Build und Tests: [`fluency/README.md`](fluency/README.md)

llama.cpp (CPU + Adreno-GPU + Hexagon-NPU) + sherpa-onnx + whisper.cpp · Hy-MT2-1.8B / MiLMMT-46 / Parakeet-TDT-0.6B-v3 / Whisper.
