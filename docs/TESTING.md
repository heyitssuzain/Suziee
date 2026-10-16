# Testing Guide

Unit tests are located in `app/src/test/java/com/example/`.
- `VoiceCommandParserTest`: Verifies speech-to-intent parsing (play, search, pause, resume, next, previous, shuffle, repeat, lyrics, downloads) and duration formatting.
- `ExampleUnitTest`: Base sanity assertions.

To execute tests:
```bash
gradle testDebugUnitTest
```
