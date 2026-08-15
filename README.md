# Lokaler Live-Chat

Eine Android-App für den Freihand-Sprachmodus, den man von den großen Chat-Apps
kennt — nur vollständig offline. Flugmodus an, sprechen, zuhören, weitersprechen.
Kein Byte verlässt das Gerät.

Die Schleife ist genau die, die man erwartet:

```
zuhören  ─►  verstehen  ─►  denken  ─►  vorlesen  ─┐
   ▲                                               │
   └───────────────────────────────────────────────┘
```

Nach jeder Antwort geht das Mikrofon von selbst wieder auf. „Stopp“ beendet das
Gespräch, ohne dass man den Bildschirm anfassen muss.

## Wie das offline funktioniert

Drei Bausteine, alle auf dem Gerät:

| Schritt | Umsetzung | Woher |
|---|---|---|
| Sprache → Text | `SpeechRecognizer`, ab Android 13 der geräteinterne Erkenner, sonst mit `EXTRA_PREFER_OFFLINE` | Android-Bordmittel + Offline-Sprachpaket |
| Text → Text | MediaPipe LLM Inference (Google AI Edge) | `.task`-Datei, die du selbst ablegst |
| Text → Sprache | `TextToSpeech` mit einer Stimme, die keine Netzverbindung braucht | Android-Bordmittel + Offline-Stimme |

Zu deiner Frage aus der Beschreibung: **Gemini Nano** wäre der naheliegende
Kandidat, ist aber über ML Kit GenAI / AICore an wenige Geräte gebunden (Pixel 9
aufwärts, ausgewählte Galaxy-Modelle) und lässt sich nicht mitliefern. Deshalb
läuft hier **MediaPipe LLM Inference**: dieselbe Idee, aber auf jedem halbwegs
aktuellen Android-Telefon, und du entscheidest selbst, welches Modell drin liegt.
Gemma 3 1B ist der empfohlene Start.

Sprache-zu-Text macht bewusst **nicht** dasselbe Modell. Ein 1B-Sprachmodell kann
kein Audio; Android bringt eine gute Offline-Erkennung ohnehin mit, und die ist
schneller und sparsamer als alles, was man daneben stellen könnte.

## Erste Version ausprobieren

1. **APK holen.** Unter *Actions* → letzter grüner Lauf des Workflows „Android“ →
   Artefakt `lokaler-live-chat-debug-apk` herunterladen und entpacken.
2. **Installieren.** Es ist ein Debug-Build, also „Installation aus unbekannter
   Quelle“ erlauben.
3. **Starten.** Ohne Modelldatei antwortet ein Platzhalter — damit lässt sich die
   komplette Sprachschleife (Mikrofon, Erkennung, Vorlesen) schon prüfen.

Danach das eigentliche Modell nachlegen (siehe unten).

## Offline-Pakete des Systems

Ohne die geht es nicht, und beide sind einmalig einzurichten:

- **Spracherkennung:** Einstellungen → System → Sprachen & Eingabe →
  Spracheingabe → Offline-Spracherkennung → Deutsch herunterladen.
- **Sprachausgabe:** Einstellungen → Bedienungshilfen → Text-in-Sprache-Ausgabe
  → Sprachdaten installieren → Deutsch. Wichtig: eine Stimme wählen, die ohne
  Netz funktioniert. Die App sucht sich automatisch eine solche und sagt es,
  wenn nur eine Online-Stimme da ist.

## Modell ablegen

Empfohlen: **Gemma 3 1B IT (int4)** als `.task`-Bündel, rund 550 MB. Zu finden
bei LiteRT / Google AI Edge auf Hugging Face oder Kaggle (Lizenz von Google
bestätigen, dann herunterladen).

Zwei Wege, es aufs Gerät zu bekommen:

**Per App:** Einstellungen → *Modelldatei wählen* → Datei aussuchen. Die App
kopiert sie in ihren Ordner.

**Per Kabel** (schneller bei großen Dateien):

```bash
adb push gemma3-1b-it-int4.task \
  /sdcard/Android/data/de.localvoice.livechat/files/models/
```

Danach in den Einstellungen *Ordner neu einlesen* und das Modell auswählen. Der
exakte Pfad steht dort auch noch einmal.

Andere Modelle gehen ebenfalls — bei Qwen oder Phi in den Einstellungen die
Chat-Vorlage auf `CHATML` stellen, sonst redet das Modell an sich vorbei.

## Bedienung

- **Live-Modus starten** — großer Knopf unten. Danach kann der Bildschirm aus.
- **„Stopp“ sagen** — beendet das Gespräch per Zuruf.
- **Antwort abbrechen** — der Stop-Knopf rechts; die App hört sofort wieder zu.
- **Tippen statt sprechen** — Tastatursymbol links, funktioniert auch bei
  ausgeschaltetem Live-Modus.
- Eine Benachrichtigung zeigt den Zustand und hält die Sitzung am Leben, während
  du in einer anderen App Notizen machst.

## Aufbau

```
app/src/main/java/de/localvoice/livechat/
├─ domain/       reine Logik, ohne Android: Satzzerlegung, Prompt-Vorlagen,
│                Verlaufskürzung, Aufräumen der Modellausgabe (unit-getestet)
├─ llm/          LlmEngine + MediaPipe-Umsetzung + Platzhalter ohne Modell
├─ speech/       SpeechRecognizer- und TextToSpeech-Hüllen
├─ session/      LiveSessionController: die Zustandsmaschine der Schleife
├─ service/      Vordergrunddienst, damit es bei ausgeschaltetem Bildschirm läuft
├─ data/         Einstellungen und Modellordner
└─ ui/           Compose-Oberfläche
```

Der Gesprächszustand hängt an der `Application`, nicht an der Activity — Drehen
oder ein Wechsel in eine andere App reißt das Gespräch nicht ab.

Damit die Antwort nicht erst komplett fertig sein muss, bevor etwas zu hören
ist, schneidet der `SentenceChunker` den Token-Strom an Satzgrenzen und schiebt
jeden fertigen Satz sofort in die Sprachausgabe. Ordnungszahlen („am 3. Mai“)
und Abkürzungen werden dabei nicht für Satzenden gehalten.

## Selbst bauen

```bash
./gradlew assembleDebug        # APK unter app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # Logik-Tests
```

Voraussetzung: JDK 17 und ein Android SDK mit API 35.

## Was noch nicht geht

- **Ins Wort fallen.** Während die App vorliest, hört sie nicht zu. Echtes
  Barge-in bräuchte gleichzeitiges Aufnehmen und Abspielen samt
  Echo-Unterdrückung. Der Abbruch-Knopf ist der Behelf.
- **Aufwecken per Schlüsselwort.** Der Live-Modus wird per Knopf gestartet.
- **Sehr lange Gespräche.** Der Verlauf wird auf die letzten Turns gekürzt; ist
  das Kontextfenster voll, beginnt die Sitzung mit gekürztem Verlauf neu.
- **Signierte Release-Builds.** Der CI baut bisher nur Debug.

## Datenschutz

Die App fordert keine Netzwerkberechtigung an. Modelle liegen im App-eigenen
Ordner, der Verlauf nur im Arbeitsspeicher und ist nach dem Beenden weg.
