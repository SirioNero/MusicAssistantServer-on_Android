# Xonos: appunti per chi ci lavora

Xonos è un'app Android che fa girare un server Music Assistant (MA) sul telefono, un aggregatore di
servizi (Spotify, SoundCloud, Plex, Jellyfin, podcast, radio, i file del telefono), e porta la musica nel
lettore di Gearslip (alternativa ad Android Auto) e di myCar, anche in vista doppia con le mappe; si collega
anche a un MA di casa, come lettore privato. Non chiede un account Google né i servizi Google Play: va sui
telefoni senza Google (l'utente usa GrapheneOS). La musica esce dal telefono, quindi dall'auto via Bluetooth:
mai dalle casse di casa. Serve anche ad aggirare Spotify, che a Gearslip non mostra la libreria: Spotify si
aggiunge come servizio in MA. Dal 1° novembre 2026 la presentazione (README, About di GitHub, testi per
F-Droid) mette al centro il server sul telefono, i servizi in un'unica libreria e l'assenza di Google, detta per
chi non conosce Music Assistant: Spotify e tutta la musica su un telefono senza Google e senza un server esterno,
perché il server è il telefono stesso (Music Assistant spiegato in mezza frase: un server musicale libero). Scelta
dell'utente, che ha rinominato il repository di conseguenza.

L'utente (SirioNero) parla italiano e prova l'app sul telefono con Obtainium. Leggi tutto questo file
prima di cominciare: le cose scritte qui sono già state verificate, non vanno riscoperte.

## Regole

- Il repository si chiama `SirioNero/MusicAssistantServer-on_Android` dal 1° novembre 2026 (prima
  `Music-Assistant-on-Gearslip`: GitHub rimanda, anche git, le API e i download delle release, ma i link si scrivono col nome nuovo).
- Codice, commenti, commit e log in italiano, con frasi semplici come nel resto del codice. README in
  inglese, poi spagnolo, francese, tedesco, italiano e portoghese: stesse sezioni, con i nomi dei menu e
  dei tasti presi dalle stringhe dell'app in quella lingua; come si compila e la mappa del codice solo in
  inglese (le altre lingue ci rimandano). Un cambio al README va fatto in tutte e sei. L'interfaccia è
  in 9 lingue (vedi sotto), con l'inglese come predefinita.
- Si lavora su un ramo `claude/...`. In `main` si pubblica solo quando l'utente lo chiede (anche con
  "fai un aggiornamento"): ogni push in `main` che cambia l'app crea una release.
- All'utente si danno **sempre i link per rilasciare**, appena una versione è pronta e senza aspettare
  che li chieda: quello per unire il ramo in `main`
  (`https://github.com/SirioNero/MusicAssistantServer-on_Android/compare/main...<ramo>`, poi Create pull
  request e Merge: la release parte da sola), e dopo il link della release.
- Niente pull request se non richiesta.
- Niente copie offline o permanenti dei brani di Spotify, Tidal, YouTube Music e degli altri servizi
  in streaming: vorrebbe dire aggirare le loro protezioni (termini d'uso e diritto d'autore). L'offline
  si fa per podcast e librerie proprie (Jellyfin, Plex, Subsonic/Navidrome, Audiobookshelf, file).
  Un buffer temporaneo per la riproduzione in corso va bene; un archivio da ascoltare dopo no.
- Prima di dire che una cosa funziona, provala (vedi "Come si verifica"). Quello che non si è potuto
  provare (sul telefono, con Gearslip, con Spotify vero) va detto all'utente.

## Compilare e pubblicare

- In questa sandbox non c'è l'SDK Android (dl.google.com, cioè il Maven di Google, è bloccato): l'app
  si compila solo su GitHub Actions, workflow "Compila Xonos" (`.github/workflows/build.yml`), a ogni push.
- Lo stato dei workflow: gli strumenti GitHub (`actions_list`, `get_job_logs` a job finito) o
  `curl https://api.github.com/repos/SirioNero/MusicAssistantServer-on_Android/actions/runs?branch=...`
  (senza token: `gh` qui non è autenticato). Gli errori di compilazione finiscono anche in
  un'annotazione del check run. Non si può leggere l'elenco dei secret.
- Release: solo da `main`, solo se l'app è cambiata, solo con la chiave. File `Xonos.apk` e il pacchetto di
  Music Assistant per il telefono, `xonos-server-arm64-v8a.zip` con la sua firma `.sig` (vedi "Il server di
  Music Assistant sul telefono"; se non si costruisce la release non parte, così escono sempre insieme), tag
  `v<versionName>`. Obtainium vede un APK solo. La versione è scritta in `app/build.gradle.kts` e **va alzata
  a mano a ogni pubblicazione** che cambia l'app: `versionCode` +1 e `versionName` = `0.1.<versionCode>` (fino alla
  v0.1.36 il numero era quello del workflow; la prima scritta nel sorgente è la 37). Insieme si scrivono
  le note `fastlane/metadata/android/{en-US,it-IT}/changelogs/<versionCode>.txt` (massimo 500 caratteri),
  che diventano anche il testo della release. Se il tag c'è già il workflow si ferma con un errore.
- La chiave è nel secret `XONOS_KEYSTORE` (è quella di Plancia, alias `plancia`), la sua password nel
  secret `XONOS_KEYSTORE_PASSWORD`: senza, la compilazione si ferma. Repository e log dei workflow sono
  pubblici: le password non si scrivono nei file e non passano da `GITHUB_ENV` (nei log un secret
  diventa `***`, una variabile no). Senza chiave la release resta non firmata (come la vuole F-Droid);
  con `XONOS_DEBUG_SIGNING=true` (le misure) si firma con la chiave di debug.
- Licenza AGPL-3.0-or-later (`LICENSE`). Tutte le librerie sono Apache 2.0, compatibili. F-Droid: testi,
  icona e schermate in `fastlane/metadata/android/` (inglese e italiano), ricetta da proporre a
  fdroiddata in `tools/fdroid/it.xonos.ma.yml` (toglie `tools/` prima di compilare: le prove usano un
  mirror di Maven Central che lo scanner di F-Droid non accetta), workflow "Come F-Droid" che compila
  senza chiave e passa ricetta e APK a `fdroid lint` e `fdroid scanner`. `dependenciesInfo` è spento
  (F-Droid rifiuta quel blocco). Build riproducibili: il workflow "Riproducibile" compila la release
  come F-Droid (la loro immagine `fdroidserver:buildserver-trixie`, OpenJDK 21, Gradle del wrapper,
  senza `tools/`) e la confronta byte per byte con l'APK della release tolta la firma: per la 0.1.38, la
  0.1.42, la 0.1.49 e la 0.1.50 identici. Così la ricetta ha `Binaries` e `AllowedAPKSigningKeys` (SHA-256 del certificato
  `18ef3fc3…2c836`): F-Droid pubblica l'APK con la nostra firma e si passa da GitHub/Obtainium a F-Droid
  senza disinstallare. Dopo ogni release va lanciato a mano "Riproducibile": una versione non identica
  F-Droid non la pubblica. Il modello delle richieste di fdroiddata vuole in `commit` l'hash completo e
  chiede di decidere le build riproducibili prima dell'inclusione (dopo non si può). `apksigcopier`
  di Debian non verifica gli APK firmati solo v2 (cerca `META-INF/MANIFEST.MF`).
- La richiesta a fdroiddata (verificato il 26 ottobre 2026): l'Inclusion Policy vuole tre mesi di
  sviluppo attivo, salvo utenti dimostrati o altre app dello stesso autore già su F-Droid (di SirioNero
  non ce ne sono), e i manutentori chiudono in poche ore le richieste più giovani. Il repository è nato
  il 24 ottobre 2026: non prima del 24 gennaio 2027. Il modello (`Default.md`) chiede anche cosa ha
  l'app in più di quelle simili, e chiudono anche per questo: su F-Droid ci sono già MassDroid
  (`net.asksakis.massdroidv2`, anche Android Auto e Sendspin) ed Ensemble (`com.collotsspot.ensemble`).
  Contro il codice scritto con l'IA non c'è una regola (una proposta aperta, fdroid/admin#699,
  incoraggia a dirlo). Se GitLab chiede telefono o carta per la CI non si danno: si lascia una nota
  nella richiesta. Un connettore GitLab non c'è: o a mano, o un token GitLab nelle impostazioni
  dell'ambiente.
- Strumenti: AGP 8.11.1, Kotlin 2.2.0, Gradle 8.14.3, compileSdk 36, targetSdk 34, minSdk 26,
  Media3 1.11.1, OkHttp 4.12.0, coroutines 1.10.2, Compose 1.9.4, Material 3 1.4.0, activity-compose
  1.10.1, Coil 3.3.0 (con coil-svg per le icone dei servizi), profileinstaller 1.4.1. Pacchetto `it.xonos.ma`.
- NDK fissato (`ndkVersion` 27.3.13750724, cioè r27d) dalla 0.1.49: compila proot (`app/native/proot.sh`, task
  `buildProot`) per arm64-v8a e x86_64, dentro l'APK come `libproot.so` e `libproot-loader.so`; le librerie si
  estraggono all'installazione (`useLegacyPackaging`). Sorgenti scaricati da versioni fissate con lo SHA-256 (quelli
  di Termux): termux/proot 5.1.107.101 e talloc 2.5.0 di Samba, compilato da solo con `talloc-replace.h` al posto
  di waf; percorsi tolti, niente build-id, pagine da 16 KB. Nella ricetta di F-Droid le build nuove vogliono
  `ndk: r27d` e `sudo: [apt-get update, apt-get install -y make gawk binutils]` (l'immagine `buildserver-trixie` non
  ha make né binutils, e il suo awk è mawk, senza lo `strtonum` che usa `loader/loader-info.awk` di proot);
  "Riproducibile" fa le stesse due cose. Il GNUmakefile di proot chiama `readelf` del sistema (non quello dell'NDK)
  per l'offset di `pokedata_workaround` (solo arm64, usato se PTRACE_POKEDATA non va): senza readelf non dà errore,
  l'offset diventa 0, `libproot.so` arm64 è sbagliato e l'APK non è identico (Riproducibile, 1° novembre 2026).
  Nella 0.1.49 della release è giusto (1016, controllato dal caricatore), e con binutils Riproducibile la dà
  identica. Dalla 0.1.50 `proot.sh` mette
  nel GNUmakefile il `llvm-readelf` dell'NDK e al posto dello script di proot `app/native/loader-info.awk` (stesso
  file generato, con ogni awk; si ferma se mancano i simboli), controlla l'offset e scrive le impronte SHA-256 delle
  quattro librerie nel log: in CI uguali a quelle della 0.1.49, e Riproducibile dà la 0.1.50 identica. gawk e binutils allora non dovrebbero più servire
  (da provare con Riproducibile prima di toglierli dalla ricetta). In locale (sandbox) non si compila né si esegue:
  solo in CI.
