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

## ↪️ Forward a message

Long-press a message, choose **Forward**, and pick the chat. The chat the message is already in
is not on the list: forwarding a message to the chat it came from is not something anybody means
to do.

A forward says where it came from. Copying hides the sender, which is a different act, so it is
offered by name rather than done by default: **Forward** keeps the original sender attached, and
**Forward without the sender's name** sends the same message with nothing pointing back.

Telegram imposes three rules on a forward, and breaking any of them loses the whole request
rather than part of it, so all three are applied before anything is sent:

- the identifiers go in a **strictly increasing** order, which a selection made by tapping down a
  conversation is not
- **at most 100** messages at once - two hundred become two forwards of a hundred rather than one
  request that is refused
- a message that cannot leave the chat it is in has to be left out, which is asked of Telegram
  rather than guessed at, because a channel can forbid forwarding and a client that guesses wrong
  either sends something it should not or refuses something it could

The messages left out are counted and reported, so nobody is left wondering where one went. A
forward that stops part way says how many went before it stopped.

---

## 👍 Reactions

A reaction is the shortest possible answer: it says "seen" without asking anybody to read
anything. Long-press a message, choose **React**, and eight emoji are on offer in one row - 👍 👎
❤️ 🔥 🎉 😂 😮 😢. Eight, because a picker you have to search is slower than typing the reply.

Choosing the one already chosen takes it away, which is how a reaction is undone. Telegram
replaces the whole set rather than adding to it, so a reaction cannot be doubled and a mistake
cannot pile up.

Under the bubble a strip shows what is on the message - `👍 3 🔥 2` - with the one you made
first, then the most used. A reaction this client cannot draw is left out rather than shown as a
box, because a reaction that reads wrong is worse than one that is not there.

---

## 🗑️ Delete, and how far it reaches

Deleting asks a question first, because the two answers are different acts. **Delete for
everyone** takes the message away from everybody who could see it. **Delete only for me** takes it
from your copy and leaves everybody else's exactly as it was.

One button labelled "Delete" cannot carry both, and the difference is only visible afterwards, on
somebody else's screen - which is the worst time to find out. Deleting somebody else's message for
everybody takes words out of their conversation, and that is not what "delete" usually means to
the person tapping it.

What is offered is what Telegram says the message allows, so a message that can only be deleted for
yourself is not offered a choice that would fail, and a message that cannot be deleted says so
instead of failing. When Telegram cannot be asked at all, only the deletion that changes nothing
anybody else sees is offered, rather than a guess that reaches further than it should.

---

## 🚫 Block somebody

A person's profile has **Block** on it, and once blocked it says **Unblock** instead.

The row says which because Telegram keeps no mark of being blocked on a user - the block list is
the only place that says so, and it is read from there. Offering one button that means two
different things depending on what you cannot see is how people block somebody twice and wonder
why nothing changed.

Blocking is a setting of the account rather than of this device, so it holds in every Telegram
client until it is taken back. The profile is read back after the change, so the row shows what
Telegram now holds and not what was just tapped.

A group's profile does not offer it. The block list holds senders and a chat is not a sender, so
claiming a block state there would be a claim about something Telegram does not track.

Settings → **Blocked people** is the other half of the same feature, and the reason blocking is
safe to offer at all: a block nobody can find is a block nobody can take back. Everybody on the
list is there with an Unblock beside them, including anybody whose name could not be fetched - a
person is not quietly unblocked because their name was unavailable, and a block that vanishes from
the list is a block the account still has.

---

## 🗄️ Archived chats

Long-press a chat and choose **Archive** and it leaves the list. Menu → **Archived chats** brings
up everything that has been put away, and each one can be moved back.

The screen is the point of the feature, not an afterthought to it: archiving a chat with nowhere
to get it back would be a way to lose a conversation, so the two ship together.

A chat is either on the main list or in the archive and never in both, which is Telegram's rule
rather than a choice made here. It means there is one call in each direction - which list the chat
is added to - and no separate unarchive that could drift out of step with the archive.

An archived chat still receives messages. Putting a chat away is about the list, not about the
conversation.

---

## 📎 Send a file

A picture, an animation, a video, a recording or any other file, from the system picker. Each is
sent as what it is, so a photo arrives as a picture, a GIF still moves, and a PDF still says it is
a PDF.

The type the picker reports is believed before the extension on the name, because the type is what
the sending side acts on - but neither is trusted alone. A picker will report nothing, and a file
will be named `notes.txt` and be a photograph.

Three things are worth saying about how it works:

