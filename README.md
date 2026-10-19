# LUXE Music Android build

Python/Kivy Android project with a dark premium UI, cleaner vector-drawn folder/rescan/settings icons, a more usable desktop folder browser, Android Storage Access Framework folder selection, recursive music-file scanning, and Android MediaPlayer playback for selected-folder content URIs.

## Build the debug APK in GitHub Actions

1. Create a GitHub repository named `LUXE-Music`.
2. Upload the contents of this folder to the repository's `main` branch.
3. Open **Actions** and choose **Build LUXE Music APK**.
4. Click **Run workflow** and wait for the build to finish.
5. Open the completed run and download the `LUXE-Music-debug-APK` artifact. Extract the `.apk` from it.
6. Transfer the APK to your Android phone and install it. You may need to allow installation from the app you used to download it.

The APK is compiled in GitHub's cloud runner, so the Android SDK/NDK does not need to be installed on your PC.

## Test on Windows

```powershell
python -m pip install kivy==2.3.1
python main.py
```

## Notes

- Android folder access uses the system folder picker and persists read permission when the provider supports it.
- The scan includes supported audio files in subfolders.
- This project has not yet been compiled or tested on a physical Android phone in this workspace. Please treat the APK as a test build until folder selection, playback, and different audio formats have been verified on your device.
- Lock-screen/notification controls, true album-art extraction, and custom background image selection still need dedicated implementation and device testing.
