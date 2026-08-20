# Lokaler Live-Chat

Eine Android-App für den Freihand-Sprachmodus, den man von den großen Chat-Apps
kennt — nur lokal. Flugmodus an, sprechen, zuhören, weitersprechen. Das Gespräch
verlässt das Gerät nie; Netz braucht allein der einmalige Modell-Download, und
auch der lässt sich umgehen.

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
enthält immer den aktuellen Stand. Für die Installation muss „Installation aus
unbekannter Quelle“ erlaubt sein. Gebaut wird nur für **arm64-v8a** — das
trifft jedes Telefon der letzten Jahre, aber keinen x86-Emulator.

**Updates** installieren sich über den vorigen Stand, Einstellungen und
geladenes Modell bleiben erhalten. Möglich wird das durch einen festen
Signaturschlüssel, der im Repo liegt — was das bedeutet, steht weiter unten.

> Einmalig nötig: Wer eine Version vor `0.2.x` installiert hat, muss die App
> vorher **deinstallieren**. Die alten Builds trugen bei jedem CI-Lauf eine
> andere Signatur, und Android lässt einen Signaturwechsel nicht zu. Das
> geladene Modell geht dabei verloren und muss neu geladen werden — danach
> passiert das nicht wieder.

Ohne Modelldatei antwortet ein Platzhalter. Damit lässt sich die komplette
Sprachschleife — Mikrofon, Erkennung, Vorlesen — direkt nach der Installation
prüfen, bevor man ein halbes Gigabyte lädt.

## Wie das offline funktioniert

Drei Bausteine, alle auf dem Gerät:

| Schritt | Umsetzung | Woher |
|---|---|---|
| Sprache → Text | `SpeechRecognizer`, ab Android 13 der geräteinterne Erkenner, sonst mit `EXTRA_PREFER_OFFLINE` | Android-Bordmittel + Offline-Sprachpaket |
| Text → Text | [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google AI Edge) | `.litertlm`-Datei, einmalig geladen oder von Hand abgelegt |
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

Erwartet wird eine `.litertlm`-Datei. Empfohlen: **Gemma 3 1B IT**, rund 0,6 GB.

**Beim ersten Start fragt die App**, ob sie ein Modell laden soll, und bietet
eine kleine Auswahl an — orientiert an der Liste der Google-AI-Edge-Gallery.
Der Download ist der einzige Moment, in dem die App ins Netz geht; er lässt sich
abbrechen und setzt beim nächsten Versuch an derselben Stelle fort. Dieselbe
Auswahl steht dauerhaft in den Einstellungen.

Der genaue Dateiname ist nirgends fest verdrahtet: die App fragt das
Dateiverzeichnis der Ablage ab und wählt selbst — bevorzugt die allgemeine
Variante mit kleiner Quantisierung, keine, die auf einen bestimmten Chip
zugeschnitten ist.

### Gemma braucht eine Lizenzzustimmung

Die Gemma-Ablagen auf Hugging Face sind *gated*: der Download klappt erst, wenn
man eingeloggt der Lizenz zugestimmt hat. Dafür einmalig:

1. Modellseite öffnen und der Lizenz zustimmen (die App verlinkt sie direkt).
2. Auf Hugging Face unter *Settings → Access Tokens* ein Token mit Leserecht
   anlegen.
3. Das Token in den Einstellungen eintragen.

Qwen 2.5 steht unter Apache-Lizenz und lädt ohne Token.

### Von Hand ablegen

**Per App:** Einstellungen → *Modelldatei wählen* → Datei aussuchen. Die App
kopiert sie in ihren Ordner.

**Per Kabel** (schneller bei großen Dateien):

```bash
adb push modell.litertlm \
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
./gradlew assembleRelease      # APK unter app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # Logik-Tests
```

Signiert wird mit dem Schlüssel aus `keystore/`, lokal wie im CI. Die
Versionsnummer kommt aus `VERSION_CODE`; ohne die Variable ist sie 1, ein
lokaler Build lässt sich also nicht über einen neueren aus dem CI legen.

Voraussetzung: JDK 17 und ein Android SDK mit API 36.

## Was noch nicht geht

- **Ins Wort fallen.** Während die App vorliest, hört sie nicht zu. Echtes
  Barge-in bräuchte gleichzeitiges Aufnehmen und Abspielen samt
  Echo-Unterdrückung. Der Abbruch-Knopf ist der Behelf.
- **Aufwecken per Schlüsselwort.** Der Live-Modus wird per Knopf gestartet.
- **Sehr lange Gespräche.** Ist das Kontextfenster voll, beginnt das Gespräch
  im Modell von vorn; der angezeigte Verlauf bleibt erhalten.
- **Anmeldung bei Hugging Face.** Statt eines Anmeldevorgangs im Browser wird
  ein Zugangstoken von Hand eingetragen.

## Der Signaturschlüssel liegt im Repo

Unter `keystore/` liegen Schlüssel und Passwort im Klartext. Das ist Absicht
und eine bewusste Abwägung.

**Warum:** Android akzeptiert ein Update nur, wenn das neue APK dieselbe
Signatur trägt wie das installierte. Ein CI ohne festen Schlüssel signiert
jeden Lauf mit einem neu erzeugten — jedes Update scheitert dann, man muss
deinstallieren und verliert Einstellungen und ein halbes Gigabyte Modell.

**Was das kostet:** Der Schlüssel beweist nichts über die Herkunft eines APK.
Jeder kann eines bauen, das Geräte als dieselbe App annehmen und über eine
bestehende Installation legen — mitsamt deren Daten. Ein Klick auf ein APK aus
fremder Quelle ist damit gefährlicher als bei einer App aus dem Play Store.

**Praktisch heißt das:** APKs nur aus dem [Release dieses Repos][release]
installieren und vorher auf die Quelle schauen. Wer das nicht will, baut selbst
(siehe oben) und ersetzt den Schlüssel unter `keystore/` durch einen eigenen.

## Datenschutz

Das Gespräch selbst läuft vollständig auf dem Gerät: Erkennung, Sprachmodell und
Sprachausgabe brauchen kein Netz.

Netzzugriff gibt es an genau einer Stelle — beim Herunterladen einer
Modelldatei von Hugging Face. Dafür hat die App die Berechtigung `INTERNET`.
Wer das nicht will, legt die Datei von Hand ab (siehe oben); dann geht nie
etwas hinaus. Ein hinterlegtes Zugangstoken wird nur an `huggingface.co`
geschickt und nicht an das CDN weitergereicht, auf das der Download umgeleitet
wird.

Modelle liegen im app-eigenen Ordner, der Verlauf nur im Arbeitsspeicher und ist
nach dem Beenden weg.

[release]: https://github.com/s-vlaude-netizen/-full-local-live-audio-chatbot-offline-/releases/tag/dev-latest
[release-apk]: https://github.com/s-vlaude-netizen/-full-local-live-audio-chatbot-offline-/releases/latest/download/lokaler-live-chat.apk
