# AutoDial Pro

Android sequential dialer for devices with one or more SIMs.

## Current features
- Import phone numbers from a CSV/text file (one number per line or first column).
- Select an available calling SIM when Android exposes phone accounts.
- Place one call at a time using the selected SIM account.
- Pause, resume, and stop the campaign.
- Calls are user-initiated from the foreground app; return to AutoDial Pro after each call and tap **CALL NEXT**.

## Important limitations
- This app does not spoof or alter caller ID. The recipient sees the number assigned by your mobile operator to the SIM used.
- Android/OEM versions and carriers differ in how SIM selection works. If the SIM picker/account is unavailable, set the preferred calling SIM in Android/Samsung phone settings.
- Android does not reliably expose call outcomes to ordinary apps. This initial version does not claim answered/busy/no-answer reporting.
- The app deliberately does not run unattended bulk calls in the background. Confirm you have consent to contact each person and follow Kenyan communications and privacy requirements.

## Build
A GitHub Actions workflow builds a debug APK on every push to `main` and on manual dispatch. Download it from the workflow run's **Artifacts** section.

## Install
Download `AutoDial-Pro-debug` from a successful workflow run, extract the ZIP, then install `app-debug.apk` on your Android phone. You may need to allow installation from the browser/file manager you used.
