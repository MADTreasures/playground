# playground

## Fluency – Live-Übersetzer für Android, vollständig offline

- **App installieren**: [`dist/Fluency-1.3.0.apk`](dist/Fluency-1.3.0.apk). Neu in 1.3.0:
  - Die Übersetzung beachtet den Zusammenhang: „Nachrichten“ im Chat wird *messages*, nicht *news*.
  - Schweizerdeutsch rechnet mit den schnellen ARM-Kernels (Q8_0 statt Q5_0, mehr Threads).
  - Seit 1.2.0 läuft die Übersetzung auch auf der Hexagon-NPU.
- **Projekt für Android Studio**: [`dist/Fluency-AndroidStudio-1.3.0.zip`](dist/Fluency-AndroidStudio-1.3.0.zip)
  (entpacken → in Android Studio öffnen → Run). Alternativ funktioniert auch „Code → Download ZIP“
  dieses Repos; dann den Ordner `fluency` öffnen.
- Beschreibung, Modelle, Build und Tests: [`fluency/README.md`](fluency/README.md)

llama.cpp (CPU + Adreno-GPU + Hexagon-NPU) + sherpa-onnx + whisper.cpp · Hy-MT2-1.8B / MiLMMT-46 / Parakeet-TDT-0.6B-v3 / Whisper.
