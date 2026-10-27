# Talking Tags

An Android app that makes NFC tags speak. Hold a tag to the phone and it reads the tag's text
aloud (with any installed text-to-speech engine or TalkBack) and plays a voice note recorded for
it. Built with blind and low-vision users in mind.

- Write text to a tag by typing or dictation; record an optional voice note for it.
- Voice notes stay on the phone (never in cloud backups); export and import them as a ZIP.
- Reads tags written by other apps — web links, phone numbers, email, SMS, locations, Wi-Fi
  networks, contacts — and offers to open them, only when you choose.
- Erase or permanently lock tags; 26 languages.

[Get it on Google Play](https://play.google.com/store/apps/details?id=com.appricodes.voicetag)

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Release signing and publishing are described in [RELEASE.md](RELEASE.md).

## Privacy

See [PRIVACY.md](PRIVACY.md). The app has no internet permission and collects nothing.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). You may use, change and share
this code, also commercially, as long as you keep the copyright and NOTICE and credit this project.
