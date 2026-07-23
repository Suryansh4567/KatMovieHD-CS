#!/bin/bash
export JAVA_HOME=/home/user/jdk-21.0.4+7
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=/home/user/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH

# Install emulator and system image
yes | sdkmanager --sdk_root=$ANDROID_HOME "emulator" "system-images;android-34;google_apis;x86_64"

# Create AVD
echo "no" | avdmanager create avd -n test -k "system-images;android-34;google_apis;x86_64" --force

# Start emulator in background
# Use -no-accel because we don't have KVM
emulator -avd test -no-window -no-audio -no-accel -gpu swiftshader_indirect &
