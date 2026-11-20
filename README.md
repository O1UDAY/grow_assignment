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

Use JDK 17, Android SDK Platform 35 and Build Tools 34.0.0. Point ANDROID_HOME at the SDK or set sdk.dir in local.properties. The first build needs access to GitHub, Gradle, Google Maven and Maven Central. On Windows, from the folder containing settings.gradle.kts:

```powershell
.\gradlew.bat --continue testDebugUnitTest lintDebug assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

On Linux or macOS use `bash gradlew --continue testDebugUnitTest lintDebug assembleDebug`. Pushing this revision to main triggers the included GitHub workflow. To run it again, use **Actions → Build DueBack APK → Run workflow**. A successful run uploads **DueBack-debug**, containing DueBack-review-debug.apk and its verified signing-certificate report. The APK supports Android 8.0 and later.

## Reviewing and deleting imports

All captured, CSV and Gmail payments enter review. Confirm only after checking the statement. Open a payment or refund to inspect its source, recorded original time, allocations and matching reasons. Distinct payment IDs stay separate. Unreferenced imports may need manual dismissal if the same payment was observed twice.

Calendar-day estimates use the original timestamp and zone. Business-day promises require checking the merchant calendar. Gmail uses the Date header where parseable and otherwise its internal timestamp in UTC. Notifications use the event timestamp with the device zone at capture. Pasted text with no source date cannot produce a reliable relative deadline. CSV supports an optional timestamp column containing an ISO timestamp with offset.

Under Sources, Pause capture stops processing new selected-app notifications. Delete imported data asks for confirmation, pauses capture, removes imports and related matches, clears duplicate markers and activity history, and keeps manual entries, groups and bills. Existing v1 refunds whose origin was not recorded are retained.

## What to verify on a device

- Skip onboarding or deny access. Add a refund manually, share text, and import a CSV. Return to Sources and grant, revoke and re-grant access. Check that the status refreshes each time.
- Open Sources → Capture diagnostics → Run listener test. Enable DueBack alerts only if you want to post the test notification. A pass confirms delivery through Android and recognition of the labelled INR 12.34 fixture. It creates no ledger entry. Then test your actual source app separately.
- If access is granted but the listener is disconnected, tap Reconnect listener. If Android still does not bind it, toggle DueBack in Android notification-access settings. On supported Android versions, a trusted sideloaded APK may show Allow restricted settings under DueBack app info → More. Manual entry remains available.
- Post a fresh ordinary message from a selected app. Check that diagnostics changes from “No event received yet” to “No supported transaction found”. A post from an unselected app should leave the last event unchanged.
- With Google Messages selected, receive a test SMS reading `INR 250 credited. UTR: TEST123456`. An emulator can receive it via `adb emu sms send 5551234 "INR 250 credited. UTR: TEST123456"`. Enable message previews and leave the conversation closed. Check for a single unconfirmed payment in DueBack. The SMS app must post a notification.
- Import a CSV with `amount,type,utr` followed by `250,credit,TEST123456`. Verify only one payment remains. Two different references for the same amount represent different credits. Overlapping unreferenced observations require review.
- Deny DueBack activity alerts and repeat. Confirm that ledger updates still appear in the app. Try the forms with a keyboard, large text and a narrow screen.

## Verification of this revision

All 14 standalone Java parser, accounting and notification-policy tests passed locally. The Gradle invocation for testDebugUnitTest, lintDebug and assembleDebug stopped during wrapper download because the build host could not resolve raw.githubusercontent.com. The production v2→v3 SQL ran against a populated SQLite fixture. Every original value in all nine tables, the payment indices and the outstanding balance survived. No Android SDK, emulator or adb installation was found. The Room/Robolectric and notification-extraction tests, UI, OAuth consent, device capture and APK installation remain unverified. The GitHub workflow is provided but has not run for this revision.
