# Telefarm

> A privacy-focused, personal Telegram client for Android.

Telefarm is an independent Android messaging client designed for personal use. It provides a clean, modern interface for interacting with Telegram while keeping application state and authentication data on the user's device.

Telefarm is not affiliated with, endorsed by, or sponsored by Telegram.

---

## ⚡ Vision

Telefarm is built around a simple idea:

**Your client. Your device. Your session. Your control.**

The project aims to provide a powerful Telegram experience without requiring Telefarm-operated servers, permanent databases, or a separate application backend for normal usage.

The Android application is designed to communicate directly with Telegram infrastructure through the supported Telegram client/API architecture.

---

## 🧩 Core Architecture

Telefarm follows a local-first architecture.

The main application runs directly on the Android device.

User Device
→ Telefarm Android Application
→ Telegram Client Layer
→ Telegram Infrastructure

No Telefarm-operated backend is required for normal client operation.

---

## 🔐 Privacy & Session Model

Telefarm is designed around local session management.

Authentication and session state are intended to remain on the user's device and should be protected using Android's secure storage mechanisms.

Telefarm does not require a permanent Telefarm database for normal operation.

Sensitive authentication information should never be exposed through logs, screenshots, public repositories, or debug output.

### Security Rule

Never share:

- Authentication credentials
- Verification codes
- Two-factor authentication passwords
- Session data
- API credentials
- Private keys

A valid authenticated session can provide account access and must therefore be protected like a credential.

---

## 🚀 Planned Features

### 💬 Messaging

- Private chats
- Group chats
- Channels
- Saved Messages
- Send messages
- Receive messages
- Reply to messages
- Forward messages
- Edit messages
- Delete messages
- Pin messages
- Message search
- Read status
- Delivery status
- Typing indicators where supported
- Reactions where supported

### 📁 Media

- Photos
- Videos
- Documents
- Audio
- Voice messages
- Media previews
- Media downloads
- Media uploads
- Download progress
- Upload progress
- Local media handling

### 🗂️ Chat Management

- Pinned chats
- Archived chats
- Chat folders
- Unread counters
- Chat search
- Chat filtering
- Muted chats
- Notification controls
- Chat information
- User profiles
- Group information
- Channel information

### 🎨 User Experience

- Modern Android interface
- Telegram-inspired layout
- Material Android design
- Light theme
- Dark theme
- Smooth navigation
- Smooth message scrolling
- Responsive layouts
- Native Android notifications
- Fast chat switching

### 👤 Account

- Sign in with a phone number, or with a session string of another client
- Account profile
- Username
- Profile photo
- Account information
- Settings
- Privacy-related controls supported by the underlying Telegram API
- Notification settings

---

## 🛡️ Security Principles

Telefarm follows these principles:

1. No unnecessary third-party backend.
2. No permanent Telefarm database for normal operation.
3. Authentication data should remain protected on-device.
4. Sensitive information must not be logged unnecessarily.
5. Secrets must never be committed to Git.
6. API credentials must be stored securely.
7. Debug builds must not expose authentication information.
8. Network communication should use secure transport.
9. Local application data should follow Android security best practices.
10. Security-sensitive functionality should be reviewed before release.

---

## 🏗️ Technology

Telefarm is intended to use modern Android technologies.

Core technology direction:

- Kotlin
- Android SDK
- Native Android UI
- Telegram client/API architecture
- Kotlin Coroutines
- Android secure storage
- Modern Android networking
- Local caching
- Android notification system

The exact implementation may evolve during development.

---

## 📱 Platform

Target platform:

**Android**

Telefarm is designed primarily for personal use and development.

The application can be compiled into an APK and installed directly on a compatible Android device.

Play Store publication is not required for personal testing.

---

## 🗂️ Chat folders

Folders are extra tabs at the top of the chat list. They live on your device only - they are a
way to look at the chats you already have, not a Telegram object, so nothing is sent to the
server and nothing syncs to another device.

- **New folder** in the overflow menu: give it a name and tick the chats that belong in it.
- Long-press a tab to rename it, change its rules, move it, or delete it.
- Long-press a chat to add it to a folder by hand, or take it out again.

