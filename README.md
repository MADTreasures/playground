# playground

## Fluency – Live-Übersetzer für Android, vollständig offline

- **App installieren**: [`dist/Fluency-1.5.0.apk`](dist/Fluency-1.5.0.apk). Neu in 1.5.0:
  - **Mehrdeutige Wörter**: Du trägst selbst Wörter mit mehreren Bedeutungen ein, z. B.
    „Nachrichten“ → *messages* oder *news*, mit Hinweiswörtern. Die App wählt die Bedeutung nach
    festen Regeln, schreibt sie der Übersetzung vor, prüft das Ergebnis und zeigt das Wort unter der
    Übersetzung an. Antippen wechselt die Bedeutung. Ersetzt das Kontext-Dokument aus 1.4.0.
  - Seit 1.3.0 rechnet Schweizerdeutsch mit den schnellen ARM-Kernels (Q8_0 statt Q5_0, mehr
    Threads), seit 1.2.0 läuft die Übersetzung auch auf der Hexagon-NPU.
- **Projekt für Android Studio**: [`dist/Fluency-AndroidStudio-1.5.0.zip`](dist/Fluency-AndroidStudio-1.5.0.zip)
  (entpacken → in Android Studio öffnen → Run). Alternativ funktioniert auch „Code → Download ZIP“
  dieses Repos; dann den Ordner `fluency` öffnen.
- Beschreibung, Modelle, Build und Tests: [`fluency/README.md`](fluency/README.md)

llama.cpp (CPU + Adreno-GPU + Hexagon-NPU) + sherpa-onnx + whisper.cpp · Hy-MT2-1.8B / MiLMMT-46 / Parakeet-TDT-0.6B-v3 / Whisper.
