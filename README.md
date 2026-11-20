# DueBack

DueBack is a native Android project for tracking expected refunds and group repayments. The UI uses Kotlin and Jetpack Compose. Room stores promises, credits, allocations, groups, shares, refund drafts and activity on the device. Android 8.0 (API 26) or newer is required.

## Build

Use JDK 17 and Android SDK Platform 35. Set ANDROID_HOME or set sdk.dir in local.properties to the Android SDK installation. From the project root on Windows run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On macOS or Linux run `./gradlew testDebugUnitTest lintDebug assembleDebug`. The launchers fetch the official Gradle 8.9 wrapper JAR on first use and verify its SHA-256. The Gradle distribution is also checksum-pinned. Building from a fresh checkout requires access to GitHub, Gradle, Google Maven and Maven Central. The expected successful output is `app/build/outputs/apk/debug/app-debug.apk`.

## Try the two flows

- From Refunds, tap **Try demo** in a fresh ledger for a fictional ₹1,200 refund with separate ₹900 bank and ₹200 wallet receipts. In Incoming payments, add a ₹100 credit with order reference DEMO-17. Check the refund and undo the allocation from Incoming payments.
- In Groups, create a group with two other members. Add their UPI IDs or Indian mobile numbers to enable payer matching. Create a ₹900 equal bill and record a ₹200 credit using one member's ID. If that member has multiple compatible balances, choose one in Incoming payments. The reason appears beside the allocation.
- Manual entry works independently of notification access. To share an SMS or email, select its text and share to DueBack. Supported notification apps are opt-in under Incoming payments → Alert settings. Android notification delivery is a separate permission.
- Incoming payments accepts CSV through the system picker. A credit column can replace amount and type. Values are in rupees. Rows without a reliable transaction reference require review. A common UTR, RRN or transaction ID identifies a credit across sources. Different IDs preserve separate same-value transactions. A repeated import of the identical file also uses stable row keys.

```csv
amount,type,utr,payer_id,destination,order_ref
100,credit,FICTIONAL100,,Bank,DEMO-17
200,credit,FICTIONAL200,mira@example,Bank,
```

## Verification from this workspace

Ten standalone JUnit tests passed on JDK 17. These exercise the production parser, decimal-to-paise conversion, equal splitting, matching and allocation rules, source-independent reference comparison, date extraction and CSV quoting.

Six additional Room/Robolectric integration tests cover persisted refund balances after reopening the database, undo, partial group payments, ambiguous bills, duplicate imports and explicit splitting. These tests, Android lint and APK assembly could not start in this workspace. The Android SDK and Gradle distribution were unavailable and build dependency hosts failed DNS resolution. No debug APK was generated.

Live alert capture, the Android permission and picker flows, installation, and UI balances after a force-stop and relaunch still need device verification. Demo entries and imported records are fixtures.

