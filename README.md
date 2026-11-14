# AutoCaller

An Android app that calls a list of phone numbers one by one through the phone's own SIM,
plays a recorded voice message when the call is answered, and keeps a report of every call.

- **Numbers** come from a CSV file (or can be typed in).
- **Voice message** is recorded in the app (or picked from an audio file).
- **Play on answer**: the message starts when the other person picks up, then the call ends.
- **Reports**: time-wise (every call attempt with dial, answer and end time), number-wise
  (result per number) and a summary with an hour-by-hour table. Each can be saved or shared as CSV.

Works on Android 8.0 and newer. No internet connection, account or server is used.

## Getting the APK

Every push to this repository is built by GitHub Actions (`.github/workflows/build-apk.yml`).

1. Open the repository on github.com and wait for the build under **Actions** to finish (about 4 minutes).
2. Open **Releases** and download `AutoCaller.apk` from "AutoCaller APK (latest build)".
3. Open the file on the phone and allow installation from that source when asked.

The same APK, the full build log and a status file are also written to the `build-output` branch.

To build on a computer instead: open the folder in Android Studio and run
**Build > Generate App Bundles or APKs > Generate APKs**, or run `./gradlew assembleRelease`
(JDK 17 and the Android SDK are needed). The APK is written to `app/build/outputs/apk/release/`.

## First use

1. **Setup > Set**: make AutoCaller the default phone app. Android only tells the default phone
   app the moment an outgoing call is answered, so this is required.
2. **Setup > Allow**: Phone, Microphone and Notifications permissions, and background running.
3. **Numbers > Import CSV**.
4. **Voice message > Record / change**.
5. **Start**.

Try it first with two or three of your own numbers.

### CSV format

One phone number per row. A header row and a name column are optional:

```
Name,Mobile
Ramesh,9876543210
Sita,+91 98123 45678
```

- The phone column is found automatically (a header containing Phone, Mobile, Cell, WhatsApp or
  Contact no wins). The import screen shows which column was used and the first rows, so check it.
- Comma, semicolon, tab and pipe separated files are accepted.
- Numbers need 7 to 15 digits. Spaces, dashes and brackets are removed. Repeated numbers are dropped.
- Excel writes long numbers such as 919876543210 as `9.19877E+11`, which loses digits. Those rows
  are skipped and reported. Format the column as Number with 0 decimals (or as Text) before
  saving the CSV.
- `.xlsx` files are not read: use **Save as > CSV**.

## Things to know

- **How the other person hears the message.** Android does not allow an ordinary app to put sound
  straight into a phone call. AutoCaller puts the call on loudspeaker and plays the message at
  full volume; the phone's own microphone carries it to the other side. Keep the phone in a quiet
  room, lying face up, with nothing covering the speaker or microphone. How clear it sounds depends
  on the phone model. If people cannot hear it well, try **Settings > How the message is played**.
- **While AutoCaller is the default phone app**, ordinary incoming and outgoing calls use
  AutoCaller's simple call screen. Switch back at any time with **Setup > Change**.
- **During a calling run**, an incoming call that arrives while a run call is in progress is
  rejected, so that it cannot interrupt the message. Between two calls an incoming call rings
  normally and the run waits until it is over.
- **Calling hours** default to 09:00 to 21:00 and can be changed in Settings.
- The run stops by itself after 5 failed calls in a row (no network, no balance, SIM problem).
- **Activity log** on the home screen records what happened on each call. Share it when asking
  for help with a problem.

## Results used in the reports

| Result | Meaning |
| --- | --- |
| Answered | The call was picked up. The report shows how much of the message was heard. |
| Not answered | It rang until the time limit, or the network ended it without an answer. |
| Busy | The network reported the number as busy. |
| Rejected | The call was declined. |
| Failed | The call could not be connected (switched off, unreachable, network error). |
| Skipped | You pressed "Skip call". |
| Cancelled | You pressed Stop while it was ringing. The number stays in the queue. |

The exact reason reported by the phone is kept in the "Details" column of the time-wise CSV.

## Responsible use

Call only people who expect to hear from you. In India, TRAI's rules do not allow promotional
or telemarketing calls from an ordinary 10-digit mobile number, and operators disconnect
numbers that are reported. Rules in other countries differ; check the ones that apply to you.

## Project layout

```
app/src/main/java/com/autocaller/app/
  MainActivity      home screen: setup checks, list, message, Start / Stop
  CampaignService   the calling run (foreground service)
  CallService       InCallService: receives every call while the app is the default phone app
  InCallActivity    call screen for ordinary calls
  DialerActivity    small dial pad (needed to qualify as a phone app)
  ContactsActivity  number list and CSV import
  RecordActivity    voice message recorder
  ReportsActivity   reports and CSV export
  SettingsActivity  timing, retries, calling hours, SIM
  Db, Csv, Prefs, Util, Ui, Logx   storage and helpers
```

The app uses only the Android framework (no libraries), and builds its screens in code.
`app/release.keystore` is a signing key kept in the project so that every build can be
installed over the previous one; replace it with your own before publishing the app anywhere.
