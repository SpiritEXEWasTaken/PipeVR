PipeVR version numbers
======================

Every release has a version number made of three parts: MAJOR.MINOR.PATCH (for example 1.2.0).
This is the same scheme CleanMyVR uses.

Which part goes up depends on what the release contains:

  MAJOR  (1.2.0 -> 2.0.0)  A big update: a redesign, a large new area of the app, or a change that
                           works differently from before (for example, settings or data from older
                           versions need converting, or a feature people relied on was removed).

  MINOR  (1.2.0 -> 1.3.0)  New features, with nothing that used to work changing in a way people
                           would notice. Bug fixes can come along too.

  PATCH  (1.2.0 -> 1.2.1)  Bug fixes only. No new features.

When a higher part goes up, the parts after it go back to 0 (1.2.3 -> 1.3.0, 1.3.0 -> 2.0.0).

How to release
--------------

1. In app/build.gradle, set appVersionName to the new number and raise baseVersionCode by 1.
   Android only installs an update when this code is higher than the installed one.
2. Build the release APK, signed with the PipeVR key:

       ./gradlew assembleRelease

3. On GitHub, publish a release tagged vMAJOR.MINOR.PATCH (for example v1.2.0), titled
   "PipeVR MAJOR.MINOR.PATCH". Attach the arm64 APK twice:
     - PipeVR-MAJOR.MINOR.PATCH-arm64.apk (the versioned name)
     - PipeVR-arm64.apk (the same file, so releases/latest/download/PipeVR-arm64.apk always
       points at the newest version)
4. In pipevr.framedrop.json, set sha256 to the new APK's checksum (sha256sum PipeVR-arm64.apk),
   or FrameDrop installs on the Steam Frame fail.

Everyone's PipeVR checks GitHub for new releases and offers the update with a notification
(Settings > Updates).
