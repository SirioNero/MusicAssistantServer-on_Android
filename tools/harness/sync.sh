#!/bin/sh
# Copia nel banco di prova i file dell'app che girano anche sulla JVM.
SRC="$(cd "$(dirname "$0")/../../app/src/main/java/it/xonos/ma" && pwd)"
DST=$(dirname "$0")/src/main/kotlin/app
rm -rf "$DST" && mkdir -p "$DST"
for f in Language.kt data/Model.kt data/Lyrics.kt data/MaClient.kt data/MaRepository.kt data/Library.kt data/Cache.kt data/Account.kt data/Services.kt data/SkipSender.kt data/SkipTrace.kt data/SoloistUnlock.kt data/ArtCache.kt data/PlaylistRemover.kt data/TokenInfo.kt playback/Menu.kt playback/SkipPlan.kt \
  sendspin/TimeFilter.kt sendspin/AudioEngine.kt sendspin/SendspinClient.kt \
  phone/model/Player.kt phone/model/Pages.kt phone/model/PhoneModel.kt phone/model/ConnectionCheck.kt phone/model/Discovery.kt phone/model/ServicePages.kt phone/model/ServicesModel.kt phone/model/RemoteLink.kt phone/model/PhoneServerControl.kt data/Devices.kt "$@"; do
  mkdir -p "$DST/$(dirname $f)"; cp "$SRC/$f" "$DST/$f"
done