- **A picture too big to be a picture goes as a file.** A photo is capped at ten megabytes; past
  that, sending it as a photo simply fails, and a photograph that arrives as a file is a smaller
  disappointment than one that does not arrive.
- **The copy is named from the file, with the name taken apart first.** A picked file is named by
  whatever saved it, and `../` in a file name is a way out of the directory rather than a typo.
  Two files with the same name are told apart, and Hindi names survive whole - the `ि` in
  `रिपोर्ट` is not a letter, and dropping it would turn a name a person recognises into one they
  do not.
- **Nothing outside the chosen file is read.** The system picker grants permission for the one file
  picked, so there is no storage permission to ask for and no browsing of the device.

---

## 📍 Where you left off

A conversation opens at the newest message. When there are messages that have not been read, a
line is drawn above the first of them saying how many there are, so the scroll up to be sure is
not necessary.

Two rules decide where it goes, and both follow from what the line is for:

- **Only incoming messages can be unread.** Your own messages were read by you as you wrote them,
  so a conversation you have been talking in alone has nothing to mark.
- **The line goes before the first unread message, not before the first message.** Those are the
  same thing only when the conversation opens with somebody else's words.

A chat whose unread messages are not on screen - older history that has not been loaded - gets no
line, because a line pointing at a message nobody can see points at nothing.

---

## 🔇 Mute a chat for a while

Long-press a chat and choose **Mute**, and the question is not whether but for how long: an hour,
eight hours, two days, or until it is unmuted by hand.

A mute that can only be "on" is a mute nobody turns off, because the only way to hear the chat
again is to unmute it and remember to. The four choices are the four reasons people mute: a
conversation that is loud right now, one that is loud today, one that is loud this week, and one
they never want to hear from.

Telegram holds a mute as a count of seconds left, so what comes back is a countdown rather than
the choice that was made. It is read back in the largest unit that is still honest - an exact hour
reads as "1 hour" and never as "60 minutes" - and it rounds up, so a mute that is still on never
reads as no time at all. The unmute action carries that count, so a chat that goes quiet tonight
says so.

"Forever" is the largest count a 32-bit integer holds, about 68 years. It is called forever
because that is what it means to the person choosing it, not because the number is infinite.

---

## 🔕 Quiet hours

A chat can be muted; the whole app can be quiet. Settings → **Quiet hours** sets a window - by
default 23:00 to 07:00 - in which notifications are held back, with an option to let a
notification through when it mentions you.

Three rules are built in rather than left to the reader:

- **Equal start and end means no quiet hours at all.** A zero-length window is not "quiet
  forever", and treating it as one would silence an app that was asked to do nothing.
- **A window that crosses midnight wraps.** 23:00 to 07:00 is quiet from eleven at night until
  seven in the morning, which is the only reading that matches what the setting is for.
- **The window is half open**, closed at the start and open at the end, so a notification at
  exactly 07:00 is not held back by a window that ends at 07:00.

A malformed stored value falls back to the defaults rather than to silence, and minutes are
clamped to the range a clock has.

---

## 🛡️ Account privacy

Settings → **Privacy** shows and changes the account's own privacy settings: last seen and online,
phone number, profile photo, calls, and who can add you to groups and channels.

These live on Telegram, not on the device, so the screen shows what Telegram answered rather than
what was last tapped, and says at the top that a change reaches every client.

Each setting is offered as three choices - **Everybody**, **My contacts**, **Nobody** - because
that is the choice being made. Telegram stores an ordered list of rules and applies the last one
that matches, which is more than three radio buttons can show, so the two are translated:

| Choice | Rules written |
| --- | --- |
| Everybody | allow all |
| My contacts | allow contacts, then refuse everybody else |
| Nobody | refuse everybody |

Those are the lists Telegram itself writes, so a setting changed here reads the same in another
client. Anything that does not reduce to one of the three - named exceptions, an unusual order, a
rule this client has not heard of - is shown as **Custom** and left alone until it is changed.
Rounding somebody's rules to the nearest choice would quietly change who can reach them.

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

The pen button beside the text box opens a bar of five buttons - **B**, *I*, `</>`, S, ▨ - for
bold, italic, code, strikethrough and hidden-until-tapped. They put the same markers in that
typing them would, so what is sent is what the parser already reads. Each one is a toggle: press
it again and the markers come back off.


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
- Reading Telegram's privacy rules as three choices, and writing the same three back
- What a reaction strip says, which reaction replaces which, and the order it is drawn in
- Putting formatting markers into a message while it is written, and taking them back out
- Ordering and batching a selection into forwards Telegram will accept
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
