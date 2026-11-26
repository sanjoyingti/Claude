# Arin — protected rules

These rules were set by Arin's owner. **No self-update, model, tool or other app may remove, weaken or bypass them.**
Only the owner can change them. If a feature truly needs a protected rule changed, the model must **ask the owner's
approval first** (an Approve / Keep the rule card in the **Arin Update** tab), and changes it only after **Approve**.
A self-update that tries to change a protected rule without approval is held back and shown for approval.

How they are protected:

- Every line or file that belongs to a rule is marked `ARIN-PROTECTED`. During a self-update, any change that
  removes or edits a marked line is undone automatically (`selffix/SelfUpdateRules.kt`).
- Self-updates can never touch `.github/`, the signing key, the Gradle wrapper or `settings.gradle.kts`.
- The GitHub build checks that the rules are still in place ("Check protected rules are still in place") and
  refuses to build an APK if one is missing.

## 1. Research-first auto-fix (`selffix/AutoFix.kt`)

- On **any** error (build, phone build, crash, command, install, tool), Arin **first searches the internet**
  (several searches, and it reads the best pages), **then** applies the fix.
- It may download any tool, library or file the fix needs.
- It keeps going — search → fix → try again — **until the error is fixed**. Only the Stop button ends it early.
- When the same error comes back, it tries a different approach and asks Claude for help (if the Claude helper is on).

## 1b. Every tab: research first, never give up, exactly what was asked (`selffix/ErrorDoctor.kt`)

- In **every tab** (chats, Build, Arin Update, Claude Code, Movie Maker), when something fails, Arin first searches
  the internet for that error, then explains in plain English what went wrong.
- If Arin can fix it, it does, and keeps trying **other methods** until the request is complete (builds retry by
  themselves; chats carry on by themselves). Only Stop ends it.
- If **you** must do something (a key, a sign-in, a permission), it tells you exactly how, step by step, and builds
  check again every 10 seconds so they carry on by themselves once you've done it.
- It always does **exactly what you asked**: a different method may be used, but your request is never changed,
  shortened or dropped.

## 2. The Custom API is never changed automatically

Arin always uses the API and model you picked. It never switches your Custom API address, key or model on its own
(also not when the service is busy).

## 3. The Arin Update tab updates only Arin (`selffix/ArinUpdateScreen.kt`, `selffix/SelfUpdateRules.kt`)

- It only changes Arin itself, in Arin's own source repository. It never builds a new or different app.
- The app id stays `com.arin.app` and the signing key stays the same; the version number always goes up.
  So every update installs **over** the Arin you have installed (chats, settings and logins kept).
- Before installing, Arin checks the APK is Arin, signed with the same key, and not older — otherwise it blocks it.
- The tab uses all of Arin's features: internet search, reading pages, downloads, your GitHub and the Claude helper.

## New GitHub repositories only with your approval

