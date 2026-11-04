# Xonos «Per te»: il piano

Il piano parte da «Per te, una release alla volta»: release piccole e la prima già visibile. Da «Aurora» prende il disegno della Home, la ripresa completa e il motore nell'app. Da «motore» prende la protezione del budget di Spotify, ListenBrainz `lb-radio/artist` e un servizio leggero sul server. Ogni difetto trovato dai giudici è corretto: l'elenco è nel capitolo 9.

Come leggere i segni:
- **[verificato]**: letto nel codice di Music Assistant 2.10.8 (`scratchpad/patch-check-67`) o di ListenBrainz (`progetto/lb`).
- **[misurato]**: misurato in questa sessione.
- **(da verificare)**: non provato.

---

## 1. La visione

Apri Xonos e la Home ti saluta, già pronta anche mentre il server del telefono parte. In cima c'è il punto dove avevi lasciato la musica, sul telefono, in auto o con myCar: un tocco e riparte da lì. Sotto trovi i tuoi mix del giorno, fatti da un anno dei tuoi ascolti, le novità dei tuoi artisti e qualche artista nuovo, scelto da ListenBrainz e, se vuoi, da Last.fm. Tutto è scuro e prende i colori vivi dalle copertine. Ogni release porta qualcosa che si vede subito. Spotify si chiama quasi solo quando tocchi qualcosa: in media meno di 10 richieste al giorno in disparte.

---

## 2. La Home dall'alto in basso

**Principio**
- La Home si disegna da file locali in pochi millisecondi, anche col server fermo (ServerKeeper lo spegne a riposo). I file locali sono:
  - `noBackupFilesDir/last-listen.json`, l'ultimo ascolto;
  - `noBackupFilesDir/per-te/<server_id>.json`, l'uscita del motore;
  - la cache `home` v2 di `ScreenCache`.
- Poi Music Assistant aggiorna solo le card che cambiano, con una dissolvenza di 200 ms per card, senza spostare il resto.
- `PlatformReportReady` vuol dire «cache mostrata».
- **Le righe di Music Assistant si riconoscono da `provider` + `item_id`, non da `translation_key`.** Su `/ws` MA lo toglie sempre [verificato: `media_item.py`, `d.pop("translation_key")` quando il traduttore è attivo].
- `music/recommendations` dà tutte e 16 le righe della libreria, anche quelle con `enabled_by_default` falso [verificato: `providers/recommendations/__init__.py`]. Xonos sceglie da sé quali mostrare e le chiama coi suoi titoli in 9 lingue.

