#!/bin/sh
# Copia l'interfaccia del telefono (e ciò che le serve) dall'app, con i sostituti del banco di prova
# per Android, e scrive la classe R dagli strings.xml. Da rifare dopo ogni modifica all'app.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../app/src/main/java/it/xonos/ma"
DST="$HERE/src/main/kotlin/app"
rm -rf "$DST" && mkdir -p "$DST"
for f in Language.kt data/Model.kt data/Lyrics.kt data/MaClient.kt data/MaRepository.kt data/Library.kt data/Cache.kt data/Account.kt data/Services.kt data/SkipSender.kt data/SoloistUnlock.kt data/PlaylistRemover.kt data/TokenInfo.kt playback/Menu.kt \
  phone/model/Player.kt phone/model/Pages.kt phone/model/PhoneModel.kt phone/model/ConnectionCheck.kt phone/model/Discovery.kt phone/model/ServicePages.kt phone/model/ServicesModel.kt phone/model/RemoteLink.kt phone/model/PhoneServerControl.kt data/Devices.kt; do
  mkdir -p "$DST/$(dirname $f)"; cp "$SRC/$f" "$DST/$f"
done
# L'interfaccia tutta, tranne Platform.kt: quello per la JVM sta in src/main/kotlin/it/xonos/ma/phone/ui.
mkdir -p "$DST/phone/ui"
for f in "$SRC"/phone/ui/*.kt; do
  [ "$(basename "$f")" = Platform.kt ] || cp "$f" "$DST/phone/ui/"
done
# I sostituti di Log, Trace, LruCache e Settings sono quelli del banco di prova.
mkdir -p "$DST/stubs"
cp -r "$HERE/../harness/src/main/kotlin/android" "$DST/stubs/"
cp "$HERE/../harness/src/main/kotlin/stubs/Settings.kt" "$DST/stubs/"
python3 "$HERE/gen_r.py" "$HERE/../../app/src/main/res" "$HERE/src/main/kotlin/generated"