Rules: unread, not muted, muted, pinned, private chats, groups, channels, Saved Messages. Tick
several and choose whether one rule is enough or every rule has to hold. Chats that are added or
excluded by hand always win over the rules, so one loud chat can be kept out of an "everything
unmuted" folder. Deleting a folder never touches a chat.

---

## 🔎 Search across scripts

Searching is written for the way Hindi is actually typed. "kaise" finds कैसे, "Kaise Ho?" finds
कैसे हो, and कैसे finds "kaise" - the query and the chat name are read through the same alphabet
before they are compared.

Devanagari is transliterated to Latin, which is the direction that is not ambiguous: every
character of the script has one reading, while Romanized Hindi has several spellings for one
sound. The reading follows what a Hindi reader says, not what the character table alone gives,
so कैसे reads *kaise* and not *kaisea*, and a nukta written as a combining mark is joined to its
consonant first, because that is how a keyboard produces it.

Answers are then ranked - same text, starts with it, one of its words starts with it, contains
it, matches with doubled letters collapsed, letters in order - and the chats found that way are
added to the ones Telegram returned. Rule based transliteration has limits it does not hide: the
inherent vowel before an anusvara is dropped, so संगीत reads *sngiit* and a search for "sangeet"
does not reach it.

---

## 📱 Active sessions

Settings → **Active sessions** lists every device signed in to the account: the application and
its version, the device and its system, where Telegram last saw it from, and how long ago that
was. A row can be signed out on its own, or **Sign out everywhere else** takes every device but
this one in one go.

Two rules are built in rather than left to the person reading the list:

- **This device cannot be signed out from here.** Signing yourself out of the screen that lists
  your sessions is a way to lose an account, not to protect it. The logout meant for this device
  has its own door, with its own warning.
- **Signing everything else out stops at the first failure** and says which device it could not
  reach. A job that quietly leaves three devices signed in is worse than one that stops and tells
  you.

A session that is signed out cannot be signed back in from here - that device has to enter the
verification code again - which is the whole point of the screen.

---

## 📤 Export a chat

The overflow menu of a conversation writes it out as a document - a page for a browser, or plain
text - and hands it to any application that can take a file.

What is written is what the device holds, so scrolling up first is how more of the conversation
gets into the transcript. A date heading appears once per day, a name appears when the writer
changes, media is written as a placeholder with its caption, and an edited message says so.

Two things about it are deliberate:

- **Everything is escaped.** A message can contain `<script>`, and it is somebody's message, not
  code. Every character of what people wrote is escaped before it goes near markup, and a test
  asserts that no angle bracket or quote that was typed survives into the page.
- **Nothing extra is written out.** No message id, no phone number, nothing about the account
  that the conversation itself does not already carry. The file name is stripped of path
  separators and control characters, and an empty title falls back to a word rather than to a
  file with no name.

Dates are written by arithmetic rather than by asking the device what its calendar rules are, so
a transcript reads the same everywhere. That arithmetic is checked against `java.time` over
133 000 moments in five time zones, including dates before 1970, the leap day of 2000, and 1900 -
a year a century rule makes not a leap year.

---

## ✍️ Formatted messages

A message can be typed with markup and it arrives formatted:

| Typed | Sent as |
| --- | --- |
| `**bold**` | bold |
| `__italic__` | italic |
| `~~struck~~` | struck through |
| `||spoiler||` | hidden until tapped |
| `` `code` `` | inline code |
| ` ```a block``` ` | a block of code |
| `[label](https://example.com)` | a link |

A marker with no matching closer is left as typed, because `2 ** 3` is arithmetic and not a
failed attempt at bold. Nothing in the reader throws, whatever is typed.

The nesting rules are the ones the format itself states, not a choice made here: code, a block of
code and a link cannot hold other formatting, while bold, italic, strikethrough and a spoiler can
nest freely. So `` `**not bold**` `` sends the asterisks and no bold run, and
`[**label**](https://example.com)` sends the words without the asterisks, because the format has
no way to make a link label bold. A check runs those invariants - nothing past the end of the
text, nothing inside code, nothing partly overlapping - over every sample in the tests, because
Telegram rejects the whole message when one of them is broken.

Formatting is read on the way out. Messages arriving from other clients are still shown as plain
text.

---

## 📅 Sections and language