**Sezioni, in ordine fisso** (niente scambi secondo l'ora: un giudice li ha trovati incoerenti). Tra parentesi la tappa che porta la sezione.

| # | Sezione | Cosa c'è | Prima del motore (tappe 1–3) | Col motore (tappa 4+) |
|---|---|---|---|---|
| 0 | **Testata** | Saluto per ora: «Buongiorno» 05–12, «Buon pomeriggio» 12–18, «Buonasera» 18–23, «Buonanotte» 23–05. Manrope 800, 32sp. Sotto una riga viva, per esempio «mercoledì 4 novembre · 2 uscite dei tuoi artisti». A destra Suona su (solo con `canPlayElsewhere`) e Impostazioni. La scritta «Xonos» sparisce. Dietro, un alone alto 320 dp coi colori della copertina dell'eroe (fermo, cambia con una dissolvenza) | (1) | uguale |
| 1 | **Banner di connessione** | Pillola alta 40 dp, stessa logica di oggi: «Avvio di Music Assistant…», aggiornamento, errore con «Riprova» | (1) | uguale |
| 2 | **Riprendi** (eroe, testTag `hero_resume`) | Card larga quanto lo schermo, alta 184 dp, angoli 28 dp, fondo che va dal `contenitore` al `profondo` della copertina. Dentro: copertina 120 dp; occhiello «RIPRENDI · 2 ORE FA»; titolo su 2 righe; «Artista · dalla playlist Viaggio in auto» (o «dall'auto con myCar»); barra di 4 dp con «1:42 di 3:58 · brano 4 di 18» (con un podcast: «Mancano 23 min»); in basso «poi:» coi 3 brani dopo, in piccolo. Tasti: **Riprendi** pieno (48 dp, colore `vivo`), **Apri** (il contesto), menu ⋮ con Ricomincia e Nascondi. Si vede se il telefono non suona e l'ultimo ascolto ha meno di 14 giorni (podcast e audiolibri: 30). Mentre suona sparisce (c'è il mini-lettore). Trascinarla a sinistra la nasconde fino al prossimo ascolto | (1), dalla copia locale anche col server fermo | uguale; da myCar dalla tappa 3 |
| 3 | **Scelte rapide** (`quick_pick`) | Griglia 2×3, tessere alte 56 dp con angoli 10. Copertina 56 dp (cerchio per gli artisti) e nome su 2 righe. Fondo `surfaceContainerHigh` velato al 10% dal colore `vivo` della sua copertina. Tocco apre, pressione lunga suona | Da `recently_played_items` con `user_initiated_only` e i tipi contenitore (album, playlist, artisti, generi, podcast). Se sono meno di 6, si riempie con `favorite_playlists` | Contesti del diario degli ultimi 30 giorni, pesati anche per quanto sono recenti |
| 4 | **Per te** (carosello unico, `mix_card`) | Card quadrate da 168 dp con angoli 20: copertina generata (vedi 4), titolo, «De André, Battiato e altri», tasto play da 44 dp. Ordine: Mix per l'auto (se c'è), Mix del giorno 1–5, Scopri della settimana, Riscoperte, Un anno fa | Le playlist «Infinite Mix» di MA e la riga «Artisti che ascolti» (`recent_artists`) | Mix del motore (4); Scopri dalla 5 |
| 5 | **In corso** | Solo se c'è qualcosa: card 280×88 con copertina, «Mancano 23 min» e barra. Menu: «Dall'inizio», «Segna come ascoltato» (con Annulla) | (1): riga `in_progress`; barre da `item_by_uri`, solo per le card visibili (al massimo 5) e mai per gli episodi Spotify | uguale |
| 6 | **Novità dei tuoi artisti** | `ReleaseCard` da 148 dp con angoli 12, copertina di Cover Art Archive e un'etichetta: «OGGI», «VEN 7 NOV» oppure «ALBUM · 31 OTT». Le uscite future sono più chiare. «Mostra tutto» apre una pagina divisa per settimane. Nascosta se vuota | — | (5) |
| 7 | **I tuoi generi** | Griglia orizzontale a 2 righe. Tessere 168×96 con angoli 16: tinta piena del genere, nome in Manrope 800, la copertina dell'artista principale inclinata di 20° nell'angolo | Generi di MA, se Spotify li dà ancora (da verificare) | Generi del motore; più ricchi dalla 5 coi tag di ListenBrainz |
| 8 | **Artisti nuovi per te** | Cerchi da 96 dp con sotto «Perché ascolti Battiato». Pressione lunga: «Non fa per me» | — | (5) |
| 9 | **Riscopri** | Card brano da 132 dp con «ultima volta: giugno» | Righe `forgotten_tracks`/`forgotten_albums` | Dal diario (4) |
| 10 | **Dalla tua libreria** | Al massimo 3 righe di MA coi titoli di Xonos: «Aggiunti di recente», «Playlist preferite», «I più ascoltati» | (1) | uguale |
| 11 | **Altro** (chiusa, si apre al tocco) | random_*, never_played e le righe dei servizi che Xonos non conosce (per esempio Trending Podcasts), col nome dato da MA | (1) | uguale |
| 12 | **Piede** | «Come nascono questi consigli» (pagina Per te) e «Personalizza la Home» (interruttori e ordine delle sezioni 4–10) | (5) e (7) | |

**Interazioni comuni**
- Card premuta: scala 0,96 a molla.
- Pressione lunga: il menu di oggi, più «Salva come playlist» (mix), «Perché questo?» e «Non fa per me» (scoperte).
- Trascinare in giù aggiorna le righe di MA e ricalcola i punteggi in locale. La rete parte solo se l'ultimo giro ha più di 6 ore.
- Al primo caricamento del processo le sezioni entrano a cascata: 30 ms l'una, al massimo 6.

**Stati**
- **Persona nuova** (diario vuoto): le sezioni 4–9 mostrano quello che MA ha già. Sotto la soglia dei mix c'è la frase «Ascolta un po' e qui nasceranno i tuoi mix», con un Infinite Mix.
- **Server fermo**: tutto dalla cache. Ogni play passa da `MaPlayer.wake` (fino a 60 s).
- **Senza rete**: le card senza copertina mostrano il colore generato.
- Le sezioni vuote non lasciano spazio.

**testTag**: restano tutti (`home`, `row`, `card`, `item_row`, `item_list`, `library_list`, `mini_player`, `player`, `search_field`, `top_result`). Nuovi: `hero_resume`, `quick_pick`, `mix_card`, `genre_tile`, `release_card`.

---

## 3. Sistema visivo

### Colori
- **Base neutra quasi nera** (le copertine non si scontrano più col blu notte):
  - `background` #0D0E12, `surface` #121318;
  - contenitori #1A1B21, #23242B, #2D2E36;
  - `onSurface` #EDEDF2, `onSurfaceVariant` #A9AAB6, `outline` #3A3B45.
- **L'ambra #FFC27A resta il colore del marchio** dove non c'è una copertina: Impostazioni, tasti generici, icona e menu dell'auto.
- **Palette dalla copertina**: `phone/ui/ArtPalette.kt`, Kotlin puro senza librerie, provato sulla JVM e usato uguale da `tools/screens`. Calcolo:
  - parte dai pixel che `rememberArtworkPixels` già campiona, portati da 32×32 a 48×48;
  - li porta in OKLCH;
  - li conta in 24 spicchi di tinta pesati per popolazione × (C+0,03)^1,5, con i grigi (C < 0,03) a parte;
  - una tinta vince solo se copre almeno il 3% dei pixel; la seconda tinta deve stare ad almeno 40° dalla prima.
- **Ruoli ricavati**, tutti nella stessa tinta; se un tono esce dalla gamma sRGB si riduce la croma:

  | Ruolo | OKLCH | Dove |
  |---|---|---|
  | `profondo` | L 0,25, C ≤ 0,09 (bianco sopra ≥ 10:1) | Fondo di eroe, testate, lettore |
  | `contenitore` | L 0,34, C ≤ 0,10 | Mini-lettore, chip |
  | `vivo` | L 0,78, C 0,10–0,17 (≥ 4,5:1 sul fondo) | Play, barre, slider, brano in corso |
  | `suVivo` | L 0,20 o nero, quello col contrasto più alto (≥ 4,5:1) | Icone sui tasti `vivo` |
  | `alone` | L 0,55, C 0,14–0,20, alfa 0,5 | Alone della testata e del lettore |
  | `secondo` | seconda tinta | Sfumature delle copertine dei mix |

  Una copertina grigia usa l'ambra a croma bassa.
- **Come si applica**: `ArtTheme(palette) { … }` copre `primary`/`onPrimary`/`primaryContainer`/`secondaryContainer` solo dentro il suo blocco. I colori cambiano in 600 ms. Le palette stanno in una LruCache da 64 voci per indirizzo e nel JSON del motore, così all'apertura i colori non saltano. Sostituisce `backdropColor`, che oggi ha luminosità fissa 0,24 e dà colori spenti.
- Le card normali non calcolano la palette. La usano solo eroe, mix, testate, mini-lettore, lettore e il velo delle scelte rapide.
- Niente Material You: il colore lo dà la musica.

### Carattere
- **Manrope** (Mikhail Sharanda, SIL OFL 1.1), presa da `@fontsource-variable/manrope` 5.3.0. Copre latino, latino esteso, greco e cirillico; il cinese ripiega sul carattere di sistema.
- **Peso [misurato in questa sessione]**: dalle 4 parti del file variabile, le istanze statiche pesano 89,9 KB (400), 89,9 KB (600) e 90,6 KB (800); il variabile 158 KB.
- **Scelta**: solo due istanze statiche, **600 e 800, circa 180 KB**. Il testo corrente e le etichette piccole restano col carattere di sistema (Roboto o Noto su GrapheneOS): più leggibile in piccolo, e il cinese ripiega da sé.
- Le istanze statiche servono anche a `tools/screens`: lì la resa dei pesi di un carattere variabile non è garantita.
- **Come si fanno**: `tools/font/make_font.py` (fontTools: `varLib.instancer`, `pyftmerge`, sottoinsiemi). I `.ttf` vanno nel repository, in `res/font/manrope_semibold.ttf` e `manrope_extrabold.ttf`: nessun passo nella build, che resta riproducibile. La ricetta di F-Droid toglie `tools/`, ma i file sono già pronti.
- La licenza va in `assets/licenses/OFL-Manrope.txt`, nella pagina Informazioni e nel README.
- `FontFamily` sta in `Platform.kt`: su Android `Font(R.font…)`, nel gemello di `tools/screens` `Font(file)` dagli stessi file.
- **Scala**:
  - headlineLarge 32/38, 800 (saluto);
  - headlineMedium 28/34, 800 (titoli di pagina);
  - headlineSmall 24/30, 800 (testate, titolo del lettore);
  - titleLarge 20/26, 800 (sezioni);
  - titleMedium 16/22, 600 (card);
  - titleSmall 14/20, 600 (tessere);
  - occhielli 11/16, 600, maiuscolo, +6% di spaziatura;
  - corpo ed etichette: carattere di sistema.
- Tempi e durate con cifre tabellari (`tnum`; se Manrope non le ha, nel carattere di sistema: da verificare).

### Forme
- `Shapes`: extraSmall 6, small 10, medium 14, large 20, extraLarge 28.
- Copertine (le legge `coverShape()`): brano 8, album 12, playlist e mix 20, artista cerchio, eroe 28, genere 16, mini-lettore 20.

### Spazi
- `phone/ui/Space.kt`: 2, 4, 8, 12, 16, gutter 20, 24, e 32 fra le sezioni della Home.
- Fine dei valori in dp scritti a mano in ogni composable.

### Movimento (`phone/ui/Motion.kt`)
- **Pagine**: `AnimatedContent` in `PageScreen`. Avanti: scorrimento di 1/8 della larghezza con dissolvenza, 260 ms, curva enfatizzata. Indietro: l'inverso, 180 ms.
- **Schede**: solo dissolvenza, 150 ms.
- **Pressioni**: molla (smorzamento 0,8, rigidità 380).
- **Scheletri** che pulsano (alfa 0,35–0,6 in 1,2 s) al posto delle rotelle.
- **Animazioni spente nel sistema** (`reducedMotion()` in `Platform.kt`): niente cascata, scheletri fermi, transizioni in dissolvenza da 100 ms.
- Niente animazioni infinite fuori schermo; l'alone non si muove.
- **Rimandati alla tappa 7, e solo se le Misure non peggiorano**:
  - `SharedTransitionLayout` (sperimentale in Compose 1.9);
  - l'indietro predittivo, che vuole `android:enableOnBackInvokedCallback="true"` nel manifest (oggi manca);
  - il pager delle copertine nel lettore.

### Gesti
- **Mini-lettore**: a destra o a sinistra = avanti o indietro (soglia 30%, la copertina segue il dito, passa da `SkipSender`); in su apre il lettore. Il trascinamento sta solo sul mini-lettore, così non ruba lo scorrimento delle righe.
- **Lettore**: trascinato in giù si chiude.
- **Eroe**: trascinato a sinistra si nasconde.

### Componenti nuovi
In `Components.kt` e in un nuovo `HomeCards.kt`: `SectionHeader` (occhiello, titolo, «Tutto ›»), `HeroResume`, `QuickTile`, `CoverCard` (con il tasto play), `MixCard`, `ReleaseCard`, `GenreTile`, `ProgressCard`, `ArtistBubble`, `PlayOverlay`, `Skeleton`, `Collage`.
- `CoverPainter` (DrawScope + TextMeasurer) è comune a schermo, auto e `tools/screens`.

### Mini-lettore e lettore
- **Mini-lettore**: pillola alta 64 dp con angoli 20, fondo `contenitore`, copertina 48 dp, barra di 2 dp `vivo` sul bordo basso.
- **Lettore**:
  - in alto «IN RIPRODUZIONE DA · Playlist Viaggio in auto»: un tocco la apre; arriva dai nuovi `EXTRA_SOURCE_URI/NAME/TYPE` di `MaPlayer`;
  - fondo dall'`alone` al `profondo`;
  - Play da 72 dp `vivo`;
  - titolo in Manrope 800 che scorre con bordi sfumati (niente più lettere tagliate);
  - riga attiva del testo in 800, le altre al 40%.

### Accessibilità
- Contrasti controllati in `PaletteCheckKt`.
- Bersagli di almeno 48 dp.
- Azioni «Avanti» e «Indietro» per TalkBack sul mini-lettore; descrizioni su eroe e tessere.

---

## 4. Playlist e Libreria

### Libreria
- La mensola Playlist si apre con una tessera tratteggiata **«+ Nuova playlist»**. Chiede il nome (`create_playlist`), poi apre la playlist vuota con «Aggiungi brani».
- Sopra la griglia, la striscia **«Per te»** coi mix pubblicati (riconosciuti dagli id in `per-te.json`). Fuori dalla striscia non compaiono: non si mescolano alle playlist vere.
- Ordine: Modificate di recente, A-Z, Ascoltate di recente.

### Copertine
- **Playlist di Spotify**: la loro immagine.
- **Playlist di MA senza immagine** (o con quella a punti generata da Playlist Metadata): `Collage` 2×2 delle prime 4 copertine d'album diverse.
  - Gli indirizzi vengono dai brani già scaricati o dal JSON del motore. Per le builtin `playlist_tracks` legge il database: 0 richieste a Spotify.
  - Solo per le prime 12 visibili; si tengono in `ScreenCache`.
- **Mix** (`MixCover`): sfumatura diagonale da `vivo` a `secondo` dell'artista più presente; occhiello «MIX»; nome in Manrope 800 26sp su 2 righe; 3 cerchi d'artista sovrapposti.
  - Lo disegna `CoverPainter`: sullo schermo con Compose; per l'auto in un PNG da 512 px in `ArtCache`, servito come `content://gen/<mixId>/<versione>`.

### Pagina della playlist (separata da quella dell'album)
- **Testata editoriale**:
  - alone dalla palette, copertina o collage da 200 dp, titolo in Manrope 800;
  - descrizione (`metadata.description`) su 3 righe, espandibile;
  - riga «di SirioNero · 42 brani · 2 h 31 min · modificata 3 giorni fa».
- **Tasti**: Play 56 dp `vivo`, Mescola, «Aggiungi brani» (solo per le modificabili), menu ⋮.
- **«Aggiungi brani»** apre Cerca in modo aggiunta: ogni risultato ha un «+» (`add_playlist_tracks`), e Cerca torna alla playlist col conteggio.
- **Rinomina**: `music/playlists/update`. Vuole `LIBRARY_MANAGE` [verificato: `media/base.py:226-228`], quindi solo gli admin la vedono, anche su un MA di casa. Solo per le playlist di MA; che il builtin scriva il nome nell'M3U è da provare in Docker.
- **Riordina** («Modifica ordine», maniglie a destra, trascinamento dopo pressione lunga, «Fatto» salva tutto in una volta):
  - **solo playlist di MA sul server del telefono**, col comando `xonos/playlists/reorder`, che riscrive l'M3U sotto `_get_playlist_lock`;
  - **niente ripiego «togli tutto e rimetti»** sugli altri MA: dalla 2.10 le due chiamate girano in disparte, e se l'aggiunta arriva prima della rimozione la playlist si svuota (un giudice l'ha mostrato nel codice). Lì le maniglie non ci sono.
- **Condividi**: `music/playlists/export_playlist` dà l'M3U come testo, che va alla condivisione di Android.
- **«Brani consigliati per questa playlist»** (tappa 7): 10 brani degli artisti della playlist e dei loro gruppi di gusto, presi da libreria e scoperte già in cache, con 0 richieste. Ognuno ha il «+»; «Altri 10» ne mostra altri.

### Salva come playlist (mix, Scopri, la coda dal menu del lettore)
- **Sempre con `music/playlists/import_playlist`**. Il builtin scrive le voci com'è, senza risolverle [verificato: `builtin/__init__.py:562-591`]. Alla lettura costruisce i brani dai metadati salvati, senza chiamare Spotify [verificato: `_resolve_playlist_item`, `:1341-1369`].
  - Sul server del telefono l'M3U lo scrive il codice di MA (`media_item_to_playlist_item`) dentro il comando `xonos/playlists/replace`.
  - Su un altro MA lo scrive l'app, con **`#EXTALBUM`** (non `#EXTALB`: MA legge `#EXTALBUM` [verificato: `helpers/playlists.py:205`]). Si prova in Docker: import, poi `playlist_tracks`.
- Non si usano `add_playlist_tracks` né `player_queues/save_as_playlist`: chiedono un `get_item_by_uri` per ogni brano fuori libreria.
- Nome proposto: «Cantautori · 4 nov».

### Mix pubblicati in Music Assistant (per myCar e l'interfaccia web)
- Sul server del telefono, acceso di base. **Al massimo 6 posti fissi** «Per te · Mix del giorno 1»… con **id stabili**: ogni giorno `xonos/playlists/replace` riscrive l'M3U dello stesso posto, senza creare né togliere playlist.
  - La cache dei brani del builtin usa la data di modifica del file, quindi si aggiorna da sola [verificato: `:1376-1381`]; l'app chiede comunque `force_refresh`.
- Così la libreria non si riempie, e myCar e l'interfaccia web vedono sempre le stesse 6 playlist.
- Con un MA di casa condiviso: spento, e «Salva come playlist» solo a richiesta.
- In Xonos stanno nella striscia «Per te», non nella griglia.

### Pagina del mix
- Testata alta 300 dp con la copertina generata in grande e «Aggiornato oggi · 40 brani · 2 h 41 min».
- Chip «Perché questo mix»: «Ascolti spesso De André e Battiato», «12 brani che non senti da 3 mesi», «4 artisti nuovi».
- Tasti: Play, Mescola, Salva come playlist, Rigenera.
- I brani scoperti hanno un puntino `vivo` e la nota «nuovo per te».

---

## 5. Riprendi

### Fonti (nessuna chiama Spotify)
1. **La coda del telefono** (`player_queues/get` con `Settings.playerId`).
   - `QueueState.from` impara a leggere `resume_pos`, `sources`, `ended` ed `elapsed_time_last_updated`.
   - Posizione: `resume_pos` se è più di 0, sennò `elapsed_time` (la regola di MA).
   - I 3 brani dopo: `player_queues/items` con offset `current_index+1` e limit 3.
2. **La copia locale** `data/LastListen.kt` → `noBackupFilesDir/last-listen.json` (meno di 4 KB, scrittura atomica tmp + rename, fuori dal backup).
   - Campi:
     - `server_id`, `queue_id`, `queue_item_id`;
     - il brano: uri, nome, artisti, album, riferimento all'immagine (non un URL firmato), durata;
     - posizione, indice, numero di voci;
     - `sources[0]` (uri, nome, tipo, immagine) e i 3 brani dopo;
     - casuale, ripeti, ora.
   - La scrive `PlaybackService` dallo stato di `MaPlayer`: a pausa, stop e cambio di brano, ogni 30 s mentre suona, in `onTaskRemoved` e `onDestroy`.
   - Nel logcat solo «Ripresa: copia salvata», tramite `Logged`.
   - **È nella prima tappa**: così l'eroe si vede subito anche col server fermo, invece di comparire dopo `playerReady` (fino a 60 s).
3. **Il diario sul server** (dalla tappa 3): l'ultima riga di un'altra coda (myCar) e i contesti per «Torna a».

### Decisione (`phone/model/ResumePlan.kt`, Kotlin puro, `ResumeCheckKt`)
- (a) Coda di MA con lo stesso `queue_item_id` e scarto di posizione di al massimo 10 s → `player_queues/play`.
- (b) Stesso brano, ma la copia è più recente e più avanti di oltre 10 s → `play_index {index, seek_position}`.
- (c) Coda assente o diversa → `play_media(sources[0].uri, start_item = brano)`, poi `seek` se la posizione supera 15 s. Senza contesto: il solo brano, poi `seek`.
- (d) Ultimo ascolto su myCar (tappa 3) → `player_queues/transfer` verso il telefono se la sua coda esiste; sennò come (c), coi secondi del diario.
- Coda finita (`ended`) → «Ascolta di nuovo». «Ricomincia» = `play_index` con seek 0.
- Una copia di un altro `server_id` si scarta.
- Con Soloist un seek dopo l'avvio può costare (da misurare sul telefono).

### Correzioni al server del telefono (pacchetto 2.10.8-13, `_resume_saves` in `prepare.py`)
- **P1**: in `on_player_remove(permanent=False)`, invece di annullare il salvataggio, si calcola la posizione corretta e si salva subito. Copre la pausa seguita entro 5 s da scollegamento, chiusura o arresto (in Docker le code risultavano salvate a 4,8–10,7 s).
- **P2**: mentre suona, un salvataggio ogni 30 s. Se Android uccide il server si perdono al massimo 30 s, anche per la coda di myCar.
- **P3**: per podcast e audiolibri la posizione si scrive nel playlog ogni 60 s anche mentre suona, così «In corso» regge un'uccisione.
- Tutte e tre sono candidate per Music Assistant a monte.

### Segnalibri per contesto (tappa 7)
- `noBackupFilesDir/per-te/bookmarks.json`: uri del contesto → {brano, indice, posizione, ora}; al massimo 200 voci (LRU).
- La pagina di album e playlist mostra «Eri a «Bocca di rosa» · 7 di 12 · Riprendi» per 60 giorni.

### Auto e sistema (tappa 3)
- **Nodo `Menu.RESUME`** primo nella radice, suonabile:
  - titolo dalla copia locale, sottotitolo «Riprendi · Viaggio in auto · 1:42», copertina `content://` di `ArtCache`;
  - c'è anche col server fermo, e il tocco passa da `MaPlayer.wake`.
- **`onGetLibraryRoot` con `isRecent`**: una radice col solo nodo di ripresa, servita senza server (la scheda di ripresa di System UI di AOSP, quindi anche GrapheneOS: da provare).
- **`onPlaybackResumption`**: restituisce subito quel MediaItem coi metadati della copia locale, così la notifica in primo piano compare entro i tempi di Android mentre il server parte. Stato BUFFERING, poi `wake` fino a 60 s, più i circa 7 s di Soloist. Corregge il rischio di `ForegroundServiceDidNotStartInTimeException`.
- **`androidx.media3.session.MediaButtonReceiver`** nel manifest: il play Bluetooth dell'autoradio fa ripartire Xonos.
  - **Guardia**: la musica parte da sola solo se c'è un'uscita esterna collegata o se il comando viene dalla scheda di sistema; altrimenti prepara senza suonare. Valgono anche `outputGoneAt` e `Drop.server`.
- **Ricerca a voce**: «riprendi» e «continua» → RESUME.

### Limiti detti chiari
- La posizione dentro un album fuori dalla coda la sa solo Xonos (copia e segnalibri), non MA.
- Su un MA di casa niente P1–P3: fa tutto la copia locale.

---

## 6. Il motore dei consigli

### 6.1 Il registro degli ascolti (decisione dell'utente: 1 anno, telefono, auto e myCar)

**Due fonti, con un taglio nel tempo semplice** (non l'unione di tre fonti, che un giudice ha trovato complicata):
- **Diario dell'app** (`data/taste/Journal.kt`, dalla **tappa 1**, va con qualsiasi MA):
  - file `noBackupFilesDir/per-te/journal/<server_id>/AAAA-MM.jsonl`, scritto da `PlaybackService` ai cambi di brano di `MaPlayer`;
  - campi: ora, uri, uri di libreria, nome, artisti [uri, nome, mbid], album, durata, secondi, finito, saltato (avanti prima di 30 s), contesto, luogo;
  - circa 250 byte a riga, 5 MB l'anno; i mesi di oltre 365 giorni si cancellano;
  - **luogo** = «auto» se l'ora cade in un intervallo CAR di ServerKeeper (`car-sessions.json`, solo inizio e fine), sennò «telefono».
- **Diario del server** (`xonos_plays.py`, `_plays` in `prepare.py`, dalla **tappa 3**):
  - SQLite a sé, in **`noBackupFilesDir/per-te/plays.db`**: il percorso arriva da `PhoneServer` con `XONOS_PLAYS_DB`. Quindi niente backup di Android e niente `library.db` (che il «reset database» e le migrazioni di MA possono toccare);
  - scritto in `_handle_playback_progress_report` **prima** del filtro dei 5 s [verificato: `playback_tracker.py:642`], così si vedono anche i salti veloci;
  - copre telefono, Gearslip e myCar, con `userid`; `xonos/plays` dà solo le righe dell'utente collegato;
  - al primo avvio importa il playlog di MA (90 giorni, una riga per voce);
  - potatura a 365 giorni, all'avvio e una volta al giorno;
  - comandi `xonos/plays {since, limit ≤ 5000}` e `xonos/plays/forget`.
- **Regola**: dal momento della prima riga del diario del server, il motore usa quello; prima, il diario dell'app.
  - Il luogo «auto» si ricava sempre da `car-sessions.json`.
  - Le code di altre app sono «altra app (myCar)». Da portare in myCar: `device_info.model = "myCar"` nel saluto Sendspin, per riconoscerla con certezza.
- **Non si usa l'evento `media_item_played`**: arriva a ogni client senza filtro per utente e non vede i salti sotto i 5 s.
- **Privacy**:
  - mai nel logcat: `Logged` scrive solo conteggi, per esempio «Per te: 5 mix, 212 brani, 3 ricerche su Spotify»; lo controllano `LoggedCheckKt` e la guardia `privacy()` della CI;
  - fuori dal backup;
  - Impostazioni › Per te: interruttore «Cronologia degli ascolti» (acceso, come ha deciso l'utente), conteggi e «Cancella la cronologia»;
  - «Togli dal telefono» toglie anche `plays.db`.

### 6.2 Dove gira

| Pezzo | Dove | Perché |
|---|---|---|
| Punteggi, gruppi, mix, scelta delle scoperte | **App**, Kotlin puro in `data/taste/`, su `Dispatchers.Default` | Va con qualsiasi MA (anche umbrel). Si prova sul banco JVM. Non pesa sui processi figli, che Android uccide oltre il 25% di un core per 5 minuti. myCar lo può copiare |
| Diario completo, salvataggi della coda, protezione di Spotify, comandi delle playlist, copia compatta della libreria | **Server del telefono** (`prepare.py`, `server/ma`) | Solo SQL brevi |
| ListenBrainz, Cover Art Archive | **App** (OkHttp) | Servizi liberi |
| Last.fm | **Solo server del telefono** (pacchetto) | Nessun codice né host Last.fm nell'APK (F-Droid) |
| Endless Mix e autoplay «simile» | **Servizio minimo nel pacchetto** (tappa 6) | Dati locali, 0 richieste a Spotify |

- Il servizio del server **non arriva come asset dell'APK** («motore» lo proponeva, ma il meccanismo era nuovo e fragile e portava codice Last.fm nell'APK): sta in `server/ma`, dentro il pacchetto.
- Il motore **non sveglia il telefono e non avvia il server**: usa il server già acceso per altro (Home aperta, play, auto collegata). I lavori sono brevi e ripartono da un cursore; niente WorkManager né sveglie.
- **Costo**: il prototipo in Python (`progetto/motore_prova.py`) fa 5 gruppi, 5 mix da 40 brani e le riscoperte in 0,06 s per 20.000 ascolti e 0,1 s per 40.000 (Xeon). In Kotlin si misura in `TasteCheckKt`, con l'obiettivo di stare sotto i 300 ms.

### 6.3 La libreria per il motore
- **Server del telefono**: `xonos/library_digest {since}` (tappa 3) dà righe compatte via SQL. Per ogni brano: id, nome, artisti (id, nome, mbid), album, immagine, preferito, `last_played`, `play_count`, generi.
  - È incrementale per data di modifica.
  - Corregge il difetto della copia con `summary=false` a ogni sincronizzazione, che fa serializzare a Python megabyte di JSON.
- **Altri MA**: `library_items` a pagine da 500 con `summary=true`; una volta a settimana per intero e ogni giorno solo le nuove (`timestamp_added_desc`). `last_played` e `play_count` lì non ci sono: vengono dal diario. Che il riassunto porti gli artisti è da verificare.

### 6.4 Punteggi
- **Peso di un ascolto w**:
  - 1,0 se completo;
  - 0,6 se oltre metà;
  - 0,3 da 30 s a metà;
  - −0,5 se saltato (meno di 30 s, non finito, e un altro brano sulla stessa coda entro 10 s).
- **Artista**: P_a = Σ w·0,5^(età/30 g), più questi bonus:
  - +3 se seguito;
  - +0,3·min(preferiti, 10);
  - +1 per album salvato (al massimo +5);
  - dai `me/top` di Spotify, se rispondono: +3·(51−r)/50.

  L_a è lo stesso punteggio con mezza vita di 365 giorni.
- **Brano**: T_t = Σ w·0,5^(età/45 g), +1,5 se preferito. Si tengono anche la quota di salti e l'ultimo ascolto.

### 6.5 Gruppi e generi
- **Grafo** dei 150 artisti con L_a più alto. Il peso di un legame somma:
  - le sessioni in comune (pause sotto i 30 min), diviso per √(s_a·s_b);
  - i contesti e le playlist proprie in comune (Jaccard);
  - 0,7 × il coseno dei tag, solo sopra 0,3 (dalla tappa 5);
  - 0,5 × il legame «simile» di ListenBrainz o Last.fm.
- **Gruppi**: propagazione delle etichette in ordine fisso. Quelli con meno di 3 artisti si uniscono al vicino. Si tengono da 2 a 5 gruppi con almeno l'8% del peso. Si legano a quelli della settimana prima (Jaccard ≥ 0,4), così nomi e posti restano.
- **Generi**: quelli di MA sugli artisti (se Spotify li dà ancora: da verificare) e, dalla tappa 5, i tag di ListenBrainz con `genre_mbid`. La tabella `Genres.kt` di regole sulle parole li porta a circa 32 macro-generi, ognuno con la stringa `genre_<id>` in 9 lingue.
  - Un artista senza tag prende la maggioranza dei vicini nel grafo (3 giri).
  - Il mix di genere passa dagli artisti, perché Spotify mette i generi sugli artisti e i filtri di MA li cercano sui brani.

### 6.6 Le playlist del motore (tutte entro 150 voci)
- **Mix del giorno 1–5**, uno per gruppo, 40 brani. Seme hash(data, gruppo): il mix resta uguale per tutta la giornata.
  - 60% preferiti del gruppo, scelti ∝ T^0,7, senza i brani sentiti nelle ultime 48 h e senza quelli saltati oltre il 50%;
  - 25% «da scavare»: brani della libreria e delle playlist proprie degli stessi artisti, con al massimo 1 ascolto completo in un anno;
  - 15% scoperte con gli stessi tag (fino alla tappa 5 quella quota va ai preferiti).
  - Ordine: due preferiti forti in testa, mai lo stesso artista entro 3 posti, una scoperta ogni 5 brani circa.
  - Nome: il macro-genere del gruppo («Cantautori»); sennò «Mix del giorno N».
  - **Soglia**: 150 ascolti pesati (diario più playlog importato) oppure 40 brani preferiti. Una proposta chiedeva 300: i mix sarebbero arrivati troppo tardi. Il valore si tara con `TasteCheckKt`. Sotto la soglia: «Mix di <artista>» per i primi 4 artisti con almeno 8 brani in libreria, e «I tuoi preferiti».
- **Mix per l'auto**: la stessa ricetta sugli ascolti «auto» e «myCar» degli ultimi 60 giorni, se sono almeno 30. In auto è la prima voce di «Per te».
- **Riscoperte**, ogni lunedì, 30 brani.
  - Candidati: almeno 3 ascolti completi oppure preferito; ultimo ascolto oltre 60 giorni fa; salti sotto il 30%.
  - Punteggio log(1+n)·(1+0,5·preferito)·rampa(60→180 giorni). Al massimo 2 brani per artista e 1 per album.
  - Finché il diario è giovane: `last_played` del digest e `forgotten_tracks` di MA.
- **Un anno fa**: ascolti di 358–372 giorni fa, nessuno negli ultimi 60, almeno 10 brani.
- **Mix per genere** (tessere): 60% brani propri degli artisti del genere, 40% scoperte dello stesso genere. Queste si risolvono al primo play: al massimo 6 ricerche a settimana.
- **«I tuoi brani di X»** nella pagina dell'artista, al posto della «Top»: dal diario. Spotify in Development Mode non dà le top tracks.

### 6.7 Come parte un mix
- **Pubblicato sul server del telefono**: `play_media` dell'uri della sua playlist. È una chiamata sola e i brani si costruiscono dai metadati dell'M3U [verificato nel codice]. Se allo stream Spotify chiede di nuovo ogni brano (`tracks/{id}`) è da misurare: lo dirà il conteggio per tipo di `_spotify_limits` nel registro.
- **Su un altro MA**: uri `library://` per i brani in libreria (0 richieste); per i brani fuori libreria al massimo 10 uri del servizio alla partenza, gli altri aggiunti con `option=add` mentre suona.
- `play_media` con gli oggetti brano interi è solo una difesa con un TODO nel codice di MA [verificato: `queue_loader.py:797-803`]: il piano non ci conta. Si misura comunque con PlayCheck.

### 6.8 Novità dei tuoi artisti (tappa 5)
- **Una GET anonima al giorno**: `api.listenbrainz.org/1/explore/fresh-releases/?days=7&past=true&future=true`. Senza token [verificato: `explore_api.py`, `days` fino a 90]; non manda niente di personale.
- **Confronto sul telefono**:
  - `artist_mbids` contro gli mbid della libreria e del diario;
  - per gli artisti senza mbid, il nome normalizzato, confermato poi dalla ricerca.
- **Ordine**: L_a × tipo (album 1, EP 0,7, singolo 0,5; senza compilation né live) × e^(−giorni/14). Le voci si tengono 90 giorni.
- **Copertine** da Cover Art Archive (`release/<mbid>/front-250`), che passano da `ArtCache` (anche per l'auto).
- **Al tocco**: `music/search {providers:[istanza spotify], media_types:[album], search:"<artista> <titolo>", limit:5}`, poi la scelta per nome e artista. Se non lo trova: «Non ancora su Spotify».
- Mai `artist_albums`: ha 14 giorni di cache.
- Per l'auto, le prime 3 si risolvono prima, a server acceso: niente ricerche mentre si guida.

### 6.9 Scopri e artisti nuovi (tappa 5, il lunedì)
- **Semi**: i primi 15 per P_a più 5 a caso tra i primi 50 per L_a, purché abbiano un mbid.
- **Simili**: `GET /1/lb-radio/artist/<mbid>?mode=easy&max_similar_artists=10&max_recordings_per_artist=3&pop_begin=0&pop_end=100`. È nella API stabile `/1/`, senza token, solo col limite di frequenza [verificato: `api.py:902`, `get_artist_radio_recordings`], e dà artisti simili e registrazioni. Non si usa Labs `similar-artists`, che è sperimentale. Con Last.fm configurato si aggiungono i suoi dati grezzi (tappa 6).
- **Tag**: `/1/metadata/artist/?artist_mbids=…&inc=tag` [verificato: esiste, senza token], a blocchi di 50, in cache 30 giorni.
- **Candidato**: c(b) = Σ P_seme·somiglianza. Via gli artisti in libreria, quelli ascoltati nell'ultimo anno, quelli rifiutati e quelli con coseno dei tag sotto 0,2. Si tengono 12–15 artisti; nessun seme oltre il 40%.
- **Risoluzione**: 1 ricerca Spotify per artista, a server già acceso, una ogni 4 s. Dà l'immagine e 2 brani: prima i titoli di ListenBrainz, niente live o remix, album diversi.
  - Il filtro `artist:"Nome"` in Development Mode è da verificare con la riga di diagnosi; se non va, si filtra il nome sul telefono.
- **Reazioni**: «Non fa per me» esclude l'artista per 180 giorni; un salto sotto i 30 s toglie peso; un ascolto finito o un mi piace lo porta nei gruppi.
- Scopri resta spento con meno di 10 semi con mbid, con una frase onesta.

### 6.10 ListenBrainz, MusicBrainz, Cover Art Archive
- **Interruttore «Novità e scoperte»: acceso di base** (decisione dell'utente). Il testo dice cosa esce:
  > «Xonos chiede a ListenBrainz (non profit, dati liberi) le novità e gli artisti simili ai tuoi. Escono gli identificativi MusicBrainz di alcuni artisti che ascolti (quelli di partenza per i simili) e, per le copertine delle novità, gli identificativi degli album a Cover Art Archive (archive.org). Mai il tuo nome, il tuo account o l'elenco dei tuoi ascolti. Come ogni sito, vedono l'indirizzo IP.»
- **L'APK non chiama MusicBrainz**: gli mbid li aggiunge piano il servizio musicbrainz di MA. Niente ricerca degli id Spotify su MusicBrainz (scartata: più dati in uscita del previsto).
- **Ritmo**: User-Agent «Xonos/<versione> ( https://github.com/SirioNero/MusicAssistantServer-on_Android )», 1 richiesta al secondo, rispetto di `X-RateLimit-*`, cache su disco in `per-te/open/`.
- **Diagnostica**: «Artisti con MBID: 182 su 240».

### 6.11 Last.fm (tappa 6, con la chiave gratuita della persona)
- **Flusso guidato sul server** (`_lastfm` in `prepare.py`, come quello del Client ID di Spotify; Xonos lo disegna con `ServicesScreens`). Passi:
  1. spiegazione: servizio non libero e facoltativo, che riceve i nomi degli artisti usati come semi;
  2. link per creare la chiave;
  3. la chiave (32 caratteri esadecimali) e una prova con un solo `artist.getSimilar`.
  - Testi in `server/ma/strings.json` nelle 9 lingue, non nell'APK.
- **Si usano solo i dati grezzi**: `xonos/lastfm/simili {artisti}` dà nomi, mbid e somiglianza **senza risolverli su Spotify**.
- **Sul telefono `lastfm_recommendations` perde SIMILAR_ARTISTS, SIMILAR_TRACKS e ARTIST_TOPTRACKS**: ognuna risolve ogni voce con una ricerca Spotify, fino a 25 per chiamata, e `top_tracks` le raccoglie da tutti i servizi a ogni pagina d'artista (un giudice l'ha trovato nel codice).
- **Le righe di Last.fm restano spente**. Se la persona le accende: ricostruite ogni 24 h e non a ogni avvio (oggi 20 s dopo ogni avvio, e ServerKeeper avvia il server molte volte al giorno); la prima costruzione costa circa 35–50 ricerche, in cache 90 giorni (stima dal codice, non provata).
- **F-Droid**:
  - nell'APK nessun host né codice Last.fm. Un controllo in CI cerca `audioscrobbler` e `last.fm` nei dex e in `resources.arsc` e si ferma se li trova;
  - Last.fm lo chiama solo il pacchetto del server, scaricato su richiesta esplicita, dopo che la persona ha messo la sua chiave: come Spotify;
  - va detto nella descrizione e nella richiesta a fdroiddata; se i revisori lo chiedono, `NonFreeNet` come funzione facoltativa (toccherebbe comunque prima Spotify);
  - ListenBrainz, MusicBrainz e Cover Art Archive sono liberi: nessuna anti-feature.

### 6.12 Spotify: protezione e conti
- **Tappa 3, prima di qualsiasi mix**: sul telefono Spotify perde SIMILAR_TRACKS e ARTIST_TOPTRACKS. Oggi Endless Mix e autoplay mandano circa 10 richieste rifiutate per giro (stima dal codice).
- **Gusti di Spotify** (`xonos/spotify_taste`, tappa 3): `me/top/artists|tracks` × 3 periodi, **6 richieste a settimana**, nella corsia di background, cache 7 giorni; con un 403 dà un elenco vuoto e la riga di stato lo dice. Niente `recently-played` quotidiano (scartato: costo senza bisogno).
- **`Budget.kt`** conta le ricerche partite dal motore, salvate per giorno: **tetto 40 al giorno**. Al primo `xonos_rate_limited` o Retry-After si ferma fino alla fine del blocco. Sotto resta il limitatore del server (30 ogni 30 s, blocco salvato).

| Cosa | Quando | Richieste Spotify |
|---|---|---|
| Home, Riprendi, Scelte rapide, In corso, generi dalla libreria | sempre | 0 |
| Mix del giorno, Mix per l'auto, Riscoperte, Un anno fa, pubblicazione dei mix | ogni giorno | 0 |
| Novità, costruzione | ogni giorno | 0 |
| Novità, aperta | al tocco | 1 (cache 24 h di MA) |
| Novità per l'auto | a server acceso | ≤ 3 a settimana |
| Scopri | lunedì, a server acceso | ≤ 15, una ogni 4 s |
| Mix per genere con artisti nuovi | al primo play della settimana | ≤ 6 |
| Gusti (`me/top`) | una volta a settimana | 6 |
| Last.fm (dati grezzi) | — | 0 |
| Diagnostica `artist:` | una volta | 1 |
| **Di solito** | | **meno di 5 al giorno; picco del lunedì circa 25** |

- Da misurare sul telefono: se suonare una scoperta o un mix pubblicato costa un `tracks/{id}` per brano.

---

## 7. Auto

**Radice di `playback/Menu.kt`**
- Ordine: **Riprendi**, **Per te**, Playlist, Album, Artisti, Brani, Preferiti, Generi, Radio, Podcast, Audiolibri, Sfoglia.
- «Ascoltati di recente» (brani sparsi) finisce dentro Riprendi come «Torna a».

**Riprendi** (`cat|resume`, al massimo 15 voci, tappa 3)
- Il nodo RESUME (suonabile anche col server fermo).
- Le voci «In corso» come `play|uri`: MA riprende dal punto.
- Fino a 12 contesti «Torna a» come `open|uri`.

**Per te**
- **Tappa 1**: le righe di MA nello stesso ordine e coi nomi di Xonos (`MenuLabels.row(itemId)`, le stesse stringhe `row_*` del telefono).
- **Tappa 4**: in testa le cartelle suonabili dei mix:
  - `mix|<id>`: suona il mix intero; i figli `mixt|<id>|<i>` lo suonano da quel brano;
  - prima il Mix per l'auto, poi Mix del giorno 1–5, Riscoperte e generi (`gen|<id>`);
  - dalla tappa 5 anche Scopri e «Novità» (`rel|<k>`, solo quelle già risolte: niente ricerche mentre si guida).
- Le righe di MA restano in fondo.

**Senza rete né attese**
- Le cartelle si leggono da `per-te/<server_id>.json` e dalla cache, anche col server fermo. Oggi il menu aspetta fino a 45 s: ora aspetta solo il play (`MaPlayer.wake`).
- Mix da 40–50 brani, Scopri 30, «Per te» meno di 20 voci: sotto i 150 di `Menu.PART` e sotto i 256 KB di Media3. `part|` resta come rete di sicurezza.
- Il play di un mix: uri della playlist pubblicata sul server del telefono; sugli altri MA, uri di libreria più al massimo 10 del servizio (6.7).

**Aspetto**
- Negli extra di `BrowseTree`, `CONTENT_STYLE_BROWSABLE_HINT` e `CONTENT_STYLE_PLAYABLE_HINT`: griglia per Per te, mix, album e playlist; elenco per i brani. Se Gearslip li ignora non fanno danni.
- Copertine generate e collage come `content://` di `Artwork` (`gen/<mixId>/<versione>`, PNG da 512 px dal `CoverPainter`). Quelle di Cover Art Archive passano da `ArtCache`: Gearslip non carica http.
- Sottotitoli corti: «40 brani · De André, Battiato».

**Voce**: «riprendi» e «continua» → RESUME; «mix del giorno», «mix per l'auto» e «scopri» → la cartella, con le parole nelle 9 lingue in `MenuLabels`. Si cercano fra i nomi in memoria, senza ricerche su Spotify.

**Ascolti in auto**: gli intervalli CAR di ServerKeeper segnano il luogo «auto» nel diario. I salti in auto pesano come gli altri: un tasto del volante non si distingue da un tocco.

**myCar**
- I suoi ascolti entrano dal diario del server (tappa 3).
- Vede i mix come le 6 playlist «Per te · …», senza codice nuovo.
- Da portare là: `LastListen`, il nodo RESUME coi metadati, `EXTRA_SOURCE_*`, `MenuLabels.row`, `device_info.model`; più avanti `data/taste` e le cartelle `mix|`.

---

## 8. Le tappe

**A ogni tappa**:
- `versionCode` +1 e note `fastlane` in en e it;
- link per unire il ramo in `main` e link della release, dati all'utente appena la versione è pronta;
- «Riproducibile» lanciato dopo la release;
- i file nuovi aggiunti a `tools/harness/sync.sh` e `tools/screens/sync.sh`;
- `python3 tools/check_strings.py`.

**Solo due aggiornamenti del pacchetto del server** (tappe 3 e 6). Un pacchetto nuovo vuol dire che il server non parte finché la persona non scarica circa 75 MB [verificato: `ServerPackage.installed` vuole la cartella della versione esatta]. Lo dice la nota della release.

### Tappa 1 · 0.1.58 «Home in ordine, Riprendi, il diario parte» (solo app)

**Cosa entra**
- `HomeRows.kt` (puro): sezione, ordine e titolo `row_<item_id>` in 9 lingue per (dominio del provider, `item_id`). Le righe sconosciute vanno in «Altro» col nome di MA.
- Testata col saluto; eroe Riprendi dalla coda e dalla **copia locale** (casi a–c); Scelte rapide; In corso con le barre; `CoverCard` col tasto play; righe di MA scelte.
- `QueueState` coi campi nuovi; `EXTRA_SOURCE_*` in `MaPlayer`; `NowPlaying.context`; la riga «In riproduzione da…» nel lettore.
- **Diario dell'app** (`Journal.kt`) e `car-sessions.json` da ServerKeeper; Impostazioni › Per te con l'interruttore (acceso), i conteggi e «Cancella».
- Riga di diagnosi tramite `Logged`, solo numeri: «Per te: artisti N, con generi N, con MBID N; ricerca artist: sì/no» (una ricerca, una volta sola).
- `backdropColor` più vivo, provvisorio.
- In auto, «Per te» in ordine e tradotto.
- Cache `home` v2 (la v1 si legge ancora).

**File**: `phone/model/HomeRows.kt` (nuovo), `PhoneModel.kt` (`loadHome` diviso in `showLocal`/`refresh`), `Pages.kt`, `phone/ui/HomeScreen.kt`, `phone/ui/HomeCards.kt` (nuovo), `data/Model.kt`, `data/Library.kt`, `data/Cache.kt`, `data/LastListen.kt` (nuovo), `phone/model/ResumePlan.kt` (nuovo), `data/taste/Journal.kt` (nuovo), `playback/MaPlayer.kt`, `PlaybackService.kt`, `phone/model/ServerKeeper.kt`, `playback/Menu.kt`, `ResourceLabels.kt`, `res/values*/strings.xml`.

**Prove senza telefono**
- Banco JVM con il MA in Docker (2.10.8 e 2.11.0b7):
  - `HomeRowsCheckKt`: 16 righe con titolo in 9 lingue, sconosciute in «Altro», nessuna dipendenza da `translation_key`;
  - `PhoneCheck`: sezioni, Home offline dalla cache v2, server che parte;
  - `ResumeCheckKt` (a–c): un lettore del banco suona un album e va in pausa; staccato e riattaccato → stesso brano ±2 s; coda persa → ricostruzione da contesto, `start_item` e seek; copia più nuova → `play_index`;
  - `JournalCheckKt`: file mensili, 365 giorni, 20.000 righe lette in meno di 300 ms;
  - `LoggedCheck`: nessun titolo nel logcat;
  - `MenuCheck`.
- `tools/screens`: scene `home`, `home-resume`, `home-first-run`, `home-starting`.
- Restano i testTag di Misure e baselineprofile.

**Da provare sul telefono**: il Riprendi dopo aver riaperto l'app col server fermo; i nomi delle righe; la riga di diagnosi.

**Cosa serve all'utente**: niente da configurare. Dopo qualche giorno, mandare «Condividi il registro del server sul telefono», che porta in fondo il registro di Xonos con la riga di diagnosi.

**Da portare in myCar**: `QueueState`, `EXTRA_SOURCE_*`, `MenuLabels.row`.

### Tappa 2 · 0.1.59 «Nuovo aspetto» (solo app)

**Cosa entra**
- `Theme.kt` con la base neutra, `Shapes` e la tipografia Manrope 600/800 (`res/font`, licenza OFL); `Space.kt`; `Motion.kt` (pagine, pressioni, scheletri, `reducedMotion`).
- `ArtPalette.kt` e `ArtTheme` al posto di `backdropColor`.
- Mini-lettore a pillola coi gesti; lettore che si chiude trascinando in giù, con l'alone e «In riproduzione da» toccabile.
- Card della Home ridisegnate.
- Playlist: testata editoriale, `Collage`, tessera «Nuova playlist», «Aggiungi brani», Rinomina (solo admin).
- Le schermate del README (`docs/*.jpg`).

**File**: `phone/ui/Theme.kt`, `Space.kt`, `Motion.kt`, `ArtPalette.kt` (nuovi), `Components.kt`, `HomeCards.kt`, `App.kt`/`PageScreen`, `PlayerScreen.kt`, `ItemScreens.kt`, `LibraryScreen.kt`, `Platform.kt` e il suo gemello in `tools/screens`, `tools/font/make_font.py`, `assets/licenses/OFL-Manrope.txt`.

**Prove senza telefono**
- `PaletteCheckKt` su 2000 colori a caso, sulle copertine del MA in Docker e su copertine sintetiche (rosso vivo, pastello, grigia, bianca, nera):
  - bianco su `profondo` ≥ 10:1, `vivo` sul fondo ≥ 4,5:1, `suVivo` ≥ 4,5:1;
  - le grigie ripiegano;
  - stesso risultato a ogni giro, meno di 1 ms per copertina.
- `PlaylistCheck` in Docker: rinomina di una builtin, il nome nuovo dopo un riavvio di MA.
- Scene in it, el, ru e zh (glifi e ripiego del cinese), più un **foglio PNG prima/dopo** dal commit precedente in `refs/xonos/screens`, **mandato all'utente prima di unire**.
- Misure con `[misure][profilo]` (solo numeri della stessa esecuzione) e Baseline Profile rigenerato; peso dell'APK prima e dopo (atteso circa +180 KB).

**Da provare sul telefono**: resa del carattere, densità, gesti, animazioni, colori su copertine vere.

**Cosa serve all'utente**: guardare il foglio prima/dopo e dire sì o cosa cambiare. I valori stanno tutti nei token: si ritocca in fretta.

### Tappa 3 · 0.1.60 + pacchetto 2.10.8-13 «Diario sul server, coda che non si perde, Spotify protetto, Riprendi in auto»

**Cosa entra, server** (`prepare.py`, `server/ma`)
- `_plays` → `xonos_plays.py`: `plays.db` in `XONOS_PLAYS_DB`, scritto prima del filtro dei 5 s, importazione del playlog, potatura, `xonos/plays` e `forget`.
- `_resume_saves` P1–P3.
- `_spotify_features`: Spotify senza SIMILAR_TRACKS e ARTIST_TOPTRACKS; `lastfm_recommendations` senza SIMILAR_* e ARTIST_TOPTRACKS.
- `xonos/spotify_taste`, `xonos/library_digest`, `xonos/playlists/replace`, `xonos/playlists/reorder`.
- `xonosServerPackage` = 2.10.8-13.

**Cosa entra, app**
- `PhoneServer` passa `XONOS_PLAYS_DB`, e «Togli dal telefono» toglie il file.
- I comandi nuovi si riconoscono dall'errore 12, ricordato per `server_id`.
- `ResumePlan` (d) per myCar.
- Nodo RESUME, radice `isRecent`, `onPlaybackResumption` coi metadati, `MediaButtonReceiver` con la guardia, voce «riprendi».

**Prove senza telefono**
- CI «Server MA», nuove prove in `xonos_selftest.py`:
  - `ascolti-registro`: 3 brani della cartella su un lettore Sendspin, uno saltato a 3 s, uno ripetuto; una riga finta di 400 giorni sparisce; importazione dal playlog;
  - `coda-salvata`: lettore staccato 2 s dopo la pausa, server ucciso con SIGKILL a 45 s, podcast a 70 s; dopo il riavvio le posizioni sono giuste;
  - `spotify-senza-simili`: Endless Mix di un brano Spotify con 0 chiamate, col contatore finto di `spotify-blocco`;
  - `spotify-gusti`: risposte finte, 6 richieste, la seconda chiamata dalla cache, 403 → elenco vuoto;
  - `playlist-sostituisci`: M3U scritto dal codice di MA, id stabile, cache aggiornata;
  - `cpu-audio` e `privacy()` come prima.
- Banco JVM: `PlaysCheckKt` contro il server avviato sul computer (`python -m xonos_server`), `ResumeCheckKt` (d) con un secondo lettore come myCar finto, `MenuCheck` (RESUME primo e presente anche con MA spento), `KeeperCheck`.

**Da provare sul telefono e in auto**
- La scheda di ripresa di GrapheneOS dopo un riavvio.
- Il tasto play dell'autoradio con Xonos chiuso.
- Gearslip col nodo RESUME.
- Quanto costa riprendere con un seek sotto Soloist.
- La riga «Gusti di Spotify: N artisti / non disponibile».

**Cosa serve all'utente**: aggiornare Music Assistant sul telefono (circa 75 MB: fino ad allora il server non parte), poi le tre prove qui sopra.

**Da portare in myCar**: `LastListen`, RESUME coi metadati, `MediaButtonReceiver`.

### Tappa 4 · 0.1.61 «I tuoi mix» (solo app)

**Cosa entra**
- `data/taste/`:
  - `Play.kt`, `Plays.kt` (le due fonti dietro `PlaySource`);
  - `LibraryCopy.kt` (digest o pagine);
  - `Taste.kt`, `Groups.kt`, `Genres.kt`;
  - `Mixes.kt` (Mix del giorno, Mix per l'auto, Riscoperte, Un anno fa, Mix di <artista>, I tuoi preferiti, mix per genere dalla libreria);
  - `M3u.kt` (solo per gli altri MA), `Publish.kt` (posti fissi), `PerTe.kt` (regia), `Budget.kt`.
- Home: sezioni 4, 7, 9; pagina del mix con «Perché», Salva e Rigenera; «I tuoi brani di X».
- Libreria: striscia «Per te».
- Auto: cartelle `mix|`, `mixt|`, `gen|`.
- `CoverPainter` anche per `Artwork`.
- **Il motore gira**: una volta al giorno al primo evento dopo le 04:00 (Home aperta, server pronto per un play, auto collegata), e dopo 30 ascolti nuovi.

**Prove senza telefono**
- `TasteCheckKt` (JVM puro), su un anno sintetico di 20.000 ascolti con 4 gruppi di gusto e salti:
  - stessa data, stesso mix;
  - niente brani delle ultime 48 h, salti penalizzati, mai lo stesso artista entro 3 posti;
  - al massimo 150 voci, meno di 300 ms;
  - soglie di partenza a freddo.
- `MixStartCheckKt` in Docker: al servizio finto `xonos_test` si aggiunge un contatore di `get_track`. Si confrontano la playlist pubblicata, gli uri `library://`, gli oggetti interi e gli uri nudi.
- `PlaylistCheck`: import dell'M3U scritto dall'app (con `#EXTALBUM`), brani in ordine e suonabili.
- `MenuCheck` e `LongListCheck`: cartelle `mix|` entro 150 voci e 256 KB.
- Scene `home-mixes`, `mix`, `genre`.

**Da provare sul telefono**: la qualità dei mix con la storia vera, il costo reale del play di un mix nel registro di `_spotify_limits`, Gearslip con le cartelle dei mix.

**Cosa serve all'utente**: ascoltare i mix qualche giorno e usare «Rigenera» e «Non fa per me»; dire se i nomi dei gruppi tornano.

### Tappa 5 · 0.1.62 «Novità e scoperte» (solo app, interruttore acceso)

**Cosa entra**
- `data/taste/OpenData.kt`: fresh-releases ogni giorno, `lb-radio/artist` e tag ogni settimana, Cover Art Archive.
- Home: sezioni 6, 8 e piede «Come nascono questi consigli»; pagina delle novità; «Non fa per me».
- Il 15% di scoperte nei mix; mix per genere con artisti nuovi; auto `rel|` e Scopri.

**Prove senza telefono**
- `tools/harness/fake_lb.py`: finto ListenBrainz con risposte scritte dagli schemi del sorgente in `progetto/lb`, che conta le richieste.
- `OpenDataCheckKt`: User-Agent, ritmo, cache; nelle richieste solo mbid; niente con l'interruttore spento.
- `BudgetCheckKt`: una settimana simulata, tetto rispettato, stop dopo `xonos_rate_limited`.
- `LoggedCheck`: niente mbid nel logcat.
- **Job CI «Fonti aperte»** sul runner di GitHub, che raggiunge ListenBrainz (la sandbox no): forma delle risposte, peso di fresh-releases con `days=7`, tempi, `lb-radio/artist` senza token.
- Scene `releases`, `discover`, `about-recs`.

**Da provare sul telefono**: quanti artisti hanno l'mbid, le novità vere, se `artist:` filtra, quante ricerche fa davvero Scopri.

**Cosa serve all'utente**: niente (acceso di base); può spegnerlo nella pagina Per te.

### Tappa 6 · 0.1.63 + pacchetto 2.10.8-14 «Last.fm e radio vere»

**Cosa entra, server**
- `_lastfm`: flusso, chiave e prova; righe spente; nessuna ricostruzione a ogni avvio.
- `xonos/lastfm/simili` coi dati grezzi.
- Servizio minimo `xonos_per_te` in `server/ma`: SIMILAR_ARTISTS, SIMILAR_TRACKS e ARTIST_TOPTRACKS da libreria, diario e grafo dei simili spinto dall'app (`xonos/per_te/grafo`), **0 richieste a Spotify**. Tornano Endless Mix, l'autoplay «simile» e la «Top» dell'artista, anche in myCar e nell'interfaccia web.
- `xonosServerPackage` = 2.10.8-14.

**Cosa entra, app**
- La card «Scoperte più ricche con Last.fm» (admin, server del telefono) apre il flusso generico.
- Scopri usa anche Last.fm.
- Tasto «Radio» (`radio_playlist://`) su brano, album e artista.

**Prove senza telefono**
- `xonos_selftest`:
  - `lastfm-flusso` con un finto Last.fm HTTP: chiave sbagliata, nessuna chiamata senza chiave;
  - `lastfm-riavvio`: nessuna ricostruzione dopo un riavvio;
  - `per-te-servizio`: Endless Mix di un brano con più di un brano e 0 chiamate a Spotify.
- `ServicesCheckKt` per il flusso.
- Controllo dei dex e di `resources.arsc` senza `audioscrobbler`.
- Facoltativo: un secret `LASTFM_API_KEY` per una chiamata vera.

**Da provare sul telefono**: Last.fm vero (in sandbox non c'è una chiave), Endless Mix con la libreria vera.

**Cosa serve all'utente**: facoltativo, creare la chiave gratuita su Last.fm (circa 5 minuti) e incollarla; aggiornare Music Assistant sul telefono (circa 75 MB).

### Tappa 7 · 0.1.64 «Playlist da modificare e rifiniture» (solo app)

**Cosa entra**
- Riordino trascinando (builtin sul server del telefono).
- Condividi M3U, «Brani consigliati per questa playlist», «Salva la coda come playlist».
- Segnalibri per contesto.
- «Personalizza la Home»; in Cerca, «Sfoglia per genere».
- Se le Misure lo permettono: `SharedTransitionLayout` e indietro predittivo (col flag nel manifest).
- README nelle 6 lingue, schermate di fastlane, descrizione per F-Droid (cosa contatta l'APK), CLAUDE.md, note per myCar.

**Prove senza telefono**
- Prova `playlist-ordine` sul server: M3U riscritto, copertina intatta, lucchetto rispettato.
- `PlaylistCheck` (export) e `ResumeCheckKt` coi segnalibri.
- Scene `playlist-edit`, `playlist-reorder`.
- Misure prima/dopo per le transizioni, `[profilo]`.

**Da provare sul telefono**: il trascinamento, l'indietro predittivo su Android 17.

### Oltre (da decidere dopo i registri dell'utente)
- Radio di Spotify senza Web API: le voci autoplay di `queue_changed` di Soloist in modalità Spotify Connect (da verificare col Soloist vero).
- Le righe Per te come servizio di MA, anche per un MA di casa (asset di release).
- «Il tuo anno in musica» a dicembre.
- Proposte a monte: P1/P3 e un servizio ListenBrainz per MA.

---

## 9. Rischi e decisioni aperte

### Non verificato (lo chiariscono le righe di diagnosi delle tappe 1 e 3, o la CI)
- **Spotify in Development Mode**: generi degli artisti, `me/top`, filtro `artist:`, il costo `tracks/{id}` allo stream di un brano fuori libreria. Se mancano: generi solo da ListenBrainz, partenza a freddo da preferiti e playlog.
- **ListenBrainz** (dalla sandbox non si raggiunge): forma vera delle risposte, peso di fresh-releases, stabilità di `lb-radio/artist`. Le prova il job «Fonti aperte».
- **Quanti artisti hanno un mbid**: se sono pochi, novità e scoperte si impoveriscono. Rimedio: il confronto per nome confermato al tocco.
- **`music/playlists/update`** per rinominare le builtin; che la riga «Top» di MA venga davvero dal servizio minimo.
- **Telefono e auto**: GrapheneOS (ripresa di sistema, `MediaButtonReceiver`, Android 17), Gearslip (stili a griglia, voci suonabili nella radice), resa di Manrope e dei gesti, la batteria.
- **`tnum` in Manrope**.

### Rischi
1. **Blocco di Spotify** (già capitato: un'ora). Prime tre tappe a 0 richieste nuove; tetto di 40 al giorno; stop al blocco; Last.fm senza risoluzione; `top_tracks` senza fonti che risolvono.
2. **Patch a MA 2.10.8**: `_replace` si ferma se il testo cambia. Al passaggio alla 2.11 vanno rivisti `_plays`, `_resume_saves`, `_spotify_features`, i comandi delle playlist e il servizio minimo. Ogni pezzo ha la sua prova in `xonos_selftest`.
3. **Processore del server**: solo SQL brevi e niente calcolo del gusto in Python. Last.fm non ricostruisce a ogni avvio. Il digest è incrementale. `cpu-audio` e la riga «Processore negli ultimi 60 s» lo controllano.
4. **Prestazioni dell'interfaccia**: palette in cache, niente animazioni infinite, transizioni condivise solo dopo le misure, Baseline Profile rigenerato.
5. **Mix poveri all'inizio**: diario dalla tappa 1, playlog importato nella 3, soglia a 150, `me/top`, Mix di artista.
6. **myCar diverge**: l'elenco «da portare» è in ogni tappa; i mix arrivano a myCar come playlist anche senza codice.
7. **Traduzioni**: circa 32 generi, saluti, sezioni e righe in 9 lingue; testi del server in `strings.json`; README in 6.

### Decisioni aperte per l'utente
1. **Pubblicare i mix** come 6 playlist fisse nel Music Assistant del telefono (visibili in myCar e nell'interfaccia web)? Proposta: sì.
2. **Backup di Android** in generale: oggi `files/` (token e dati di MA) va nel backup, perché nel manifest non c'è `allowBackup` né `dataExtractionRules`. Il diario resta fuori comunque (`noBackupFilesDir`). Escludere anche il resto?
3. **Gusti di Spotify** (`me/top`, 6 richieste a settimana) come partenza a freddo? Proposta: sì.
4. **Home**: ordine delle sezioni e quali righe di MA accese di base (proposta: quelle della tabella del capitolo 2).
5. **Last.fm**: se e quando creare la chiave; se accendere anche le sue righe (costano 35–50 ricerche la prima volta).
6. **Riprendi**: 14 giorni per la musica e 30 per podcast e audiolibri vanno bene?
7. **myCar**: aggiungere `device_info.model` per contare con certezza i suoi ascolti.

### Correzioni ai difetti trovati dai giudici

| Difetto | Correzione |
|---|---|
| `translation_key` non arriva su `/ws` | Righe riconosciute da `provider` + `item_id`, titoli di Xonos |
| Prima tappa invisibile e da 75 MB (Aurora) | Tappe 1 e 2 solo app e visibili; il pacchetto arriva nella 3 |
| Diario tardi e senza playlog (Tappe) | Diario dell'app dalla tappa 1; quello del server con il playlog nella 3 |
| Riprendi solo dopo `playerReady` | Copia locale già nella tappa 1 |
| Mix in auto all'ultima tappa | Cartelle `mix|` nella tappa 4, insieme ai mix |
| 11 sezioni e scambi al mattino | Ordine fisso, carosello unico «Per te», «Altro» chiusa, Personalizza |
| Transizioni sperimentali, pager, indietro predittivo senza flag | Rimandati alla tappa 7, dopo le Misure, col flag nel manifest |
| Libreria piena di playlist ogni giorno; corsa fra togli e aggiungi | 6 posti fissi con id stabili riscritti dal server; `import_playlist`; niente togli+aggiungi |
| `#EXTALB` | `#EXTALBUM`, e sul server l'M3U lo scrive il codice di MA |
| Diario in `library.db` o in `files/` (backup) | `plays.db` a sé in `noBackupFilesDir` |
| Servizio copiato dall'APK, codice Last.fm nell'APK | Servizio nel pacchetto (`server/ma`); nell'APK nessun Last.fm, con controllo in CI |
| Last.fm che risolve su Spotify in segreto | Via SIMILAR_* e ARTIST_TOPTRACKS da `lastfm_recommendations`; solo dati grezzi |
| Labs `similar-artists` sperimentale | `lb-radio/artist`, API `/1/` senza token |
| Copia della libreria pesante a ogni sincronizzazione | `xonos/library_digest` SQL incrementale; pagine riassunte sugli altri MA |
| `media_item_played` di tutti gli utenti, senza i salti brevi | Non usato; diario prima del filtro dei 5 s, per utente |
| Oggetti brano interi in `play_media` (ramo di difesa) | Playlist pubblicata o uri `library://`; al massimo 10 uri nudi |
| Interruttore delle scoperte spento | Acceso di base, testo onesto su mbid, Cover Art Archive e IP |
| Cover Art Archive rivela gli album | Detto nel testo dell'interruttore |
| Peso del carattere non misurato | Misurato: 2 istanze statiche, circa 180 KB |
| Notifica tardi col tasto Bluetooth | `onPlaybackResumption` dalla copia locale, subito; guardia sull'uscita |
| Rinomina solo per admin, non detto | Tasto visibile solo con `LIBRARY_MANAGE` |
| Con un MA di casa niente mix (motore) | Motore nell'app: i mix ci sono anche con umbrel (senza myCar) |
| Dati in uscita in più (id Spotify verso MusicBrainz, recently-played) | Tolti |