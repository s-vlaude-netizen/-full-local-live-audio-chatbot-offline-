# Lokaler Live-Chat

Eine Android-App für den Freihand-Sprachmodus, den man von den großen Chat-Apps
kennt — nur vollständig offline. Flugmodus an, sprechen, zuhören, weitersprechen.
Kein Byte verlässt das Gerät.

Die Schleife:

```
zuhören  ─►  verstehen  ─►  denken  ─►  vorlesen  ─┐
   ▲                                               │
   └───────────────────────────────────────────────┘
```

Nach jeder Antwort geht das Mikrofon von selbst wieder auf. „Stopp“ beendet das
Gespräch, ohne dass man den Bildschirm anfassen muss.

## Download

**[Aktuelles APK aus dem letzten Build herunterladen][release-apk]**

[![Android](https://github.com/s-vlaude-netizen/-full-local-live-audio-chatbot-offline-/actions/workflows/android.yml/badge.svg)](https://github.com/s-vlaude-netizen/-full-local-live-audio-chatbot-offline-/actions/workflows/android.yml)

Das Release [`dev-latest`][release] wird bei jedem grünen Build ersetzt und
enthält immer den aktuellen Stand. Es ist ein Debug-Build, für die Installation
muss „Installation aus unbekannter Quelle“ erlaubt sein. Gebaut wird nur für
**arm64-v8a** — das trifft jedes Telefon der letzten Jahre, aber keinen
x86-Emulator.

Ohne Modelldatei antwortet ein Platzhalter. Damit lässt sich die komplette
Sprachschleife — Mikrofon, Erkennung, Vorlesen — direkt nach der Installation
prüfen, bevor man ein halbes Gigabyte lädt.

## Wie das offline funktioniert

Drei Bausteine, alle auf dem Gerät:

| Schritt | Umsetzung | Woher |
|---|---|---|
| Sprache → Text | `SpeechRecognizer`, ab Android 13 der geräteinterne Erkenner, sonst mit `EXTRA_PREFER_OFFLINE` | Android-Bordmittel + Offline-Sprachpaket |
| Text → Text | [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google AI Edge) | `.litertlm`-Datei, die man selbst ablegt |
| Text → Sprache | `TextToSpeech` mit einer Stimme, die keine Netzverbindung braucht | Android-Bordmittel + Offline-Stimme |

Sprache-zu-Text und Text-zu-Text sind bewusst getrennte Modelle. Ein 1B-Modell
kann kein Audio, und Androids Offline-Erkennung ist schneller und sparsamer als
alles, was man daneben stellen könnte.

LiteRT-LM führt Chat-Vorlage, Systemanweisung und Gesprächsverlauf selbst mit.
Die App schickt pro Zug nur die neue Äußerung und bekommt die Antwort
stückweise zurück; ein eigener Prompt-Zusammenbau würde die Vorlage des Modells
doppelt anwenden.

## Offline-Pakete des Systems

Beide sind einmalig einzurichten, ohne sie geht es nicht:

- **Spracherkennung:** Einstellungen → System → Sprachen & Eingabe →
  Spracheingabe → Offline-Spracherkennung → Deutsch herunterladen.
- **Sprachausgabe:** Einstellungen → Bedienungshilfen → Text-in-Sprache-Ausgabe
  → Sprachdaten installieren → Deutsch. Wichtig ist eine Stimme, die ohne Netz
  funktioniert. Die App sucht sich automatisch eine solche und meldet es, wenn
  nur eine Online-Stimme vorhanden ist.

## Modell ablegen

Erwartet wird eine `.litertlm`-Datei. Empfohlen: **Gemma 3 1B IT (int4)**, rund
550 MB, zu finden bei Google AI Edge / LiteRT auf Hugging Face oder Kaggle.

Zwei Wege aufs Gerät:

**Per App:** Einstellungen → *Modelldatei wählen* → Datei aussuchen. Die App
kopiert sie in ihren Ordner.

**Per Kabel** (schneller bei großen Dateien):

```bash
adb push gemma3-1b-it-int4.litertlm \
  /sdcard/Android/data/de.localvoice.livechat/files/models/
```

Danach in den Einstellungen *Ordner neu einlesen* und das Modell auswählen. Der
exakte Pfad steht dort ebenfalls.

## Bedienung

- **Live-Modus starten** — großer Knopf unten. Danach kann der Bildschirm aus.
- **„Stopp“ sagen** — beendet das Gespräch per Zuruf.
- **Antwort abbrechen** — der Stop-Knopf rechts; die App hört sofort wieder zu.
- **Tippen statt sprechen** — Tastatursymbol links, funktioniert auch bei
  ausgeschaltetem Live-Modus.
- Eine Benachrichtigung zeigt den Zustand und hält die Sitzung am Leben,
  während in einer anderen App Notizen entstehen.

## Aufbau

```
app/src/main/java/de/localvoice/livechat/
├─ domain/       reine Logik, ohne Android: Satzzerlegung, Aufräumen der
│                Modellausgabe, Sprachbefehle (unit-getestet)
├─ llm/          LlmEngine + LiteRT-LM-Umsetzung + Platzhalter ohne Modell
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
und Abkürzungen gelten dabei nicht als Satzende.

## Selbst bauen

```bash
./gradlew assembleDebug        # APK unter app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # Logik-Tests
```

Voraussetzung: JDK 17 und ein Android SDK mit API 36.

## Was noch nicht geht

- **Ins Wort fallen.** Während die App vorliest, hört sie nicht zu. Echtes
  Barge-in bräuchte gleichzeitiges Aufnehmen und Abspielen samt
  Echo-Unterdrückung. Der Abbruch-Knopf ist der Behelf.
- **Aufwecken per Schlüsselwort.** Der Live-Modus wird per Knopf gestartet.
- **Sehr lange Gespräche.** Ist das Kontextfenster voll, beginnt das Gespräch
  im Modell von vorn; der angezeigte Verlauf bleibt erhalten.
- **Signierte Release-Builds.** Der CI baut bisher nur Debug.

## Datenschutz

Die App fordert keine Netzwerkberechtigung an. Modelle liegen im app-eigenen
Ordner, der Verlauf nur im Arbeitsspeicher und ist nach dem Beenden weg.

[release]: https://github.com/s-vlaude-netizen/-full-local-live-audio-chatbot-offline-/releases/tag/dev-latest
[release-apk]: https://github.com/s-vlaude-netizen/-full-local-live-audio-chatbot-offline-/releases/latest/download/lokaler-live-chat-debug.apk
