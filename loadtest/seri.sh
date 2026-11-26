#!/usr/bin/env bash
# Koşu serisi: her koşudan önce ana makinede Android emülatörü varsa bitmesini bekler (ölçüm kirlenmesin).
# Kullanım: bash loadtest/seri.sh <etiket> [ORTAM=DEĞER ...] [-- <etiket> ...]   (her koşu ';' ile ayrı satırda da verilebilir)
# Örnek:    bash loadtest/seri.sh "b-3kat-30 OFFICERS=30 DURATION=600" "c-10kat-100 OFFICERS=100 DURATION=600"
set -u
wait_clean() {
  local n=0
  while tasklist 2>/dev/null | grep -qiE "qemu-system|emulator\.exe"; do
    [ $n -eq 0 ] && echo "[seri] emülatör açık, bekleniyor..."
    n=$((n + 1)); sleep 15
  done
}
for spec in "$@"; do
  wait_clean
  echo "[seri] $(date +%H:%M:%S) başla: $spec"
  # shellcheck disable=SC2086
  bash loadtest/run.sh $spec > /dev/null 2>&1
  echo "[seri] $(date +%H:%M:%S) bitti: ${spec%% *}"
done