The chat list is grouped under **Today**, **Yesterday**, **This week**, **This month** and
**Earlier**. A label appears the first time an age shows up and never again, so a pinned chat of
last month sitting between two of today's chats joins the month section already on screen
instead of opening a second one.

The whole interface is translated into **Hindi**. It is used whenever the device language is
Hindi; every other language falls back to English. All 382 strings and all four plurals have a
translation, and a check compares the two files for missing keys and for placeholders that
changed, so a string added to one file and forgotten in the other is caught rather than shipped.

---

## 🔒 Passcode lock

A passcode in Settings keeps the chat list behind a lock screen.

- Only the stretched passcode is stored - PBKDF2-HMAC-SHA256 with a random salt, 120 000
  rounds - never the passcode itself, in the store already encrypted with a key in the Android
  keystore.
- Passcodes of one repeated character or consecutive digits are refused when they are set.
- Five wrong guesses are free. After that each one costs longer: 30 seconds, a minute, five,
  fifteen, and an hour at the most.
- **Lock automatically** chooses how long one unlock lasts, from immediately to an hour.
- While a passcode is set, the window is kept out of screenshots and out of the task switcher,
  because the task switcher shows the last frame of the app - the chat list - to anyone holding
  the phone.

Turning the passcode off needs the passcode, and guessing at it there is throttled the same way.
The passcode protects this device only; it is not a Telegram password and it is not synced.

---

## 🔑 Signing in

Telefarm offers two ways in from the first screen:

| | Phone number | Session string |
| --- | --- | --- |
| What you need | Your number and the code Telegram sends | A session string of a client that is already signed in |
| Where it comes from | Telegram | Telethon, GramJS, Hydrogram or Pyrogram |
| Code / password needed | Yes | No |

### Session login

Choose **Session string** on the sign-in screen and paste the string. Telefarm reads the data
center and the authorization key out of it, asks Telegram for a login token, and lets the pasted
session approve that token - exactly what a signed-in device does when it scans a login QR code.
The session string is parsed in memory, used once, and never written to disk, to a log, or to the
interface; the screen only ever shows a four byte digest of the key so that two sessions of the
same account can be told apart.

Supported layouts:

- Telethon 1.23+ `StringSession` (IPv4 and IPv6)
- GramJS `StringSession`, including the host name variant
- Hydrogram / Pyrogram `export_session_string()`
- The older layout that stores the application id
- The same shared layout with a `telefarm:` prefix

Sessions of the Telegram **test environment** are rejected, because this client only talks to the
production data centers.

A session is a credential. Anybody who has it has your account, so treat it like a password.

---

## ⚙️ Configuration

MTProto identifies the application on every connection, so a Telegram client always needs an
`api_id` and an `api_hash`. Telefarm resolves them in this order:

1. **Your own pair, entered in the app** - "Use your own Telegram API credentials" on the sign-in
   screen. Stored in the Android keystore backed store of this device only.
2. **The build** - `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` from Gradle properties, environment
   variables or `local.properties`.
3. **The shared identity the app ships with** - the application id of the official Android client.

Step 3 is why a freshly installed build works at all: the sign-in screen appears and both login
methods are usable without registering anything first.

> [!IMPORTANT]
> Telegram's API terms ask every application to register its own pair at
> <https://my.telegram.org>. The built-in identity is shared, it is not yours, and using it for a
> distributed app can put the accounts that use it at risk. Register your own pair - it takes a
> minute and is free - and enter it in the app or in the build.

Build time configuration example:

TELEGRAM_API_ID=your_api_id
TELEGRAM_API_HASH=your_api_hash

Never commit real credentials to:

- Git repositories
- README files
- Screenshots
- Public releases
- Logs
- Issue reports

---

## 🧪 Development Status

Telefarm is currently under active development.

Development roadmap:

- [ ] Android project foundation
- [ ] Application architecture
- [ ] Telegram client integration
- [ ] Secure local authentication/session handling
- [ ] Account initialization
- [ ] Chat list
- [ ] Chat view
- [ ] Message synchronization
- [ ] Message sending
- [ ] Message receiving
- [ ] Message actions
- [ ] Media support
- [ ] Search
- [ ] Notifications
- [ ] Profile
- [ ] Settings
- [ ] Local caching
- [ ] Performance optimization
- [ ] Security review
- [ ] APK release build

---

