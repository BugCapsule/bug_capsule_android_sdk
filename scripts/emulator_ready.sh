#!/bin/sh
# Waits until the emulator has fully booted and its settings service
# answers, then turns animations off (for Espresso). Used by the emulator
# job in release-gates.yml.
set -eu
adb wait-for-device
i=0
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  i=$((i + 1)); [ "$i" -gt 180 ] && { echo "emulator did not finish booting"; exit 1; }
  sleep 2
done
for key in window_animation_scale transition_animation_scale animator_duration_scale; do
  n=0
  until adb shell settings put global "$key" 0 >/dev/null 2>&1; do
    n=$((n + 1)); [ "$n" -gt 30 ] && { echo "settings service not answering"; exit 1; }
    sleep 2
  done
done
echo "emulator ready"