- R8 è acceso nella release (`isMinifyEnabled` e `isShrinkResources`, regole in `app/proguard-rules.pro`,
  `-dontobfuscate` così gli errori restano leggibili). L'APK ridotto si prova sull'emulatore con le misure
  (sotto): parte, si collega, suona Opus, nessun arresto.
- Baseline Profile: il modulo `baselineprofile/` (Macrobenchmark 1.4.1, UiAutomator) lo genera
  sull'emulatore; il profilo sta in `app/src/main/generated/baselineProfiles/baseline-prof.txt` e va
  rigenerato (commit con `[profilo]`) quando cambiano molto avvio, Home, Libreria o lettore.
- Compose e Material 3 hanno le versioni di Compose Multiplatform 1.9.3 / material3 1.9.0 (lo dicono i
  file `.module` di CMP su Maven Central): così `tools/screens` disegna le stesse composable. Coil 3.3.0
  è l'ultimo compilato con Kotlin 2.2 (il 3.4 vuole la stdlib 2.3).

## Come è fatta

| File | Cosa fa |
|---|---|
| `data/MaClient.kt` | WebSocket `/ws` di MA: accesso con token, comandi con risultati a pezzi, eventi. Ogni errore è un `MaException` con un `Problem` |
| `data/MaRepository.kt` | Prova l'indirizzo di casa poi quello remoto, si ricollega, smista gli eventi |
| `data/Library.kt` | I comandi di libreria e coda. Elenchi interi a pagine di 500; ricerca divisa per tipo |
| `data/Model.kt` | `MaItem`, `QueueItem`, `QueueState`, `Connection`, `Problem` |
| `data/Settings.kt` | Preferenze: indirizzi, token, nome e id del lettore, volume, lingua |
| `sendspin/SendspinClient.kt` | Il telefono come lettore Sendspin (protocollo audio di MA), sincronia dell'orologio |
| `sendspin/AudioEngine.kt` | Suona i pacchetti al momento giusto; piccole correzioni un campione alla volta |
| `sendspin/AndroidAudio.kt` | AudioTrack e decoder Opus/FLAC di MediaCodec (OpusHead ricostruito, pre-skip 0) |
| `playback/PlaybackService.kt` | MediaLibraryService di Media3 che Gearslip apre: sessione, focus audio, notifica, rete |
| `playback/MaPlayer.kt` | SimpleBasePlayer che rispecchia la coda di MA |
| `playback/Menu.kt` | Il menu e la ricerca, senza Media3 (si prova sulla JVM). Gli id dicono cosa fare: vedi il commento in testa |
| `playback/BrowseTree.kt`, `ResourceLabels.kt` | Il menu come MediaItem; le sue scritte dalle risorse |
| `playback/Artwork.kt` | Copertine servite a Gearslip, alla notifica e al lettore come `content://` firmati; i file li tiene `data/ArtCache.kt` (una copertina chiesta da più parti insieme si scarica una volta sola) |
| `data/SkipSender.kt` | Avanti, indietro e "salta a" mandati a MA uno alla volta, sempre verso l'ultimo brano chiesto; raccolti per 400 ms solo con i servizi a un flusso (`QueueItem.singleStream`) |
| `data/PlaylistRemover.kt` | Toglie brani da una playlist: i tocchi vicini in un giro, il giro dopo solo quando il primo è applicato |
| `data/SoloistUnlock.kt` | Spotify (Soloist) "già occupato" da un ascolto appeso: se nessun altro lettore lo suona, ricarica il servizio e riprova il comando (play, play_media, salti, ripresa dopo un calo di rete) |
| `data/Lyrics.kt` | Il testo di un brano: lettore LRC (più tempi per riga, offset, tag, parole a tempo) e testo semplice |
| `data/Cache.kt` | `ScreenCache`: le ultime schermate (Home, prime pagine della Libreria) su disco, mostrate subito all'apertura |
| `data/Account.kt` | Accesso con nome utente e password (auth/login, poi un token suo con auth/token/create), primo account (POST /setup) |
| `data/Services.kt` | I servizi (provider) di MA: elenco, tipi, icone, opzioni, pulsanti, flussi a passi; i campi come `ConfigField` |
| `data/Devices.kt` | I lettori di MA su cui suonare ("Suona su"), il passaggio della coda, i comandi a una coda |
| `data/AccountProvider.kt` | L'account (indirizzi, token, nome utente) per myCar, che ha Xonos dentro di sé: `content://it.xonos.ma.account`, metodo `account`; risponde solo a `io.github.sirionero.mycar` (e `.dev`) firmati col certificato di myCar (SHA-256 `e477a479…cccc31`), a nessun altro |
| `data/MyCarBridge.kt` | Il ponte per myCar lasciata fuori da Tailscale (dalla 0.1.40): myCar si lega al servizio (azione `io.github.sirionero.mycar.BRIDGE`) e gli chiede una connessione verso Music Assistant; Xonos la apre e le passa l'altro capo di un socket locale. Solo a myCar firmata col suo certificato (la build di prova anche a myCar `.dev`), solo verso gli indirizzi scritti qui. Risposte: OK 1, NOT_MYCAR 2, NOT_OURS 3, NO_ANSWER 4, OTHER 0 |
| `phone/MainActivity.kt` | L'app sul telefono: Compose, lingua, MediaController verso il servizio finché è in vista |
| `phone/ControllerLink.kt` | `PlayerLink` dal MediaController: lo stesso `MaPlayer` che vede Gearslip |
| `phone/model/` | `PhoneModel` (schede con pile di pagine, caricamenti, ricerca, suona/dopo/in coda, preferiti, testi, impostazioni, "Suona su"), `Pages.kt`, `Player.kt` (`NowPlaying`, `PlayerLink`), `ConnectionCheck.kt`, `Discovery.kt` (server trovati con mDNS), `ServicesModel.kt` e `ServicePages.kt` (i servizi), `RemoteLink.kt` (un lettore di MA comandato dallo schermo). Senza Android: si prova sulla JVM |
| `phone/NsdFinder.kt` | Cerca Music Assistant nella rete con NsdManager (`_mass._tcp`) |
| `phone/ui/` | Le schermate in Compose/Material 3. Tutto comune tranne `Platform.kt` (indietro, pixel della copertina, "schermata piena" per le misure, id per UiAutomator). `ServicesScreens.kt` disegna i moduli di MA dai campi; `Markdown.kt` i loro testi; `DevicesSheet.kt` "Suona su"; `PlaylistSheet.kt` "Aggiungi a una playlist" |
| `baselineprofile/` | Le prove sull'emulatore: avvio, scorrimento di Home e Libreria, lettore, playlist lunga; genera il Baseline Profile |
| `Language.kt`, `Texts.kt` | Le lingue (senza Android); `wrap()` e `describe()`, che racconta un `Problem` nella lingua scelta |

## Music Assistant: cose verificate (2.10.8 e 2.11.0b7)

- API: `/ws`, primo messaggio `server_info`, poi `auth` con il token. Codici d'errore: 12 comando
  sconosciuto (MA più vecchio della 2.7), 20/21/23 token, 22 permessi.
- `music/<tipo>/library_items`: `limit` (500 di base), `offset`, `order_by`; risultati "summary" che
  includono `provider_mappings`. `music/search`: `limit` per tipo (25 di base), cerca in libreria e in
  tutti i servizi, un servizio lento (oltre 8 s) resta fuori da quella risposta.
- Per un artista della libreria `artist_albums`/`artist_tracks` danno solo ciò che è in libreria:
  per la discografia intera si chiede a ogni `provider_mapping` (lo fa `Library.everywhere`).
  `album_tracks` dà già i brani di tutti i servizi. `top_tracks` c'è dalla 2.10.
- Generi (2.10+): `music/genres/library_items` con `hide_empty=true` e `content_type="music"`.
- Episodi dei podcast: `provider_mappings[].url` è l'indirizzo diretto del file dell'episodio.
- Immagini: `/imageproxy/<proxy_id>?size=256&fmt=jpg` (o 512), senza token. Le misure accettate sono
  solo 0, 80, 160, 256, 512 e 1024 (0 = originale): con le altre MA risponde 400.
- `order_by` di `library_items`: `sort_name`, `sort_name_desc`, `timestamp_added_desc`,
  `last_played_desc`, `play_count_desc`, `year`. `search` filtra per nome.