Arin never creates a new GitHub repository for an update on its own. If one is needed (for example your own copy of
Arin's source), the Arin Update tab shows **Create a new GitHub repository? → Approve / Keep**, and Arin waits.

## Everything is saved; Resume carries on

- The Arin Update discussion, Movie Maker's conversation and every chat stay on the phone until **Clear all chats**.
- **Resume** (Arin Update, Build mode, chat builds, chat replies, Claude Code, Movie Maker) continues from where the work
  stopped — changes already made and uploaded are kept, nothing starts from the beginning.

## 4. AI helper in every tab (protected: `selffix/AiHelperUi.kt`, `selffix/ClaudeHelper.kt`)

The AI helper — its on/off switch and **Talk to Claude / ChatGPT / Gemini / Custom API** — is available in **every
tab** (a helper button in each screen's top bar, also in new empty chats, Build's file and project screens and voice
mode, plus the chat ⋮ menu). No update may remove, hide or change it; the GitHub build refuses to build if it's
missing from any screen. Only you can switch it on or off, by tapping. Changing it needs your approval.

### How the AI helper works

- **Your model always reads your whole message first.** In "Talk to …" mode, the tab's own model reads the message and
  drafts its answer; then it takes the helper's help (the helper corrects and fills gaps); then the model gives you the
  final answer. In Build mode the model writes the code first, then the helper reviews it and the model fixes what it found.

- **On:** any model (DeepSeek, ChatGPT, a Custom API, free models) can ask the chosen helper for help with
  `<ask_helper>…</ask_helper>`, and Arin asks it on its own when an error keeps coming back. **Off:** every model
  works on its own.
- The helper can be **Claude** (Claude Code Pro login or a Claude API key), **ChatGPT**, **Gemini**, or **any model of a
  Custom API you already saved**. "Talk to Custom API…" lists your saved APIs, then all of that API's models, to choose
  the assistant.
- The switch and "Talk to…" are in the ⋮ menu of every chat (next to Clear all chats) and in the helper button at the
  top of every other tab.
- **The helper only assists and forgets:** each question is asked fresh, nothing is kept afterwards. Claude Code as
  helper runs in a throwaway folder and home that are deleted afterwards, so the Claude Code tab's own conversation is
  never touched. **The tab's own model remembers everything** discussed in that tab, including the helper's answers.
- The tab's model and your Custom API are never changed by the helper.

## helper-never-takes-over
The model the person picks for a tab is the boss. The AI helper (Claude, GPT, Gemini or any Custom API) only advises it:
it finds causes, researches fixes and suggests next steps, as private notes to the boss. It never answers the person,
never does the boss's work in its place, and never changes the boss's API, key or model. Only the owner can change this.
(selffix/ClaudeHelper.kt, chat/ChatViewModel.kt, selffix/ArinUpdateChat.kt)

## helper-sees-only-the-boss
Only the model the person picked sees the person's messages and files. The AI helper never receives them: the boss
model describes what it needs in its own words, and Arin only passes the boss's own steps, drafts, code and error
output to the helper. Only the owner can change this. (selffix/ClaudeHelper.kt ask(), chat/ChatViewModel.kt,
selffix/ArinUpdateChat.kt, builder/BuildAgent.kt, builder/BuildService.kt)

## 5. Team of three in every tab (`selffix/TeamWork.kt`, `selffix/Worker.kt`)

When worker mode is on, every tab (chats, Reverse engineering/REA, Rebuild, Build, Arin Update) works as one team:

- **Main model (boss)** — only gives short directions and checks results. It does not write the commands or long
  files itself.
- **Middle model (worker, e.g. GPT-6 Luna)** — writes the commands, new files and research summaries from the boss's
  directions. It **never sees your chat, the files/code or any command output**. Arin removes code blocks from
  directions before the worker sees them.
- **Helper (e.g. Claude)** — helps the worker when it is stuck, with the same limits.
- **Arin itself (no AI)** runs the commands and shortens every result before the boss reads it.

No self-update may remove this, or let the worker or helper see the chat, files or results, without the owner's approval.

## 6. A summary after every task, in every tab — STRICT (`selffix/TaskSummary.kt`)

Set by the owner. After every task in every tab (chats, Reverse engineering, Rebuild, Build, Arin Update and all
others), Arin ends with:

- **Summary — what I did:** the main things done and their result (and anything that failed).
- **What to do next:** the next step(s) for the owner.

The model is told to write it. If it doesn't, Arin itself adds one from the steps it ran (no AI, no credit). The
Build tab shows it on every build result.

**No one may change or remove this rule — no self-update, model, tool, app or assistant (including Claude) — unless
the owner says so directly.** The GitHub build refuses to make an APK if it is missing.

## 7. Every task stays in its own tab (`selffix/TabIsolation.kt`)

Set by the owner. Each tab (each chat, Reverse engineering, Rebuild, Build, Arin Update, Claude Code…) follows ONLY the
task the owner gave in that tab:

- Other chats are not listed or searched, unless the owner's own message in that tab asks for an earlier chat.
- Memory keeps only lasting facts and preferences about the owner — never tasks, apps or projects.
- Every chat has its own model session; one chat never reuses another chat's session.
- Every model is told: never continue or mention a task from another tab.

No self-update, model, tool or app may remove or weaken this without the owner's approval.

## 8. Every APK and build tool, newest versions, in every tab (`terminal/Linux.kt`, `assets/apk-toolkit-setup.sh`)

Arin's Linux carries the full toolchain to compile source code and to decompile, change, rebuild, sign and package
APKs: JDK 21, Gradle (latest), Kotlin compiler (latest), Android SDK (command-line tools, platform, build-tools with
apksigner/d8/r8), native ARM aapt2/aidl/zipalign, arin-build, apktool, APKEditor, jadx, dex2jar (smali/baksmali),
bundletool, uber-apk-signer and androguard. Each install looks up the newest releases and updates older ones
(`arin-tools update`). Every tab installs them automatically the first time a command needs them, and every model is
told they are there.

## Explain after every coding (`builder/BuildAgent.kt`, `selffix/TaskSummary.kt`)

- In **every tab** (chats, Build, Arin Update and all others), after any code is written or changed, Arin explains it
  in detail in plain English for someone who isn't a programmer: what it does, each part, and how it works.
- Then it gives **suggestions** to change, improve or update it, and offers to make them or to build.
- Your own model (the boss) writes the explanation; the worker and helper never see the code for it.

## Complete project that compiles (`builder/BuildAgent.kt`, `builder/ProjectTemplate.kt`)

- When coding, Arin includes **every file** needed to compile the app into an APK in Android Studio and on GitHub: all code,
  the manifest, and every resource the code uses.
- Before building, Arin checks (no AI) that every resource the code uses exists, and has the missing ones written first.
- Libraries always use their newest **stable** release (never alpha, beta or RC, which break builds with
  "checkDebugAarMetadata"/"compileSdk" errors); saved pre-release versions are moved to stable automatically.
- Only the owner can change this rule.

## Tested before handover (`builder/BuildAgent.kt`, `builder/ProjectTemplate.kt`)

- **Every app, in every tab** — apps Arin makes, uploaded projects and Arin's own updates — is installed on an Android
  emulator, opened, and tapped around before any APK, install file or download link is given.
- Every model in every tab is told this too: an APK made any other way (terminal, rebuild, phone) is never shared untested.
- A phone-only build is not handed over (a phone can't run an emulator): say "build on github" to have it tested.
- If it crashes, closes by itself or won't install, Arin fixes it like a build error and builds and tests again.
- The APK download link is given **only after the app opens and runs without crashing**. If the test can't run, or it
  still crashes after the fix rounds, no link is given — say "try again" to keep going.
- Only the owner can change this rule.

## Exactly what was asked (`selffix/TaskSummary.kt`, given to every model in every tab)

- Arin makes exactly the app or change the owner asked for, with every feature and detail. It never builds a different,
  smaller or simplified app, and never drops a feature to make an error go away.
- If something truly can't be done, it says so and asks instead of quietly changing it.
- Only the owner can change this rule.

## Heavy work off the main model (`builder/BuildAgent.kt`, `selffix/Worker.kt`)

- With worker mode on, your main model only directs and inspects (short). It never writes code.
- The workers write everything, in your order; if all refuse, the helper writes it. Explanations are written by the worker.
- If no worker or helper can be reached, Arin stops and says so instead of spending your main model's credit.
- Only the owner can change this rule.

## Main model uses as little credit as possible (`llm/Llm.kt`, `chat/ChatViewModel.kt`)

- With worker mode on, your main model goes straight to its API (never through the Claude Code harness), thinks as
  little as the service allows (DeepSeek's paid thinking off), remembers the whole chat through a running memory note (see below), and writes short
  replies (directions and checks). The worker and helper do the heavy work.
- Only the owner can change this rule.

## The main model remembers everything, cheaply (`selffix/ChatMemory.kt`)

- Each message, your main model gets: the chat's first message, a running **memory** of everything older (every request,
  requirement, decision, name, number, file, link and unfinished task), older messages that match your new message
  **word for word**, and the latest 8 messages in full.
- The memory is updated in batches from only the new messages (written by the offline model when you have one, free;
  otherwise briefly by your main model). The worker and helper never see the chat. Nothing is deleted from the chat.
- Only the owner can change this rule.
