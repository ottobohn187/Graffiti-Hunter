# Dumpster Hunter Native Android

This is the standalone Android implementation of Dumpster Hunter. It does not
contain or launch Unity. The application ID and signing setup remain compatible
with the earlier demo APK so an upgrade preserves shared preferences and queued
packages under the app's external `DumpReports` directory.

## User flow

1. Home opens phone Camera2 or the glasses stream directly.
2. Capture opens native photo/map approval.
3. Approve queues the package and immediately reopens the selected camera.
4. Reset discards the pending package and retakes.
5. Cancel or Android Back returns to Home.
6. Review Queue edits packages and prepares the official Get It Done form.

The final City submission remains user-controlled because San Diego does not
publish a supported third-party report-submission API.
