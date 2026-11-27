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
export ANDROID_AVD_HOME=$HOME/.android/avd; mkdir -p $ANDROID_AVD_HOME
for n in a b; do echo no | avdmanager create avd -n emu$n -k "system-images;android-34;google_apis;x86_64" -d pixel_6 --force 2>&1 | tee -a $OUT/avd.log; done
avdmanager list avd 2>&1 | tee -a $OUT/avd.log; ls -la ~/.android/avd 2>&1 | tee -a $OUT/avd.log; emulator -list-avds | tee -a $OUT/avd.log
emulator -list-avds | grep -q emua || { echo "AVD CREATION FAILED"; exit 1; }
nohup emulator -avd emua -port 5554 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on -memory 1792 -cores 2 > $OUT/emua.log 2>&1 &
nohup emulator -avd emub -port 5556 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on -memory 1792 -cores 2 > $OUT/emub.log 2>&1 &
for s in $S1 $S2; do
  echo "[$(date +%T)] waiting for $s"
  timeout 300 adb -s $s wait-for-device; echo "wait rc=$?"
  for i in $(seq 1 72); do [ "$(timeout 10 adb -s $s shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 5; done
  echo "$s booted: $(timeout 10 adb -s $s shell getprop sys.boot_completed)" | tee -a $OUT/boot.txt
  adb -s $s shell settings put global window_animation_scale 0
  adb -s $s shell settings put global transition_animation_scale 0
  adb -s $s shell settings put global animator_duration_scale 0
done
adb devices | tee $OUT/devices.txt
nproc; free -m; ps aux | grep -c [q]emu-system
for f in emua emub; do echo "== $f"; tail -n 25 $OUT/$f.log; done
if ! adb devices | grep -q "$S1.*device$"; then echo "EMULATOR 1 NOT RUNNING, abort"; exit 1; fi
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
# give Nearby time to discover
echo "[$(date +%T)] wait for discovery"; sleep 90
for s in $S1 $S2; do
  adb -s $s shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 4
  adb -s $s exec-out screencap -p > $OUT/$s-03-mesh-after-wait.png
  timeout 60 python3 .github/scripts/ui.py $s texts > $OUT/$s-03-mesh-texts.txt
done
for s in $S1 $S2; do adb -s $s logcat -d > $OUT/$s-logcat-pre.txt; adb -s $s shell dumpsys activity activities | grep -m3 -iE "mResumedActivity|topResumed" | tee -a $OUT/resumed.txt; done
free -m | tee $OUT/mem.txt; adb devices | tee -a $OUT/mem.txt
# open channel on Alpha and send a message
U="timeout 60 python3 .github/scripts/ui.py"
$U $S1 tap "#Nearby Mesh" 0; sleep 3
adb -s $S1 exec-out screencap -p > $OUT/A-04-channel.png
$U $S1 nodes > $OUT/A-04-nodes.txt
$U $S1 edit; sleep 1
$U $S1 type "hello_from_alpha_1"; sleep 1
$U $S1 tapdesc Send; sleep 3
adb -s $S1 exec-out screencap -p > $OUT/A-05-typed.png
$U $S1 nodes > $OUT/A-05-nodes.txt
sleep 10
adb -s $S2 shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 4
$U $S2 tap "#Nearby Mesh" 0; sleep 3
adb -s $S2 exec-out screencap -p > $OUT/B-06-channel.png
$U $S2 texts > $OUT/B-06-texts.txt
# direct chat via Contacts
adb -s $S1 shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 3
$U $S1 tapdesc Chats; sleep 2
$U $S1 tap "Contacts" 0; sleep 3
adb -s $S1 exec-out screencap -p > $OUT/A-07-contacts.png
$U $S1 tap "Message" 0; sleep 3
adb -s $S1 exec-out screencap -p > $OUT/A-08-direct.png
$U $S1 texts > $OUT/A-08-texts.txt
$U $S1 edit; sleep 1
$U $S1 type "direct_secret_1"; sleep 1
$U $S1 tapdesc Send; sleep 3
adb -s $S1 exec-out screencap -p > $OUT/A-09-direct-sent.png
$U $S1 texts > $OUT/A-09-texts.txt
sleep 10
adb -s $S2 shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 3
$U $S2 tapdesc Chats; sleep 3
adb -s $S2 exec-out screencap -p > $OUT/B-10-chats.png
$U $S2 texts > $OUT/B-10-texts.txt
$U $S2 tap "Direct ·" 0; sleep 3
adb -s $S2 exec-out screencap -p > $OUT/B-11-direct.png
$U $S2 texts > $OUT/B-11-texts.txt
# group UI exploration on Alpha
adb -s $S1 shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 3
$U $S1 tapdesc Chats; sleep 2
$U $S1 tap "New Encrypted Group" 0; sleep 3
$U $S1 edit; sleep 1
$U $S1 type "TeamAlpha"; sleep 1
$U $S1 tap "Select Peers" 0; sleep 1
adb -s $S1 shell input tap 138 706; sleep 1
adb -s $S1 exec-out screencap -p > $OUT/A-12-group.png
$U $S1 texts > $OUT/A-12-texts.txt
$U $S1 tap "Create AES-256 Encrypted Room" 0; sleep 4
adb -s $S1 exec-out screencap -p > $OUT/A-13-group-created.png
$U $S1 texts > $OUT/A-13-texts.txt
$U $S1 edit; sleep 1
$U $S1 type "group_secret_1"; sleep 1
$U $S1 tapdesc Send; sleep 3
adb -s $S1 exec-out screencap -p > $OUT/A-14-group-sent.png
$U $S1 texts > $OUT/A-14-texts.txt
sleep 10
adb -s $S2 shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 3
$U $S2 tapdesc Chats; sleep 3
adb -s $S2 exec-out screencap -p > $OUT/B-15-chats.png
$U $S2 texts > $OUT/B-15-texts.txt
$U $S2 tap "TeamAlpha" 0; sleep 3
adb -s $S2 exec-out screencap -p > $OUT/B-16-group.png
$U $S2 texts > $OUT/B-16-texts.txt
# photo exploration on Alpha direct chat
adb -s $S1 exec-out screencap -p > /tmp/photo.png
adb -s $S1 push /tmp/photo.png /sdcard/Pictures/test_photo.png
adb -s $S1 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/test_photo.png >/dev/null
adb -s $S1 shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 3
$U $S1 tapdesc Chats; sleep 2
$U $S1 tap "Bravo" 0; sleep 3
$U $S1 tapdesc "Choose Photo"; sleep 4
adb -s $S1 exec-out screencap -p > $OUT/A-17-picker.png
$U $S1 nodes > $OUT/A-17-nodes.txt
for s in $S1 $S2; do
  adb -s $s shell pidof $PKG | tee -a $OUT/pids.txt
  adb -s $s logcat -d > $OUT/$s-logcat.txt
  adb -s $s logcat -d -b crash > $OUT/$s-crash.txt
done
echo done
