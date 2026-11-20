# DueBack for Android

DueBack tracks promised refunds, group bills, and money received. Refund promises and unconfirmed payments stay outside received totals. The ledger is stored on the device in Room. Existing v1 and v2 ledgers migrate to v3 on first open. Historical records retain their values. Original timestamps that were never recorded are shown as unavailable.

## Start here

1. Open DueBack and choose **Continue with manual entry**, or **Choose capture sources**.
2. For new notifications, select one or more source apps. All switches start off on a fresh install. Tap **Open Android notification access**, choose **DueBack**, and enable access. Return to DueBack to see the refreshed status.
3. Open **Sources → View capture diagnostics**. Send a fresh notification from a selected app. The screen shows whether Android connected the listener, the selected apps, and the last eligible event’s time, app and parsing result. No message text, amount, account or transaction ID appears in diagnostics.
4. Check **Refunds** for draft promises and **Payments** for credits. A supported transaction must appear there before it can affect a balance. Confirm uncertain credits against your statement. Dismiss a possible duplicate or select a specific refund or bill share to allocate it.

## Inputs and permissions

| Input | When access is requested | What DueBack receives |
| --- | --- | --- |
| Notifications | Only after you open Android notification access settings | Android grants the listener device-level notification access. DueBack checks the package and your local selection before reading any content. The picker lists installed SMS, email and UPI handlers plus compatible named banking apps. Individual posts and grouped message entries are eligible. Empty group summaries are skipped. Redacted or disabled previews may contain no usable transaction. |
| Shared or pasted text | When you choose Share → DueBack or paste text in the app | Only the selected text. Paste text → Open text file also accepts a UTF-8 file containing one message, up to 12,000 characters. Use these for earlier SMS and email. No SMS history permission is declared. |
| Statement CSV | When you choose Payments → Import CSV | The system picker grants access to the chosen document. No broad storage permission. Use a credit column or amount and type. Optional columns include utr, rrn, transaction_id, payer_id, destination and order_ref. Amounts are in rupees. |
| Manual entry | No permission | Enter refunds, incoming payments, groups and bills. The optional contact picker grants access only to the chosen contact data. You can type a member’s details instead. |
| DueBack activity alerts | Only after tapping Manage DueBack alerts | On Android 13+ the posting permission lets DueBack show its own alerts. Denying it leaves capture and the in-app activity feed available. The optional listener self-test requires DueBack to be able to post its labelled test notification. |
| Gmail inbox import | Only in a configured build, after choosing a sender and a Google account | Google’s consent flow grants read-only mailbox access. Each explicit import queries that sender’s last 30 days by default, or all matching mail when explicitly selected, follows all result pages and checks subjects and inline plain-text or HTML bodies and puts detected items into review. Gmail notification capture is independent. |

The network permission supports HTTPS requests to the Gmail API during an explicit mailbox import. It has no runtime dialog. The listener binding permission restricts who can bind to the listener. Android controls access through its settings. DueBack requests no email passwords and stores no Gmail access or refresh tokens.

### Optional Gmail setup

The distributed source defaults to **Account connection unavailable**. To enable the implemented Google authorization path:

1. In a Google Cloud project, enable the Gmail API. Configure the OAuth consent screen and specify the Gmail read-only scope `https://www.googleapis.com/auth/gmail.readonly`. For a testing app, add the intended Google accounts as test users.
2. Create an **Android** OAuth client for package `app.dueback` and the SHA-1 of the signing certificate for the actual APK. Run `gradlew.bat :app:signingReport` to obtain the local debug certificate fingerprint. For a GitHub build, extract signing-certificate.txt next to DueBack-review-debug.apk. Use the “Signer #1 certificate SHA-1 digest” from that file. A fresh hosted runner may generate a different debug certificate. For repeatable Gmail testing use the same local debug keystore and signingReport each time.
3. Set `dueback.gmailConfigured=true` in your local Gradle user properties, or pass `-Pdueback.gmailConfigured=true` to the build. This flag enables the screen. The registered package and signing certificate identify the client to Google Play services. No client secret belongs in the APK.
4. Use a device with Google Play services and a Google account. Open **Sources → Gmail inbox import**, enter one sender, and tap **Choose account and import**. Cancel at either prompt to keep using other inputs. Production distribution of a restricted Gmail scope may require Google verification. Revoke account access through your Google Account’s third-party connections.

## Build and install

DueBack uses JDK 17, Gradle 8.9 via the wrapper, Android Gradle Plugin 8.7.3, SDK
Platform 35 and Build Tools 34.0.0. The debug APK supports Android 8.0 and newer.
The first build needs access to GitHub, Gradle's distribution host, Google Maven
and Maven Central.

