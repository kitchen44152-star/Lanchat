#!/bin/bash
# Emulator smoke test, step 2: two emulators, onboarding, main screens, Nearby discovery.
PKG=com.aistudio.lanchat.mshxqr
OUT=emu-out2; mkdir -p $OUT /tmp/apk
S1=emulator-5554; S2=emulator-5556
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin
echo "[$(date +%T)] sdk setup"; ls $ANDROID_HOME $ANDROID_HOME/emulator 2>&1 | head -20
yes | sdkmanager --licenses > $OUT/lic.log 2>&1
timeout 600 sdkmanager "emulator" "platform-tools" "system-images;android-34;google_apis;x86_64" > $OUT/sdk.log 2>&1; echo "sdkmanager rc=$?"; tail -3 $OUT/sdk.log
echo "[$(date +%T)] create avds"
for n in a b; do echo no | avdmanager create avd -n emu$n -k "system-images;android-34;google_apis;x86_64" -d pixel_6 --force >/dev/null; done
nohup emulator -avd emua -port 5554 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on -memory 2048 > $OUT/emua.log 2>&1 &
nohup emulator -avd emub -port 5556 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on -memory 2048 > $OUT/emub.log 2>&1 &
for s in $S1 $S2; do
  echo "[$(date +%T)] waiting for $s"
  timeout 300 adb -s $s wait-for-device; echo "wait rc=$?"
  for i in $(seq 1 72); do [ "$(adb -s $s shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 5; done
  echo "$s booted: $(adb -s $s shell getprop sys.boot_completed)" | tee -a $OUT/boot.txt
  adb -s $s shell settings put global window_animation_scale 0
  adb -s $s shell settings put global transition_animation_scale 0
  adb -s $s shell settings put global animator_duration_scale 0
done
adb devices | tee $OUT/devices.txt
tail -5 $OUT/emua.log $OUT/emub.log
echo "[$(date +%T)] installing"
gh release download v0.1.0 --repo "$GITHUB_REPOSITORY" --pattern '*.apk' --dir /tmp/apk
APK=$(ls /tmp/apk/*.apk | head -1)
adb -s $S1 shell dumpsys package com.google.android.gms | grep versionName | head -1 | tee $OUT/gmsver.txt
for s in $S1 $S2; do
  adb -s $s install -r "$APK" | tee -a $OUT/install.txt
  for p in BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES CAMERA RECORD_AUDIO POST_NOTIFICATIONS ACCESS_FINE_LOCATION; do adb -s $s shell pm grant $PKG android.permission.$p; done
  adb -s $s shell svc wifi enable; adb -s $s shell svc bluetooth enable
  adb -s $s logcat -c
done
# onboarding on both
i=0
for s in $S1 $S2; do
  i=$((i+1)); NAME=$([ $i = 1 ] && echo Alpha || echo Bravo)
  adb -s $s shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 6
  timeout 60 python3 .github/scripts/ui.py $s tap "Display Name"; sleep 1
  timeout 60 python3 .github/scripts/ui.py $s type $NAME; sleep 1
  adb -s $s shell input keyevent 4; sleep 1
  adb -s $s exec-out screencap -p > $OUT/$s-01-name.png
  timeout 60 python3 .github/scripts/ui.py $s tap "Initialize Mesh Identity"; sleep 8
  adb -s $s exec-out screencap -p > $OUT/$s-02-after-init.png
  timeout 60 python3 .github/scripts/ui.py $s texts > $OUT/$s-02-texts.txt
done
# visit main tabs on device 1
for t in Mesh Contacts Settings Chats; do
  timeout 60 python3 .github/scripts/ui.py $S1 tap "$t" 0 ; sleep 3
  adb -s $S1 exec-out screencap -p > $OUT/$S1-tab-$t.png
  timeout 60 python3 .github/scripts/ui.py $S1 texts > $OUT/$S1-tab-$t.txt
done
# give Nearby time to discover
echo "[$(date +%T)] wait for discovery"; sleep 90
for s in $S1 $S2; do
  timeout 60 python3 .github/scripts/ui.py $s tap "Mesh" 0; sleep 3
  adb -s $s exec-out screencap -p > $OUT/$s-03-mesh-after-wait.png
  timeout 60 python3 .github/scripts/ui.py $s texts > $OUT/$s-03-mesh-texts.txt
  adb -s $s shell pidof $PKG | tee -a $OUT/pids.txt
  adb -s $s logcat -d > $OUT/$s-logcat.txt
  adb -s $s logcat -d -b crash > $OUT/$s-crash.txt
done
echo done