## 🗂️ Project Philosophy

Telefarm is designed as a client application rather than a hosted messaging platform.

The goal is to keep the architecture simple:

User
→ Android Device
→ Telefarm
→ Telegram Infrastructure

Instead of:

User
→ Telefarm Server
→ Telefarm Database
→ Telegram

This reduces unnecessary infrastructure and keeps the application focused on its primary purpose: providing a personal Android client.

---

## 🔄 Local-First Design

The local-first design means that the Android application is responsible for:

- User interface
- Local session management
- Local settings
- Local cache
- Message presentation
- Network communication
- Notifications
- Media handling

Telegram remains responsible for the underlying messaging infrastructure and account data.

---

## 🚫 No Permanent Telefarm Backend

Telefarm is intentionally designed without a mandatory Telefarm backend for normal operation.

This means the project does not need:

- Render
- VPS
- Cloud database
- Telefarm-hosted authentication server
- Permanent session database
- Separate messaging backend

The application can operate as a standalone Android client while communicating with Telegram's infrastructure.

---

## 🔒 Local Data Protection

Local application data should be protected using Android's security mechanisms.

Sensitive information should not be stored in:

- Public external storage
- Plain-text configuration files
- Debug logs
- Git repositories
- Shared application files

Where sensitive local data is required, the application should use appropriate Android secure storage facilities.

---

## ⚡ Performance Goals

Telefarm aims to provide:

- Fast startup
- Fast chat navigation
- Smooth scrolling
- Efficient network usage
- Efficient media handling
- Minimal unnecessary background work
- Reliable reconnection
- Low memory overhead where practical

Performance will be optimized progressively during development.

---

## 🧪 Testing

`./gradlew :app:test` runs the unit tests. They cover the parts that decide correctness and that
need no device:

- Session string parsing of every supported client format, against strings written by those
  clients, and the rejection of test-mode sessions and unusable keys
- The key fingerprint that the sign-in screen shows, and that it leaks nothing
- Which application credentials a build resolves to, and the validation of a pair that was typed
- When a pasted session may be used, and with which application id
- Chat folders: which chats a folder holds, its badge, and the stored form of the folders,
  including the corrupt texts an older version could have left behind
- Reading Devanagari as Latin - the inherent vowel, the nukta a keyboard writes as a separate
  mark - and ranking what a query matches
- The sections of the chat list, including a chat that is out of age order because it is pinned
- The passcode: what is refused when it is set, how a stored passcode verifies, the waits a wrong
  guess costs, and how long one unlock lasts
- Reading typed markup into the text plus runs that the wire format asks for, and the invariants
  that format imposes on the runs
- Quiet hours: what the range means when it crosses midnight, and which messages come through it
- Writing a conversation out as a document, the escaping that keeps it a document, and the
  calendar arithmetic behind the dates in it
- The order and wording of the active session list, and which sessions it is allowed to sign out
- The login token of a `tg://login` link
- The MTProto primitives: AES-IGE, the TL codec, message ids and the message key

Still to cover: chat synchronization, media transfers, notifications, offline states and
reconnection.

---

## ⚠️ Disclaimer

Telefarm is an independent third-party project.

It is not an official Telegram application.

Telefarm is not affiliated with, endorsed by, or sponsored by Telegram.

"Telegram" and related trademarks belong to their respective owners.

Telefarm does not claim complete feature parity with official Telegram applications.

Available functionality depends on the underlying Telegram API/client capabilities and the implementation of this project.

---

## 📜 License

License information will be added as the project matures.

---

## 👨‍💻 Project Information

Project: Telefarm

Platform: Android

Type: Independent Telegram client

Architecture: Local-first

Backend: Not required for normal operation

Permanent Database: Not required

Primary Language: Kotlin

Status: Active Development

---

## 🎯 Long-Term Goal

The long-term goal of Telefarm is to become a clean, fast, secure, maintainable, and feature-rich Android Telegram client focused on personal use.

The project prioritizes:

- Privacy
- Security
- Performance
- Reliability
- Clean architecture
- Native Android experience
- Local-first design
- Maintainable code

---

## ⭐ Telefarm

**Build locally. Store securely. Connect directly.**

A personal Android client built with a simple philosophy:

**Your client. Your device. Your session. Your control.**