### Linux Mint or Ubuntu on x86-64

Install missing prerequisites. The first two commands require sudo on your Linux system.
Reuse the current SDK and command-line tools if already installed. Set
ANDROID_HOME to their actual path, or use the default below.

```bash
sudo apt update
sudo apt install openjdk-17-jdk ca-certificates curl unzip perl
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
java -version
```

If sdkmanager is missing, review the license at the [official Android SDK tools page](https://developer.android.com/studio#command-line-tools-only).
Use the pinned official download below. This block stages files in a temporary
directory and uses a fresh numbered location inside the SDK for the tools.

```bash
(
  set -eu
  android_cli_dir="$ANDROID_HOME/cmdline-tools/15859902"
  if command -v sdkmanager >/dev/null || [ -x "$android_cli_dir/bin/sdkmanager" ]; then
    exit 0
  fi
  test ! -e "$android_cli_dir"
  android_download="$(mktemp -d)"
  cd "$android_download"
  curl --fail --location --show-error --output cli.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
  echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  cli.zip' | sha256sum -c -
  unzip -q cli.zip
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv cmdline-tools "$android_cli_dir"
)
if ! command -v sdkmanager >/dev/null; then
  export PATH="$ANDROID_HOME/cmdline-tools/15859902/bin:$PATH"
fi

# Review and accept the applicable licenses at the prompts.
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;34.0.0" "emulator"
sdkmanager --sdk_root="$ANDROID_HOME" \
  "system-images;android-35;google_apis_playstore;x86_64" \
  "system-images;android-33;google_apis_playstore;x86_64"
```

From the directory containing gradlew, create a local SDK pointer if one
doesn't exist. If it exists, inspect its sdk.dir and edit it only if it points
at a different SDK. The wrapper downloads the official 8.9 JAR when missing
and checks its pinned SHA-256 before launch. The distribution hash is pinned
in gradle/wrapper/gradle-wrapper.properties.

```bash
if [ ! -e local.properties ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi
bash gradlew --no-daemon --max-workers=2 --continue testDebugUnitTest lintDebug assembleDebug
apk=app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs "$apk"
```

On Windows, point JAVA_HOME to JDK 17 and set ANDROID_HOME or sdk.dir. Then run
`gradlew.bat --no-daemon --max-workers=2 --continue testDebugUnitTest lintDebug assembleDebug`.
The GitHub workflow runs those Gradle tasks on Linux and uploads
**DueBack-debug**, containing **DueBack-capture-debug.apk** and the
apksigner report, after all checks pass.

### Start the emulator and install

Run the builds first. Close each emulator before starting the next one to
limit memory usage. Reuse your existing Google Play AVD where available.
Check hardware virtualization with `emulator -accel-check`. If KVM is missing
on Mint or Ubuntu, install it with `sudo apt install qemu-kvm` and use an
account with permission to access /dev/kvm.

```bash
avdmanager list avd
# Create these only if they don't already exist.
avdmanager create avd -n DueBack_API35 -k "system-images;android-35;google_apis_playstore;x86_64"
avdmanager create avd -n DueBack_API33 -k "system-images;android-33;google_apis_playstore;x86_64"
emulator -avd DueBack_API35 -memory 2048 -no-snapshot-load
```

In another terminal, with the same PATH configuration, wait for the Android
home screen and then run:

```bash
adb wait-for-device
adb shell getprop ro.build.version.sdk
adb shell pm path com.google.android.apps.messaging
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.dueback/.MainActivity
```

If Google Messages is absent, install it through Play Store and set it as
the default SMS app. Allow its own notifications and keep its conversation
closed. Repeat with the API 33 AVD after running `adb emu kill` on API 35.

### Test on your phone

1. Verify the APK's signature using the command above. Install with `adb install -r PATH_TO_APK` after enabling USB debugging and authorizing your computer, or tap the APK on your phone and allow installation from that source. An update requires the same signing certificate as the existing app. If certificates differ, rebuild with the original signing key to preserve the installed app and its data.
2. In DueBack open **Sources**, enable notification access in Android settings, select **Messages**, and resume capture if paused. Allow notifications and previews in Messages. Leave the conversation closed. DueBack's own alerts are optional.
3. Ask a second phone to send a synthetic SMS such as `INR 450 credited. UTR TEST987655`. Use a fresh reference on each independent run. First confirm that Messages posted the notification in Android's shade.
4. Open **Sources > View capture diagnostics** and verify access, listener status, event time and processing result. In **Payments**, verify one unconfirmed INR 450 entry with reference TEST987655 and no increase in received totals. Reopen the app and check it persists.
5. On Android 13 and newer, sideloaded apps may require **Settings > Apps > DueBack > More > Allow restricted settings** if that option is available. If unavailable, share or paste a message into DueBack or enter the payment manually.

## Reviewing and deleting imports

All captured, CSV and Gmail payments enter review. Confirm only after checking the statement. Open a payment or refund to inspect its source, recorded original time, allocations and matching reasons. Distinct payment IDs stay separate. Unreferenced imports may need manual dismissal if the same payment was observed twice.

Calendar-day estimates use the original timestamp and zone. Business-day promises require checking the merchant calendar. Gmail uses the Date header where parseable and otherwise its internal timestamp in UTC. Notifications use the event timestamp with the device zone at capture. Pasted text with no source date cannot produce a reliable relative deadline. CSV supports an optional timestamp column containing an ISO timestamp with offset.

Under Sources, Pause capture stops processing new selected-app notifications. Delete imported data asks for confirmation, pauses capture, removes imports and related matches, clears duplicate markers and activity history, and keeps manual entries, groups and bills. Existing v1 refunds whose origin was not recorded are retained.

## How automatic capture works

Sources stores an explicit package selection. Android grants listener access
separately and calls the listener when connected. Each new notification post
is checked against the selected package and pause setting. A second package
discovery lookup is unnecessary and previously could drop a selected event
before diagnostics.

The previous extractor discarded summaries without MessagingStyle bundles,
even when those summaries included readable text lines. Unreadable message
bundles could also suppress a valid expanded preview. DueBack now reads
MessagingStyle entries, inbox lines, expanded text and ordinary previews.
For previews that exist only on a summary, a transaction reference lets
DueBack reconcile the summary with child events. Generic summaries are
skipped and their diagnostic results preserve the previous processed result.
This fixes observable code paths. The original Google Messages event payloads
and reason for the historical failure remain unverified.

The parser receives each extracted message's timestamp. Room checks repeated
event identities and payment references before writing. Payments appear in
review via the ledger's Room change flow. The Payments summary counts only
confirmed credits. Repeated unreferenced details presented in different
formats without a stable identity may still require manual review.

## Testing real SMS capture on an emulator

Set up Sources and Messages as in the phone steps. Use an emulator with
only synthetic test data. Leave the notifications in the shade so the next SMS
can update or group them. Confirm each post in the shade before reading the
DueBack result.

```bash
adb emu sms send 5551234 "INR 450 credited. UTR TEST987655"
adb emu sms send 5551234 "INR 650 credited. UTR ALPHABET"
adb emu sms send 5551235 "INR 450 credited. UTR TEST987655"
adb emu sms send 5551234 "I will call later"
adb logcat -d -s DueBackCapture:D
```

| Check | Expected result |
| --- | --- |
| First fresh SMS | One unconfirmed payment, INR 450 and TEST987655 |
| Same conversation and another sender | Two payments total, with INR 650 and ALPHABET on the second payment |
| Repeat reference and ordinary SMS | No extra payment and no change in received totals |
| Deselect Messages and send another fresh credit | No entry. Reselect it, pause capture and repeat using a new reference |
| Revoke Android notification access and open DueBack | Access denied and manual entry available. Restore access and confirm a fresh SMS is processed after reconnecting |
| Force stop and reopen DueBack | Captured records persist. Use Reconnect listener if the service needs rebinding |

Diagnostics log only the event shape, number of usable inputs and outcome.
If the latest event is an empty summary, look at the previous processing
result as well. Android 15 redacts notifications it classifies as containing
an OTP for untrusted listeners. A missing preview can also be caused by the
source app. DueBack cannot determine the cause from an empty payload.
Notification access only exposes what Android supplies in posted
notifications. Share text or enter the payment manually if the preview is
unavailable. See the [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction)
and the [notification listener reference](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

## Verification of this revision

| Check | Observed result |
| --- | --- |
| Standalone Java tests | 15 passed, including both SMS parser fixtures |
| Gradle unit tests, lint and APK | Attempted on Windows. Wrapper download blocked by DNS resolution for raw.githubusercontent.com. Robolectric and Android build tasks did not start |
| APK signature | No APK built to verify |
| API 35 and API 33 Google Messages | Unverified. This environment had no adb, Android SDK or emulator, and WSL returned access denied |
| Physical phone | Unverified |

The Linux setup and emulator commands above are instructions for the target
machine. The Windows command attempted here was
`gradlew.bat --max-workers=2 --continue testDebugUnitTest lintDebug assembleDebug`
with JAVA_HOME set to an installed JDK 17.
