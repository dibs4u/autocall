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

**If step 1 shows "Restricted setting".** Android 15 and newer block the default phone app
choice for apps installed from a downloaded file. Open the phone's
**Settings > Apps > AutoCaller**, tap the three dots at the top right, choose
**Allow restricted settings**, then go back to AutoCaller and press **Set** again.
(The option only appears after the phone has shown the "Restricted setting" message once.)

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

## If the other person cannot hear the message

Android does not allow an ordinary app to put sound straight into a phone call, so the message
has to reach the other person through a microphone. Which way works depends on the phone.
Choose the method in **Settings > How the message reaches the other person**, and check it with
**Test call to one number** on the home screen (call a second phone you can listen to, kept in
another room).

| Method | What happens | When to use it |
| --- | --- | --- |
| Loudspeaker, media volume | The call is on loudspeaker; the phone's speaker plays the message and its microphone picks it up. | Default. Many phones remove their own speaker sound from a call as echo. If the message plays loudly but the other person hears nothing, this is why. |
| Loudspeaker, call volume | The same, played on the call's own volume channel. | Worth one test if the first method is silent or very quiet. |
| Call on earpiece, message on loudspeaker | The call stays on the earpiece and the app asks the phone to play the message on the loudspeaker. | The message is not the call's own sound, so it is not removed as echo. Not every phone allows the split. |
| Call on loudspeaker, message on earpiece | The reverse split. | Same idea; try it if the previous one does not work. |
| Earpiece only | Call and message both on the small speaker at the top of the phone. | Quiet, but some phones do not filter it out. |
| Wired earphones or audio cable | The call and the message go to the earphones. | Works on almost every phone. Plug in earphones and keep one earbud pressed against the microphone: the one on the earphone cable, or the phone's own microphone if the earphones have none. A little tape holds it in place. Set the loudness to 60 to 80 percent. |

For every answered call the activity log records the call's audio route, the volumes, and
which speaker the message really came out of (the phone has the last word on that). Share
the log when a method fails and you want to know why.

## Things to know

- Keep the phone in a quiet room while it is calling; whatever the microphone hears goes to
  the other person together with the message.
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
