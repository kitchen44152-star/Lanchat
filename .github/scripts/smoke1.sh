#!/bin/bash
# Emulator smoke test, step 1: install the v0.1.0 APK, launch, capture screens.
PKG=com.aistudio.lanchat.mshxqr
OUT=emu-out
mkdir -p $OUT
adb wait-for-device
adb shell getprop ro.build.version.sdk | tee $OUT/sdk.txt
adb shell pm list packages | grep -E "com.google.android.gms|com.android.vending" | tee $OUT/gms.txt
gh release download v0.1.0 --repo "$GITHUB_REPOSITORY" --pattern '*.apk' --dir /tmp/apk
APK=$(ls /tmp/apk/*.apk | head -1)
adb install -r "$APK" 2>&1 | tee $OUT/install.txt
adb shell dumpsys package $PKG | grep -E "versionName|versionCode|requested permissions" -A0 | tee $OUT/pkginfo.txt
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 | tee $OUT/launch.txt
sleep 8
adb exec-out screencap -p > $OUT/01-launch.png
adb shell uiautomator dump /sdcard/ui1.xml >/dev/null 2>&1; adb pull /sdcard/ui1.xml $OUT/ui1.xml
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | tee $OUT/activity1.txt
adb shell pidof $PKG | tee $OUT/pid1.txt
adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d > $OUT/logcat1.txt
# Step 1b: grant runtime permissions and relaunch
for p in android.permission.BLUETOOTH_SCAN android.permission.BLUETOOTH_ADVERTISE android.permission.BLUETOOTH_CONNECT android.permission.NEARBY_WIFI_DEVICES android.permission.CAMERA android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS android.permission.ACCESS_FINE_LOCATION; do
  adb shell pm grant $PKG $p 2>&1 | sed "s/^/$p: /" | tee -a $OUT/grant.txt
done
adb shell am force-stop $PKG
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8
adb exec-out screencap -p > $OUT/02-after-grant.png
adb shell uiautomator dump /sdcard/ui2.xml >/dev/null 2>&1; adb pull /sdcard/ui2.xml $OUT/ui2.xml
adb shell pidof $PKG | tee $OUT/pid2.txt
adb logcat -d > $OUT/logcat2.txt
adb logcat -d -b crash >> $OUT/crash.txt
echo done