- Testi: `metadata/get_track_lyrics` (c'è dalla 2.7.5) vuole l'oggetto brano intero (quello di
  `music/item_by_uri`) e risponde `[testo semplice, testo LRC]`. Il filesystem legge il tag LYRICS
  (anche in formato LRC: allora finisce nel primo campo) e un file `.lrc` accanto al brano. Se il brano
  non ha testo MA lo cerca nei servizi di metadati (LRCLIB ecc.): senza rete può metterci decine di secondi.
- `play_media` con `start_item` (2.10.8): toglie dalla coda i brani prima di quello scelto (se il
  casuale è spento). `shuffle` in `play_media` c'è solo dalla 2.10, e le versioni vecchie ignorano gli
  argomenti che non conoscono: per mescolare si accende prima `player_queues/shuffle`. Dalla 2.10 un
  album suona in ordine anche con il casuale acceso, se non si passa `shuffle`.
- `music/recommendations` (2.10.8): 17 righe (cartelle) senza contenuto; il contenuto con
  `music/recommendations/items`. La riga `recently_played` ripete gli ascolti recenti.
- Gli indirizzi HTTP dei brani in coda (`/single/<session>/...`, porta 8097) sono protetti da un
  codice di sessione interno che l'API non dà; `music/tracks/preview` è solo un assaggio di 30 s.
- Accesso: `auth/login` (username, password, device_name) apre una sessione; con quella
  `auth/token/create` dà un token a lunga durata, poi `auth/logout`. Agli ospiti niente token: resta
  la sessione. Senza utenti MA risponde 200 a `GET /setup` e all'API `setup_required` (codice 503); il
  primo account si crea con `POST /setup`. Password di almeno 8 caratteri. Ruoli: admin, user, guest.
- `auth` accetta `locale` (e c'è `translations/set_locale`): MA traduce da sé etichette dei moduli,
  errori e titoli (pt_PT è molto più completa di pt_BR; l'italiano ha poche voci, il resto resta inglese).
- MA si annuncia con mDNS `_mass._tcp`; nei TXT le informazioni di `server_info` (internal_url, base_url,
  external_url, server_version...).
- Servizi (solo admin): `config/providers` (dalla 2.10 `last_error` è un oggetto con `message`),
  `providers/manifests`, `providers/icon` (data URI, di solito SVG), `config/providers/get` + `get_entries`
  (l'ordine dei campi), `save` (solo i valori cambiati; una password salvata torna come
  `this_value_is_encrypted`), `remove`, `invoke_action`. Dalla 2.10 (schema 63) si aggiunge con
  `config/providers/setup` e i flussi (`config/flows/submit|get|abort`, `config/providers/reconfigure`):
  passi form/external/progress/finish/abort, anche con l'evento `setup_flow_updated`. L'accesso nel
  browser torna a `{base_url}/setup_flow/callback/<flow>`. I flussi restano vivi 15 minuti anche se la
  connessione cade. Nei moduli dei flussi non ci sono pulsanti; i segreti non tornano mai.
- Bug di MA: dopo `save` il servizio si ricarica un secondo più tardi (`call_later(1, ...)`); un
  `remove` in quel momento lascia in settings.json una voce senza `domain` e da lì `config/providers`
  risponde `'domain'` per sempre. `Services.remove` aspetta 3 s dal salvataggio e la fine del
  caricamento. Nel Docker di prova la voce rotta si toglie a mano da `data/settings.json` (MA fermo).
- Playlist: `music/playlists/create_playlist` (`name`; senza servizio la crea "builtin", cioè di MA, che
  accetta brani di tutti i servizi; di base solo brani). `add_playlist_tracks` (`db_playlist_id`, `uris`)
  apre album e playlist nei loro brani, salta i doppioni e per la playlist di un servizio cerca lì lo
  stesso brano; `remove_playlist_tracks` (`db_playlist_id`, `positions_to_remove`) vuole le posizioni
  da 1, il campo `position` dei brani di `playlist_tracks`. Dalla 2.10 aggiunta e rimozione girano in
  disparte (MA risponde prima di farle) e lo stato dei lavori (`tasks/get`) è solo per gli admin.
  `playlist_tracks` passa da una cache che dopo una modifica può restare vecchia per più di 15 s:
  `force_refresh: true` la salta. Nelle playlist di MA i brani hanno l'uri del loro servizio, non quello
  della libreria. `is_editable` è nei risultati di `library_items`. Per cancellare una playlist:
  `music/library/remove_item` (`media_type`, `library_item_id`); `music/playlists/remove` vuole `item_id`.
- Coda: `player_queues/delete_item` (`item_id_or_index`), `move_item` (`queue_item_id`, `pos_shift`;
  0 = in testa ai prossimi), `move_item_end`. Il brano in corso e quelli fino a `index_in_buffer` (già
  mandati al lettore) non si toccano: `delete_item` lo ignora in silenzio, `move_item` dà errore.
- Lettori: `players/all` dà anche `private` (i lettori delle app, come il telefono: non si offrono) e
  `hide_in_ui`; i lettori di tipo `protocol` non compaiono. Una cassa Sendspin che non è un'app diventa
  un Universal Player (`up...`) circa 15 s dopo. `player_queues/transfer` sposta coda, punto e casuale,
  svuota e ferma la coda di prima, e riparte se quella suonava.

### Sendspin (come arriva l'audio)

- Si usa la modalità non cifrata ("legacy") attraverso `/sendspin` sulla porta di MA: primo messaggio
  `{"type":"auth","token":...,"client_id":...}`, poi `client/hello`. L'API va collegata prima di
  Sendspin. `product_name` "Mobile Application" rende il lettore privato (id = client_id);
  `unpaired_access.enabled=false`, altrimenti alla riconnessione il server lo rifiuta.
- Ai client legacy l'intestazione dei pacchetti audio è di 9 byte (tipo + tempo), senza il
  `send_ahead` della specifica nuova: con 13 byte Opus diventa rumore.
- Il server tiene al massimo 30 s di audio in anticipo per lettore (`max_duration_us` in aiosendspin,
  fisso). Più di così si può avere solo cambiando Music Assistant/aiosendspin.
- Il primo suono arriva circa `required_lead_time_ms` − 150 ms dopo l'inizio del flusso (misurato:
  800 → 0,65 s, 1500 → 1,3 s, 300 → subito). Xonos chiede 300 ms in Wi-Fi e 600 in rete mobile: il
  margine si paga a ogni play e a ogni salto, le gallerie le copre l'anticipo che il server manda dopo.
- L'uscita del telefono ci mette di più ad aprirsi (sull'emulatore l'avvio era in ritardo di 1042, 652,
  169 ms; con il Bluetooth si aggiunge il suo ritardo). Fino alla 0.1.37 l'audio in ritardo all'avvio si
  saltava (l'inizio del brano tagliato): ora si suona tutto un po' dopo e si recupera piano (0,4%), e la
  posizione mostrata è quella del server meno `AudioEngine.lateMs`. L'AudioTrack di suo parte solo con il
  buffer pieno: da Android 12 Xonos gli fa bastare 40 ms (`setStartThresholdInFrames`) e mentre aspetta
  i suoi tempi tiene in coda solo 50 ms di silenzio (`StartCheckKt` lo simula).
- Il decoder Opus di MediaCodec con gli ingressi pieni aspetta che gli si prendano le uscite: prima si
  prendono quelle, poi si riprova (fino alla 0.1.37 il pacchetto si buttava: un buco di 20 ms, 18–26
  volte per giro di misure). Con la correzione, sull'emulatore: nessun pacchetto buttato, avvii a 195,
  −213 e −8 ms dal momento giusto, poi scarto medio 0 ms.

### Avvio e limiti dei servizi (misurati con `LatencyCheckKt`)

- Prima di far partire un brano MA aspetta audio nel suo buffer: 2 s di solito, 5 con la
  normalizzazione dinamica, 8 con la dissolvenza incrociata; per le sorgenti "realtime" (Spotify
  Soloist) 1 s, 2 con la normalizzazione dinamica, 5 all'inizio di un brano sotto il minuto. Con una
  sorgente che arriva alla velocità della musica (`/slow/<n>.flac` del finto server) il play aspetta
  6–7 s e ogni avanti 3–4 s; con un file locale il play 0,1 s, ma ogni avanti costa sempre ~1 s.
- `max_concurrent_streams` per servizio: Spotify con librespot 3, con Soloist 1, Tidal 1, YouTube Music
  3, gli altri servizi in streaming 5, file e server propri senza limite. Con 1 non c'è il precarico del
  brano dopo, e una raffica di salti finiva in "has reached its limit of 1 simultaneous streams":
  con quei servizi Xonos raccoglie avanti/indietro premuti in fretta in un salto solo (`SkipSender`).
  Due comandi insieme MA li esegue in parallelo e vince l'ultimo a finire: i salti vanno uno alla volta.
- Spotify in MA ha due motori: librespot (parte prima, 3 flussi, ma va solo con gli account creati
  prima di dicembre 2024) e Soloist (quello ufficiale: tutti gli account Premium, un flusso, audio alla
  velocità della musica). Si sceglie riconfigurando il servizio.
- Soloist occupato (verificato nel codice di MA 2.10.8 e nel registro dell'utente, 30 ottobre 2026, su umbrel):
  un'esecuzione sola per volta (`SoloistBackend._acquire_run`); se ne è in corso una per un altro brano, ogni
  play riceve `soloist_session_busy` ("… is already playing something else. Spotify Soloist plays one thing at a
  time, so stop the other player first"), con `translation_args` il nome del servizio. Un flusso che nessuno
  ritira (il telefono scollegato a metà brano) tiene l'esecuzione finché il servizio non si ricarica. Gli errori
  di MA portano `translation_key` e `translation_args` (in `MaException.key`/`args`). `SoloistUnlock`: se nessun
  altro lettore suona Spotify (`player_queues/all`: `state` playing, `available`, brano con fonte spotify)
  ricarica il servizio (`config/providers/reload`, solo admin) e riprova una volta; più errori entro 15 s, una
  ricarica sola. `SoloistCheckKt` lo prova col servizio dei file al posto di Spotify (errore finto). In
  `config/providers` il `name` dei servizi è spesso null: si ricaricano tutti quelli del dominio.

## Gearslip: cosa conta

- Elenca le app con `MediaBrowserService` e il descrittore `automotive_app_desc` (`<uses name="media"/>`).
- Sfoglia con il MediaBrowser del sistema, senza pagine: vuole l'elenco intero. Cerca con
  MediaBrowserCompat, i cui risultati passano in un solo messaggio Binder (circa 1 MB): tenerli corti.
- Non è un'app di sistema: Media3 gli darebbe solo la lettura, serve `onConnectAsync` con tutti i comandi.
- Le copertine `http://` non le carica (targetSdk 37): passano da `content://`.
- La vista doppia compare solo con un brano attivo. I testi li cerca Gearslip da sé.

## myCar

- myCar (`SirioNero/MyCar`, il fork di Gearslip) ha Xonos dentro di sé dalla 0.5.11: lo stesso motore
  (`data/`, `sendspin/`, `playback/`) copiato nel suo pacchetto `xonos`, senza le schermate del telefono,
  con il PCM che va dritto nel canale musicale di Android Auto quando l'audio passa da myCar. L'app Xonos
  resta per Gearslip e per chi la preferisce. Le correzioni al motore qui vanno portate anche là.
- Dalla 0.1.39 Xonos passa a myCar il suo account (`AccountProvider`), così in myCar non si riscrive niente.
- Dalla 0.1.40 Xonos fa da ponte a myCar (`MyCarBridge`): per collegarsi all'auto senza fili myCar va lasciata
  fuori da Tailscale, e allora le sue connessioni verso un Music Assistant sulla tailnet (API, Sendspin, copertine)
  le apre Xonos, che resta dentro Tailscale. Il legame di myCar tiene Xonos sveglio e con la rete per il viaggio.
  Verificato in CI dal lato di myCar (Car preview con `xonos_ref`, Release check `tailscale-out`): con
  Tailscale vero e myCar esclusa, myCar si collega a Music Assistant attraverso Xonos; nell'anteprima
  suona un album con Sendspin dal ponte.

## Il server di Music Assistant sul telefono (`server/`, ramo `claude/server-ma`)

Per chi non ha un Music Assistant. L'app è una sola (`Xonos.apk`, la stessa per F-Droid): nelle impostazioni
(e nel benvenuto) la scheda "Music Assistant su questo telefono" ha il tasto "Fai diventare Xonos il tuo server
Music Assistant"; dopo una domanda con quanto pesa (circa 70 MB) e un sì, Xonos scarica il pacchetto che porta
Music Assistant sul telefono, lo controlla e lo avvia. L'utente l'ha scelto al posto di due APK (Xonos e
Xonos-server, 72 MB solo arm64, che c'erano prima nel ramo). Verificato sull'emulatore (workflow "Server MA",
0.1.42, risultati in `refs/xonos/server/<ramo>`): Xonos scarica il pacchetto (72 MB per x86_64) dal server HTTP
del runner, controlla la firma, lo estrae e avvia Python con `linker64` (download ed estrazione circa 20 s, poi
Music Assistant risponde in circa 10 s); crea il primo account, legge una cartella con ffprobe, un lettore
Sendspin riceve PCM 16 bit/48 kHz 0,5 s dopo play; ffmpeg (sempre con `linker64`) apre https; la build di prova
di Xonos suona dal server sul telefono; la release ridotta con R8, da capo come una persona (il tasto, "Scarica",
"Consenti" per la musica), si collega da sola e il suo lettore suona 15 s dalla cartella Music del telefono.

- **Il pacchetto** (`server/package/make_payload.py`, a partire dall'AAR del modulo `:server`): uno zip per ABI,
  sempre uguale a parità di contenuto. Dentro: `bin/` (`python3`, compilato con l'NDK da `python3.c` che chiama
  `Py_BytesMain`; `ffmpeg`, `ffprobe`, `librespot`), `lib/` (`libpython3.13.so` e le altre librerie, la libreria
  standard in `python313.zip`, i moduli nativi in `python3.13/lib-dynload`, `cacert.pem`), `app/` (Music
  Assistant preparato e `xonos_server.py`), `site-packages/` (i pacchetti di Chaquopy), `payload.json` (versione,
  ABI, Python). La versione è `xonosServerPackage` in `gradle.properties` (es. `2.10.8-1`): si alza quando cambia
  il server (Music Assistant, pacchetti, `xonos_server.py`, `make_payload.py`). L'app la confronta con quella
  scaricata: se è cambiata la scheda dice di aggiornarlo, con la stessa domanda.
- **Da dove e con quale firma**: dalla release di GitHub della stessa versione di Xonos
  (`releases/download/v<versionName>/xonos-server-<abi>.zip` e `.sig`, `BuildConfig.SERVER_PACKAGE_URL`). Il
  workflow di release lo costruisce per arm64-v8a e lo firma con la chiave delle app (`keytool -importkeystore`,
  `openssl dgst -sha256 -sign`); Xonos lo usa solo se la firma corrisponde alla chiave del certificato che ha
  firmato Xonos stesso (`ServerPackage.signedLikeXonos`, SHA256withRSA o con ECDSA; la chiave vera è RSA 2048):
  nessun altro può metterne uno al suo posto. Il peso si chiede con un HEAD. Prima di scaricare si guarda lo
  spazio (4 volte lo zip); lo zip si estrae in `noBackupFilesDir/music-assistant-server/<versione>.new`
  (niente voci fuori dalla cartella, i file di `bin/` eseguibili), si controlla `payload.json`, poi si rinomina
  e le versioni vecchie si tolgono: mai niente a metà. Le prove mettono un altro indirizzo nel file
  `files/server-package-url` dell'app (vale solo per un pacchetto firmato come l'app).
- **F-Droid**: scaricare codice eseguibile è permesso solo se la persona lo chiede esplicitamente e le si dice
  che F-Droid non lo controlla: lo dice la domanda prima del download. Con la build riproducibile F-Droid
  pubblica l'APK con la nostra firma, quindi la firma del pacchetto torna anche lì. La ricetta e i workflow
  che la imitano tolgono ancora `server/`: il modulo serve solo a costruire il pacchetto.
- **Processo a sé** (`PhoneServer`): `python3 -m xonos_server --data <files/music-assistant> --cache
  <cache/music-assistant>`, figlio di Xonos (vive quanto il processo dell'app). Da Android 10 un'app con target
  29 o più non può avviare i file che ha scaricato (W^X), ma può caricarli come librerie: i programmi si avviano
  col linker di sistema, `/system/bin/linker64 <programma> argomenti` (come Termux). Anche ffmpeg, ffprobe e
  librespot, quando li lancia Music Assistant: `xonos_server.py` sostituisce `subprocess.Popen` (lo usa anche
  asyncio) e per i programmi di `XONOS_BIN` mette davanti `XONOS_LINKER`. Ambiente: `PYTHONHOME`, `PYTHONPATH`
  (`app` e `site-packages`), `LD_LIBRARY_PATH`, `PATH`, `SSL_CERT_FILE`, `HOME`, `TMPDIR`. Lo stato arriva con
  righe `XONOS_STATE starting|running|stopped|failed <perché>` sull'uscita; il resto va nel logcat col tag
  `XonosServer`. Si ferma con SIGTERM (Music Assistant chiude database e servizi), dopo 15 s SIGKILL; l'avvio si
  aspetta al massimo 180 s. I dati di Music Assistant (account, servizi, libreria) restano in `files/` quando
  il pacchetto cambia; "Togli dal telefono" toglie pacchetto e dati.
- **Python 3.13 di Chaquopy 17.2.0** (MIT), modulo Gradle `:server`: serve solo a costruire il pacchetto
  (Python per Android e i pacchetti compilati per Android); il ponte Java di Chaquopy non si usa più. Non il
  3.14 che Music Assistant dichiara: per Android Chaquopy compila Pillow (11.0.0), cryptography (42.0.8), cffi
  (1.17.1), numpy (1.26.2) solo fino al 3.13 (il suo indice, `chaquo.com/pypi-13.1`, dalla sandbox dà 403).
  Del 3.14 MA usa due cose: `except A, B:` senza parentesi e le annotazioni lette tardi; `server/ma/prepare.py`
  le riscrive (parentesi, `from __future__ import annotations`) e controlla che ogni file si compili col 3.13.
- `prepare.py` prende MA 2.10.8 dal tag su GitHub, tiene il nucleo e i servizi della lista `PROVIDERS` e
  applica modifiche controllate (il testo deve esserci una volta sola, sennò si ferma): niente
  installazioni di pacchetti, DNS di Android al posto di c-ares, API (8095) e flussi (8097) solo su
  127.0.0.1, niente mDNS né UPnP, Zeroconf legato a 127.0.0.1, Sendspin in PCM 16 bit (sotto).
- **Sendspin senza PyAV**: MA prepara l'audio in float e aiosendspin lo converte con PyAV, che per Android
  non c'è. La sessione si prepara già in PCM 16 bit 48 kHz (lo fa ffmpeg) e aiosendspin la passa così com'è;
  `av` è un modulo vuoto che si lascia importare (Opus e FLAC risultano non disponibili e il server li
  scarta da solo: Xonos non deve cambiare i formati che annuncia). numpy serve solo agli import del
  visualizzatore di aiosendspin.
- Al posto di pacchetti senza versione Android: `orjson`, `aiodns` (moduli in Python, `server/wheels`),
  `zeroconf` in Python puro dal sorgente, `modern_colorthief` (Pillow), `music_assistant_frontend` (una
  pagina sola). `server/ma/wheels.py` ne fa wheel identiche byte per byte; pip le trova con `--find-links`.
  `requirements.txt`: tutte le versioni fissate. Chaquopy installa solo wheel (`--only-binary`).
- **ffmpeg 7.1.5** (+ mbedTLS 3.6.5 per https) compilato dall'NDK in `server/ffmpeg/build.sh`, librerie
  condivise e i programmi chiamati `libffmpeg.so`/`libffprobe.so` (i nomi di quando stavano nell'APK: il
  pacchetto li rimette in `bin/ffmpeg` e `bin/ffprobe`). MA senza ffmpeg 7 non parte. In CI ffmpeg si compila
  in pochi minuti e resta in cache finché lo script non cambia.
- `music_assistant` è in `extractPackages` di Chaquopy: MA cerca `setup_flow.py` sul disco per sapere quali
  servizi hanno una procedura (nel pacchetto tutto è comunque estratto).
- Pesa 68,5 MB lo zip per arm64 (165 MB estratto), 72 MB per x86_64 (179 MB), fino al 2.10.8-3; dal 2.10.8-4 c'è anche
  `rootfs.tar` (+6,4 MB nello zip, 16,7 MB il tar e altrettanto estratto: circa 75 MB e 200 MB in tutto, stima da
  verificare sulla release; Soloist, scaricato da MA quando serve, altri 34 MB). Xonos chiede libero 4 volte lo zip,
  per zip ed estratto insieme. Solo 64 bit: Python 3.13 per Android non c'è
  per armeabi-v7a; su un telefono a 32 bit la scheda non c'è (`PhoneServer.create` dà null).
- **Servizi** (`PROVIDERS` in `prepare.py`): 59, cioè tutti quelli le cui librerie esistono per Android, che
  si importano col 3.13 e che non entrano con le chiavi di Music Assistant (sotto); fuori, con il motivo scritto
  lì, Apple Music (DRM), Deezer (per ora), NFS e SMB (vogliono mount), YouTube Music (deno), nicovideo
  (pydantic 2), QQ Music (cryptography 47), BBC Sounds e iBroadcast (solo 3.14), Qobuz, Tidal, fanart.tv e
  TheAudioDB (dalla 2.10.8-2). `LIBRARIES`: cartelle importate da altri ma non proponibili (Spotify Connect, senza
  manifest). Per aggiungere un servizio: le sue `requirements` del manifest in `requirements.txt` con tutta
  la chiusura fissata. La chiusura per Android si calcola nella sandbox con `uv pip compile --python-version
  3.13 --python-platform aarch64-linux-android --only-binary :all: --find-links <wheel di wheels.py>
  --find-links <segnaposto>`, dove i segnaposto sono wheel vuote con nome, versione e dipendenze dei pacchetti
  che solo Chaquopy ha (numpy, pillow, cryptography, cffi, pycryptodome...). Poi si prova l'import di ogni
  servizio in un ambiente 3.13 con quegli stessi pacchetti. Dipendenze non dichiarate dai manifest esistono
  (WebDAV vuole `defusedxml`).
- **Le chiavi di Music Assistant** (`helpers/app_vars.py`: Client ID di Spotify, chiavi di Tidal, Qobuz, Last.fm,
  fanart.tv, TheAudioDB, AcoustID...) ci sono solo nelle sue build ufficiali (`app_secrets.json`, da un
  repository privato), e Music Assistant chiede esplicitamente di non prenderle da lì: mai. Nel pacchetto
  compilato dal sorgente `app_var()` dà "": Spotify rispondeva "client_id: not present" (segnalato dall'utente
  sul telefono, 0.1.42). Quindi via i servizi che senza chiave non vanno (Qobuz, Tidal: niente alternativa per
  la persona; fanart.tv e TheAudioDB, builtin, avrebbero fatto solo richieste rifiutate), i consigli di Last.fm
  non si aggiungono più da soli (`DEFAULT_PROVIDERS`; con la chiave Last.fm della persona si aggiungono a mano),
  e Spotify usa il Client ID della persona (sotto). Le configurazioni dei servizi tolti restano in
  `settings.json` e Music Assistant le ignora (provato con i dati di un server 2.10.8-1).
- **Spotify con il Client ID della persona** (`_spotify` in `prepare.py`, pacchetto 2.10.8-2, Xonos 0.1.43): il
  flusso chiede per primo il Client ID di un'app Spotify della persona (passo `xonos_client_id`, 32 caratteri
  esadecimali, salvato nei dati dell'account come `xonos_client_id` e riproposto quando si riconfigura), con
  Redirect URI `https://music-assistant.io/callback` (la pagina di Music Assistant che rimanda al server, qui
  `http://127.0.0.1:8095/setup_flow/callback/<flusso>`, che il browser del telefono raggiunge). Con quello entra e
  rinnova i token (`login()` del provider), con i limiti di una chiave propria (30 richieste ogni 30 s invece di
  1 ogni 2). Niente passo facoltativo per una chiave sviluppatore (è già quella), niente scelta del motore (solo
  librespot), e la seconda approvazione (librespot) sempre nel browser: un passo `xonos_playback` la spiega e
  mostra gli errori, poi il browser torna da solo a librespot su `127.0.0.1:5588` (atteso 5 minuti invece di 30 s);
  l'abbinamento dall'app Spotify non troverebbe Music Assistant, che sul telefono non si annuncia. I testi dei
  passi nuovi (e di "authenticate" e "playback_browser_open", che Music Assistant non traduce in italiano) sono in
  `server/ma/strings.json` nelle 9 lingue di Xonos, coi nomi di Music Assistant (`pt_PT`, `el_GR`, `ru_RU`,
  `zh_CN`); `_texts` li mette nei suoi `translations/*.json`. Verificato col server sul computer: il flusso
  attraverso l'API (primo passo, Client ID sbagliato, link di Spotify col Client ID e il ritorno giusto, testi in
  italiano), il flusso intero con la rete finta (anche riconfigura ed errore del browser) e il rinnovo col
  Client ID della persona; in CI (Server MA, ramo `claude/spotify-telefono`) sull'emulatore Android il primo passo
  `xonos_client_id` e il link di Spotify col Client ID (`check.py --spotify`), `librespot --version` del pacchetto
  ("librespot 0.8.0") e la release che suona dal server sul telefono. Non provato: l'accesso vero a Spotify, la
  pagina di ritorno di music-assistant.io (dalla sandbox non si raggiunge) e librespot con un account vero.
  Regole di Spotify per le app personali (febbraio 2026): Premium del proprietario, al massimo 5 utenti
  (aggiunti in User Management), un Client ID in Development Mode per persona, e meno API: niente playlist di
  Spotify stesso (Daily Mix...), top tracks degli artisti, novità; `GET /me` senza `product` né `country`
  (allora il controllo del Premium di Music Assistant si salta). Music Assistant 2.10.8 manda quelle chiamate
  alla "sessione globale", che qui è la chiave della persona: rispondono "non trovato" (400/403/404 diventano
  `MediaNotFoundError`), senza fermare il servizio.
- **Perché non suona** (pacchetto 2.10.8-3, Xonos 0.1.45): collegato Spotify, l'utente riceveva a ogni brano
  "There is nothing to play here." (`no_playable_items` di `queue_loader.play_media`): Music Assistant scarta il
  perché (un `MusicAssistantError` del servizio) e lo scrive solo nel suo registro. `_reasons` in `prepare.py` lo
  fa dire: `xonos_no_playable_items` col motivo come argomento ("There is nothing to play here: track not found in
  library: 999999"), `xonos_not_available` se il servizio non ha dato brani suonabili, e un errore `xonos_*` già
  spiegato passa com'è. Con un'app personale (Development Mode) Spotify dà i brani (`playlists/{id}/items`) solo
  delle playlist della persona (o collaborative): per le altre (seguite, di Spotify come Daily Mix) Music
  Assistant passa alla sua chiave, che qui non c'è; ora `xonos_playlist_not_yours` (owner `provider.spotify`) dice di
  copiare i brani in una propria playlist. Nella scheda del server sul telefono "Condividi il registro del
  server sul telefono" (`PhoneServer.log`: le ultime 96 KB di `musicassistant.log`, versioni in testa,
  condivisione di Android come testo). Prima diceva solo "del registro di Music Assistant" e l'utente, collegato a
  umbrel, mandava quello del telefono: dalla 0.1.48 per qualsiasi altro Music Assistant c'è nelle Impostazioni
  "Registro di Music Assistant" (sotto i servizi, solo admin), che lo prende dal server collegato con
  `logging/get` (scope `SYSTEM_MANAGE`; risponde con tutto il file, `Library.serverLog` tiene le ultime 96 KB da
  una riga intera; `LogCheckKt`) e dice di quale server è (il nome, o l'indirizzo se si chiama solo "Music Assistant"). Verificato: col server sul computer il motivo nel messaggio (`check.py`, anche in CI), con la rete finta
  il messaggio solo per le playlist non proprie (non per le proprie né per i brani piaciuti); il banco JVM compila.
  Poi l'utente: "qualsiasi brano non funziona". Un brano della libreria al play non passa da Spotify
  (`get_library_item_by_prov_id`, poi `[track]`): se non suona è la sua mappatura `available` falsa, cioè
  `is_playable` false da Spotify (`parsers.parse_track`), o il servizio non caricato (`available_providers`; per
  esempio dopo un INVALID_CREDENTIALS di librespot, che lo scarica). Con l'app della persona Spotify non dà più
  `country`/`product` dell'account: probabile che `is_playable` (con `market=from_token`) venga falso. Quindi
  (`_xonos_playable` in `parsers.py`) un brano "non suonabile" si prova comunque, con un avviso nel registro (i primi
  5), tranne `restrictions.reason == "explicit"` (il filtro della persona). Non scavalca i limiti per paese:
  librespot li controlla da sé col paese e il catalogo dell'account (`available_for_user` in `metadata`, poi
  "is not available"); il filtro degli espliciti invece con `--single-track` no, per questo resta qui. Al primo avvio
  del servizio `_xonos_playable_again` rimette `available = 1` alle tracce Spotify di quell'istanza in
  `provider_mappings`, tranne quelle esplicite (`json_extract(metadata, '$.explicit') = 1`: le rivede la
  sincronizzazione), e svuota la sua cache, solo quella volta (segno `.xonos-playable-<istanza>` nei dati); se non
  riesce lo scrive nel registro e il servizio parte lo stesso. E `xonos_service_down` dice quale servizio non è caricato.
  Provato col server sul computer (servizio dei file spento: "Filesystem (local disk) isn't working right now", anche
  in italiano), con la rete finta e col database vero di Music Assistant (`DatabaseConnection`). Da confermare col
  registro dell'utente.
- **Spotify**: il fork di librespot di Music Assistant (`music-assistant/librespot`, ramo `mass`, 0.8.0)
  compilato per Android in `server/librespot/build.sh` (Rust con cargo-ndk, rustls al posto di OpenSSL, senza
  uscite audio), nel pacchetto `bin/librespot`; `prepare.py` fa cercare a MA `librespot` nel PATH. Va solo con
  gli account Premium creati prima di dicembre 2024. Per gli altri c'è Soloist (sotto).
- **Soloist sul telefono** (Xonos 0.1.49, pacchetto 2.10.8-4; "strada B", voluta dall'utente: "deve funzionare per
  tutti"). Soloist è il motore ufficiale di Spotify (tutti gli account Premium; 1.3.10.31 a ottobre 2026), un
  programma per Linux con glibc che dà l'audio a PulseAudio (apre da sé `libpulse.so.0`, PipeWire se c'è) e fa da
  solo l'annuncio mDNS per l'abbinamento (niente Avahi). Non si ridistribuisce: lo scarica Music Assistant da
  Spotify (`soloist-builds.spotifycdn.com/soloist_release_{arm64|x86_64}.tar.gz`) dopo il consenso della persona,
  che mette anche una sua chiave API di Soloist; le build scadono dopo 90 giorni e MA le rinnova. Come gira:
  - **proot** (nell'APK, vedi sopra) fa da chroot con ptrace e gestisce il SIGSYS del seccomp di Android; il suo
    caricatore va avviato con execve, quindi deve stare tra le librerie dell'app (i file scaricati non si avviano).
    Su GrapheneOS il ptrace delle app installate è permesso di serie, salvo l'opzione che blocca il "native code
    debugging" per le app nuove: allora va permesso a Xonos.
  - **Il sistema Linux**: `server/package/rootfs.py` prende glibc 2.39, PulseAudio 16.1 (demone, pactl, pacat, moduli
    `native-protocol-unix` e `pipe-sink`) e libpulse dai pacchetti di Ubuntu 24.04 (firme controllate da apt) e
    tiene solo i file chiesti (NEEDED, come le cerca il caricatore): 38 pacchetti, 16,6 MB per arm64, 6,4 MB
    compressi. `make_payload.py` lo mette nel pacchetto come `rootfs.tar` (sempre uguale), con i pacchetti nel
    NOTICE e i loro `copyright` dentro. Ci sono `client.conf` e `daemon.conf` senza shm (Android non ha /dev/shm) e
    senza rtkit/D-Bus.
  - **xonos_server.py** (`_linux_programs`): estrae `rootfs.tar` la prima volta (tutto o niente, tarfile con
    `filter="data"`); i programmi `pulseaudio`, `pactl`, `pacat`, `parec` e quelli dentro la cartella dei dati
    (Soloist sta in `<dati>/soloist/soloist`) passano da `proot --kill-on-exit -r <rootfs> -b /dev -b /proc -b /sys
    -b <dati> -b <cache>` più `/etc/resolv.conf` (il file del DNS che Xonos aggiorna) e `/etc/machine-id`
    (`<dati>/machine-id`). Dati e cache allo stesso percorso, sia `/data/user/0/...` (come li usa MA) sia
    `/data/data/...` (il percorso vero: `/data/user/0` è un collegamento; con uno solo PulseAudio non trovava la sua
    configurazione): socket di PulseAudio, tubo del pipe sink, porta della WebSocket di Soloist restano gli stessi. Toglie dall'ambiente LD_LIBRARY_PATH, PYTHONHOME/PATH e
    SSL_CERT_FILE (i certificati del pacchetto non si vedono dentro). Se XONOS_PROOT o il sistema Linux mancano,
    toglie XONOS_PROOT e Spotify resta con librespot. Durante `soloist --pair` stampa `XONOS_MULTICAST on/off`.
  - **Fermarli** (pacchetto 2.10.8-5, Xonos 0.1.50): proot ignora SIGINT, SIGTERM e SIGHUP (`event_loop` in
    `tracee/event.c`: aspetta il ^C di un terminale, che va a tutto il gruppo di processi) e non usa
    PTRACE_O_EXITKILL: se muore, il programma che seguiva resta acceso, staccato. Music Assistant ferma Soloist e
    PulseAudio con `AsyncProcess.close()`: SIGINT al processo che ha avviato (proot), poi fino a cinque SIGKILL con
    `os.kill(pid, 9)`. Con la 0.1.49 sul telefono dell'utente (1° novembre 2026, Pixel 10 Pro, GrapheneOS, Android
    17): abbinamento riuscito e la musica partita (Soloist, PulseAudio e pipe sink vanno anche su arm64 vero), poi al
    secondo play "The Spotify Soloist daemon could not be stopped", la musica ferma e il telefono lentissimo (il
    lettore di `iter_stdout` tiene la sua serratura finché Soloist non esce: dopo 5 s close() rinuncia). Ora ogni
    proot parte in un gruppo suo (`start_new_session`, il numero del gruppo è quello di proot, in `_guest_groups`) e
    `os.kill` per un proot va a tutto il gruppo (`os.killpg`; l'os.kill vero resta in `os.kill.__wrapped__`): ci
    passano anche `send_signal`, `terminate` e `kill` di Popen e di asyncio. All'avvio `_end_leftovers` toglie proot
    e programmi Linux di un server di prima (riconosciuti dall'argv[0]: il percorso di proot, `/usr/bin/<programma>`,
    la cartella dei dati), alla fine `_end_guests` dà SIGKILL ai gruppi rimasti; tutti e due lo scrivono nel
    registro. Provato in locale con un finto proot in C che ignora gli stessi segnali: com'era SIGINT al solo proot
    lascia acceso il programma; con la correzione SIGINT, terminate e SIGKILL fermano tutto il gruppo, la pulizia
    toglie i rimasti. In CI (Server MA, emulatore x86_64, proot e Soloist veri): `proot-ignora-sigint` (SIGINT al
    solo proot, Soloist resta acceso: il guasto del telefono), `soloist-ferma` (AsyncProcess.close() con il lettore
    di iter_stdout, come in un brano: fermo in 0,0 s, codice 0, nessun processo rimasto), `pulseaudio-ferma`
    (nessuno rimasto dopo release()); `cpu-audio`, 10 s di rumore con pacat letti dal tubo a 1,1 volte il tempo
    reale: PulseAudio 1%, proot 1%, pacat 0% (3,87 MB letti su 3,53 MB del tempo reale).
  - **Il processore nel registro**: ogni minuto (`CPU_EVERY_S`), finché gira un programma Linux, una riga
    "Processore dei programmi Linux negli ultimi 60 s: soloist N%, proot N%, pulseaudio N%" (100% = un core, i nomi
    dall'argv[0] di /proc; gli zombie non contano), così dal registro di una persona si vede quanto pesa proot. Sul
    telefono dell'utente (0.1.50, Soloist che suona): soloist 16%, proot 14% (2 processi), pulseaudio 13%. Dal
    pacchetto 2.10.8-6 si guarda ogni 5 s (`CPU_SAMPLE_S`): con un'occhiata al minuto il Soloist di un brano finito
    prima della riga non compariva; "(N processi)" è quanti ne giravano insieme al massimo. Dal 2.10.8-6 ogni proot porta il
    nome del programma che segue (`proot[soloist]`, `proot[pulseaudio]`, dopo `-w <cartella>` nella sua riga di comando). Il proot di Termux
    intercetta anche `sendto`, `recvfrom`, `sendmsg`, `recvmsg`, `close`, `ioctl`, `brk` (`proot_sysnums` in
    `syscall/seccomp.c`): la rete di Soloist e il protocollo di PulseAudio passano tutti da lui, da qui il suo peso.
    In CI la riga non compare (le prove con i programmi Linux durano 21 s, prima del primo minuto): provata in
    locale; `_processes` e `SC_CLK_TCK` sull'emulatore li usa anche `cpu-audio`.
  - **prepare.py** (`_soloist`): `_resolve_architecture` accetta Android quando c'è XONOS_PROOT; il flusso di Spotify
    mostra la scelta del motore solo allora (sennò librespot); `PAVolumeController` comanda PulseAudio con `pactl`
    (stessa interfaccia: load/unload-module, set-sink-volume) perché la libpulse del sistema Linux è di glibc e il
    Python del pacchetto no.
  - **PhoneServer**: XONOS_PROOT e XONOS_PROOT_LOADER dalla cartella delle librerie, XONOS_DNS_FILE
    (`<dati>/resolv.conf`: i DNS della rete in uso più 1.1.1.1 e 8.8.8.8, riscritto con un NetworkCallback), il
    MulticastLock del Wi-Fi solo tra `XONOS_MULTICAST on` e `off` (permesso CHANGE_WIFI_MULTICAST_STATE).
  - **Verificato in CI** (emulatore Android 14 x86_64): workflow "Soloist" (`tools/soloist`, ExecCheckActivity della
    build di prova: comandi dentro il processo di Xonos) con proot dal sorgente: glibc, `soloist --version`, PulseAudio
    in 0,1 s, pipe sink, audio nel tubo, Soloist acceso con una chiave finta fino a "waiting for login — connect to
    … from your Spotify app", nessun SIGSYS; la rete da dentro proot (DNS, http verso Spotify). "Server MA" con
    `xonos_selftest.py` (parte se c'è `<dati>/xonos-selftest`, aspetta `xonos-selftest-go`; dal 2.10.8-5 11 prove,
    tutte passate: anche `proot-ignora-sigint`, `soloist-ferma` con AsyncProcess come in un brano, `cpu-audio` con
    10 s di audio a tempo reale e `pulseaudio-ferma`): Music Assistant scarica Soloist e lo prova, PulseAudio e pipe sink col suo codice, audio nel tubo,
    Soloist acceso, e lo si cerca come l'app Spotify: risponde all'mDNS (`_spotify-connect._tcp.local`: PTR, SRV con
    la porta, TXT `CPath=/zc`, A con gli indirizzi del telefono, in multicast al gruppo, non con risposte dirette) e
    la sua pagina getInfo risponde su 127.0.0.1 e sull'indirizzo di rete. `tools/soloist/ifaddrs-test.c`: dentro
    proot, dall'app, glibc vede le interfacce (getifaddrs, netlink anche con bind e RTM_GETLINK, SIOCGIFCONF) su
    Android 14. **Non provato**: l'abbinamento vero dall'app Spotify, la riproduzione con un account e una chiave
    veri, arm64 su un telefono vero (Android 17, GrapheneOS: limiti di rete e ptrace forse diversi), la batteria.
- **Gli account recenti** (registro dell'utente, 29 ottobre 2026, pacchetto 2.10.8-3): accesso a Spotify riuscito,
  poi al primo brano librespot `Unable to load audio item: ... FaultyRequest(INVALID_CREDENTIALS)` e Music
  Assistant scarica il servizio ("Spotify is not loaded" ai play dopo). Music Assistant scrive che librespot non va
  con gli account creati da dicembre 2024; Spotify un giorno preciso non l'ha mai dato (un altro progetto parla
  di "account degli ultimi due anni" da dicembre 2025, non confermato). Per questo, col server sul telefono, il
  flusso di Spotify mostra in cima l'avviso `spotify_old_accounts` (`PhoneModel.onPhoneServer`: l'indirizzo
  salvato è quello del server; con un altro Music Assistant no), e il README lo dice in ogni lingua. Dalla 0.1.49
  l'avviso spiega i due motori e cosa serve a Soloist (chiave API, "Music Assistant Pairing" dall'app Spotify, Wi-Fi).
  Scena `spotify-phone` in `tools/screens`.
- I task Gradle di ffmpeg e librespot **non dichiarano uscite**: alla prima esecuzione Gradle svuota le
  cartelle di uscita dei task, e con la cache di GitHub lo script trovava il suo segno (fuori dalla cartella)
  e non ricompilava, così ffmpeg mancava. Gli script controllano anche che i programmi ci siano.
- **Nell'app** (`PhoneModel`, `SettingsScreen.PhoneServerCard`): finché il pacchetto non c'è, il tasto apre la
  domanda (peso dal HEAD, "qualche decina di MB" se non si sa); "Scarica" chiede anche il permesso per la musica
  del telefono e mostra l'avanzamento con "Annulla"; a download finito Xonos si collega da solo
  (`usePhoneServer`): avvia il server, la prima volta crea l'account `xonos` (amministratore) con una password
  che Xonos tiene in `Settings` (per rientrare se perde il token), poi token e prova della connessione come
  per ogni Music Assistant. Scaricato: "Usa Music Assistant su questo telefono" e "Togli dal telefono" (con
  conferma); in uso: "Usa un altro Music Assistant". In uso ma senza il pacchetto di questa versione (Xonos
  aggiornato, il server non parte) dalla 0.1.51 la scheda dice di aggiornarlo e ha "Aggiorna Music Assistant su
  questo telefono" (fino alla 0.1.50 il tasto compariva solo dopo "Usa un altro Music Assistant", e l'utente non lo
  trovava), e la Home lo dice nel `ConnectionBanner`, con un tasto per le Impostazioni. I dati restano. `XonosApp` riavvia il server all'avvio e quando la
  connessione cade, se l'indirizzo salvato è il suo e il pacchetto c'è. `PhoneServerControl` è l'interfaccia
  senza Android: `PhoneServerCheckKt` (banco JVM) la prova con un download finto contro il server avviato sul
  computer.
- **La musica del telefono**: l'app dichiara `READ_MEDIA_AUDIO` (`READ_EXTERNAL_STORAGE` fino ad Android 12) e
  lo chiede al sì del download (`Host.askMusicFiles`); il servizio dei file sul disco (Filesystem (local
  disk)) propone `/storage/emulated/0/Music` invece di `/media` (`prepare.py`).
- **ABI**: `xonosServerAbis` in `gradle.properties` (arm64-v8a e x86_64, quella dell'emulatore delle prove).
  La release pubblica solo il pacchetto arm64-v8a.
- **Le prove** (workflow "Server MA", `tools/server/emulator.sh`, a ogni cambio all'app su un ramo `claude/`):
  il workflow crea una chiave usa e getta (RSA 2048) che firma le due app e il pacchetto x86_64 con gli stessi
  comandi della release (sul runner la chiave di debug di Android non è in `~/.android`), e un piccolo server
  HTTP sul runner serve il pacchetto (l'emulatore lo vede a `10.0.2.2:8000`). 1. il server da solo:
  `ServerCheckActivity` (solo debug) lo scarica e lo avvia, poi `tools/server/check.py` lo prova da fuori
  (con `--spotify` anche il primo tratto del flusso di Spotify; e che "nothing to play" dica perché), FFmpeg con
  https e `librespot --version`;
  2. la build di prova con `ServerCheckActivity --ez xonos true` (cartella come servizio, primo brano dal
  lettore di Xonos); 3. la release ridotta con R8, da capo come la usa una persona: uiautomator tocca il tasto e
  "Scarica" (i testTag sono resource-id; il dialogo è una finestra a sé e vuole `automationIds()` anche lui) e
  "Consenti" alla domanda di Android (animazioni spente, sennò uiautomator non legge lo schermo), i toni nella
  cartella Music del telefono, indirizzo del pacchetto scritto e token e lettore letti con root
  (l'emulatore google_apis lo permette), poi `check.py --player` suona sul lettore di Xonos e guarda dalla sua
  coda che suoni. Sull'emulatore sotto carico è comparso «Pixel Launcher isn't responding», che copriva tutto:
  ora i dialoghi d'errore sono nascosti (`hide_error_dialogs`) e, se uno compare, lo script tocca «Attendi».
  Lanciato a mano con `arm64`, il workflow costruisce dopo le prove anche il pacchetto per i telefoni, per
  vederne peso, allineamento e NOTICE (`payload-arm64.txt`, `NOTICE-arm64.txt` tra i risultati).
- **Licenze del pacchetto**: dentro ci sono parti con licenze diverse (Music Assistant Apache 2.0, Python PSF,
  FFmpeg LGPL 3, Mbed TLS, OpenSSL, librespot MIT, pacchetti come mutagen GPL 2 e chardet LGPL). Per questo
  `make_payload.py` scrive `NOTICE.txt` (ogni parte, la sua licenza, dove sta il sorgente; i pacchetti Python
  dai loro METADATA) e mette in `licenses/` i testi (`server/package/licenses/`, presi da
  `/usr/share/common-licenses`, da Python e dai repository di Chaquopy, librespot e Mbed TLS, più la licenza di
  Xonos). Se in `lib/` compare una libreria senza voce, lo dice nel log.
- **Pagine da 16 KB**: i telefoni con Android 15 e successivi possono avere pagine di memoria da 16 KB, e lì si
  caricano solo programmi e librerie con i segmenti allineati a 16 KB. `make_payload.py` elenca quelli che non
  lo sono (`ELF: N, allineati a meno di 16 KB: M`); `python3` si compila con `-z max-page-size=16384`. Per arm64
  (0.1.42): su 153 ne resta uno solo, `site-packages/chaquopy/lib/libfreetype.so` (il pacchetto chaquopy-freetype
  di Chaquopy, per Pillow). Music Assistant usa FreeType solo per il testo sulle copertine generate delle
  playlist (Playlist Metadata): `prepare.py` lo fa ripiegare sul carattere a punti di Pillow se FreeType non si
  carica (provato con Pillow 11.0.0 togliendo FreeType). Per x86_64 (solo l'emulatore) quattro librerie di Chaquopy.

## L'app sul telefono

- Lo stato è in `PhoneModel` (valori osservabili di Compose, runtime 1.5 sulla JVM): vive in
  `XonosApp.phone`, così sopravvive a rotazione e cambio di lingua (`recreate()`). Tre schede (Home,
  Cerca, Libreria) con la loro pila di pagine; il lettore a schermo intero sta sopra tutto, e la sua
  vista (copertina, testo, coda) è nel modello.
- Music Assistant risponde a `play_media` solo quando la musica parte (con Soloist circa 7 s sul telefono
  dell'utente): `PhoneModel.starting` accende subito la rotella al posto di play (o `StartingBar` se prima non
  suonava niente) e lo stesso "suona" toccato di nuovo nel frattempo non parte una seconda volta (con Soloist
  rifarebbe tutto da capo). Dalla 0.1.51.
- Si suona con `Library.playMedia` sulla coda del telefono (id del lettore), dopo aver aspettato
  `XonosApp.playerReady`, che il servizio accende quando API e Sendspin sono pronti e Music Assistant ha la
  coda del telefono (`player_queues/get` con `available`, al massimo 10 s): MA registra il lettore un attimo
  dopo il saluto di Sendspin, e col server sul telefono il primo play arrivava prima ("The player is not
  available"). Stato e comandi del
  lettore passano dal MediaController del servizio: `MaPlayer` mette negli extra dei metadati uri, tipo,
  album e artista del brano (`MaPlayer.EXTRA_*`).
- Le copertine sul telefono: indirizzi http dell'imageproxy con Coil (OkHttp dell'app, con timeout);
  quelle del lettore sono i `content://` di `Artwork`. Al cambio di brano la stessa copertina la chiedono
  insieme notifica, mini-lettore (copertina e colore) e auto: fino alla 0.1.37 i download si pestavano
  i piedi e una richiesta su tre restava senza (`ArtCheckKt`); ora c'è `ArtCache`. Il colore di fondo si calcola dai pixel della
  copertina (`backdropColor`). Le icone sono disegnate in `Icons.kt` (la libreria di icone pesa megabyte).
- La Home si aggiorna trascinando in giù, e da sola riaprendo l'app se ha più di dieci minuti. Home e
  prime pagine della Libreria si mostrano subito da `ScreenCache`, poi si aggiornano.
- Gli elenchi lunghi si convertono a pezzi mentre arrivano, fuori dal thread principale
  (`MaClient.items`): la "Playlist grande" di prova (2880 brani) è ~14 MB di JSON, come MaItem ~1 MB.
  I pezzi (500) si mostrano subito (`onPart`): playlist e podcast con la rotella in fondo, l'artista prima
  con la libreria e poi con ogni servizio. La ricerca mostra subito i risultati della sola libreria
  (`library_only`), poi quelli di tutti i servizi. I testi: dopo 8 s "nessun testo", poi se arriva si mostra.
- Playlist: "Aggiungi a una playlist" (brani, album, playlist; anche una nuova), "Togli" nelle playlist
  modificabili della libreria, "Elimina" per quelle di MA. Dopo una modifica i brani si chiedono senza
  cache per 10 minuti. Coda: "Suona dopo" e "Togli dalla coda" dai tre puntini dei brani in arrivo;
  `MaPlayer` le riceve come modifiche di Media3 (`COMMAND_CHANGE_MEDIA_ITEMS`) e mette negli extra del
  brano in corso da dove la coda si può cambiare (`EXTRA_EDITABLE_FROM`).
- Impostazioni: i server trovati in rete, accesso con nome utente e password (o token), primo account.
  In fondo le donazioni in bitcoin (`DONATION_ADDRESS` in `SettingsScreen.kt`, lo stesso indirizzo del
  README e del campo `Bitcoin` della ricetta di F-Droid): "Copia" e "Apri nel portafoglio" (`bitcoin:`;
  senza un portafoglio installato copia). La copia negli appunti è in `Platform.kt` (`rememberCopy`).
  I servizi di MA solo per gli admin (Impostazioni, e la Home vuota).
- "Suona su": `PhoneModel.remote` è il lettore di MA comandato dallo schermo (null: il telefono); "suona"
  va lì. In auto non cambia niente: il menu suona sempre sulla coda del telefono. Se il telefono comincia
  a suonare da sé, lo schermo torna al telefono (`phonePlaying`). La scelta non si salva.
- Ciò che sta fuori dallo `Scaffold` (lettore, benvenuto) ha bisogno della `Surface` della radice
  sopra, altrimenti `LocalContentColor` è nero.

## Correzioni della notte (1–2 novembre 2026, ramo `claude/notte`)

Da una caccia ai bug con più agenti (8 aree, un giudice avversario per area) e verificate nel codice:
- `MaPlayer.forgetDrop` + `AudioDeviceCallback` in `PlaybackService`: un'uscita esterna (Bluetooth, cuffie) che si
  stacca a musica già ferma (rete caduta) annulla la ripresa automatica, che durava 10 minuti e sarebbe uscita
  dall'altoparlante.
- Dall'auto una scelta arriva con un play subito dopo: `player_queues/play` su una coda non in pausa per Music
  Assistant è un `resume` che rifà partire il brano (Soloist: altri 7 s). `MaPlayer.choosing`: durante la scelta il
  play si ignora, una pausa si mette appena la musica parte. Sul telefono il tasto play è spento mentre parte.
- Server sul telefono: al cambio di rete niente `repo.reconnect()` (127.0.0.1 non cambia; ricollegarsi fermava la
  coda) e margine Sendspin del Wi-Fi anche in rete mobile (`PlaybackService.onPhoneServer`).
- `SendspinClient.start`: se il ciclo sta solo aspettando il prossimo tentativo (fino a 30 s), riparte subito.
- `MaPlayer.stateItems` si aggiorna subito a ogni "togli"/"sposta": la modifica dopo arriva con le posizioni del
  segnaposto di Media3.
- Wake lock anche mentre la coda deve suonare ma l'audio non esce ancora (al massimo 2 minuti, solo processore):
  in auto a schermo spento la preparazione di Soloist si sarebbe fermata.
- `AudioEngine.decode`: un decoder guasto si sostituisce (al massimo una volta al secondo).
- Token: Music Assistant 2.10 fa durare un anno i token a lunga durata, senza rinnovo (2.7–2.9: dieci anni).
  `TokenInfo` legge il JWT (`exp`, `is_long_lived`, `token_name`); collegato a meno di 30 giorni dalla scadenza
  `MaRepository.renewToken` se ne fa dare uno nuovo con lo stesso nome e lo salva (il vecchio resta valido: revocarlo
  chiuderebbe anche questa connessione). Col server sul telefono, su `BAD_TOKEN` `XonosApp` rientra con
  `phoneServerPassword`. `TokenCheckKt` (contro la 2.10.8 in Docker).
- `MaClient`: per gli elenchi a pezzi il tempo concesso conta dall'ultimo pezzo (`lastPart`), e i pezzi di una
  richiesta già lasciata si buttano. `SlowListCheckKt` con `tools/harness/slow_ma.py` (finto MA a pezzi lenti).
- Fuori casa: con un indirizzo remoto, quello di casa ha 3 s per aprire la connessione (`homeHttp`, prima 8 s);
  `MaPlayer.handlePrepare` si ricollega solo se il collegamento è fallito. `HomeCheckKt`: 3,1 s.
- `AudioEngine`: prima del primo tempo vero dell'uscita (`timed`, al massimo `UNTIMED_GRACE_US` = 5 s) un ritardo
  stimato non fa saltare niente, e quello che il primo tempo vero rivela si tratta come all'avvio (`lateUs`). Un
  Bluetooth sospeso (autoradio dopo una pausa) faceva saltare 240–300 ms subito dopo l'inizio: `StartCheckKt`, caso
  "Bluetooth dell'auto lento a ripartire" (uscita ferma 1,5 s), ora niente saltato con Android 11 e 12.
- Telefono: avvisi sopra il lettore a schermo intero, errori 10 s con la ×; benvenuto nel modello
  (`welcomePage`, regge a rotazione e lingua); "Togli dal telefono" guarda le impostazioni salvate; la notifica apre
  il lettore solo alla prima `onCreate`; `RemoteLink` rilegge la coda della cassa a ogni collegamento; Casuale,
  Ripeti, Testo e Coda sono `IconToggleButton` (TalkBack); "Suona su" non c'è col server sul telefono
  (`canPlayElsewhere`); un server trovato in rete diverso da quello salvato non riceve il token (`choose`).
- Server sul telefono, app: download uno alla volta (`Mutex`), l'estrazione si ferma ad "Annulla", a download finito
  "Controllo e preparo Music Assistant…"; se non parte la Home dice perché (`phoneServerFailure`) con "Riprova", e
  `XonosApp` smette di riavviarlo dopo 3 avvii falliti di fila (`AUTO_STARTS`; prima ogni 20 s per sempre); si ferma
  quando si passa a un altro Music Assistant; dopo l'uscita di Python Xonos chiude il tubo (2 s) e lo stato di un
  processo vecchio non tocca quello nuovo; `stop()` manda un SIGKILL vero con `Os.kill` (il pid da
  `Process.toString()`, "Process[pid=…": su Android `destroyForcibly()` è un altro SIGTERM). Android 10:
  `requestLegacyExternalStorage`.
- Server sul telefono, pacchetto 2.10.8-6: le righe per Xonos con una write sola (`_line`: con `-u`/PYTHONUNBUFFERED
  `print()` scrive testo e a capo in due volte, e una riga del registro finiva in mezzo: in locale 5608 righe
  rovinate su 20000, poi nessuna; Xonos restava 180 s su "Avvio…"); l'uscita dei programmi Linux che nessuno legge va
  a /dev/null (PulseAudio la ereditava e teneva aperto il tubo dopo Python); il flusso di Spotify ripete il passo
  finale con l'errore (`xonos_finish_retry`) invece di chiudersi e cancellare l'abbinamento di Soloist; all'avvio una
  prova di ptrace (`_ptrace_refused`: proot con `pactl --version`, cerca "ptrace" nell'errore, il messaggio di
  `tracee/event.c` è "ptrace(TRACEME)"): se negato `XONOS_PROOT_ERROR=ptrace`, niente Soloist (prima lo scaricava e
  buttava a ogni tentativo), riga nel registro e passo `xonos_soloist_blocked` nel flusso.
- Auto: menu e ricerca aspettano il collegamento (`awaitConnected`, 20 s) e se non arriva, collegati, si chiede
  all'auto di rileggere la radice (`notifyChildrenChanged`); col server sul telefono l'errore dice che parte, va
  aggiornato o perché non è partito (`phoneServerProblem`); gli errori di play e dei comandi vanno alla sessione
  (`MaPlayer.onFailure` → `MediaSession.sendError`, Media3 1.11, `@UnstableApi`).
- Play dopo una pausa (Soloist 7 s): Music Assistant risponde a `player_queues/play` solo quando la musica parte.
  `getPlaceholderState` dà BUFFERING finché un play aspetta (rotella invece di "sta suonando"), i 3 s di attesa
  dell'effetto contano dalla risposta (`COMMAND_CAP_MS` 60 s in ogni caso), un secondo play mentre parte non manda
  niente, una pausa mentre parte manda `stop` (in Music Assistant `_handle_stop` aspetta la fine dell'avvio, `pause`
  no). La ripresa dopo un calo di rete non parte se un'uscita esterna si è staccata dopo l'ultima musica
  (`outputGoneAt`) o se il server è cambiato (`Drop.server`).
- Proposte non fatte: PulseAudio senza shm né memfd (`rootfs.py`: `enable-shm = no`, `enable-memfd = no`) non apre lo
  srbchannel, e ogni pacchetto audio tra Soloist e PulseAudio passa da sendto/recvmsg, che proot intercetta: con memfd
  (che non usa /dev/shm) il peso di proot (14% sul telefono) potrebbe scendere. Da provare sul telefono prima di
  pubblicarlo. Il backup di Android porta con sé token e dati di Music Assistant (nessun `allowBackup`): da decidere.

## Lingue

- `Language.kt`: en (predefinita), it, es, de, fr, pt, el, ru, zh-CN. La scelta vale per la schermata
  (attachBaseContext) e per il menu in auto (`ResourceLabels`).
- Testi in `res/values*/strings.xml`: stesse chiavi in ogni lingua, plurali con le forme giuste
  (russo one/few/many/other, cinese solo other). Dopo ogni modifica: `python3 tools/check_strings.py`.

## Come si verifica (senza telefono)

Vedi `tools/README.md`: un Music Assistant vero in Docker (album con copertine e testi, 720 brani, un
podcast RSS e un finto servizio di ricerca podcast) e un banco di prova JVM che compila le parti
dell'app senza Android (data, sendspin, Menu, phone/model) e fa le stesse cose del telefono e di
Gearslip (`PhoneCheckKt` per l'app del telefono). La parte Android si verifica solo compilando su GitHub.

L'aspetto si guarda con `tools/screens`: Compose Desktop disegna in PNG le stesse composable con dati
finti. Compose Desktop dalla 1.6 vuole librerie androidx che stanno solo sul Maven di Google: il workflow
"Schermate" le mette, con le PNG e il log, nel riferimento nascosto `refs/xonos/screens` (non è un ramo).
Copiate in `tools/screens/libs`, si disegna anche qui in una ventina di secondi. Le schermate del README
(`docs/*.jpg`) vengono da lì, in inglese. L'utente giudica comunque sul telefono (caratteri, densità,
gesti e animazioni sono diversi).

Nella sandbox: Docker Hub risponde 429, si usa `mirror.gcr.io` (vedi `tools/README.md`); se il lavoro
si riavvia, il demone Docker si ferma e i container `ma` e `pods` vanno riavviati (`docker start`).
Altre prove del banco: `ServicesCheckKt` (con il servizio finto `tools/ma-test/xonos_test`),
`PlayersCheckKt` (una cassa finta, anche la sua coda), `AccountCheckKt`, `PerfCheckKt`, `PlaylistCheckKt`,
`SearchCheckKt`, `ArtCheckKt`, `StartCheckKt` (l'avvio dell'audio senza server).

Le misure sull'emulatore: il workflow "Misure" parte con `[misure]` nel messaggio del commit (con
`[profilo]` genera anche il Baseline Profile) o a mano; costruisce MA in Docker sul runner, l'app
release (R8) e le prove di `baselineprofile/`, e mette tabella, log e tracce in
`refs/xonos/measures/<ramo con - al posto di />`. Sull'emulatore (senza GPU) contano i confronti, non
i valori assoluti; le prove usano gli id dei testTag (`Modifier.automationIds()`).
- Si confrontano solo numeri della stessa esecuzione: tra esecuzioni diverse cambiano la macchina di
  GitHub (l'avvio è variato del 30% con lo stesso codice) e, se si toccano le prove, la lunghezza dei
  gesti. Per un prima/dopo vero servirebbe misurare le due versioni nello stesso job (non c'è ancora).
- Risultati che reggono (ottobre 2026, playlist di 2880 brani): conversione sul thread principale da
  130 ms a 0, heap massimo da 163 a 83 MB, memoria anonima da 256 a 174 MB, lettura del JSON da 2,1 a
  1,5 s. L'APK con R8 parte, si collega e suona Opus, nessun arresto. Avvio, scorrimento e lettore non
  hanno un miglioramento dimostrato sull'emulatore; con e senza Baseline Profile la differenza è nel rumore.
- Mentre Xonos suona, il servizio in primo piano tiene vivo il processo: la prova d'avvio a freddo a
  volte si ferma con "must not be running prior to cold start" (andrebbe fermata la musica prima).

## Richieste dell'utente

Fatte: app sul telefono con grafica moderna (riproduzione, mini-lettore, libreria, ricerca), testi
sincronizzati sul telefono, fluidità (R8, Baseline Profile, elenchi fuori dal thread principale, meno
ricomposizioni, schermate salvate), server trovati in rete e accesso con nome utente e password,
gestione dei servizi di MA per gli admin, donazioni in bitcoin, "Suona su" con passaggio della coda, playlist
modificabili (aggiungi, togli, nuova, elimina) e coda modificabile (suona dopo, togli), Music Assistant sul
telefono stesso scaricato su richiesta ("Fai diventare Xonos il tuo server Music Assistant", da provare sul telefono),
Spotify per tutti gli account Premium su quel server: Soloist dentro proot (0.1.49; sul telefono dell'utente
l'abbinamento riesce e la musica parte, ma Soloist non si fermava: corretto nella 0.1.50, da riprovare sul telefono).
Proposto, non fatto: cercare dal telefono con mDNS/SSDP le casse che MA non vede e suggerire il
servizio da aggiungere. Ancora da fare:

3. Cali di rete: almeno 3 brani coperti (per poter saltare in galleria) e 10 minuti di podcast.
   Per i podcast si può (episodi scaricati dall'indirizzo diretto). Per la musica in streaming il
   limite è il server (30 s): serve una modifica a Music Assistant/aiosendspin, da proporre a monte;
   per la musica dei server propri (Jellyfin, Plex, Subsonic) si può scaricando direttamente da lì.
4. Offline: podcast e librerie proprie; per le playlist di Spotify, scaricare le copie dei brani
   presenti nella libreria propria dell'utente. Niente copie dai servizi in streaming.
5. Coda e playlist: fatto (vedi sopra). Si potrebbe aggiungere il riordino trascinando.
