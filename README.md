# playground

## Fluency – Live-Übersetzer für Android, vollständig offline

- **App installieren**: [`dist/Fluency-1.6.0.apk`](dist/Fluency-1.6.0.apk). Neu in 1.6.0:
  - **Mehrdeutige Wörter in sechs Sprachen**: eine mitgelieferte Sammlung von 132 Doppeldeutungen
    (Bank, Schloss, Steuer, bill, date, avocat, vol, riso, gato, manga, Schweizer Wörter wie
    Estrich und zügeln …), jeder Eintrag mit Übersetzungen in alle Zielsprachen.
    - Steht ein Hinweiswort im Satz, gibt die App dem Modell die Bedeutung vor und prüft sie.
    - Sonst entscheidet das Modell, die Übersetzung bleibt unverändert, und der Chip zeigt, welche
      Bedeutung es genommen hat. Ein Tipp korrigiert sie für das ganze Gespräch.

    Eigene Wörter kannst du ergänzen, alles lässt sich ändern.
  - Seit 1.3.0 rechnet Schweizerdeutsch mit den schnellen ARM-Kernels (Q8_0 statt Q5_0, mehr
    Threads), seit 1.2.0 läuft die Übersetzung auch auf der Hexagon-NPU.
- **Projekt für Android Studio**: [`dist/Fluency-AndroidStudio-1.6.0.zip`](dist/Fluency-AndroidStudio-1.6.0.zip)
  (entpacken → in Android Studio öffnen → Run). Alternativ funktioniert auch „Code → Download ZIP“
  dieses Repos; dann den Ordner `fluency` öffnen.
- Beschreibung, Modelle, Build und Tests: [`fluency/README.md`](fluency/README.md)

llama.cpp (CPU + Adreno-GPU + Hexagon-NPU) + sherpa-onnx + whisper.cpp · Hy-MT2-1.8B / MiLMMT-46 / Parakeet-TDT-0.6B-v3 / Whisper.
